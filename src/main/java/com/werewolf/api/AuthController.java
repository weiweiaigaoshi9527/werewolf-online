package com.werewolf.api;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record AuthRequest(String username, String password, String nickname, Integer rememberDays, String scope) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest req) {
        AuthService.RegisterResult r = authService.register(req.username(), req.password(), req.nickname());
        if (r.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", r.error()));
        }
        AuthService.LoginResult login = authService.login(req.username(), req.password(), 1);
        return ResponseEntity.ok(Map.of(
                "token", login.token(),
                "user", view(login.user())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest req) {
        int remember = req.rememberDays() == null ? 0 : req.rememberDays();
        // scope=admin 时登录到「后台会话域」：与玩家端会话互不挤掉（同一账号可同时登录两端）
        AuthService.LoginResult r = authService.login(req.username(), req.password(), remember, req.scope());
        if (r.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", r.error()));
        }
        return ResponseEntity.ok(Map.of(
                "token", r.token(),
                "user", view(r.user())));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String auth) {
        authService.logout(stripBearer(auth));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String auth) {
        return authService.resolve(stripBearer(auth))
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(view(u)))
                .orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "未登录或会话已过期")));
    }

    static String stripBearer(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7);
        }
        return auth;
    }

    static Map<String, Object> view(User u) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickname", u.getNickname());
        m.put("avatarId", u.getAvatarId());
        m.put("avatarUrl", u.getAvatarUrl());
        m.put("nickColor", u.getNickColor());
        m.put("frameId", u.getFrameId());
        m.put("titleId", u.getTitleId());
        m.put("level", u.getLevel());
        m.put("exp", u.getExp());
        m.put("gold", u.getGold());
        m.put("admin", u.isAdmin());
        m.put("banned", u.isBanned());
        int vip = (u.getVipLevel() > 0 && (u.getVipExpireAt() == null || u.getVipExpireAt().isAfter(java.time.LocalDateTime.now()))) ? u.getVipLevel() : 0;
        m.put("vipLevel", vip);
        return m;
    }
}
