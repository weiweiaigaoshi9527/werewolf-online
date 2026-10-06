package com.werewolf.config;

import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/** 后台接口鉴权：/api/admin/** 必须登录且是管理员，否则 401/403。服务器权威，前端隐藏只是辅助。 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    public AdminInterceptor(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String token = bearer(req.getHeader("Authorization"));
        Optional<User> u = authService.resolve(token);
        if (u.isEmpty()) {
            deny(resp, 401, "未登录或会话已过期");
            return false;
        }
        if (!u.get().isAdmin()) {
            deny(resp, 403, "需要管理员权限");
            return false;
        }
        return true;
    }

    private String bearer(String auth) {
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7);
        return auth;
    }

    private void deny(HttpServletResponse resp, int code, String msg) throws Exception {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
