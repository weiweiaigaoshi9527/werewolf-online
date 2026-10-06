package com.werewolf.api;

import com.werewolf.config.VoiceSettingsStore;
import com.werewolf.voice.VoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 后台·语音服务配置（仅管理员）。保存即持久化到 app_setting.voice 并即时生效。 */
@RestController
@RequestMapping("/api/admin/voice")
public class AdminVoiceController {

    private final VoiceSettingsStore store;
    private final VoiceService voiceService;

    public AdminVoiceController(VoiceSettingsStore store, VoiceService voiceService) {
        this.store = store;
        this.voiceService = voiceService;
    }

    @GetMapping
    public ResponseEntity<?> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", store.current());
        out.put("health", voiceService.health());
        return ResponseEntity.ok(out);
    }

    /** body 为语音配置的（可部分）JSON，字段名与 application.yml 的 voice.* 一致。 */
    @PostMapping
    public ResponseEntity<?> save(@RequestBody String json) {
        try {
            store.applyAndPersist(json);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("config", store.current());
            out.put("health", voiceService.health());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "配置解析失败：" + e.getMessage()));
        }
    }
}
