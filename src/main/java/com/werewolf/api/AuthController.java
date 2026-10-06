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
    private final com.werewolf.service.ProfileService profileService;

    public AuthController(AuthService authService, com.werewolf.service.ProfileService profileService) {
        this.authService = authService;
        this.profileService = profileService;
    }

    public record AuthRequest(String username, String password, String nickname, String qq, Integer rememberDays) {}

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthRequest req) {
        // QQ 号可选：填了必须合法（5-11 位数字）
        String qq = req.qq() == null ? "" : req.qq().trim();
        if (!qq.isEmpty() && !AuthService.validQq(qq)) {
            return ResponseEntity.badRequest().body(Map.of("error", "QQ 号需为 5-11 位数字（不以 0 开头）"));
        }
        AuthService.RegisterResult r = authService.register(req.username(), req.password(), req.nickname());
        if (r.error() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", r.error()));
        }
        if (!qq.isEmpty()) {
            // 注册即填 QQ：直接视为已满足（不发补填奖励），并立即拉取 QQ 头像（失败静默，不阻塞注册）
            try {
                profileService.setQqAtRegister(r.user(), qq);
            } catch (Exception ignored) {}
        }
        AuthService.LoginResult login = authService.login(req.username(), req.password(), 1);
        return ResponseEntity.ok(Map.of(
                "token", login.token(),
                "user", view(login.user())));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthRequest req) {
        int remember = req.rememberDays() == null ? 0 : req.rememberDays();
        AuthService.LoginResult r = authService.login(req.username(), req.password(), remember);
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
        m.put("qq", u.getQq());
        // needQq=true：还没填 QQ 号，前端据此弹强制补填窗（填完奖 1000 金币）
        m.put("needQq", u.getQq() == null || u.getQq().isBlank());
        int vip = (u.getVipLevel() > 0 && (u.getVipExpireAt() == null || u.getVipExpireAt().isAfter(java.time.LocalDateTime.now()))) ? u.getVipLevel() : 0;
        m.put("vipLevel", vip);
        return m;
    }
}
