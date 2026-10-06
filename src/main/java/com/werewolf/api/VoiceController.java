package com.werewolf.api;

import com.werewolf.config.VoiceProperties;
import com.werewolf.voice.VoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 语音服务状态：前端据此决定麦克风按钮/语音模式开关是否可用，并显示健康指示。 */
@RestController
@RequestMapping("/api/voice")
public class VoiceController {

    private final VoiceService voiceService;
    private final VoiceProperties props;

    public VoiceController(VoiceService voiceService, VoiceProperties props) {
        this.voiceService = voiceService;
        this.props = props;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("allowVoiceMode", props.isAllowVoiceMode() && props.isEnabled());
        out.put("sampleRate", props.getSampleRate());
        out.put("health", voiceService.health());
        return ResponseEntity.ok(out);
    }
}
