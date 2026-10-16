package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.model.UserTicket;
import com.werewolf.service.AuthService;
import com.werewolf.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** 用户工单：建单、我的工单、查看/回复。 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final AuthService authService;
    private final TicketService ticketService;

    public TicketController(AuthService authService, TicketService ticketService) {
        this.authService = authService;
        this.ticketService = ticketService;
    }

    public record CreateBody(String category, String title, String content, Long targetUserId, String roomNo) {}
    public record ReplyBody(String content) {}
    public record SupportBody(String content) {}

    /* ---------- 在线客服（AI 客服 + 转人工） ---------- */

    /** 当前客服会话快照（无会话时 ticketId 为 null）。 */
    @GetMapping("/support")
    public ResponseEntity<?> support(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        return ResponseEntity.ok(ticketService.supportSnapshot(u.get().getId()));
    }

    /** 给在线客服发一条消息；没有会话会自动建单。客服答复异步生成，前端稍后刷新。 */
    @PostMapping("/support/message")
    public ResponseEntity<?> supportMessage(@RequestHeader(value = "Authorization", required = false) String auth,
                                            @RequestBody SupportBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        Map<String, Object> r = ticketService.supportSend(u.get().getId(), body == null ? null : body.content());
        return r.containsKey("error") ? ResponseEntity.badRequest().body(r) : ResponseEntity.ok(r);
    }

    /** 请求转人工客服。 */
    @PostMapping("/support/human")
    public ResponseEntity<?> supportHuman(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        String err = ticketService.supportHuman(u.get().getId());
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody CreateBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        if (body.content() == null || body.content().trim().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "请填写内容"));
        String content = body.content().trim();
        if (content.length() > 2000) content = content.substring(0, 2000);
        UserTicket t = ticketService.create(u.get().getId(), body.category(), body.title(), content, null, body.targetUserId(), body.roomNo());
        return ResponseEntity.ok(Map.of("ok", true, "id", t.getId()));
    }

    @GetMapping
    public ResponseEntity<?> mine(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        return ResponseEntity.ok(ticketService.listMine(u.get().getId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> thread(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @PathVariable long id) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        Map<String, Object> t = ticketService.thread(id, u.get().getId(), u.get().isAdmin());
        return t == null ? ResponseEntity.badRequest().body(Map.of("error", "工单不存在或无权查看")) : ResponseEntity.ok(t);
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<?> reply(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable long id, @RequestBody ReplyBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        if (body.content() == null || body.content().trim().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "内容不能为空"));
        String err = ticketService.userReply(id, u.get().getId(), body.content());
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }
}
