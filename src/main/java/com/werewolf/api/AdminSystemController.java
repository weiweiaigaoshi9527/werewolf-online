package com.werewolf.api;

import com.werewolf.ai.AiService;
import com.werewolf.game.LiveGameService;
import com.werewolf.repo.RoomRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.service.PresenceService;
import com.werewolf.voice.VoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** 后台·系统状态（仅管理员）。 */
@RestController
@RequestMapping("/api/admin/system")
public class AdminSystemController {

    private final AiService aiService;
    private final VoiceService voiceService;
    private final PresenceService presence;
    private final RoomRepository roomRepo;
    private final UserRepository userRepo;
    private final LiveGameService gameService;
    private final com.werewolf.repo.AppSettingRepository settingRepo;

    public AdminSystemController(AiService aiService, VoiceService voiceService, PresenceService presence,
                                 RoomRepository roomRepo, UserRepository userRepo, LiveGameService gameService,
                                 com.werewolf.repo.AppSettingRepository settingRepo) {
        this.aiService = aiService;
        this.voiceService = voiceService;
        this.presence = presence;
        this.roomRepo = roomRepo;
        this.userRepo = userRepo;
        this.gameService = gameService;
        this.settingRepo = settingRepo;
    }

    /** 显示设置：网页最大高度（px，0=不限制）。管理员可改，客户端经 /api/config/display 读取。 */
    @GetMapping("/display")
    public ResponseEntity<?> display() {
        String v = settingRepo.findByKey("webMaxHeight").map(com.werewolf.model.AppSetting::getValue).orElse("0");
        int h = 0;
        try { h = Integer.parseInt(v.trim()); } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("webMaxHeight", h);
        return ResponseEntity.ok(out);
    }

    /** 功能开关：键为功能 id，值为是否启用。关闭的功能在大厅/房间中隐藏，房间模式由服务端拒绝。 */
    @GetMapping("/features")
    public ResponseEntity<?> features() {
        String v = settingRepo.findByKey("featureFlags").map(com.werewolf.model.AppSetting::getValue).orElse("{}");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flags", parseFlags(v));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/features")
    public ResponseEntity<?> saveFeatures(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        Object raw = body.get("flags");
        Map<String, Boolean> clean = new LinkedHashMap<>();
        if (raw instanceof Map) {
            for (Object o : ((Map<?, ?>) raw).entrySet()) {
                Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                clean.put(String.valueOf(e.getKey()), Boolean.TRUE.equals(e.getValue()) || "true".equals(String.valueOf(e.getValue())));
            }
        }
        com.werewolf.model.AppSetting s = settingRepo.findByKey("featureFlags").orElseGet(() -> {
            com.werewolf.model.AppSetting n = new com.werewolf.model.AppSetting();
            n.setKey("featureFlags");
            return n;
        });
        s.setValue(json(clean));
        settingRepo.save(s);
        return ResponseEntity.ok(Map.of("ok", true, "flags", clean));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> parseFlags(String json) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> m = om.readValue(json == null || json.isBlank() ? "{}" : json, Map.class);
            for (Map.Entry<String, Object> e : m.entrySet()) out.put(e.getKey(), Boolean.TRUE.equals(e.getValue()));
        } catch (Exception ignored) {}
        return out;
    }

    private static String json(Map<String, Boolean> m) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m); }
        catch (Exception e) { return "{}"; }
    }

    @PostMapping("/display")
    public ResponseEntity<?> saveDisplay(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        int h = 0;
        try { h = Integer.parseInt(String.valueOf(body.get("webMaxHeight")).trim()); } catch (Exception ignored) {}
        h = Math.max(0, Math.min(h, 100));   // 百分比：占屏幕高度的 0-100%
        com.werewolf.model.AppSetting s = settingRepo.findByKey("webMaxHeight").orElseGet(() -> {
            com.werewolf.model.AppSetting n = new com.werewolf.model.AppSetting();
            n.setKey("webMaxHeight");
            return n;
        });
        s.setValue(String.valueOf(h));
        settingRepo.save(s);
        return ResponseEntity.ok(Map.of("ok", true, "webMaxHeight", h));
    }

    @GetMapping
    public ResponseEntity<?> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", "0.1.0");
        out.put("serverTime", LocalDateTime.now().toString());
        out.put("ai", aiService.publicConfig());
        out.put("voice", voiceService.health());
        out.put("online", presence.snapshot().size());
        out.put("rooms", roomRepo.count());
        out.put("activeGames", gameService.activeGameCount());
        out.put("users", userRepo.count());
        return ResponseEntity.ok(out);
    }
}
