package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.FriendService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** 好友系统 REST（需登录）。 */
@RestController
@RequestMapping("/api/friends")
public class FriendController {

    private final AuthService authService;
    private final FriendService friendService;

    public FriendController(AuthService authService, FriendService friendService) {
        this.authService = authService;
        this.friendService = friendService;
    }

    public record ReqBody(long userId, String greeting) {}
    public record IdBody(long id) {}
    public record MsgBody(long toId, String content, String type, String roomNo) {}

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        return me(auth, u -> ResponseEntity.ok(friendService.listFriends(u.getId())));
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam String kw) {
        return me(auth, u -> ResponseEntity.ok(friendService.searchUsers(u.getId(), kw)));
    }

    @GetMapping("/requests")
    public ResponseEntity<?> requests(@RequestHeader(value = "Authorization", required = false) String auth) {
        return me(auth, u -> ResponseEntity.ok(friendService.incomingRequests(u.getId())));
    }

    @PostMapping("/request")
    public ResponseEntity<?> request(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @RequestBody ReqBody body) {
        return me(auth, u -> {
            String err = friendService.sendRequest(u.getId(), body.userId(), body.greeting());
            return err == null ? ResponseEntity.ok(Map.of("ok", true)) : err(err);
        });
    }

    @PostMapping("/requests/{id}/accept")
    public ResponseEntity<?> accept(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        return me(auth, u -> {
            String err = friendService.acceptRequest(id, u.getId());
            return err == null ? ResponseEntity.ok(Map.of("ok", true)) : err(err);
        });
    }

    @PostMapping("/requests/{id}/reject")
    public ResponseEntity<?> reject(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        return me(auth, u -> {
            String err = friendService.rejectRequest(id, u.getId());
            return err == null ? ResponseEntity.ok(Map.of("ok", true)) : err(err);
        });
    }

    @PostMapping("/remove")
    public ResponseEntity<?> remove(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody IdBody body) {
        return me(auth, u -> { friendService.removeFriend(u.getId(), body.id()); return ResponseEntity.ok(Map.of("ok", true)); });
    }

    @GetMapping("/messages")
    public ResponseEntity<?> messages(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestParam long peerId) {
        return me(auth, u -> ResponseEntity.ok(friendService.conversation(u.getId(), peerId)));
    }

    @PostMapping("/messages")
    public ResponseEntity<?> send(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody MsgBody body) {
        return me(auth, u -> {
            if (body.content() == null || body.content().isBlank()) return err("消息不能为空");
            String type = "invite".equals(body.type()) ? "invite" : "chat";
            var msg = friendService.sendMessage(u.getId(), body.toId(), body.content(), type, body.roomNo());
            // 非好友等业务拒绝会返回带 error 的结果，此处转为 400 响应
            if (msg != null && msg.get("error") != null) return err(String.valueOf(msg.get("error")));
            return ResponseEntity.ok(msg);
        });
    }

    @GetMapping("/unread")
    public ResponseEntity<?> unread(@RequestHeader(value = "Authorization", required = false) String auth) {
        return me(auth, u -> ResponseEntity.ok(friendService.unread(u.getId())));
    }

    @GetMapping("/profile/{id}")
    public ResponseEntity<?> profile(@RequestHeader(value = "Authorization", required = false) String auth,
                                     @PathVariable long id) {
        return me(auth, u -> {
            Map<String, Object> p = friendService.profile(u.getId(), id);
            return p == null ? err("用户不存在") : ResponseEntity.ok(p);
        });
    }

    private interface Fn { ResponseEntity<?> apply(User u); }

    private ResponseEntity<?> me(String auth, Fn fn) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        return fn.apply(u.get());
    }

    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
