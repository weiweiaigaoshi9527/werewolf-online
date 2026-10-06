package com.werewolf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.service.SettingsService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 语音配置的持久化：启动时把 app_setting.voice 的 JSON 叠加到 VoiceProperties（覆盖 yml 默认），
 * 后台保存时写回数据库并即时生效（同一 Bean 实例，无需重启）。
 */
@Component
public class VoiceSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(VoiceSettingsStore.class);
    private static final String KEY = "voice";

    private final SettingsService settings;
    private final VoiceProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public VoiceSettingsStore(SettingsService settings, VoiceProperties props) {
        this.settings = settings;
        this.props = props;
    }

    @PostConstruct
    public void load() {
        settings.get(KEY).ifPresent(json -> {
            try {
                mapper.readerForUpdating(props).readValue(json);
                log.info("已从数据库加载语音配置覆盖（enabled={} asr={} tts={}）", props.isEnabled(), props.getAsrUrl(), props.getTtsUrl());
            } catch (Exception e) {
                log.warn("语音配置覆盖解析失败，忽略：{}", e.getMessage());
            }
        });
    }

    /** 用一段（可部分）JSON 覆盖当前配置并持久化。返回覆盖后的完整配置对象。 */
    public VoiceProperties applyAndPersist(String partialJson) throws Exception {
        mapper.readerForUpdating(props).readValue(partialJson);
        settings.put(KEY, mapper.writeValueAsString(props));
        return props;
    }

    public VoiceProperties current() { return props; }
}
