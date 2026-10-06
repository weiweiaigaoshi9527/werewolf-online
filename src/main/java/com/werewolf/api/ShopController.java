package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.ShopService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/shop")
public class ShopController {

    private final AuthService authService;
    private final ShopService shopService;

    public ShopController(AuthService authService, ShopService shopService) {
        this.authService = authService;
        this.shopService = shopService;
    }

    public record BuyBody(long itemDefId) {}

    @GetMapping("/catalog")
    public ResponseEntity<?> catalog(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        return ResponseEntity.ok(shopService.catalog(u.get()));
    }

    @GetMapping("/inventory")
    public ResponseEntity<?> inventory(@RequestHeader(value = "Authorization", required = false) String auth) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        return ResponseEntity.ok(shopService.inventory(u.get()));
    }

    @PostMapping("/buy")
    public ResponseEntity<?> buy(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestBody BuyBody body) {
        Optional<User> u = resolve(auth);
        if (u.isEmpty()) return err("未登录或会话已过期");
        String error = shopService.buy(u.get(), body.itemDefId());
        if (error != null) return err(error);
        return ResponseEntity.ok(Map.of("ok", true, "gold", u.get().getGold()));
    }

    private Optional<User> resolve(String auth) {
        return authService.resolve(AuthController.stripBearer(auth));
    }

    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
