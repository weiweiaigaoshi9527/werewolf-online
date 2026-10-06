package com.werewolf.voice;

import com.werewolf.config.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 逐句发声编排：把整段文本按逗号/句号等切成短句，一句一句“字幕 + 合成语音”广播给房间，
 * 句间按 字数×每字时长 + 随机停顿 节流，模拟真人说话节奏（不一次性连续念完）。
 * 由调用方在独立线程上调用（AI 回合线程 / 语音中继执行器），本方法会阻塞到全部播完。
 */
@Component
public class SpeechDirector {

    private static final Logger log = LoggerFactory.getLogger(SpeechDirector.class);
    private final VoiceService voice;
    private final VoiceChannel channel;
    private final VoiceProperties props;
    private final Random rnd = new Random();

    /** 每个房间一把“发声锁”：保证同一房间内旁白 / AI 逐句语音串行播出，互不叠加（避免遗言与旁白同时发声“打架”）。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Object> roomLocks = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每个房间锁对象的“最后访问时间”（毫秒时间戳），用于防泄漏：配合 sweepIdleRooms 清理长期未使用的锁对象。 */
    private final java.util.concurrent.ConcurrentHashMap<Long, Long> lastUsed = new java.util.concurrent.ConcurrentHashMap<>();

    /** 取房间发声锁，同时刷新其最后访问时间（供 sweepIdleRooms 判定空闲）。 */
    private Object roomLock(long roomId) {
        lastUsed.put(roomId, System.currentTimeMillis());
        return roomLocks.computeIfAbsent(roomId, k -> new Object());
    }

    /**
     * 房间结束（销毁）时调用，释放该房间的发声锁，避免 roomLocks 随房间数增长而无界泄漏。
     * <p>接线说明：房间生命周期由 LiveGameService 管理，而该类不在本次授权的可改文件范围内，
     * 故本方法仅在此暴露、未强行接线。**应由房间销毁处调用** director.removeRoom(roomId)。</p>
     */
    public void removeRoom(long roomId) {
        roomLocks.remove(roomId);
        lastUsed.remove(roomId);
    }

    /**
     * 清理“空闲超过 idleMillis 未使用”的房间锁（防泄漏兜底，避免漏调用 removeRoom 时 roomLocks 无界增长）。
     * <p>判定依据为 lastUsed 中记录的最后访问时间：若当前距离上次访问已超过阈值，则移除该房间的锁对象与时间戳。
     * 阈值应显著大于单次逐句发声的最长耗时（含句间停顿），以免误清理正在使用的锁。</p>
     * <p>接线说明：本方法可挂到任意定时任务/调度器上周期执行；但本类位于可改白名单，调度器接线点在其它文件，
     * 为避免改动非授权文件，此处只提供方法。**应由房间销毁处或全局定时任务周期调用**。
     * 若在房间销毁处已调用 {@link #removeRoom(long)}，本方法可作为兜底。</p>
     */
    public void sweepIdleRooms(long idleMillis) {
        if (idleMillis <= 0) return;
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<Long, Long> e : lastUsed.entrySet()) {
            Long t = e.getValue();
            if (t == null || now - t >= idleMillis) {
                long roomId = e.getKey();
                lastUsed.remove(roomId, t);
                roomLocks.remove(roomId);
            }
        }
    }

    /** 当前仍被缓存的房间锁数量，便于观测 roomLocks 是否泄漏（正常应随房间销毁回落）。 */
    public int roomLockCount() { return roomLocks.size(); }

    public SpeechDirector(VoiceService voice, VoiceChannel channel, VoiceProperties props) {
        this.voice = voice;
        this.channel = channel;
        this.props = props;
    }

    /**
     * 把 text 逐句发声广播。kind: "ai"（AI 发言）或 "relay"（真人语音中继）。
     * @return 实际播出的完整文本（可能被截断到 maxSpeechChars / maxSentencesPerTurn）。
     */
    public String speak(long roomId, int seat, String name, String text, String kind) {
        return speak(roomId, seat, name, text, kind, null);
    }

    /** voiceOverride 传具体音色（按性别解析）；为 null 时回退到该座位默认音色。 */
    public String speak(long roomId, int seat, String name, String text, String kind, String voiceOverride) {
        if (text == null) text = "";
        List<String> clauses = splitClauses(text);
        if (clauses.isEmpty()) return "";
        // 真人语音中继要近实时，不排队；其余（旁白 / AI）按房间串行，杜绝两路语音同时播出。
        if ("relay".equals(kind)) return speakLocked(roomId, seat, name, clauses, kind, voiceOverride, true);
        synchronized (roomLock(roomId)) {
            return speakLocked(roomId, seat, name, clauses, kind, voiceOverride, false);
        }
    }

    private String speakLocked(long roomId, int seat, String name, List<String> clauses, String kind, String voiceOverride, boolean relay) {
        String voiceId = (voiceOverride != null && !voiceOverride.isBlank()) ? voiceOverride : props.voiceForSeat(seat);
        // AI 发言/旁白：句间加“思考”停顿，模拟人一句句说；真人语音中继：背靠背发送，由客户端顺序播放（近实时同传）。
        boolean pace = !relay;

        if (pace) sleep(randBetween(props.getLeadInMinMs(), props.getLeadInMaxMs()));

        int seq = 0;
        for (String c : clauses) {
            seq++;
            channel.broadcastCaption(roomId, seat, name, c, seq, kind);
            byte[] wav = voice.synthesize(c, voiceId, 1.0);
            long estMs = (wav != null) ? voice.wavDurationMs(wav, c.length())
                    : (long) c.length() * props.getMsPerChar();
            long durMs = estMs;
            channel.broadcastTts(roomId, seat, name, seq, kind, durMs, wav);
            if (pace) sleep(estMs + randBetween(props.getGapMinMs(), props.getGapMaxMs()));
        }
        return String.join("", clauses);
    }

    /** 仅合成不播放（供测试/预取）；失败返回 null。 */
    public byte[] synthForSeat(String text, int seat) {
        return voice.synthesize(text, props.voiceForSeat(seat), 1.0);
    }

    /** 按逗号/句号/问号/叹号/分号切句，保留标点；合并过短碎片；限制句数与总字数。 */
    public List<String> splitClauses(String text) {
        String t = text.replaceAll("[\\r\\n]+", " ").trim();
        int cap = props.getMaxSpeechChars();
        if (t.length() > cap) t = t.substring(0, cap);
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            cur.append(ch);
            boolean boundary = (ch == '，' || ch == '。' || ch == '！' || ch == '？' || ch == '；'
                    || ch == ',' || ch == '.' || ch == '!' || ch == '?' || ch == ';' || ch == ':' || ch == '：');
            if (boundary) {
                String seg = cur.toString().trim();
                if (!seg.isEmpty()) {
                    if (!out.isEmpty() && seg.length() < 3) {
                        out.set(out.size() - 1, out.get(out.size() - 1) + seg); // 并入前句
                    } else {
                        out.add(seg);
                    }
                }
                cur.setLength(0);
                if (out.size() >= props.getMaxSentencesPerTurn()) break;
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty() && out.size() < props.getMaxSentencesPerTurn()) out.add(tail);
        return out;
    }

    private void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int randBetween(int a, int b) {
        if (b <= a) return Math.max(0, a);
        return a + rnd.nextInt(b - a + 1);
    }
}
