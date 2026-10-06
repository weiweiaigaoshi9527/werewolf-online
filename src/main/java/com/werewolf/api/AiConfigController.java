package com.werewolf.api;

import com.werewolf.ai.AiService;
import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/** AI 后台配置（局域网自用，登录即可管理）。 */
@RestController
@RequestMapping("/api/admin/ai")
public class AiConfigController {

    private final AuthService authService;
    private final AiService aiService;

    public AiConfigController(AuthService authService, AiService aiService) {
        this.authService = authService;
        this.aiService = aiService;
    }

    @GetMapping("/config")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolve(auth).isEmpty()) return err("未登录或会话已过期");
        return ResponseEntity.ok(aiService.publicConfig());
    }

    @PostMapping("/config")
    public ResponseEntity<?> save(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestBody AiService.ConfigDto dto) {
        if (resolve(auth).isEmpty()) return err("未登录或会话已过期");
        aiService.saveConfig(dto);
        return ResponseEntity.ok(aiService.publicConfig());
    }

    /** 连通性测试：发一条极短消息，返回是否成功（不落库、不进对局）。 */
    @PostMapping("/test")
    public ResponseEntity<?> test(@RequestHeader(value = "Authorization", required = false) String auth) {
        if (resolve(auth).isEmpty()) return err("未登录或会话已过期");
        if (!aiService.available()) return err("请先填写 base_url/model 并启用 AI");
        try {
            String r = aiService.chat(java.util.List.of(
                    new com.werewolf.ai.AiProvider.Message("user", "只回复两个字：收到")));
            return ResponseEntity.ok(Map.of("ok", true, "reply", r == null ? "" : (r.length() > 60 ? r.substring(0, 60) : r)));
        } catch (Exception e) {
            return err("AI 连接失败：" + e.getMessage());
        }
    }

    private Optional<User> resolve(String auth) {
        return authService.resolve(AuthController.stripBearer(auth));
    }

    private ResponseEntity<?> err(String msg) { return ResponseEntity.badRequest().body(Map.of("error", msg)); }
}
