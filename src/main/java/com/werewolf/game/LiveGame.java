package com.werewolf.game;

import com.werewolf.rules.GameEngine;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;

/** 一局进行中的对局（内存态）。 */
public class LiveGame {
    public final long roomId;
    public final String roomNo;
    /** 发言音频账本：最近一次登记的 TTS 音频预计播完时刻。用于把"界面推进"与"声音播放"对齐到同一频道。 */
    public volatile long speechAudioUntil = 0L;
    /** 音频闸门防重入：已有一次"等音频"的延迟推进在排队时，其余 pump 调用直接返回。 */
    public volatile boolean audioGateBusy = false;
    public final GameEngine engine;
    public final Map<Integer, SeatInfo> seats;      // seat -> info
    public final Map<Long, Integer> userToSeat;     // 真人 userId -> seat
    public final Random rnd;
    public final boolean huntByBorder;

    public volatile ScheduledFuture<?> timer;
    public volatile ScheduledFuture<?> aiWatchdog;
    public volatile long phaseDeadlineMs;           // 当前阶段截止时刻（供 UI 倒计时）
    public volatile int waitingHumanSeat;           // 当前等待的真人座位（0=无）
    public volatile int waitingAiSeat;              // 当前等待的 AI 座位（0=无）
    public volatile boolean finished = false;
    /** 结束落库后的对局记录 id（供结算页点赞/举报/安慰关联）。 */
    public volatile Long gameId;
    /** 对局开始时刻（毫秒），用于统计各角色使用时长。 */
    public final long startMs = System.currentTimeMillis();
    /** 上一次旁白播报的阶段（用于只在阶段切换时旁白一次）。 */
    public volatile com.werewolf.rules.GamePhase lastNarrPhase = null;
    /** 已播报“天黑/夜幕降临”的天数（每晚只播一次）。 */
    public volatile int narratedNightDay = -1;
    /** 已播报“天亮被杀结果”的天数（每天首个白天阶段只播一次）。 */
    public volatile int narratedDawnDay = -1;
    /** 本夜上一个“已睁眼”的角色名（用于在其行动结束后再播报“XX请闭眼”）。 */
    public volatile String lastNightOpen = null;
    /** 上一次播报“请X号发表遗言”的遗言座位（用于每位遗言者各播一次，避免只播首位）。 */
    public volatile int lastWordsSeat = 0;
    /** 旁白待播队列（FIFO）：按顺序播报，不丢句，保证旁白与阶段一一对应、跟得上系统行动。 */
    public final java.util.ArrayDeque<String> narrationQueue = new java.util.ArrayDeque<>();
    /** 旁白队列的监视器：drain 线程与对局线程通过它协调“旁白是否已播完”。 */
    public final Object narrationLock = new Object();
    /** 是否有旁白正在播报（true=播放中，false=空闲）。 */
    public volatile boolean narrationBusy = false;
    /** 正在播报的这条旁白的预计总时长(ms)与开始时刻，用于估算“还要多久播完”。 */
    public volatile long narrationPlayingMs = 0;
    public volatile long narrationPlayingStart = 0;
    /** 房主强制结束标记：置为 true 后，AI 回合线程在检查点尽快退出，进行中的 LLM 调用被中断取消。 */
    public volatile boolean aborted = false;
    /** 当前 AI 回合的 Future，供强制结束时 cancel(true) 中断阻塞的 HTTP 调用。 */
    public volatile java.util.concurrent.Future<?> aiFuture;

    /* ---------- 语音 / 匿名 ---------- */
    public final long hostUserId;                   // 房主（用于强制结束鉴权 + 前端按钮可见性）
    public final boolean voiceMode;
    public final boolean anonymous;
    public final boolean itemMatch;                 // 是否功能道具赛（关闭则携带道具本局不生效、结算不双倍/不消耗）
    /** 匿名模式下：realSeat(1..N) -> 对外显示号(随机置换)；非匿名为 null。 */
    public final int[] showOf;
    /** 显示号 -> 真实座位（showOf 的逆）。 */
    public final int[] realOf;
    /** seat -> 当前发言轮次累积的 ASR 转写（真人语音中继）。 */
    public final java.util.Map<Integer, StringBuilder> voiceTranscripts = new java.util.concurrent.ConcurrentHashMap<>();
    /** 当前正在语音发言的座位（0=无），用于麦克风开/关时切换光环与提交守卫。 */
    public volatile int voiceSpeakingSeat = 0;

    public LiveGame(long roomId, String roomNo, GameEngine engine, Map<Integer, SeatInfo> seats,
                    Map<Long, Integer> userToSeat, long seed, boolean huntByBorder,
                    boolean voiceMode, boolean anonymous, boolean itemMatch, long hostUserId) {
        this.roomId = roomId;
        this.roomNo = roomNo;
        this.engine = engine;
        this.seats = seats;
        this.userToSeat = userToSeat;
        this.rnd = new Random(seed);
        this.huntByBorder = huntByBorder;
        this.voiceMode = voiceMode;
        this.anonymous = anonymous;
        this.itemMatch = itemMatch;
        this.hostUserId = hostUserId;
        // 匿名打乱改为“座位分配洗牌”（在 LiveGameService.start 里重排玩家到 1..N 号位），
        // 因此显示号=座位号=行动/发言顺序，界面从左到右即回合顺序。showOf 保持恒等。
        this.showOf = null;
        this.realOf = null;
    }

    /** 真实座位 -> 对外显示号（非匿名时原样返回）。 */
    public int show(int realSeat) { return (showOf == null || realSeat <= 0 || realSeat >= showOf.length) ? realSeat : showOf[realSeat]; }

    /** 对外显示号 -> 真实座位（非匿名时原样返回）。 */
    public int real(int dispSeat) { return (realOf == null || dispSeat <= 0 || dispSeat >= realOf.length) ? dispSeat : realOf[dispSeat]; }

    /** 追加一段某座位的语音转写（中继时逐句累积）。 */
    public void appendTranscript(int seat, String text) {
        if (text == null || text.isEmpty()) return;
        voiceTranscripts.computeIfAbsent(seat, k -> new StringBuilder()).append(text);
    }

    public String takeTranscript(int seat) {
        StringBuilder sb = voiceTranscripts.remove(seat);
        return sb == null ? "" : sb.toString().trim();
    }
}
