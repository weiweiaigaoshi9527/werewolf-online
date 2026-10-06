package com.werewolf.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.config.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本地两枚 Python 微服务的 HTTP 客户端：
 *  - ASR：POST {asrUrl}/transcribe?sr=16000，裸 PCM16 单声道请求体，返回 {"text":"带标点整句"}。
 *  - TTS：POST {ttsUrl}/tts {"text","voice","speed"}，返回 audio/wav 裸字节。
 * 任一服务不可达/异常都优雅降级：返回 null，由上层退回纯文字，绝不抛出打断对局。
 */
@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    private final VoiceProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public VoiceService(VoiceProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)   // uvicorn 仅 HTTP/1.1，避免 h2c Upgrade 被 h11 拒绝
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean active() { return props.isEnabled(); }

    /** 识别一段裸 PCM16（单声道，采样率 props.sampleRate）为带标点文本。失败返回 null。 */
    public String transcribe(byte[] pcm16) {
        if (!props.isEnabled() || pcm16 == null || pcm16.length < 320) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getAsrUrl() + "/transcribe?sr=" + props.getSampleRate()))
                    .timeout(Duration.ofSeconds(props.getServiceTimeoutSeconds()))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pcm16))
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("ASR HTTP {} body={}", resp.statusCode(), new String(resp.body()));
                return null;
            }
            Map<?, ?> j = mapper.readValue(resp.body(), Map.class);
            Object t = j.get("text");
            return t == null ? null : cleanPunct(t.toString().trim());
        } catch (Exception e) {
            log.warn("ASR 调用失败（降级不识别）：{}", e.getMessage());
            return null;
        }
    }

    /** 折叠 SenseVoice/标点模型常见的重复标点，并去掉句首标点。 */
    private String cleanPunct(String s) {
        if (s.isEmpty()) return s;
        s = s.replaceAll("([，。！？、；：,.!?;:])\\1+", "$1");
        s = s.replaceAll("^[，。、；：,.!?;:]+", "");
        return s.trim();
    }

    /** 合成一句话为 WAV 字节。失败返回 null。 */
    public byte[] synthesize(String text, String voice, double speed) {
        if (!props.isEnabled() || text == null || text.isBlank()) return null;
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", text);
            body.put("voice", voice);
            body.put("speed", speed);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getTtsUrl() + "/tts"))
                    .timeout(Duration.ofSeconds(props.getServiceTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("TTS HTTP {} body={}", resp.statusCode(), new String(resp.body()));
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("TTS 调用失败（降级不出声）：{}", e.getMessage());
            return null;
        }
    }

    /** 从 WAV 字节估算播放时长（毫秒）。解析失败返回按字数估算的兜底值。 */
    public long wavDurationMs(byte[] wav, int fallbackChars) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
            if (wav.length < 44 || bb.getInt(0) != 0x52494646) throw new IllegalArgumentException("not wav");
            int byteRate = bb.getInt(28); // 每秒字节数
            int dataSize = wav.length - 44;
            if (byteRate > 0) return (long) dataSize * 1000L / byteRate;
        } catch (Exception ignored) {}
        return (long) fallbackChars * props.getMsPerChar();
    }

    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        out.put("asr", probe(props.getAsrUrl() + "/health"));
        out.put("tts", probe(props.getTtsUrl() + "/health"));
        return out;
    }

    private Map<String, Object> probe(String url) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (!props.isEnabled()) { r.put("ok", false); r.put("reason", "语音总开关未启用"); return r; }
        try {
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            r.put("ok", resp.statusCode() == 200);
            r.put("body", resp.body());
        } catch (Exception e) {
            r.put("ok", false);
            r.put("reason", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return r;
    }
}
