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
