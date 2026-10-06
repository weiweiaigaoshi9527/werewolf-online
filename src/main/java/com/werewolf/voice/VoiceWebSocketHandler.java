package com.werewolf.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.config.VoiceProperties;
import com.werewolf.game.LiveGame;
import com.werewolf.game.LiveGameService;
import com.werewolf.model.Room;
import com.werewolf.model.User;
import com.werewolf.service.AuthService;
import com.werewolf.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音通道 /ws/voice：与游戏状态 /ws 分离的二进制通道。
 * 上行：真人发言回合内持续推裸 PCM16(16k 单声道) → 服务端能量 VAD 断句 → ASR → 逐句 TTS 同语种声纹中继并广播。
 * 控制：voice.bind(绑房间) / voice.start(开始录音) / voice.stop(结束并提交转写)。
 * 每用户一个单线程执行器，保证语音片段按序处理且不阻塞 WS 读线程；语音服务不可用则静默降级。
 */
@Component
public class VoiceWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceWebSocketHandler.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuthService authService;
    private final RoomService roomService;
    private final LiveGameService gameService;
    private final VoiceService voiceService;
    private final SpeechDirector director;
    private final VoiceChannel channel;
    private final VoiceProperties props;

    private final Map<Long, VadSegmenter> segmenters = new ConcurrentHashMap<>();
    private final Map<Long, ExecutorService> workers = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(AuthService authService, RoomService roomService, LiveGameService gameService,
                                 VoiceService voiceService, SpeechDirector director, VoiceChannel channel,
                                 VoiceProperties props) {
        this.authService = authService;
        this.roomService = roomService;
        this.gameService = gameService;
        this.voiceService = voiceService;
        this.director = director;
        this.channel = channel;
        this.props = props;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = extractToken(session);
        Optional<User> user = authService.resolve(token);
        if (user.isEmpty()) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        long uid = user.get().getId();
        session.getAttributes().put("userId", uid);
        roomService.findRoomOfUser(uid).ifPresent(r -> session.getAttributes().put("roomId", r.getId()));
        channel.register(uid, session);
        segmenters.put(uid, new VadSegmenter(props));
        workers.put(uid, Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ww-voice-" + uid); t.setDaemon(true); return t; }));
        channel.sendJson(session, Map.of("type", "voice.hello", "enabled", props.isEnabled(),
                "sampleRate", props.getSampleRate()));
        log.info("语音连接建立 uid={}", uid);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long uid = (Long) session.getAttributes().get("userId");
        if (uid == null) return;
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "ping" -> channel.sendJson(session, Map.of("type", "pong", "time", System.currentTimeMillis()));
                case "voice.start" -> onMicStart(session, uid);
                case "voice.stop" -> onMicStop(session, uid);
                default -> { /* 忽略未知控制 */ }
            }
        } catch (Exception e) {
            log.debug("语音控制消息解析失败: {}", e.getMessage());
        }
    }

    private void onMicStart(WebSocketSession session, long uid) {
        Long roomId = (Long) session.getAttributes().get("roomId");
        if (roomId == null || !props.isEnabled()) { channel.sendJson(session, Map.of("type", "voice.denied", "reason", "语音未启用")); return; }
        LiveGame lg = gameService.get(roomId);
        Integer seat = lg == null ? null : lg.userToSeat.get(uid);
        if (seat == null || !gameService.isUserSpeechTurn(roomId, uid)) {
            channel.sendJson(session, Map.of("type", "voice.denied", "reason", "现在没有轮到你发言"));
            return;
        }
        session.getAttributes().put("recording", true);
        lg.voiceSpeakingSeat = seat;
        VadSegmenter v = segmenters.get(uid); if (v != null) v.reset();
        String name = gameService.displayName(lg, seat);
        channel.broadcastSpeaking(roomId, lg.show(seat), name, true);
    }

    private void onMicStop(WebSocketSession session, long uid) {
        session.getAttributes().remove("recording");
        Long roomId = (Long) session.getAttributes().get("roomId");
        if (roomId == null) return;
        ExecutorService ex = workers.get(uid);
        if (ex == null) return;
        // 串行：先把在途语音切完中继，再提交发言（保证 transcript 完整）。
        ex.submit(() -> {
            try {
                VadSegmenter v = segmenters.get(uid);
                if (v != null) { byte[] seg = v.flush(); if (seg != null) handleSegment(roomId, uid, seg); }
                LiveGame lg = gameService.get(roomId);
                Integer seat = lg == null ? null : lg.userToSeat.get(uid);
                if (seat != null) channel.broadcastSpeaking(roomId, lg.show(seat), gameService.displayName(lg, seat), false);
                // 不再自动提交：语音已实时转文字回给本人，最终发言由客户端「结束发言」把语音+手打文本一起提交
            } catch (Exception e) {
                log.warn("语音停止处理失败 uid={}: {}", uid, e.getMessage());
            }
        });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Long uid = (Long) session.getAttributes().get("userId");
        Boolean recording = (Boolean) session.getAttributes().get("recording");
        Long roomId = (Long) session.getAttributes().get("roomId");
        if (uid == null || recording == null || !recording || roomId == null || !props.isEnabled()) return;
        ByteBuffer bb = message.getPayload();
        byte[] pcm = new byte[bb.remaining()];
        bb.get(pcm);
        ExecutorService ex = workers.get(uid);
        VadSegmenter v = segmenters.get(uid);
        if (ex == null || v == null) return;
        ex.submit(() -> {
            try {
                List<byte[]> segs = v.feed(pcm);
                for (byte[] s : segs) handleSegment(roomId, uid, s);
            } catch (Exception e) {
                log.debug("语音上行处理异常 uid={}: {}", uid, e.getMessage());
            }
        });
    }

    /** 每个 uid 的字幕序号（原声帧与字幕共用同一递增序号，前端可按序对齐）。 */
    private final Map<Long, Integer> seqs = new ConcurrentHashMap<>();

    /** 一个 VAD 短语：ASR → 转写。
     *  - 非匿名且未开语音同传（直接语音交流）：把这段【真人原声】直接转发给全场实时收听，
     *    同时转写字幕同步展示（AI 也能读到文字）。声音就是说话人本人的音色，不经 TTS。
     *  - 匿名 / 语音同传：仅把转写回填给说话者本人，真正发声发生在"结束发言/确认"之后
     *    （由 LiveGameService 用统一音色转述，避免边说边回放串音）。 */
    private void handleSegment(long roomId, long uid, byte[] pcmSegment) {
        LiveGame lg = gameService.get(roomId);
        if (lg == null) return;
        Integer seat = lg.userToSeat.get(uid);
        if (seat == null) return;
        String name = gameService.displayName(lg, seat);
        boolean directMode = !lg.anonymous && !lg.voiceMode; // 直接语音交流：不伪装、不同传
        int seq = seqs.merge(uid, 1, Integer::sum);
        if (directMode) {
            // 1) 先把真人原声实时转发全场（自己的浏览器做回声消除，通常也会听到自己，可自然交流）
            channel.broadcastRawAudio(roomId, lg.show(seat), name, seq, pcmSegment, props.getSampleRate());
            // 2) 再做转写：文字广播给全场（真人看到字幕、AI 后续也能读到），并累积进本轮发言。
            //    注意：字幕 kind 必须用 "relay"——前端只有收到 relay 才会把转写回填进发言输入框，
            //    否则玩家说了话但提交时输入框为空，会被当成"过"。（音频帧的 k="raw" 是另一套标记，勿混淆）
            String text = voiceService.transcribe(pcmSegment);
            if (text != null && !text.isBlank()) {
                gameService.appendVoiceTranscript(roomId, seat, text);
                channel.broadcastCaption(roomId, lg.show(seat), name, text, seq, "relay");
            }
        } else {
            String text = voiceService.transcribe(pcmSegment);
            if (text == null || text.isBlank()) return;
            // 说话者本人实时看到转写文字（回填到发言框）；不即时广播语音，避免"边输入边输出"。
            channel.captionToUser(uid, lg.show(seat), name, text, seq, "relay");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long uid = (Long) session.getAttributes().get("userId");
        if (uid == null) return;
        Long roomId = (Long) session.getAttributes().get("roomId");
        if (roomId != null) {
            LiveGame lg = gameService.get(roomId);
            Integer seat = lg == null ? null : lg.userToSeat.get(uid);
            if (seat != null) channel.broadcastSpeaking(roomId, lg.show(seat), gameService.displayName(lg, seat), false);
            if (lg != null && lg.voiceSpeakingSeat == (seat == null ? -1 : seat)) lg.voiceSpeakingSeat = 0;
        }
        channel.unregister(uid, session);
        // 顶号竞态修复：若旧连接关闭时该 uid 已被新连接顶替（channel 中当前不是本 session），
        // 说明 segmenters/workers 已被新连接重建，旧回调不得再按 uid 无条件删除，否则会误删新连接的资源。
        WebSocketSession current = channel.get(uid);
        if (current != null && !current.getId().equals(session.getId())) {
            log.info("语音旧连接关闭，uid={} 已被新连接顶替，跳过资源清理", uid);
            return;
        }
        segmenters.remove(uid);
        seqs.remove(uid);
        ExecutorService ex = workers.remove(uid);
        if (ex != null) ex.shutdown();
    }

    private String extractToken(WebSocketSession session) {
        if (session.getUri() == null || session.getUri().getQuery() == null) return null;
        for (String kv : session.getUri().getQuery().split("&")) {
            if (kv.startsWith("token=")) return URLDecoder.decode(kv.substring(6), StandardCharsets.UTF_8);
        }
        return null;
    }
}
