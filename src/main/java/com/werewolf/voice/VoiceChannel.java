package com.werewolf.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.service.RoomMembership;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音通道注册表与广播原语：维护 userId -> /ws/voice 连接，
 * 并把“字幕(JSON 文本帧)”和“合成语音(带元信息的二进制帧)”按房间成员广播。
 * 与游戏状态用的 /ws 完全分离，互不影响。
 */
@Component
public class VoiceChannel {

    private static final Logger log = LoggerFactory.getLogger(VoiceChannel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RoomMembership membership;

    /** userId -> 语音连接（一人一条，重连顶号）。 */
    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public VoiceChannel(RoomMembership membership) {
        this.membership = membership;
    }

    public void register(long userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old.isOpen() && !old.getId().equals(session.getId())) {
            try { old.close(); } catch (Exception ignored) {}
        }
    }

    public void unregister(long userId, WebSocketSession session) {
        sessions.remove(userId, session);
    }

    public WebSocketSession sessionOf(long userId) { return sessions.get(userId); }

    /** 当前为 userId 注册的语音连接（可能为 null）。供顶号竞态判断：卸载前确认当前连接是否仍是本回调的连接。 */
    public WebSocketSession get(long userId) { return sessions.get(userId); }

    public void sendJson(WebSocketSession s, Map<String, ?> payload) {
        if (s == null || !s.isOpen()) return;
        try {
            String json = mapper.writeValueAsString(payload);
            synchronized (s) { s.sendMessage(new TextMessage(json)); }
        } catch (Exception e) {
            log.debug("语音 JSON 发送失败: {}", e.getMessage());
        }
    }

    /** 向房间所有在线成员广播一条字幕。 */
    public void broadcastCaption(long roomId, int seat, String name, String text, int seq, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "voice.caption");
        m.put("room", roomId); m.put("seat", seat); m.put("name", name);
        m.put("text", text); m.put("seq", seq); m.put("kind", kind);
        for (Long uid : membership.membersOf(roomId)) sendJson(sessions.get(uid), m);
    }

    /** 仅向某一位用户发送字幕（非语音同传房间里，把麦克风转写只回填给说话者本人）。 */
    public void captionToUser(long userId, int seat, String name, String text, int seq, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "voice.caption");
        m.put("seat", seat); m.put("name", name);
        m.put("text", text); m.put("seq", seq); m.put("kind", kind);
        sendJson(sessions.get(userId), m);
    }

    /** 向房间所有在线成员广播一段合成语音（TTS）。payload 为 WAV 字节。 */
    public void broadcastTts(long roomId, int seat, String name, int seq, String kind, long durMs, byte[] wav) {
        if (wav == null) return;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("k", "tts"); meta.put("room", roomId); meta.put("seat", seat);
        meta.put("name", name); meta.put("seq", seq); meta.put("kind", kind); meta.put("durMs", durMs);
        byte[] frame;
        try { frame = AudioEnvelope.encode(mapper.writeValueAsString(meta), wav); }
        catch (Exception e) { return; }
        for (Long uid : membership.membersOf(roomId)) {
            WebSocketSession s = sessions.get(uid);
            if (s == null || !s.isOpen()) continue;
            try { synchronized (s) { s.sendMessage(new BinaryMessage(frame)); } }
            catch (Exception e) { log.debug("语音 TTS 广播失败 uid={}: {}", uid, e.getMessage()); }
        }
    }

    /**
     * 向房间所有在线成员广播一段"真人原声"（PCM16 单声道裸数据，前端按采样率直接播放）。
     * 用于"直接语音交流"模式（非匿名、非语音同传）的房间：麦克风声音直接转发，
     * 不经 TTS 二次转述，保留说话人自己的音色。kinds 固定 "raw"。
     */
    public void broadcastRawAudio(long roomId, int seat, String name, int seq, byte[] pcm16, int sampleRate) {
        if (pcm16 == null || pcm16.length == 0) return;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("k", "raw"); meta.put("room", roomId); meta.put("seat", seat);
        meta.put("name", name); meta.put("seq", seq); meta.put("kind", "raw");
        meta.put("sr", sampleRate);
        byte[] frame;
        try { frame = AudioEnvelope.encode(mapper.writeValueAsString(meta), pcm16); }
        catch (Exception e) { return; }
        for (Long uid : membership.membersOf(roomId)) {
            if (uid == null) continue;
            WebSocketSession s = sessions.get(uid);
            if (s == null || !s.isOpen()) continue;
            try { synchronized (s) { s.sendMessage(new BinaryMessage(frame)); } }
            catch (Exception e) { log.debug("真人原声广播失败 uid={}: {}", uid, e.getMessage()); }
        }
    }

    /** 通知房间成员：某人正在说话 / 说完了（用于座位光环动画）。 */
    public void broadcastSpeaking(long roomId, int seat, String name, boolean speaking) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "voice.speaking");
        m.put("room", roomId); m.put("seat", seat); m.put("name", name); m.put("speaking", speaking);
        for (Long uid : membership.membersOf(roomId)) sendJson(sessions.get(uid), m);
    }
}
