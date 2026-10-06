package com.werewolf.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.werewolf.service.PresenceService;
import com.werewolf.service.RoomMembership;
import com.werewolf.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;

/**
 * 房间消息广播：把 REST 侧的状态变更实时推给房间内所有在线连接。
 */
@Component
public class RoomBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(RoomBroadcaster.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PresenceService presence;
    private final RoomMembership membership;

    public RoomBroadcaster(PresenceService presence, RoomMembership membership) {
        this.presence = presence;
        this.membership = membership;
    }

    public void sendToUser(long userId, String type, Map<String, ?> payload) {
        presence.sessionOf(userId).ifPresent(s -> send(s, type, payload));
    }

    /** 向所有在线连接广播一条全局消息（用于系统公告 / 维护通知）。返回送达的会话数。 */
    public int broadcastAll(String type, Map<String, ?> payload) {
        int n = 0;
        for (WebSocketSession s : presence.snapshot().values()) {
            if (s != null && s.isOpen()) { send(s, type, payload); n++; }
        }
        return n;
    }

    /** 广播房间最新状态给所有成员（含观战） */
    public void broadcastState(long roomId, RoomService.RoomView view) {
        for (Long uid : membership.membersOf(roomId)) {
            sendToUser(uid, "room.state", Map.of("room", view));
        }
    }

    /** 广播一条事件（用于布告栏） */
    public void broadcastEvent(long roomId, String message) {
        for (Long uid : membership.membersOf(roomId)) {
            sendToUser(uid, "room.event", Map.of("message", message));
        }
    }

    private void send(WebSocketSession session, String type, Map<String, ?> payload) {
        if (session == null || !session.isOpen()) return;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", type);
            payload.forEach(root::putPOJO);
            synchronized (session) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(root)));
            }
        } catch (IOException e) {
            log.debug("WS 发送失败: {}", e.getMessage());
        }
    }
}
