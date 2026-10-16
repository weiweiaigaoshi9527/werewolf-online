package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** 后台·工单处理（仅管理员，鉴权由 AdminInterceptor 处理）。 */
@RestController
@RequestMapping("/api/admin/tickets")
public class AdminTicketController {

    private final TicketService ticketService;
    private final AuthService authService;

    public AdminTicketController(TicketService ticketService, AuthService authService) {
        this.ticketService = ticketService;
        this.authService = authService;
    }

    public record ReplyBody(String content, String status) {}
    public record StatusBody(String status) {}

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false, defaultValue = "OPEN") String status,
                                  @RequestParam(required = false) String category) {
        return ResponseEntity.ok(ticketService.adminList(status, category));
    }

    /** 查看工单完整对话（含「在线客服」自动答复与人工回复），供后台客服直接接手聊天。 */
    @GetMapping("/{id}")
    public ResponseEntity<?> thread(@PathVariable long id) {
        Map<String, Object> t = ticketService.thread(id, 0L, true);
        return t == null ? ResponseEntity.badRequest().body(Map.of("error", "工单不存在")) : ResponseEntity.ok(t);
    }

    @PostMapping("/{id}/reply")
    public ResponseEntity<?> reply(@RequestHeader(value = "Authorization", required = false) String auth,
                                   @PathVariable long id, @RequestBody ReplyBody body) {
        long adminId = authService.resolve(AuthController.stripBearer(auth)).map(User::getId).orElse(0L);
        String err = ticketService.adminReply(id, adminId, body.content(), body.status());
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<?> status(@PathVariable long id, @RequestBody StatusBody body) {
        String err = ticketService.setStatus(id, body.status());
        return err == null ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.badRequest().body(Map.of("error", err));
    }
}
