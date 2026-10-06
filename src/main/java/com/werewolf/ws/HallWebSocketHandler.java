package com.werewolf.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.AuthService;
import com.werewolf.service.DisconnectTracker;
import com.werewolf.service.PresenceService;
import com.werewolf.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * 大厅 + 房间 WebSocket 通道：
 * - 连接鉴权（?token=）
 * - 心跳 ping/pong
 * - 顶号（同 userId 新连接踢掉旧连接）
 * - 断线 60s 宽限，超时自动离座
 * - 房间状态实时推送（REST 变更后由 RoomBroadcaster 触发）
 */
@Component
public class HallWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(HallWebSocketHandler.class);
    private static final long DISCONNECT_GRACE_SECONDS = 60;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthService authService;
    private final PresenceService presence;
    private final DisconnectTracker disconnectTracker;
    private final RoomService roomService;
    private final RoomBroadcaster broadcaster;
    private final UserRepository userRepo;

    public HallWebSocketHandler(AuthService authService, PresenceService presence,
                                DisconnectTracker disconnectTracker, RoomService roomService,
                                RoomBroadcaster broadcaster, UserRepository userRepo) {
        this.authService = authService;
        this.presence = presence;
        this.disconnectTracker = disconnectTracker;
        this.roomService = roomService;
        this.broadcaster = broadcaster;
        this.userRepo = userRepo;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session);
        Optional<User> user = authService.resolve(token);
        if (user.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION);
            log.warn("WS 连接被拒绝（无效 token）: {}", session.getId());
            return;
        }
        long uid = user.get().getId();
        // 顶号：关掉同一 userId 的旧连接
        WebSocketSession old = presence.register(uid, session);
        if (old != null && old.isOpen() && !old.getId().equals(session.getId())) {
            try { old.close(CloseStatus.POLICY_VIOLATION); } catch (Exception ignored) {}
        }
        session.getAttributes().put("userId", uid);
        session.getAttributes().put("nickname", user.get().getNickname());
        session.getAttributes().put("loginAt", System.currentTimeMillis());
        userRepo.findById(uid).ifPresent(u -> { u.setLastOnlineAt(java.time.LocalDateTime.now()); userRepo.save(u); });

        // 取消断线清理，重新加入房间成员集合
        disconnectTracker.cancel(uid);
        roomService.syncMembership(uid);

        log.info("WS 已连接: {} ({})", user.get().getNickname(), session.getId());
        send(session, "welcome", Map.of("message", "欢迎来到狼人杀，" + user.get().getNickname()));

        // 若在房间中，推送最新状态并广播给其他人
        roomService.findRoomOfUser(uid).ifPresent(room -> {
            broadcaster.sendToUser(uid, "room.state", Map.of("room", roomService.view(room)));
            broadcaster.broadcastState(room.getId(), roomService.view(room));
        });
    }

    private String extractToken(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) return null;
        for (String kv : session.getUri().getQuery().split("&")) {
            if (kv.startsWith("token=")) {
                return URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Object uidAttr = session.getAttributes().get("userId");
        if (!(uidAttr instanceof Long uid)) return;
        JsonNode node;
        try {
            node = mapper.readTree(message.getPayload());
        } catch (Exception e) {
            // JSON 解析失败：记录 uid 与消息摘要（截断，避免日志爆炸），不记录完整原始消息。
            log.warn("WS 消息解析失败 uid={} 内容摘要={}", uid, summary(message.getPayload()));
            send(session, "error", Map.of("message", "无法解析的消息"));
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "ping" -> send(session, "pong", Map.of("time", System.currentTimeMillis()));
            case "bye" -> session.close(CloseStatus.NORMAL);
            case "room.refresh" -> roomService.findRoomOfUser(uid)
                    .ifPresent(room -> broadcaster.sendToUser(uid, "room.state", Map.of("room", roomService.view(room))));
            default -> {
                // 未知消息类型：记录 uid 与消息摘要（截断），便于排查客户端协议问题。
                log.warn("WS 未知消息类型 uid={} type={} 内容摘要={}", uid, type, summary(message.getPayload()));
                send(session, "error", Map.of("message", "未知消息类型: " + type));
            }
        }
    }

    /** 日志用消息摘要：截断到 200 字符以内，避免超长消息撑爆日志。 */
    private static String summary(String payload) {
        if (payload == null) return "";
        return payload.length() > 200 ? payload.substring(0, 200) + "..." : payload;
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object uidAttr = session.getAttributes().get("userId");
        if (!(uidAttr instanceof Long uid)) return;
        // 累计本次连接在线时长并刷新上次在线时间
        Object loginAt = session.getAttributes().get("loginAt");
        if (loginAt instanceof Long la) {
            long secs = (System.currentTimeMillis() - la) / 1000;
            if (secs > 0) userRepo.findById(uid).ifPresent(u -> {
                u.setOnlineSeconds(u.getOnlineSeconds() + secs);
                u.setLastOnlineAt(java.time.LocalDateTime.now());
                userRepo.save(u);
            });
        }
        // 顶号场景：新连接已接管 presence，不做断线处理
        if (presence.sessionOf(uid).map(s -> !s.getId().equals(session.getId())).orElse(false)) {
            log.info("WS 旧连接关闭（已被顶号）: uid={}", uid);
            return;
        }
        presence.unregister(uid, session);
        log.info("WS 已断开: {} ({})", session.getAttributes().getOrDefault("nickname", "?"), session.getId());

        Optional<Room> roomOpt = roomService.findRoomOfUser(uid);
        if (roomOpt.isPresent()) {
            Room room = roomOpt.get();
            // 广播"某人离线"状态
            broadcaster.broadcastState(room.getId(), roomService.view(room));
            // 60s 后自动离座
            disconnectTracker.schedule(uid, DISCONNECT_GRACE_SECONDS, () -> {
                if (!presence.isOnline(uid)) {
                    RoomService.ActionResult r = roomService.doLeave(room, uid);
                    if (r.view() != null) {
                        broadcaster.broadcastState(room.getId(), r.view());
                        broadcaster.broadcastEvent(room.getId(),
                                (String) session.getAttributes().getOrDefault("nickname", "玩家") + " 断线超时，已离座");
                    }
                }
            });
        }
    }

    private void send(WebSocketSession session, String type, Map<String, ?> payload) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", type);
        payload.forEach(root::putPOJO);
        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(root)));
        }
    }
}
