package com.werewolf.api;

import com.werewolf.game.LiveGameService;
import com.werewolf.repo.GameRecordRepository;
import com.werewolf.repo.RoomRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.PresenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务端本地管理端点：仅允许 127.0.0.1 访问（供服务管理 GUI 使用），局域网其他机器不可达。
 */
@RestController
@RequestMapping("/api/admin/local")
public class AdminLocalController {

    private final PresenceService presence;
    private final LiveGameService gameService;
    private final RoomRepository roomRepo;
    private final UserRepository userRepo;
    private final GameRecordRepository recordRepo;

    public AdminLocalController(PresenceService presence, LiveGameService gameService,
                                RoomRepository roomRepo, UserRepository userRepo, GameRecordRepository recordRepo) {
        this.presence = presence;
        this.gameService = gameService;
        this.roomRepo = roomRepo;
        this.userRepo = userRepo;
        this.recordRepo = recordRepo;
    }

    private boolean notLocal(HttpServletRequest req) {
        // 只信任 socket 直连地址，绝不采信 X-Forwarded-For / X-Real-IP：
        // 若服务被反向代理前置，这些头可被伪造，导致远端伪装成 127.0.0.1 调用 /shutdown。
        String ip = req.getRemoteAddr();
        return !("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "localhost".equals(ip));
    }

    /**
     * 额外的 CSRF 防护：本机端点无 Token 鉴权，恶意网页可通过跨站表单 POST 触发 /shutdown。
     * 这里要求请求头带有自定义标记（浏览器跨站简单请求无法携带自定义头，可有效阻断表单 CSRF），
     * 且校验 Host 头必须指向本机，双重兜底。
     */
    private boolean csrfBlocked(HttpServletRequest req) {
        String marker = req.getHeader("X-WW-Local");
        if (marker == null || !"1".equals(marker.trim())) return true;
        String host = req.getHeader("Host");
        if (host == null) return true;
        String h = host.toLowerCase();
        int colon = h.indexOf(':');
        String hostname = colon > 0 ? h.substring(0, colon) : h;
        return !(hostname.equals("localhost") || hostname.equals("127.0.0.1") || hostname.equals("[::1]") || hostname.equals("::1"));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(HttpServletRequest req) {
        if (notLocal(req)) return ResponseEntity.status(403).body(Map.of("error", "仅限本机访问"));
        if (csrfBlocked(req)) return ResponseEntity.status(403).body(Map.of("error", "非法请求来源"));
        Runtime rt = Runtime.getRuntime();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online", presence.snapshot().size());
        m.put("roomsWaiting", roomRepo.countByStatus("WAITING"));
        m.put("roomsPlaying", roomRepo.countByStatus("PLAYING"));
        m.put("liveGames", gameService.activeCount());
        m.put("users", userRepo.count());
        m.put("gamesFinished", recordRepo.count());
        m.put("uptimeSec", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        m.put("memUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        m.put("javaVersion", System.getProperty("java.version"));
        return ResponseEntity.ok(m);
    }

    /** 优雅关停（先返回响应再退出），仅本机。 */
    @PostMapping("/shutdown")
    public ResponseEntity<?> shutdown(HttpServletRequest req) {
        if (notLocal(req)) return ResponseEntity.status(403).body(Map.of("error", "仅限本机访问"));
        if (csrfBlocked(req)) return ResponseEntity.status(403).body(Map.of("error", "非法请求来源"));
        Thread t = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            System.exit(0);
        }, "ww-admin-shutdown");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
