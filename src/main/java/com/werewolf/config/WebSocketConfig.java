package com.werewolf.config;

import com.werewolf.voice.VoiceWebSocketHandler;
import com.werewolf.ws.HallWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Arrays;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final HallWebSocketHandler hallHandler;
    private final VoiceWebSocketHandler voiceHandler;

    /**
     * WebSocket 允许的来源白名单，逗号分隔（对应配置项 ws.allowed-origins）。
     * 默认 "*"：允许全部来源，兼容局域网/内网自用场景（与旧行为一致）。
     * 生产环境务必配置为具体域名，例如 ws.allowed-origins=https://game.example.com,https://www.example.com，
     * 以免任意站点通过浏览器发起跨站 WebSocket 连接（CSWSH 风险）。
     */
    @Value("${ws.allowed-origins:*}")
    private String allowedOrigins;

    public WebSocketConfig(HallWebSocketHandler hallHandler, VoiceWebSocketHandler voiceHandler) {
        this.hallHandler = hallHandler;
        this.voiceHandler = voiceHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 两个入口（大厅 /ws 与语音 /ws/voice）统一使用同一份来源白名单策略
        String[] origins = resolveAllowedOrigins();
        registry.addHandler(hallHandler, "/ws").setAllowedOriginPatterns(origins);
        registry.addHandler(voiceHandler, "/ws/voice").setAllowedOriginPatterns(origins);
    }

    /**
     * 解析配置的来源白名单：为空或 "*" 时返回 {"*"}（允许全部，兼容自用）；
     * 否则按逗号切分并去除空白，用于精确匹配允许的来源。使用 setAllowedOriginPatterns
     * 可在需要时支持 https://*.example.com 这类通配写法。
     */
    private String[] resolveAllowedOrigins() {
        if (allowedOrigins == null || allowedOrigins.isBlank() || "*".equals(allowedOrigins.trim())) {
            return new String[]{"*"};
        }
        String[] parsed = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return parsed.length == 0 ? new String[]{"*"} : parsed;
    }

    /** 放大 WebSocket 收发缓冲，容纳麦克风 PCM 与合成的 WAV 二进制帧。 */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(256 * 1024);
        container.setMaxBinaryMessageBufferSize(1024 * 1024);
        container.setMaxSessionIdleTimeout(0L); // 语音回合可能静置等待发言，不因空闲断开
        return container;
    }
}
