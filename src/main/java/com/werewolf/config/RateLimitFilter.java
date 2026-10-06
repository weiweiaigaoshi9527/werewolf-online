package com.werewolf.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简易接口限流 Filter（基于单机内存的固定时间窗口计数）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>登录 / 注册接口（{@code /api/auth/login}、{@code /api/auth/register}）：每 IP 每分钟 20 次，防爆破；</li>
 *   <li>其它 {@code /api/**} 接口：每 IP 每分钟 300 次，防滥用；</li>
 *   <li>{@code /ws}、{@code /ws/voice} 及所有静态资源：不进入限流（非 /api 路径直接放行）。</li>
 * </ul>
 *
 * <p>超限返回 HTTP 429，响应体为 {@code {"error":"请求过于频繁，请稍后再试"}}，
 * Content-Type 为 {@code application/json;charset=UTF-8}。</p>
 *
 * <p><b>重要限制</b>：这是“单机内存限流”，计数保存在当前 JVM 的 ConcurrentHashMap 中，
 * 仅对单实例部署有效。多实例 / 集群部署时各节点计数相互独立、无法共享，
 * 需替换为 Redis 等集中式限流方案（如 Redis + Lua 滑动窗口 / 令牌桶）。</p>
 *
 * <p>本类仅依赖 Spring Boot 自带的 {@link OncePerRequestFilter} 与 Servlet API，未引入任何新依赖；
 * 标注 {@code @Component} 后由 Spring Boot 自动注册为 Servlet Filter。</p>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    /** 登录 / 注册接口的每分钟上限（每 IP）。 */
    private static final int LIMIT_AUTH_PER_MINUTE = 20;

    /** 其它 /api 接口的每分钟上限（每 IP）。 */
    private static final int LIMIT_API_PER_MINUTE = 300;

    /** 固定时间窗口长度：1 分钟（毫秒）。 */
    private static final long WINDOW_MS = 60_000L;

    /** 内存中 key 数量的软上限，超过后触发一次全量过期清理，避免内存无限增长。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    /**
     * 计数表：key = "IP|路径"，value = 长度为 2 的数组 [当前窗口计数, 窗口编号]。
     * 窗口编号 = System.currentTimeMillis() / WINDOW_MS（即“第几个绝对分钟”），
     * 窗口变化即视为过期并重新计数，实现固定窗口限流。
     */
    private final Map<String, int[]> counters = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // 预检请求直接放行，避免干扰 CORS / 跨域调用
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = resolvePath(request);
        // 仅对 /api/** 生效：/ws、/ws/voice 与静态资源等非 /api 路径不做限流
        if (path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        int limit = isAuthEndpoint(path) ? LIMIT_AUTH_PER_MINUTE : LIMIT_API_PER_MINUTE;
        String key = resolveClientIp(request) + "|" + path;

        if (tryAcquire(key, limit)) {
            filterChain.doFilter(request, response);
        } else {
            rejectTooManyRequests(response);
        }
    }

    /**
     * 固定窗口计数 + 惰性过期清理。
     *
     * <p>通过 {@link ConcurrentHashMap#compute} 对单个 key 做原子更新：
     * 若 key 不存在或窗口编号已变化（跨分钟），则重置为新窗口的第 1 次；
     * 否则自增。随后若计数超过上限即判定为超限。</p>
     *
     * <p>清理策略：每次访问都对当前 key 做“过期即重置”的惰性清理；
     * 当 map 规模超过 {@link #CLEANUP_THRESHOLD} 时，额外做一次全量清理，
     * 移除所有不属于当前窗口的 key，防止内存无限增长（例如被大量伪造 IP 攻击）。</p>
     *
     * @return true 表示放行，false 表示已超限
     */
    private boolean tryAcquire(String key, int limit) {
        long currentWindow = System.currentTimeMillis() / WINDOW_MS;

        int[] counter = counters.compute(key, (k, v) -> {
            // 首次访问或已跨窗口：重置为新窗口的第 1 次
            if (v == null || (long) v[1] != currentWindow) {
                return new int[]{1, (int) currentWindow};
            }
            // 同窗口内：计数 +1
            v[0] = v[0] + 1;
            return v;
        });

        if (counters.size() > CLEANUP_THRESHOLD) {
            cleanupExpired(currentWindow);
        }

        return counter[0] <= limit;
    }

    /** 全量清理：移除所有窗口编号不等于当前窗口的 key（即上一分钟及更早的过期计数）。 */
    private void cleanupExpired(long currentWindow) {
        Iterator<Map.Entry<String, int[]>> it = counters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, int[]> entry = it.next();
            if ((long) entry.getValue()[1] != currentWindow) {
                it.remove();
            }
        }
    }

    /** 登录 / 注册接口判定（这些接口每 IP 每分钟仅 20 次，用于防爆破）。 */
    private boolean isAuthEndpoint(String path) {
        return path.startsWith("/api/auth/login") || path.startsWith("/api/auth/register");
    }

    /** 取出去掉 contextPath 的请求路径，得到形如 /api/xxx 的规范路径。 */
    private String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String ctx = request.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
            uri = uri.substring(ctx.length());
        }
        return uri;
    }

    /**
     * 解析客户端 IP：优先取反向代理透传的 X-Forwarded-For 首个地址，
     * 否则回退到 socket 直连地址。
     */
    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = (comma > 0 ? xff.substring(0, comma) : xff).trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    /** 返回 429 与统一 JSON 错误体。 */
    private void rejectTooManyRequests(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
    }
}
