package com.werewolf.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 后台·服务器控制（仅管理员）。
 * restart 以退出码 86 结束，配合 start.bat 的守护循环自动重新拉起；shutdown 以 0 结束不再拉起。
 */
@RestController
@RequestMapping("/api/admin/server")
public class AdminServerController {

    private static final Logger log = LoggerFactory.getLogger(AdminServerController.class);
    public static final int RESTART_CODE = 86;

    @PostMapping("/restart")
    public ResponseEntity<?> restart() {
        log.warn("管理员触发服务器重启（退出码 {}，由 start.bat 守护循环重新拉起）", RESTART_CODE);
        exitSoon(RESTART_CODE);
        return ResponseEntity.ok(Map.of("ok", true, "action", "restarting"));
    }

    @PostMapping("/shutdown")
    public ResponseEntity<?> shutdown() {
        log.warn("管理员触发服务器关闭");
        exitSoon(0);
        return ResponseEntity.ok(Map.of("ok", true, "action", "shutting-down"));
    }

    private void exitSoon(int code) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            System.exit(code);
        }, "server-exit");
        t.setDaemon(false);
        t.start();
    }
}
