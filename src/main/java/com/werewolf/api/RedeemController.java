package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.AuthService;
import com.werewolf.service.RedeemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** 用户兑换码兑换。 */
@RestController
@RequestMapping("/api/redeem")
public class RedeemController {

    private final AuthService authService;
    private final RedeemService redeemService;
    private final UserRepository userRepo;

    public RedeemController(AuthService authService, RedeemService redeemService, UserRepository userRepo) {
        this.authService = authService;
        this.redeemService = redeemService;
        this.userRepo = userRepo;
    }

    public record RedeemBody(String code) {}

    @PostMapping
    public ResponseEntity<?> redeem(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestBody RedeemBody body) {
        Optional<User> u = authService.resolve(AuthController.stripBearer(auth));
        if (u.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期"));
        String err = redeemService.redeem(u.get().getId(), body.code());
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        long gold = userRepo.findById(u.get().getId()).map(User::getGold).orElse(0L);
        return ResponseEntity.ok(Map.of("ok", true, "gold", gold));
    }
}
