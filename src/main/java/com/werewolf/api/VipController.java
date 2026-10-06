package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.VipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** 用户 VIP：查看会员信息与档位、金币开通。 */
@RestController
@RequestMapping("/api/vip")
public class VipController {

    private final AuthService authService;
    private final VipService vipService;

    public VipController(AuthService authService, VipService vipService) {
        this.authService = authService;
        this.vipService = vipService;
    }

    public record BuyBody(int level) {}

    @GetMapping
    public ResponseEntity<?> info(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        return ResponseEntity.ok(vipService.info(u.get()));
    }

    @PostMapping("/purchase")
    public ResponseEntity<?> purchase(@RequestHeader(value = "Authorization", required = false) String auth,
                                      @RequestBody BuyBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        String err = vipService.purchase(u.get(), body.level());
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        return ResponseEntity.ok(vipService.info(u.get()));
    }
}
