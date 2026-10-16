package com.werewolf.api;

import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.RoomService;
import com.werewolf.ws.RoomBroadcaster;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/room")
public class RoomController {

    private final AuthService authService;
    private final RoomService roomService;
    private final RoomBroadcaster broadcaster;
    private final com.werewolf.service.BoardService boardService;
    private final com.werewolf.service.SettingsService settings;

    public RoomController(AuthService authService, RoomService roomService, RoomBroadcaster broadcaster,
                          com.werewolf.service.BoardService boardService,
                          com.werewolf.service.SettingsService settings) {
        this.authService = authService;
        this.roomService = roomService;
        this.broadcaster = broadcaster;
        this.boardService = boardService;
        this.settings = settings;
    }

    public record RoomNoBody(String roomNo) {}
    public record ReadyBody(boolean ready) {}
    public record UserTargetBody(long userId) {}

    @PostMapping("/create")
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth) {
        return withUser(auth, user -> {
            RoomService.ActionResult r = roomService.create(user);
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(user.getId()), r.view());
            return ok(r.view());
        });
    }

    @PostMapping("/join")
    public ResponseEntity<?> join(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody RoomNoBody body) {
        return withUser(auth, user -> {
            RoomService.ActionResult r = roomService.join(user, body.roomNo());
            if (r.error() != null) return err(r.error());
            // 用「房号 → 房间」解析广播目标，而不是 currentRoomId(uid)：
            // 观战者没有座位记录，findRoomOfUser 为空，直接用 uid 取 roomId 会得到 null 并 NPE。
            Long roomId = roomService.findByRoomNo(body.roomNo()).map(Room::getId).orElse(null);
            if (roomId != null) {
                broadcaster.broadcastState(roomId, r.view());
                broadcaster.broadcastEvent(roomId, user.getNickname() + " 加入房间");
            } else {
                broadcaster.sendToUser(user.getId(), "room.state", Map.of("room", r.view()));
            }
            return ok(r.view());
        });
    }

    @PostMapping("/spectate")
    public ResponseEntity<?> spectate(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody RoomNoBody body) {
        // withUser 内部未登录/会话失效统一返回 401，沿用现有错误风格
        return withUser(auth, user -> {
            // 调用增强后的 spectate：内部已校验房号格式与房间存在性（不存在/已解散会被拒绝）
            RoomService.ActionResult r = roomService.spectate(user, body.roomNo());
            if (r.error() != null) return err(r.error());
            // 观战者没有座位记录，必须按房号解析 roomId 才能广播（否则 NPE）
            Long roomId = roomService.findByRoomNo(body.roomNo()).map(Room::getId).orElse(null);
            if (roomId != null) broadcaster.broadcastState(roomId, r.view());
            // 给观战者本人补一份状态，保证其界面立刻进入该房间
            broadcaster.sendToUser(user.getId(), "room.state", Map.of("room", r.view()));
            return ok(r.view());
        });
    }

    @PostMapping("/leave")
    public ResponseEntity<?> leave(@RequestHeader(value = "Authorization", required = false) String auth) {
        return withUser(auth, user -> {
            Long roomId = currentRoomId(user.getId());
            RoomService.ActionResult r = roomService.leave(user);
            if (r.error() != null) return err(r.error());
            broadcaster.sendToUser(user.getId(), "room.left", Map.of("reason", "已离开"));
            if (roomId != null && r.view() != null) {
                broadcaster.broadcastState(roomId, r.view());
                broadcaster.broadcastEvent(roomId, user.getNickname() + " 离开房间");
            }
            return ok(Map.of("ok", true));
        });
    }

    @PostMapping("/ready")
    public ResponseEntity<?> ready(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @RequestBody ReadyBody body) {
        return withUser(auth, user -> {
            RoomService.ActionResult r = roomService.setReady(user, body.ready());
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(user.getId()), r.view());
            return ok(r.view());
        });
    }

    @PostMapping("/kick")
    public ResponseEntity<?> kick(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody UserTargetBody body) {
        return withUser(auth, host -> {
            RoomService.ActionResult r = roomService.kick(host, body.userId());
            if (r.error() != null) return err(r.error());
            broadcaster.sendToUser(body.userId(), "room.kicked", Map.of("message", "你被房主移出房间"));
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()), "有玩家被移出房间");
            return ok(r.view());
        });
    }

    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody UserTargetBody body) {
        return withUser(auth, host -> {
            RoomService.ActionResult r = roomService.transfer(host, body.userId());
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()), "房主已转让");
            return ok(r.view());
        });
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestHeader(value = "Authorization", required = false) String auth) {
        return withUser(auth, host -> {
            RoomService.ActionResult r = roomService.start(host);
            if (r.error() != null) return err(r.error());
            return ok(Map.of("ok", true, "message", r.view() == null ? "" : "已就绪"));
        });
    }

    @PostMapping("/add-ai")
    public ResponseEntity<?> addAi(@RequestHeader(value = "Authorization", required = false) String auth) {
        return withUser(auth, host -> {
            RoomService.ActionResult r = roomService.addAi(host);
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()), "房主添加了 AI 补位玩家");
            return ok(r.view());
        });
    }

    @GetMapping("/boards")
    public ResponseEntity<?> boards(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (authService.resolve(AuthController.stripBearer(auth)).isEmpty()) return err("未登录或会话已过期");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("presets", boardService.presets());
        m.put("roles", boardService.roleMeta());
        return ok(m);
    }

    public record BoardBody(Map<String, Integer> counts, String boardName) {}

    public record ModeBody(boolean voiceMode, boolean anonymous, boolean huntCity) {}

    public record ItemBody(boolean itemMatch) {}

    /** 房主设置是否功能道具赛（开局前）。 */
    @PostMapping("/item")
    public ResponseEntity<?> setItem(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody ItemBody body) {
        return withUser(auth, host -> {
            if (body.itemMatch() && disabledFeatures().contains("items")) return err("功能道具赛已被管理员关闭");
            RoomService.ActionResult r = roomService.setItemMatch(host, body.itemMatch());
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()),
                    "房主" + (body.itemMatch() ? "开启" : "关闭") + "了功能道具赛");
            return ok(r.view());
        });
    }

    @PostMapping("/mode")
    public ResponseEntity<?> setMode(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody ModeBody body) {
        return withUser(auth, host -> {
            Set<String> off = disabledFeatures();
            if (body.voiceMode() && off.contains("voiceMode")) return err("语音同传功能已被管理员关闭");
            RoomService.ActionResult r = roomService.setMode(host, body.voiceMode(), body.anonymous(), body.huntCity());
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()),
                    "房主更新了模式：" + (body.voiceMode() ? "语音同传 · 匿名" : (body.anonymous() ? "匿名" : "标准"))
                            + (body.huntCity() ? " · 数量规则" : ""));
            return ok(r.view());
        });
    }

    /** 被管理员关闭的功能 id 集合（featureFlags 中值为 false 的项）。 */
    private Set<String> disabledFeatures() {
        Set<String> out = new java.util.HashSet<>();
        try {
            String v = settings.get("featureFlags").orElse("{}");
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> m = om.readValue(v == null || v.isBlank() ? "{}" : v, Map.class);
            for (Map.Entry<String, Object> e : m.entrySet())
                if (!Boolean.TRUE.equals(e.getValue())) out.add(e.getKey());
        } catch (Exception ignored) {}
        return out;
    }

    @PostMapping("/board")
    public ResponseEntity<?> setBoard(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody BoardBody body) {
        return withUser(auth, host -> {
            RoomService.ActionResult r = roomService.setBoard(host, body.counts(), body.boardName());
            if (r.error() != null) return err(r.error());
            broadcaster.broadcastState(currentRoomId(host.getId()), r.view());
            broadcaster.broadcastEvent(currentRoomId(host.getId()), "房主更新了板子配置");
            return ok(r.view());
        });
    }

    @GetMapping("/my")
    public ResponseEntity<?> my(@RequestHeader(value = "Authorization", required = false) String auth) {
        return withUser(auth, user -> {
            Optional<Room> r = roomService.findRoomOfUser(user.getId());
            // 观战者没有座位记录：回退到「正在观战的房间」，使刷新页面后仍能回到观战界面
            if (r.isEmpty()) r = roomService.findWatchedRoom(user.getId());
            if (r.isEmpty()) return ok(Map.of("room", (Object) Map.of()));
            return ok(Map.of("room", roomService.view(r.get())));
        });
    }

    /* ---------- helpers ---------- */

    public record NoticeBody(String message) {}

    /** 管理员发送全局维护通知：持久化最新通知，并向所有在线连接（含各房间、App 端）广播一条底部弹幕提示。 */
    @PostMapping("/notice")
    public ResponseEntity<?> notice(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody NoticeBody body) {
        return withUser(auth, user -> {
            if (!user.isAdmin()) return err("仅管理员可发送维护通知");
            String msg = body.message() == null ? "" : body.message().trim();
            if (msg.isEmpty()) { settings.put("maintenance_notice", ""); return ok(Map.of("ok", true, "cleared", true)); }
            if (msg.length() > 120) msg = msg.substring(0, 120);
            long expiry = System.currentTimeMillis() + 6 * 3600_000L;   // 通知默认展示 6 小时
            settings.put("maintenance_notice", expiry + "|||" + msg);
            int n = broadcaster.broadcastAll("maintenance", Map.of("message", msg, "expiry", expiry));
            return ok(Map.of("ok", true, "delivered", n, "expiry", expiry));
        });
    }

    /** 当前生效的维护通知（若有），供新登录/重连的客户端与 App 恢复底部弹幕。 */
    @GetMapping("/notice")
    public ResponseEntity<?> activeNotice() {
        String v = settings.get("maintenance_notice").orElse("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("message", "");
        if (!v.isBlank()) {
            int i = v.indexOf("|||");
            try {
                long expiry = Long.parseLong(v.substring(0, i));
                if (expiry > System.currentTimeMillis()) {
                    out.put("message", v.substring(i + 3));
                    out.put("expiry", expiry);
                }
            } catch (Exception ignore) { }
        }
        return ResponseEntity.ok(out);
    }

    private Long currentRoomId(long userId) {
        return roomService.findRoomOfUser(userId).map(Room::getId).orElse(null);
    }

    private interface UserAction { ResponseEntity<?> apply(User user); }

    private ResponseEntity<?> withUser(String auth, UserAction action) {
        String token = AuthController.stripBearer(auth);
        Optional<User> user = authService.resolve(token);
        if (user.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        return action.apply(user.get());
    }

    private ResponseEntity<?> ok(Object body) {
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<?> err(String msg) {
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }
}
