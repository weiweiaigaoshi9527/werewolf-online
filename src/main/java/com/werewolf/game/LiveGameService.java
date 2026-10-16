package com.werewolf.game;

import com.werewolf.ai.AiPolicy;
import com.werewolf.ai.AiService;
import com.werewolf.config.VoiceProperties;
import com.werewolf.voice.SpeechDirector;
import com.werewolf.model.GameEventLog;
import com.werewolf.model.GameRecord;
import com.werewolf.model.ItemDefinition;
import com.werewolf.model.User;
import com.werewolf.repo.GameEventLogRepository;
import com.werewolf.repo.GameRecordRepository;
import com.werewolf.repo.ItemDefinitionRepository;
import com.werewolf.repo.UserRepository;
import com.werewolf.rules.*;
import com.werewolf.rules.sim.RandomPolicy;
import com.werewolf.service.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/**
 * 进行中对局的编排服务：驱动规则引擎，真人回合暂停等待 REST 提交，
 * AI 回合异步调用 LLM（失败降级随机），纯托管同步随机，每次变化按可见性推送，结束落库。
 */
@Service
public class LiveGameService {

    private static final Logger log = LoggerFactory.getLogger(LiveGameService.class);
    /**
     * AI/托管座位的默认昵称池。
     * 数量必须 ≥ 最大板子人数（24）：否则座位数超过池大小时会按取模回绕，出现两个 AI 同名。
     */
    private static final String[] BOT_NAMES = {
            "月影", "银狼", "夜莺", "灰羽", "烛火", "雾隐", "孤星", "寒鸦", "暮色", "幽兰", "赤瞳", "青岚",
            "白露", "疏影", "长风", "惊蛰", "子规", "望舒", "南栀", "北辰", "半夏", "流萤", "拾光", "山鬼"
    };

    /** 可选默认头像数量，与前端 app.js 中 avatarEmoji 的列表长度保持一致。 */
    private static final int BOT_AVATAR_COUNT = 20;

    /**
     * 狼人白天自爆的动作标记。
     * GameAction 没有 blowUp 字段，且白天 currentActionKind 仍为 SPEECH/VOTE 等，
     * 因此用约定的文本标记触发“附加可选动作”：前端/AI 提交该标记即表示自爆。
     * 白狼王自爆时目标放在 target 上（带走一人）；普通狼人自爆 target 必须为 0（不带人）。
     * 这样无需修改 currentActionKind 的语义，也不影响正常发言/投票流程。
     */
    public static final String BLOWUP_MARKER = "__WHITE_WOLF_BLOWUP__";
    /** 纯托管/超时兜底时，白狼王选择自爆的概率（避免托管必定自爆，保留正常流程）。 */
    private static final double AUTO_BLOWUP_CHANCE = 0.25;

    private final Map<Long, LiveGame> active = new ConcurrentHashMap<>();
    private final PresenceService presence;
    private final GameRecordRepository recordRepo;
    private final GameEventLogRepository eventRepo;
    private final SettlementService settlement;
    private final AiPolicy aiPolicy;
    private final AiService aiService;
    private final UserRepository userRepo;
    private final ItemDefinitionRepository itemRepo;
    private final com.werewolf.service.RoomMembership membership;
    private final com.werewolf.repo.RoomRepository roomRepo;
    private final com.werewolf.ws.WsPush wsPush;
    private final VoiceProperties vprops;
    private final SpeechDirector director;
    private final com.werewolf.service.BoardService boardService;
    private final GamePersistenceService persistence;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> { Thread t = new Thread(r, "ww-game"); t.setDaemon(true); return t; });
    private final ExecutorService aiExecutor =
            Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "ww-ai"); t.setDaemon(true); return t; });
    private final ExecutorService narratorExec =
            Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "ww-narrator"); t.setDaemon(true); return t; });
    /** 状态推送线程池：把 WebSocket 发送移出对局锁，避免慢客户端阻塞整局推进。 */
    private final ExecutorService pushExecutor =
            Executors.newFixedThreadPool(2, r -> { Thread t = new Thread(r, "ww-push"); t.setDaemon(true); return t; });
    private final RandomPolicy autoPolicy = new RandomPolicy();

    public LiveGameService(PresenceService presence, GameRecordRepository recordRepo,
                           GameEventLogRepository eventRepo, SettlementService settlement,
                           AiPolicy aiPolicy, AiService aiService,
                           UserRepository userRepo, ItemDefinitionRepository itemRepo,
                           com.werewolf.service.RoomMembership membership,
                           com.werewolf.repo.RoomRepository roomRepo,
                           com.werewolf.ws.WsPush wsPush,
                           VoiceProperties vprops, SpeechDirector director,
                           com.werewolf.service.BoardService boardService,
                           GamePersistenceService persistence) {
        this.presence = presence;
        this.recordRepo = recordRepo;
        this.eventRepo = eventRepo;
        this.settlement = settlement;
        this.aiPolicy = aiPolicy;
        this.aiService = aiService;
        this.userRepo = userRepo;
        this.itemRepo = itemRepo;
        this.membership = membership;
        this.roomRepo = roomRepo;
        this.wsPush = wsPush;
        this.vprops = vprops;
        this.director = director;
        this.boardService = boardService;
        this.persistence = persistence;
    }

    public LiveGame get(long roomId) { return active.get(roomId); }

    /** 进行中对局数（供管理端点）。 */
    public int activeCount() { return active.size(); }

    /** 当前进行中的对局数（后台系统状态用）。 */
    public int activeGameCount() { return active.size(); }

    /* ================= 重启恢复 ================= */

    /**
     * 启动时恢复未结束的对局：读取落盘快照，重建 LiveGame 并重新驱动。
     * 必须在「残留房间自愈」(StaleGameResetter) 之前执行，否则未完成的房间会被误复位为等待中。
     * 仅在对应房间仍存在且状态为 PLAYING 时恢复，否则丢弃该快照。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    public void restoreAll() {
        int n = 0;
        try {
            for (GameSnapshot s : persistence.loadAll()) {
                try {
                    com.werewolf.model.Room room = roomRepo.findById(s.roomId).orElse(null);
                    if (room == null || !"PLAYING".equals(room.getStatus())) {
                        persistence.delete(s.roomId);
                        continue;
                    }
                    if (active.containsKey(s.roomId)) continue;
                    LiveGame lg = new LiveGame(s.roomId, s.roomNo, s.engine, s.seats, s.userToSeat, s.seed,
                            s.huntByBorder, s.voiceMode, s.anonymous, s.itemMatch, s.hostUserId, s.aiOnly);
                    lg.gameId = s.gameId;
                    if (active.putIfAbsent(s.roomId, lg) != null) continue;
                    n++;
                    log.info("恢复未结束对局 room={} 阶段={} 天数={}", s.roomNo, s.engine.phase(), s.engine.day());
                    pump(lg);
                } catch (Exception e) {
                    log.warn("恢复对局失败 room={}: {}", s.roomId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("启动恢复对局异常: {}", e.getMessage());
        }
        if (n > 0) log.info("启动恢复：{} 局进行中的对局已继续", n);
    }

    /** 服务优雅关闭：把所有进行中的对局全量落盘一次，确保重启后可从最近状态继续。 */
    @jakarta.annotation.PreDestroy
    public void onShutdown() {
        int n = 0;
        for (LiveGame lg : active.values()) {
            try { persistence.saveNow(lg); n++; } catch (Exception ignored) {}
        }
        log.info("服务关闭：已保存 {} 局进行中的对局快照", n);
    }

    /* ================= 开始 ================= */

    public void start(long roomId, String roomNo, List<SeatInfo> knownSeats, BoardConfig board,
                      boolean allBots, boolean huntByBorder, long seed, boolean voiceMode, boolean anonymous,
                      boolean itemMatch, long hostUserId, boolean aiOnly) {
        int total = board.total();
        List<SeatInfo> seats = new ArrayList<>();
        Map<Long, Integer> userToSeat = new HashMap<>();

        if (!allBots) {
            int s = 1;
            for (SeatInfo h : knownSeats) {
                SeatInfo hi = new SeatInfo(s, h.userId, h.nickname, h.avatarId, h.bot,
                        h.avatarUrl, h.nickColor, h.frameColor, h.title, h.vip, h.skin3d);
                seats.add(hi);
                if (!h.bot) userToSeat.put(h.userId, s);
                s++;
            }
            for (; s <= total; s++) seats.add(botSeat(s, seed));
        } else {
            for (int s = 1; s <= total; s++) seats.add(botSeat(s, seed));
        }

        if (anonymous) {
            // 座位分配洗牌：随机把玩家重排到 1..N 号位，使“显示号 = 座位号 = 行动/发言顺序”，
            // 界面从左到右即回合顺序；同时保留号码打乱（玩家拿到的号是随机的）以隐藏身份。
            Collections.shuffle(seats, new Random(seed ^ 0x5EED123L));
            // 匿名模式额外打乱 3D 皮肤：皮肤本身也是身份线索（熟人能凭造型认出是谁），
            // 这里用同一局种子做确定性重排，保证所有客户端看到的一致、且与真实身份解耦。
            List<String> skinPool = new ArrayList<>();
            for (SeatInfo si : seats) skinPool.add(si.skin3d);
            Collections.shuffle(skinPool, new Random(seed ^ 0x5A1E7C3L));
            userToSeat.clear();
            List<SeatInfo> reassigned = new ArrayList<>();
            int s = 1;
            for (SeatInfo si : seats) {
                reassigned.add(new SeatInfo(s, si.userId, si.nickname, si.avatarId, si.bot,
                        si.avatarUrl, si.nickColor, si.frameColor, si.title, si.vip, skinPool.get(s - 1)));
                if (!si.bot) userToSeat.put(si.userId, s);
                s++;
            }
            seats = reassigned;
        }

        List<Long> userIds = seats.stream().map(x -> x.userId).toList();
        GameEngine engine = new GameEngine(board, userIds, seed, huntByBorder);

        // 开局应用真人携带的功能道具（仅“功能道具赛”开启时生效）
        Map<Integer, String> seatItem = new HashMap<>();
        if (itemMatch) {
            for (SeatInfo si : seats) {
                if (si.bot) continue;
                User u = userRepo.findById(si.userId).orElse(null);
                if (u != null && u.getCarryItemId() != null) {
                    ItemDefinition it = itemRepo.findById(u.getCarryItemId()).orElse(null);
                    if (it != null && "FUNCTION".equals(it.getType())) seatItem.put(si.seat, it.getAsset());
                }
            }
        }
        if (!seatItem.isEmpty()) engine.applyItems(seatItem, seed);

        Map<Integer, SeatInfo> seatMap = new LinkedHashMap<>();
        for (SeatInfo si : seats) seatMap.put(si.seat, si);

        LiveGame lg = new LiveGame(roomId, roomNo, engine, seatMap, userToSeat, seed, huntByBorder, voiceMode, anonymous, itemMatch, hostUserId, aiOnly);
        // 原子占位：并发/双击开局时，只有一个请求能成功放入，避免同一房间产生两局并存、相互污染推送与结算
        LiveGame prev = active.putIfAbsent(roomId, lg);
        if (prev != null) {
            log.warn("房间 {} 已有进行中的对局，忽略重复开局请求", roomNo);
            return;
        }
        log.info("对局开始 room={} 板子={} 真人={} AI/托管={} aiEnabled={} voiceMode={} anonymous={}", roomNo, board,
                userToSeat.size(), total - userToSeat.size(), aiService.available(), voiceMode, anonymous);
        pump(lg);
    }

    private SeatInfo botSeat(int seat, long seed) {
        int n = BOT_NAMES.length;
        // 以 seed 做整池轮转：同一局内座位 1..n 取到的名字互不相同（避免 13 号位起与前面重名）。
        // 用 floorMod 而非 %，避免 seed（nanoTime 可能为负）导致下标为负而越界。
        int idx = (int) Math.floorMod((long) seat - 1 + seed, n);
        String skin = BOT_SKINS[(int) Math.floorMod((long) seat - 1 + seed, BOT_SKINS.length)];
        return new SeatInfo(seat, -(long) seat, BOT_NAMES[idx], (seat - 1) % BOT_AVATAR_COUNT + 1, true,
                null, null, null, null, 0, skin);
    }

    /** 托管/AI 座位的 3D 皮肤池：让 AI 也有造型差异（按座位 + seed 确定性轮转）。 */
    private static final String[] BOT_SKINS = {
            "villager", "wolf", "knight", "witch", "ghost", "jester",
            "frost", "vampire", "shadow", "royal", "reaper"
    };

    /* ================= 全 AI 队局（观战房） ================= */

    /** 全 AI 观战房：一局结束后自动续开下一局的等待时长（秒），给观战者留出看结算的时间。 */
    private static final long AI_SHOW_RESTART_DELAY_SECONDS = 10L;
    /** 观战房未设置板子时的兜底板子（12 人预女猎白）。 */
    private static final Map<String, Integer> AI_SHOW_DEFAULT_BOARD = Map.of(
            "WEREWOLF", 4, "VILLAGER", 4, "SEER", 1, "WITCH", 1, "HUNTER", 1, "IDIOT", 1);

    /**
     * 以「全 AI 队局」方式开一局：所有座位都是 AI，真人只能观战。
     * 房间必须已存在且标记为 aiOnly，房间状态会被置为 PLAYING。
     */
    public String startAiShow(com.werewolf.model.Room room, BoardConfig board) {
        if (room == null) return "房间不存在";
        if (active.containsKey(room.getId())) return "本房间已有进行中的对局";
        room.setStatus("PLAYING");
        room.setUpdatedAt(LocalDateTime.now());
        roomRepo.save(room);
        boolean huntByBorder = !room.isHuntCity();
        start(room.getId(), room.getRoomNo(), List.of(), board, true, huntByBorder, System.nanoTime(),
                false, false, false, room.getHostUserId() == null ? -1L : room.getHostUserId(), true);
        return null;
    }

    /** 观战房续开：读取房间当前板子，重新开一局全 AI 对局。 */
    private void scheduleAiShowRestart(LiveGame lg) {
        if (!lg.aiOnly) return;
        final long roomId = lg.roomId;
        try {
            scheduler.schedule(() -> {
                try {
                    com.werewolf.model.Room room = roomRepo.findById(roomId).orElse(null);
                    if (room == null || !room.isAiOnly()) return;      // 房间已解散/已改为普通房
                    if (active.containsKey(roomId)) return;            // 已有新局在跑
                    if (membership.membersOf(roomId).isEmpty()) return; // 没人看了就停，避免空转消耗
                    Map<String, Integer> counts = boardService.parse(room.getBoardConfig());
                    BoardConfig board = boardService.toBoard(counts != null ? counts : AI_SHOW_DEFAULT_BOARD);
                    String err = startAiShow(room, board);
                    if (err != null) log.warn("全 AI 观战房续开失败 room={}: {}", room.getRoomNo(), err);
                    else log.info("全 AI 观战房续开新一局 room={}", room.getRoomNo());
                } catch (Exception e) {
                    log.warn("全 AI 观战房续开异常 room={}: {}", roomId, e.getMessage());
                }
            }, AI_SHOW_RESTART_DELAY_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("安排观战房续开失败: {}", e.getMessage());
        }
    }

    /* ================= 推进 ================= */

    /** 驱动引擎直到遇到真人/AI 回合（停下并推送）或游戏结束。必须在锁内调用。 */
    private void pump(LiveGame lg) {
        synchronized (lg) {
            // 幂等守卫：对局已结束/已强制结束时，任何滞后回调（超时定时器、在途 AI Future）都不得再次推进或重复结算
            if (lg.finished || lg.aborted) return;
            // 全局音频闸门：旁白/发言音频还没播完时，暂缓一切推进（阶段切换/行动派发/结算），
            // 保证"系统等旁白说完再进行下一步"。音频账本由各播报点实时登记，这里延时后重入 pump；
            // audioGateBusy 防止多次 pump 排出多个延迟任务。上限 30s 防异常卡死。
            long gateWait = Math.min(pendingAudioMs(lg), 30000L);
            if (gateWait > 300L) {
                if (!lg.audioGateBusy) {
                    lg.audioGateBusy = true;
                    scheduler.schedule(() -> { lg.audioGateBusy = false; pump(lg); }, gateWait + 250L, TimeUnit.MILLISECONDS);
                }
                return;
            }
            cancelTimer(lg);
            int guard = 0;
            boolean aiOn = aiService.available();
            while (!lg.engine.isGameOver() && guard++ < 10000) {
                int actor = lg.engine.currentActor();
                if (actor == 0) {
                    // 无合法行动者：先尝试自愈推进，仍无法推进再告警，避免整局永久卡死
                    if (lg.engine.resolveStuckPhase()) continue;
                    break;
                }
                SeatInfo si = lg.seats.get(actor);
                boolean human = si != null && !si.bot;
                if (human) {
                    lg.waitingAiSeat = 0;
                    lg.waitingHumanSeat = actor;
                    String kind = lg.engine.currentActionKind();
                    if (isSpeechKind(kind)) {
                        lg.voiceTranscripts.remove(actor); // 清空上一轮残留转写（语音同传与否都清理，供文字转语音判定）
                        lg.voiceSpeakingSeat = 0;
                    }
                    // 先播报本阶段旁白，拿到其预计时长，作为“旁白说完再轮到行动”的余量，避免旁白跟不上系统要求行动。
                    narrateIfNeeded(lg);
                    long lead = pendingAudioMs(lg);
                    int dur = durationFor(kind);
                    long totalMs = dur * 1000L + lead;
                    lg.phaseDeadlineMs = System.currentTimeMillis() + totalMs;
                    pushState(lg);
                    final int waitSeat = actor;
                    lg.timer = scheduler.schedule(() -> onTimeout(lg, waitSeat), totalMs, TimeUnit.MILLISECONDS);
                    return;
                }
                if (aiOn) {
                    lg.waitingHumanSeat = 0;
                    lg.waitingAiSeat = actor;
                    lg.phaseDeadlineMs = 0;
                    pushState(lg, false);   // AI 回合不在这里异步旁白，改由 aiAct 在思考/发言前同步播报，保证旁白先行不滞后
                    final int aiSeat = actor;
                    lg.aiFuture = aiExecutor.submit(() -> aiAct(lg, aiSeat));
                    // 看门狗必须 ≥ LLM 的真实最坏耗时（与 AiService.chat 使用同一套有效超时/重试），
                    // 否则会在 AI 正常返回前就抢先代打，导致“AI 从不生效”。
                    long wd = (long) aiService.effectiveTimeoutSeconds() * (aiService.effectiveRetries() + 1) + 15;
                    lg.aiWatchdog = scheduler.schedule(() -> onAiWatchdog(lg, aiSeat), wd, TimeUnit.SECONDS);
                    return;
                }
                // 托管（未配置 AI）也要有"人味"节奏：先播报旁白，再按「拟人思考停顿 + 旁白余量」延迟执行，
                // 杜绝 AI 秒发言/秒投票，也让界面状态与旁白声音保持同一频道（否则系统永远跑在声音前面）。
                long autoLead = pendingAudioMs(lg);
                long think = 1200L + lg.rnd.nextLong(2200L);          // 1.2~3.4s 拟人思考停顿
                long autoWait = Math.min(autoLead + think, 25000L);
                lg.phaseDeadlineMs = System.currentTimeMillis() + autoWait;
                pushState(lg);
                final int autoSeat = actor;
                scheduler.schedule(() -> {
                    String speak = null;
                    synchronized (lg) {
                        if (lg.aborted || lg.finished) return;
                        if (lg.engine.currentActor() != autoSeat) return;   // 已被人/看门狗推进
                        speak = submitAuto(lg);
                    }
                    if (speak != null) speakAuto(lg, autoSeat, speak);      // 出锁后再合成发声，避免长时间持锁
                    pump(lg);
                }, autoWait, TimeUnit.MILLISECONDS);
                return;
            }
            lg.waitingHumanSeat = 0;
            lg.waitingAiSeat = 0;
            if (lg.engine.isGameOver()) {
                finish(lg);
            } else {
                log.warn("对局疑似卡死 room={} phase={} actor=0", lg.roomNo, lg.engine.phase());
                pushState(lg);
            }
        }
    }

    private void onTimeout(LiveGame lg, int seat) {
        synchronized (lg) {
            if (lg.finished) return;
            if (lg.engine.currentActor() != seat) return; // 已有人提交，忽略
            log.info("玩家 {} 超时，自动补位", seat);
            submitAuto(lg);
        }
        pump(lg);
    }

    /** 为当前行动者提交一个合法自动动作（托管或超时）。发言/遗言类返回需要发声的台词，其余返回 null。 */
    /** 托管座位（未配置 AI）的发言池：中性、不携带信息量、按座位+天数+已发言次数轮换，避免全员只会说“过。” */
    private static final String[] AUTO_SPEECH = {
            "我先听一听大家的发言。", "这轮我没太多要说的，先观察一下。", "票型我先记着，稍后再表态。",
            "我暂时保留意见，先听后置位怎么说。", "前面几位说的我都记下了。", "我先跟一票大势，回头再细说。",
            "这轮先过，重点听下一位。", "我还在理思路，大家继续。"
    };
    private static final String[] AUTO_LAST_WORDS = {
            "就到这里吧，大家加油。", "祝你们玩得开心，我先行一步。", "我的信息都用完了，就看你们的了。"
    };

    /** 托管发言轮换：同一座位不会连续两轮说同一句。 */
    private String autoSpeech(GameEngine g, int seat) {
        int spoken = 0;
        for (GameEvent e : g.events()) if ("SPEECH".equals(e.type()) && e.actorSeat() == seat) spoken++;
        return AUTO_SPEECH[Math.floorMod(seat * 7 + g.day() * 13 + spoken, AUTO_SPEECH.length)];
    }

    private String autoLastWords(int seat) {
        return AUTO_LAST_WORDS[Math.floorMod(seat, AUTO_LAST_WORDS.length)];
    }

    private String submitAuto(LiveGame lg) {
        GameEngine g = lg.engine;
        int seat = g.currentActor();
        Player me = g.bySeat(seat);
        Random rnd = lg.rnd;
        String pendingSpeak = null;
        // 托管/超时兜底：白天行动者若是可自爆的狼人，按概率发起自爆（白狼王带人、普通狼人只自爆）。
        if (submitAutoBlowUp(g, seat, rnd)) {
            return null;
        }
        switch (g.currentActionKind()) {
            case "GUARD" -> g.submitGuard(autoPolicy.guardTarget(g, me, rnd));
            case "WOLF_KILL" -> g.submitWolfKill(seat, autoPolicy.wolfKill(g, me, rnd));
            case "WITCH" -> { int[] wp = autoPolicy.witch(g, me, rnd); g.submitWitch(wp[0], wp[1]); }
            case "SEER_CHECK" -> g.submitSeer(autoPolicy.seerTarget(g, me, rnd));
            case "CROW" -> g.submitCrow(autoPolicy.crowTarget(g, me, rnd));
            case "SILENCER" -> g.submitSilencer(autoPolicy.silencerTarget(g, me, rnd));
            case "SHOOT" -> g.submitShoot(seat, autoPolicy.shootTarget(g, me, rnd));
            case "SHERIFF_TRANSFER" -> {
                // 托管/超时：随机把警徽交给一名存活玩家；无人可交则撕毁
                List<Integer> cand = new ArrayList<>();
                for (Player p : g.alive()) if (p.seat != seat) cand.add(p.seat);
                g.submitSheriffTransfer(cand.isEmpty() ? 0 : cand.get(rnd.nextInt(cand.size())));
            }
            case "SHERIFF_ORDER" -> {
                // 托管/超时：默认从警右（座位号更大的下一位）开始发言
                List<Integer> seats = g.alive().stream().map(p -> p.seat).sorted().toList();
                int start = 0;
                if (!seats.isEmpty()) {
                    int idx = seats.indexOf(seat);
                    start = idx < 0 ? seats.get(0) : seats.get((idx + 1) % seats.size());
                }
                g.submitSheriffOrder(start);
            }
            case "LAST_WORDS" -> { String line = autoLastWords(seat); g.submitLastWords(seat, line); pendingSpeak = line; }
            case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(seat, autoPolicy.sheriffSignup(g, me, rnd));
            case "SHERIFF_WITHDRAW" -> g.submitSheriffWithdraw(seat, autoPolicy.sheriffWithdraw(g, me, rnd));
            case "SHERIFF_VOTE" -> g.submitSheriffVote(seat, autoPolicy.sheriffVote(g, me, new ArrayList<>(g.sheriffCandidates()), rnd));
            case "SPEECH" -> { String line = autoSpeech(g, seat); g.submitSpeech(seat, line); pendingSpeak = line; }
            case "VOTE" -> g.submitVote(seat, autoPolicy.exileVote(g, me, rnd));
            case "PK_SPEECH" -> { String line = autoSpeech(g, seat); g.submitTiebreakSpeech(seat, line); pendingSpeak = line; }
            case "PK_VOTE" -> g.submitTiebreakVote(seat, autoPolicy.exileVote(g, me, rnd));
            default -> throw new IllegalStateException("无自动动作: " + g.currentActionKind());
        }
        return pendingSpeak;
    }

    /** 托管发言的语音播报（与 aiAct 的发声路径一致），让语音房里的托管座位也有"人声"。 */
    private void speakAuto(LiveGame lg, int seat, String line) {
        try {
            if (!vprops.isEnabled() || line == null || line.isBlank()) return;
            String t = line.trim();
            if ("过".equals(t) || "（过）".equals(t)) return;
            registerSpeechAudio(lg, t);
            director.speak(lg.roomId, seat, displayName(lg, seat), t, "ai", resolveVoice(lg, seat));
        } catch (Exception ignored) {}
    }

    /**
     * 托管/超时兜底：白天行动者若是可自爆的狼人，按概率发起自爆。
     * 白狼王随机带走一名存活好人；普通狼人只自爆（不带人）。成功返回 true。
     */
    private boolean submitAutoBlowUp(GameEngine g, int seat, Random rnd) {
        if (!g.canBlowUp(seat)) return false;
        Player me = g.bySeat(seat);
        boolean white = me != null && me.role == com.werewolf.rules.Role.WHITE_WOLF_KING;
        // 普通狼人自爆收益较低，给更小的概率，避免托管局频繁自爆打乱节奏
        double chance = white ? AUTO_BLOWUP_CHANCE : AUTO_BLOWUP_CHANCE * 0.25;
        if (rnd.nextDouble() >= chance) return false;
        int target = 0;
        if (white) {
            // 优先带走好人（避免误伤狼队友）；无可选好人时退回除自己外的任意存活玩家。
            List<Integer> good = new ArrayList<>();
            List<Integer> any = new ArrayList<>();
            for (Player p : g.alive()) {
                if (p.seat == seat) continue;
                any.add(p.seat);
                if (p.isGood()) good.add(p.seat);
            }
            List<Integer> cand = !good.isEmpty() ? good : any;
            if (cand.isEmpty()) return false; // 无合法目标：交由正常发言/投票流程处理
            target = cand.get(rnd.nextInt(cand.size()));
        }
        try {
            g.submitWolfBlowUp(seat, target);
            return true;
        } catch (GameEngine.IllegalActionException e) {
            log.warn("托管自爆失败 seat={} target={}: {}", seat, target, e.getMessage());
            return false;
        }
    }

    /** 判断动作是否表达“狼人自爆”意图（约定的文本标记）。 */
    public static boolean isBlowUpAction(GameAction a) {
        return a != null && a.text != null && a.text.trim().startsWith(BLOWUP_MARKER);
    }

    /* ================= 真人提交 ================= */

    public String applyHumanAction(long roomId, long userId, GameAction action) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "无进行中的对局";
        int voicedSeat = -1; String voicedText = null;
        synchronized (lg) {
            if (lg.finished) return "对局已结束";
            Integer seat = lg.userToSeat.get(userId);
            if (seat == null) return "你不在本局";
            if (lg.engine.currentActor() != seat) {
                // 白狼王自爆是白天的“附加可选动作”，不要求恰好轮到自己发言/投票；其余动作仍须轮到自己。
                if (!(isBlowUpAction(action) && lg.engine.canBlowUp(seat))) {
                    return "还没轮到你行动";
                }
            }
            String kind = lg.engine.currentActionKind();
            // 自爆标记只作为内部信号，绝不能进入文字转语音链路（否则会被念出来）
            boolean blowUp = isBlowUpAction(action);
            toReal(lg, action); // 匿名：客户端提交的是显示号，映射回真实座位再交给引擎
            String err = applyActionToEngine(lg.engine, seat, action);
            if (err != null) return err;
            lg.waitingHumanSeat = 0;
            // 对局中·文字/语音转文字：仅在“匿名伪装”或“语音同传”模式下，才用该座位音色把文字朗读给全房间
            // （匿名需要统一音色藏 AI；语音同传需要统一音色转述）。
            // 普通房间（非匿名、非同传）= 直接语音交流：说话人的原声已由 /ws/voice 实时转发全场，
            // 打字提交的文字【不再】用 TTS 机器人音色复读，否则会出现"原声+机器人音"双声复读。
            boolean directVoiceRoom = !lg.anonymous && !lg.voiceMode;
            boolean reVoice = !blowUp && vprops.isEnabled() && !directVoiceRoom;
            if (reVoice && isSpeechKind(kind) && action.text != null) {
                StringBuilder relayed = lg.voiceTranscripts.get(seat);
                boolean already = relayed != null && relayed.length() > 0;
                String t = action.text.trim();
                if (!already && !t.isEmpty() && !t.equals("过") && !t.equals("（过）") && !t.startsWith("（PK）过")) {
                    voicedSeat = seat; voicedText = t;
                    // 关键：把这段朗读的预计时长登记进音频账本，下一阶段的推进才会等它播完
                    registerSpeechAudio(lg, t);
                }
            }
        }
        if (voicedSeat >= 0 && voicedText != null) {
            final int s = voicedSeat; final String txt = voicedText;
            aiExecutor.submit(() -> {
                try { director.speak(roomId, lg.show(s), displayName(lg, s), txt, "ai", resolveVoice(lg, s)); }
                catch (Exception ignored) {}
            });
        }
        pump(lg);
        return null; // 成功
    }

    /** 匿名模式下把动作里的目标座位从“显示号”映射回“真实座位”（0 保持不变）。 */
    private void toReal(LiveGame lg, GameAction a) {
        if (!lg.anonymous || a == null) return;
        a.target = lg.real(a.target);
        a.saveTarget = lg.real(a.saveTarget);
        a.poisonTarget = lg.real(a.poisonTarget);
    }

    /** 把动作应用到引擎（真人/AI 共用）。返回 null=成功，否则错误信息。 */
    private String applyActionToEngine(GameEngine g, int seat, GameAction action) {
        try {
            // 附加可选动作：狼人白天自爆（白狼王带人 / 普通狼人不带人）。优先于当前 kind 判定，用约定标记触发。
            if (action != null && isBlowUpAction(action) && g.canBlowUp(seat)) {
                Player me = g.bySeat(seat);
                boolean white = me != null && me.role == com.werewolf.rules.Role.WHITE_WOLF_KING;
                int tgt = action.target;
                if (white) {
                    if (tgt <= 0 || tgt == seat) return "白狼王自爆需要选择一个不等于自己的目标";
                    Player t = g.bySeat(tgt);
                    if (t == null || !t.alive) return "自爆目标无效";
                } else {
                    tgt = 0;   // 普通狼人自爆不带人
                }
                g.submitWolfBlowUp(seat, tgt);
                return null;
            }
            switch (g.currentActionKind()) {
                case "GUARD" -> g.submitGuard(action.target);
                case "WOLF_KILL" -> g.submitWolfKill(seat, action.target);
                case "WITCH" -> g.submitWitch(action.saveTarget, action.poisonTarget);
                case "SEER_CHECK" -> g.submitSeer(action.target);
                case "CROW" -> g.submitCrow(action.target);
                case "SILENCER" -> g.submitSilencer(action.target);
                case "SHOOT" -> g.submitShoot(seat, action.target);
                case "SHERIFF_TRANSFER" -> g.submitSheriffTransfer(action.target);
                case "SHERIFF_ORDER" -> g.submitSheriffOrder(action.target);
                case "LAST_WORDS" -> g.submitLastWords(seat, action.text);
                case "SHERIFF_SIGNUP" -> g.submitSheriffSignup(seat, action.signup);
                case "SHERIFF_WITHDRAW" -> g.submitSheriffWithdraw(seat, action.withdraw);
                case "SHERIFF_VOTE" -> g.submitSheriffVote(seat, action.target);
                case "SPEECH" -> g.submitSpeech(seat, action.text);
                case "VOTE" -> g.submitVote(seat, action.target);
                case "PK_SPEECH" -> g.submitTiebreakSpeech(seat, action.text);
                case "PK_VOTE" -> g.submitTiebreakVote(seat, action.target);
                default -> { return "当前无需行动"; }
            }
            return null;
        } catch (GameEngine.IllegalActionException e) {
            return "非法操作：" + e.getMessage();
        }
    }

    /* ================= AI 异步驱动 ================= */

    private void aiAct(LiveGame lg, int seat) {
        try {
            if (lg.aborted || lg.finished) return; // 房主已强制结束
            // AI 回合：先“同步”播报本阶段旁白（在思考/发言之前），保证旁白先于 AI 行动、与系统操作同步、不滞后。
            String narr = collectNarrationText(lg);
            if (narr != null && vprops.isEnabled()) {
                try { director.speak(lg.roomId, 0, "旁白", narr, "narrator", vprops.voiceForSeat(1, "M")); } catch (Exception ignored) {}
            }
            // 让旁白先说完再行动：把“尚未播完的旁白时长”并入本回合的思考停顿，
            // 避免旁白还在念、系统/AI 已经行动，出现“旁白跟不上操作”。上限 12s 防止异常时卡住对局。
            long narrLead = Math.min(pendingAudioMs(lg), 25000L);
            Thread.sleep(aiThinkMs() + narrLead);
            if (lg.aborted || lg.finished) return;
            GameAction action = aiPolicy.decide(lg.engine, seat, lg.voiceMode, lg.showOf); // LLM 调用（自带超时/重试/降级）
            String kind = lg.engine.currentActionKind();
            // 只要语音服务可用就把 AI 文本转语音（逐句发声），不再要求房间开启语音模式；"过"不发声。
            String said = action.text == null ? "" : action.text.trim();
            if (vprops.isEnabled() && isSpeechKind(kind) && !said.isEmpty() && !said.equals("过")) {
                synchronized (lg) {
                    if (lg.finished) return;
                    cancelAiWatchdog(lg); // 已拿到文案，撤掉看门狗，避免发声期间被代打
                }
                String name = displayName(lg, seat);
                registerSpeechAudio(lg, action.text);
                director.speak(lg.roomId, seat, name, action.text, "ai", resolveVoice(lg, seat));
            }
            synchronized (lg) {
                if (lg.finished) return;
                if (lg.engine.currentActor() != seat) return; // 看门狗已处理
                lg.waitingAiSeat = 0;
                toReal(lg, action); // 匿名：AI 返回的是显示号目标，映射回真实座位
                String err = applyActionToEngine(lg.engine, seat, action);
                if (err != null) {
                    log.warn("AI 座位{} 动作被拒({})，降级随机", seat, err);
                    submitAuto(lg);
                }
            }
        } catch (Exception e) {
            log.warn("AI 座位{} 异步任务异常，降级随机：{}", seat, e.getMessage());
            synchronized (lg) {
                if (!lg.finished && lg.engine.currentActor() == seat) {
                    cancelAiWatchdog(lg);
                    lg.waitingAiSeat = 0;
                    submitAuto(lg);
                }
            }
        }
        pump(lg);
    }

    private void onAiWatchdog(LiveGame lg, int seat) {
        synchronized (lg) {
            if (lg.finished) return;
            if (lg.waitingAiSeat != seat || lg.engine.currentActor() != seat) return;
            log.warn("AI 座位{} 看门狗超时，降级随机代打", seat);
            lg.waitingAiSeat = 0;
            submitAuto(lg);
        }
        pump(lg);
    }

    private void cancelAiWatchdog(LiveGame lg) {
        if (lg.aiWatchdog != null) { lg.aiWatchdog.cancel(false); lg.aiWatchdog = null; }
    }

    /** 拟真思考延迟：真实 LLM 只加很小的停顿（推理模型本身已耗时，避免叠加显得更慢）。 */
    private long aiThinkMs() {
        return aiService.isMock() ? 100 + lgRnd.nextInt(200) : 150 + lgRnd.nextInt(450);
    }

    private final Random lgRnd = new Random();

    /* ================= 视图推送 ================= */

    public Map<String, Object> viewFor(long roomId, long userId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return null;
        Integer seat = lg.userToSeat.get(userId);
        int viewer = seat == null ? 0 : seat; // 0=观战
        Map<String, Object> v = GameViewBuilder.build(lg.engine, lg.seats, viewer, lg.anonymous, lg.voiceMode, vprops, lg.showOf, lg.aiOnly);
        v.put("roomNo", lg.roomNo);
        // 倒计时隐私：只把当前行动者的截止时间推给本人，其他人不得感知他人思考时长
        int actor = lg.engine.currentActor();
        v.put("deadlineMs", viewer != 0 && viewer == actor ? lg.phaseDeadlineMs : 0);
        v.put("spectator", viewer == 0);
        v.put("aiOnly", lg.aiOnly);
        v.put("hostUserId", lg.hostUserId);
        return v;
    }

    private void pushState(LiveGame lg) { pushState(lg, true); }

    private void pushState(LiveGame lg, boolean narrate) {
        if (narrate) narrateIfNeeded(lg);
        // 先在锁内生成各成员视图快照，再统一在锁外异步发送：
        // 避免慢客户端/网络阻塞时长时间占用 lg 锁，导致整局推进被拖死。
        List<Map<String, Object>> snapshots = new ArrayList<>();
        List<Long> targets = new ArrayList<>();
        int actor = lg.engine.currentActor();
        for (Long uid : membership.membersOf(lg.roomId)) {
            Integer seat = lg.userToSeat.get(uid);
            int viewer = seat == null ? 0 : seat;
            Map<String, Object> v = GameViewBuilder.build(lg.engine, lg.seats, viewer, lg.anonymous, lg.voiceMode, vprops, lg.showOf, lg.aiOnly);
            v.put("roomNo", lg.roomNo);
            // 倒计时隐私：仅行动者本人可见截止时间
            v.put("deadlineMs", viewer != 0 && viewer == actor ? lg.phaseDeadlineMs : 0);
            v.put("spectator", seat == null);
            v.put("aiOnly", lg.aiOnly);
            v.put("hostUserId", lg.hostUserId);
            if (lg.engine.isGameOver()) v.put("gameId", lg.gameId);
            targets.add(uid);
            snapshots.add(v);
        }
        for (int i = 0; i < targets.size(); i++) {
            sendToUserAsync(targets.get(i), "game.state", snapshots.get(i));
        }
        // 状态有变化即按房间节流落盘一份快照，供服务器重启后继续对局
        try { persistence.saveAsync(lg); } catch (Exception e) { log.debug("快照保存失败 room={}: {}", lg.roomId, e.getMessage()); }
    }

    /** 异步推送：把 WebSocket 发送移出 lg 锁，慢客户端不再阻塞对局推进。 */
    private void sendToUserAsync(long userId, String type, Map<String, ?> payload) {
        try {
            pushExecutor.submit(() -> sendToUser(userId, type, payload));
        } catch (Exception e) {
            log.debug("推送任务提交失败 uid={}: {}", userId, e.getMessage());
        }
    }

    /** 真人回合：异步合并播报本阶段旁白，返回“尚未播完旁白”的预计剩余时长(ms)。 */
    private long narrateIfNeeded(LiveGame lg) {
        String n = collectNarrationText(lg);
        if (n != null) deliverNarration(lg, n);
        return pendingNarrationMs(lg);
    }

    /**
     * 收集本阶段应播报的旁白文本（并更新去重状态：天亮死讯每天一次 / 遗言按每位遗言者 / 阶段切换一次）。
     * 无新内容返回 null。真人回合走异步合并播报；AI 回合在 aiAct 里同步播报，保证旁白先于 AI 行动、不滞后。
     */
    private String collectNarrationText(LiveGame lg) {
        GamePhase ph = lg.engine.phase();
        StringBuilder sb = new StringBuilder();
        if (isDayPhase(ph) && lg.narratedDawnDay != lg.engine.day()) {
            lg.narratedDawnDay = lg.engine.day();
            sb.append(dawnLine(lg)).append(' ');
        }
        if (ph == GamePhase.LAST_WORDS) {
            int cur = lg.engine.currentActor();
            if (cur != 0 && cur != lg.lastWordsSeat) {
                lg.lastWordsSeat = cur;
                sb.append("请 ").append(lg.show(cur)).append(" 号发表遗言。");
            }
        } else if (ph != lg.lastNarrPhase) {
            lg.lastNarrPhase = ph;
            String line = narratorLine(lg, ph);
            if (line != null) sb.append(line);
        }
        // 退水环节与报名/发言同属 SHERIFF_ELECTION，阶段未变，需单独检测并只播一次
        if (ph == GamePhase.SHERIFF_ELECTION && lg.engine.sheriffWithdrawActive() && !lg.narratedSheriffWithdraw) {
            lg.narratedSheriffWithdraw = true;
            sb.append("竞选发言结束，请上警的玩家依次决定是否退水，退水后不再参与投票。");
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private boolean isDayPhase(GamePhase ph) {
        // 含「开枪 / 警徽移交 / 决定发言顺序」：警长夜里被刀时，天亮死讯要在这几个阶段前先播出来，
        // 否则会拖到更晚的白天阶段，玩家先听到移交提示、后听到死讯，顺序颠倒。
        return ph == GamePhase.DAWN || ph == GamePhase.SHERIFF_ELECTION || ph == GamePhase.LAST_WORDS
                || ph == GamePhase.SHOOT || ph == GamePhase.SHERIFF_TRANSFER || ph == GamePhase.SHERIFF_ORDER
                || ph == GamePhase.DAY_SPEAK || ph == GamePhase.DAY_VOTE || ph == GamePhase.DAY_VOTE_TIEBREAK;
    }

    /** 天亮播报：先让最后一位夜晚角色闭眼，再公布昨晚被杀名单（无人则平安夜）。 */
    private String dawnLine(LiveGame lg) {
        String close = lg.lastNightOpen != null ? lg.lastNightOpen + "请闭眼。" : "";
        lg.lastNightOpen = null;
        List<Integer> dead = lg.engine.nightDeaths();
        if (dead.isEmpty()) return close + "天亮了，昨晚是平安夜，无人倒牌。";
        StringBuilder sb = new StringBuilder(close).append("天亮了，昨晚 ");
        for (int i = 0; i < dead.size(); i++) {
            if (i > 0) sb.append("、");
            sb.append(lg.show(dead.get(i))).append("号");
        }
        sb.append(" 倒牌出局。");
        return sb.toString();
    }

    /** 播报一条旁白（异步，不阻塞对局推进），返回“当前所有未播完旁白”的预计总时长(ms)。
        采用 FIFO 队列：旁白按顺序播出、不丢句、不覆盖，且该时长会被加进下一回合的时限，
        确保旁白说完之后才轮到系统/玩家行动，避免旁白越播越滞后、追不上系统节奏。 */
    private long deliverNarration(LiveGame lg, String text) {
        if (text == null) return 0;
        final String t = text;
        if (vprops.isEnabled()) {
            synchronized (lg.narrationLock) {
                lg.narrationQueue.addLast(t);
                lg.narrationBusy = true;
            }
            narratorExec.submit(() -> drainNarration(lg));
        } else {
            for (Long uid : membership.membersOf(lg.roomId)) {
                wsPush.send(uid, "narrator", Map.of("room", lg.roomId, "name", "旁白", "text", t));
            }
        }
        return pendingNarrationMs(lg);
    }

    /** 逐条顺序播出队列中的旁白，播完把 busy 置回 false。单线程执行器保证同一时刻只有一条在播。 */
    private void drainNarration(LiveGame lg) {
        while (true) {
            String s;
            synchronized (lg.narrationLock) {
                s = lg.narrationQueue.pollFirst();
                if (s == null) {
                    lg.narrationBusy = false;
                    lg.narrationLock.notifyAll();
                    return;
                }
            }
            try {
                synchronized (lg.narrationLock) {
                    lg.narrationPlayingMs = estimateNarrationMs(s);
                    lg.narrationPlayingStart = System.currentTimeMillis();
                }
                director.speak(lg.roomId, 0, "旁白", s, "narrator", vprops.voiceForSeat(1, "M"));
            } catch (Exception ignored) {}
        }
    }

    /** 估算尚未播完的旁白总时长：正在播的那条的剩余时间 + 队列中各条之和（用于给回合时限预留余量）。 */
    /** 登记一段即将播出的发言音频（AI/托管发言）：把它的预计时长并入房间的音频账本。 */
    private void registerSpeechAudio(LiveGame lg, String text) {
        if (!vprops.isEnabled() || text == null || text.isBlank()) return;
        long est = estimateNarrationMs(text);
        long now = System.currentTimeMillis();
        lg.speechAudioUntil = Math.max(now, lg.speechAudioUntil) + est;
    }

    /** 音频频道总余量 = 旁白队列 + 未播完的发言音频。游戏节奏（各回合的等待）统一以此为准。 */
    private long pendingAudioMs(LiveGame lg) {
        long speech = Math.max(0, lg.speechAudioUntil - System.currentTimeMillis());
        return pendingNarrationMs(lg) + speech;
    }

    private long pendingNarrationMs(LiveGame lg) {
        if (!vprops.isEnabled()) return 0;
        long total = 0;
        synchronized (lg.narrationLock) {
            if (lg.narrationBusy && lg.narrationPlayingMs > 0) {
                long elapsed = System.currentTimeMillis() - lg.narrationPlayingStart;
                total += Math.max(0, lg.narrationPlayingMs - elapsed);
            }
            for (String s : lg.narrationQueue) total += estimateNarrationMs(s);
        }
        return total;
    }

    /** 粗略估算一段旁白 TTS 的播报时长(ms)：起手延迟 + 字数×每字时长 + 分句停顿。 */
    private long estimateNarrationMs(String text) {
        int chars = text.length();
        int clauses = Math.max(1, text.split("[，。！？；、,.!?;]").length);
        long perChar = vprops.getMsPerChar();
        long gap = (vprops.getGapMinMs() + vprops.getGapMaxMs()) / 2L;
        return 900L + chars * perChar + clauses * gap;   // 900ms 头部：实测短句起音+收尾的固定开销
    }

    private String narratorLine(LiveGame lg, GamePhase ph) {
        return switch (ph) {
            case NIGHT_GUARD, NIGHT_WOLF, NIGHT_WITCH, NIGHT_SEER, NIGHT_CROW, NIGHT_SILENCER -> {
                boolean first = lg.narratedNightDay != lg.engine.day();
                if (first) lg.narratedNightDay = lg.engine.day();
                // 先让“上一位”闭眼（说明 TA 已经选完了），再让本位睁眼——闭眼一定发生在该角色行动之后。
                String close = lg.lastNightOpen != null ? lg.lastNightOpen + "请闭眼。" : "";
                lg.lastNightOpen = nightRoleName(ph);
                yield (first ? "天黑请闭眼。" : "") + close + nightCue(lg, ph);
            }
            case DAWN -> null;   // 被杀结果由 dawnLine 在首个白天阶段播报
            case SHERIFF_ELECTION -> "现在开始警长竞选，想上警的玩家请举手。";
            case DAY_SPEAK -> "进入自由讨论环节，请大家依次发言。";
            case DAY_VOTE -> "讨论结束，开始投票，请选出你要放逐的玩家。";
            case DAY_VOTE_TIEBREAK -> "出现平票，进入 PK 环节。";
            case LAST_WORDS -> null;   // 由 narrateIfNeeded 按每位遗言者单独播报
            case SHERIFF_TRANSFER -> "警长出局，请警长决定警徽的去向。";
            case SHERIFF_ORDER -> "请警长决定从警左还是警右开始发言。";
            case GAME_OVER -> "游戏结束，"
                    + (lg.engine.winner() == Faction.WOLF ? "狼人阵营" : "好人阵营") + "获得胜利！";
            default -> null;
        };
    }

    private String nightCue(LiveGame lg, GamePhase ph) {
        return switch (ph) {
            case NIGHT_GUARD -> "守卫请睁眼，请选择今晚要守护的人。";
            case NIGHT_WOLF -> "狼人请睁眼，请统一今晚要刀的目标。";
            case NIGHT_WITCH -> "女巫请睁眼，昨晚的情况你在心里判断，是否使用解药或毒药？";
            case NIGHT_SEER -> "预言家请睁眼，请选择今晚要查验的人。";
            case NIGHT_CROW -> "乌鸦请睁眼，是否发动诽谤技能？";
            case NIGHT_SILENCER -> "禁言长老请睁眼，是否禁言某位玩家？";
            default -> "";
        };
    }

    /** 夜晚各阶段对应的角色名（用于“XX请闭眼”的收尾播报）。 */
    private String nightRoleName(GamePhase ph) {
        return switch (ph) {
            case NIGHT_GUARD -> "守卫"; case NIGHT_WOLF -> "狼人"; case NIGHT_WITCH -> "女巫";
            case NIGHT_SEER -> "预言家"; case NIGHT_CROW -> "乌鸦"; case NIGHT_SILENCER -> "禁言长老";
            default -> null;
        };
    }

    private String phaseZh(GamePhase ph) {
        return switch (ph) {
            case NIGHT_GUARD -> "守卫请行动"; case NIGHT_WOLF -> "狼人请睁眼刀人"; case NIGHT_WITCH -> "女巫请行动";
            case NIGHT_SEER -> "预言家请验人"; case NIGHT_CROW -> "乌鸦请行动"; case NIGHT_SILENCER -> "禁言长老请行动";
            default -> "夜晚";
        };
    }

    /** 对局内打字聊天：广播给房间内所有成员（显示名遵循匿名规则）。仅全真人局开放。 */
    public String chat(long roomId, long userId, String text) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "无进行中的对局";
        // 房间聊天只在「全真人局」开放：只要有 AI/托管座位就关闭（避免真人靠聊天串通、也避免 AI 局里出现无意义的聊天面板）
        if (lg.seats.values().stream().anyMatch(s -> s.bot)) {
            return "本局有 AI 玩家参与，房间聊天已关闭（仅全真人局开放）";
        }
        if (text == null || text.isBlank()) return "说点什么吧";
        String t = text.trim().replaceAll("[\\r\\n]+", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        Integer seat = lg.userToSeat.get(userId);
        String name = seat != null ? displayName(lg, seat) : "观众";
        int dispSeat = seat != null ? lg.show(seat) : 0;
        for (Long uid : membership.membersOf(roomId)) {
            wsPush.send(uid, "game.chat", Map.of("room", roomId, "seat", dispSeat, "name", name, "text", t));
        }
        return null;
    }

    /**
     * 夜晚狼队内部交流：只推给【存活的狼】，非狼既收不到消息、也不会出现在任何对局记录里。
     * 仅夜晚开放（白天狼需要伪装闭眼好人）。
     */
    public String wolfChat(long roomId, long userId, String text) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "无进行中的对局";
        Integer seat = lg.userToSeat.get(userId);
        if (seat == null) return "观战者不能参与狼队交流";
        com.werewolf.rules.Player me = lg.engine.bySeat(seat);
        if (me == null || !me.alive || !me.isWolf()) return "只有存活的狼人可以使用狼队频道";
        if (!lg.engine.phase().name().startsWith("NIGHT")) return "狼队频道只在夜晚开放";
        if (text == null || text.isBlank()) return "说点什么吧";
        String t = text.trim().replaceAll("[\\r\\n]+", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        int dispSeat = lg.show(seat);
        String name = displayName(lg, seat);
        // 只发给存活的狼（含发送者本人）；不写任何事件记录，因此对局记录里不会出现
        for (SeatInfo si : lg.seats.values()) {
            if (si.bot || si.userId <= 0) continue;
            com.werewolf.rules.Player p = lg.engine.bySeat(si.seat);
            if (p == null || !p.alive || !p.isWolf()) continue;
            wsPush.send(si.userId, "game.wolfchat", Map.of("room", roomId, "seat", dispSeat, "name", name, "text", t));
        }
        return null;
    }

    /**
     * AI 总结本局的【对话内容】：只归纳"谁说了什么"，不分析局面、不推断身份。
     * 供对局记录面板的「AI 总结」按钮使用。
     */
    public String summarizeFeed(long roomId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "无进行中的对局";
        List<String> lines = new ArrayList<>();
        for (com.werewolf.rules.GameEvent e : lg.engine.events()) {
            if (e.detail() == null || e.detail().isBlank()) continue;
            if (com.werewolf.rules.GameEvent.SPEECH.equals(e.type())) {
                lines.add("第" + e.day() + "天 " + lg.show(e.actorSeat()) + "号发言：" + e.detail());
            } else if (com.werewolf.rules.GameEvent.LAST_WORDS.equals(e.type())) {
                lines.add("第" + e.day() + "天 " + lg.show(e.actorSeat()) + "号遗言：" + e.detail());
            }
        }
        if (lines.isEmpty()) return "本局还没有人发言。";
        int from = Math.max(0, lines.size() - 60);          // 只带最近 60 条，避免上下文过长
        String body = String.join("\n", lines.subList(from, lines.size()));
        String prompt = "下面是一局狼人杀的【公开发言与遗言】原文。请做一份「对话内容摘要」：\n"
                + "1) 只梳理【谁说了什么】：每位玩家发言的核心主张、点名了谁、前后态度有无变化；\n"
                + "2) 严禁分析局面、严禁推断谁是狼、严禁给出胜负判断或投票建议；\n"
                + "3) 用简体中文，按座位分段，每人一句话；不要 markdown 标题、不要表格。\n\n"
                + body;
        try {
            String out = aiService.chat(List.of(
                    new com.werewolf.ai.AiProvider.Message("system", "你是会议纪要员：只客观归纳发言内容，不做任何推理、判断或建议。"),
                    new com.werewolf.ai.AiProvider.Message("user", prompt)));
            return out == null ? "" : out.trim();
        } catch (Exception e) {
            log.warn("AI 总结发言失败：{}", e.toString());
            return "AI 总结失败：" + e.getMessage();
        }
    }

    private void sendToUser(long userId, String type, Map<String, ?> payload) {
        Optional<WebSocketSession> s = presence.sessionOf(userId);
        if (s.isEmpty()) return;
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", type);
            payload.forEach(root::putPOJO);
            synchronized (s.get()) {
                s.get().sendMessage(new TextMessage(mapper.writeValueAsString(root)));
            }
        } catch (Exception ex) {
            log.debug("推送 game.state 失败 uid={}: {}", userId, ex.getMessage());
        }
    }

    /* ================= 结束与落库 ================= */

    private void finish(LiveGame lg) {
        // 幂等：置位在前的原子标记，确保落库与结算只执行一次（防止并发 pump 重复发奖）
        synchronized (lg) {
            if (lg.finished) return;
            lg.finished = true;
        }
        cancelTimer(lg);
        pushState(lg); // 结束态：全体亮牌
        try {
            GameRecord rec = new GameRecord();
            rec.setRoomId(lg.roomId);
            rec.setRoomNo(lg.roomNo);
            rec.setBoard(lg.engine.players().stream().map(p -> p.role.cnName()).reduce((a, b) -> a + "," + b).orElse(""));
            rec.setWinner(lg.engine.winner() == Faction.WOLF ? "狼人阵营" : "好人阵营");
            rec.setEndedAt(LocalDateTime.now());
            StringBuilder roles = new StringBuilder();
            for (Player p : lg.engine.players()) roles.append(p.seat).append(":").append(p.role.cnName()).append(" ");
            rec.setSeatRoles(roles.toString().trim());
            rec = recordRepo.save(rec);
            lg.gameId = rec.getId();

            List<GameEventLog> logs = new ArrayList<>();
            for (GameEvent ev : lg.engine.events()) {
                GameEventLog el = new GameEventLog();
                el.setGameId(rec.getId()); el.setSeq(ev.seq()); el.setPhase(ev.phase().name());
                el.setDay(ev.day()); el.setType(ev.type()); el.setActorSeat(ev.actorSeat());
                el.setTargetSeat(ev.targetSeat());
                el.setDetail(ev.detail() == null ? null : (ev.detail().length() > 990 ? ev.detail().substring(0, 990) : ev.detail()));
                logs.add(el);
            }
            eventRepo.saveAll(logs);
            settlement.settle(rec.getId(), lg.engine, lg.userToSeat, lg.roomNo, (System.currentTimeMillis() - lg.startMs) / 1000, lg.itemMatch);
            pushState(lg); // 再推一次，带上 gameId 供结算页互动（点赞/举报/安慰）
            log.info("对局结束 room={} 胜方={} 事件数={}", lg.roomNo, rec.getWinner(), logs.size());
        } catch (Exception e) {
            log.error("对局落库失败 room={}", lg.roomNo, e);
        }
        // 自然结束也要复位房间，避免卡在 PLAYING 无法退出/重开
        retire(lg);
        // 全 AI 观战房：自然结束后延时续开下一局（有人观看时），形成持续的 AI 表演赛
        scheduleAiShowRestart(lg);
    }

    /** 结束并清理：从活跃对局移除、取消定时器与进行中的 AI 调用、把房间状态复位为等待中。 */
    private void retire(LiveGame lg) {
        lg.aborted = true;
        lg.finished = true;
        cancelTimer(lg);
        java.util.concurrent.Future<?> f = lg.aiFuture;
        if (f != null) { try { f.cancel(true); } catch (Exception ignored) {} }
        active.remove(lg.roomId);
        // 对局已结束：清除快照，避免重启后被当作未完成对局恢复
        try { persistence.delete(lg.roomId); } catch (Exception ignored) {}
        // 释放该房间在语音发声锁里的条目，避免长期运行下 roomLocks 无界增长（内存泄漏）
        try { director.removeRoom(lg.roomId); } catch (Exception ignored) {}
        try {
            roomRepo.findById(lg.roomId).ifPresent(r -> {
                r.setStatus("WAITING");
                r.setUpdatedAt(java.time.LocalDateTime.now());
                roomRepo.save(r);
            });
        } catch (Exception e) {
            log.warn("复位房间状态失败 room={}: {}", lg.roomNo, e.getMessage());
        }
    }

    /** 房主强制结束对局：终止所有进行中的 AI/LLM 调用与定时器，通知成员并复位房间。返回 null=成功。 */
    public String forceEnd(long roomId, long actorUserId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "当前没有进行中的对局";
        if (actorUserId != lg.hostUserId) return "只有房主可以强制结束对局";
        synchronized (lg) {
            lg.aborted = true;
            lg.finished = true;
            cancelTimer(lg);
        }
        java.util.concurrent.Future<?> f = lg.aiFuture;
        if (f != null) { try { f.cancel(true); } catch (Exception ignored) {} } // 中断阻塞中的 LLM HTTP 调用
        for (Long uid : membership.membersOf(roomId)) {
            sendToUser(uid, "game.ended", Map.of("reason", "房主强制结束了本局", "forced", true));
        }
        retire(lg);
        log.info("房主强制结束对局 room={} by uid={}", lg.roomNo, actorUserId);
        return null;
    }

    /** 管理员：强制结束指定房间的对局（不校验房主身份）。 */
    public String forceEndByAdmin(long roomId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "当前没有进行中的对局";
        synchronized (lg) { lg.aborted = true; lg.finished = true; cancelTimer(lg); }
        java.util.concurrent.Future<?> f = lg.aiFuture;
        if (f != null) { try { f.cancel(true); } catch (Exception ignored) {} }
        for (Long uid : membership.membersOf(roomId)) {
            sendToUser(uid, "game.ended", Map.of("reason", "管理员结束了本局", "forced", true));
        }
        retire(lg);
        log.info("管理员强制结束对局 room={}", lg.roomNo);
        return null;
    }

    /** 管理员：强制结束所有进行中的对局并复位房间，返回结束数量。 */
    public int forceEndAll() {
        int n = 0;
        for (Long roomId : new ArrayList<>(active.keySet())) {
            LiveGame lg = active.get(roomId);
            if (lg == null) continue;
            synchronized (lg) { lg.aborted = true; lg.finished = true; cancelTimer(lg); }
            java.util.concurrent.Future<?> f = lg.aiFuture;
            if (f != null) { try { f.cancel(true); } catch (Exception ignored) {} }
            for (Long uid : membership.membersOf(roomId)) {
                sendToUser(uid, "game.ended", Map.of("reason", "管理员结束了所有对局", "forced", true));
            }
            retire(lg);
            n++;
        }
        log.info("管理员强制结束全部对局，共 {} 局", n);
        return n;
    }

    private void cancelTimer(LiveGame lg) {
        if (lg.timer != null) { lg.timer.cancel(false); lg.timer = null; }        if (lg.aiWatchdog != null) { lg.aiWatchdog.cancel(false); lg.aiWatchdog = null; }
    }

    /* ================= 语音模式辅助 ================= */

    public static boolean isSpeechKind(String kind) {
        return "SPEECH".equals(kind) || "PK_SPEECH".equals(kind) || "LAST_WORDS".equals(kind);
    }

    /** 对外展示名：匿名模式取显示号（N号），否则取座位真实/托管昵称。 */
    public String displayName(LiveGame lg, int seat) {
        if (lg.anonymous) return lg.show(seat) + "号";
        SeatInfo si = lg.seats.get(seat);
        return si == null ? (seat + "号") : si.nickname;
    }

    /** 该座位朗读用的音色：真人按其语音性别偏好，机器人/未设置按座位奇偶稳定分配男女声。 */
    public String resolveVoice(LiveGame lg, int seat) {
        SeatInfo si = lg.seats.get(seat);
        String gender = null;
        if (si != null && !si.bot) {
            gender = userRepo.findById(si.userId).map(User::getVoiceGender).orElse(null);
        }
        if (gender == null || (!gender.equalsIgnoreCase("M") && !gender.equalsIgnoreCase("F"))) {
            gender = (seat % 2 == 0) ? "M" : "F";
        }
        return vprops.voiceForSeat(seat, gender);
    }

    /** 语音通道查询某真人座位在其房间是否轮到发言（用于放行麦克风上行）。 */
    public boolean isUserSpeechTurn(long roomId, long userId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return false;
        Integer seat = lg.userToSeat.get(userId);
        return seat != null && !lg.finished
                && lg.engine.currentActor() == seat && isSpeechKind(lg.engine.currentActionKind());
    }

    /** 语音模式：累积该座位本轮的 ASR 转写片段。 */
    public void appendVoiceTranscript(long roomId, int seat, String clause) {
        LiveGame lg = active.get(roomId);
        if (lg != null) lg.appendTranscript(seat, clause);
    }

    /** 真人结束语音发言：把累积转写作为发言提交并推进。返回 null=成功，否则错误提示。 */
    public String commitHumanSpeech(long roomId, long userId) {
        LiveGame lg = active.get(roomId);
        if (lg == null) return "无进行中的对局";
        Integer seatObj;
        String text;
        synchronized (lg) {
            if (lg.finished) return "对局已结束";
            seatObj = lg.userToSeat.get(userId);
            if (seatObj == null) return "你不在本局";
            int seat = seatObj;
            if (lg.engine.currentActor() != seat) return "还没轮到你发言";
            if (!isSpeechKind(lg.engine.currentActionKind())) return "当前不是发言阶段";
            text = lg.takeTranscript(seat);
            lg.voiceSpeakingSeat = 0;
            if (text == null || text.isBlank()) text = "（过）";
            GameAction a = new GameAction();
            a.text = text;
            String err = applyActionToEngine(lg.engine, seat, a);
            if (err != null) return err;
            lg.waitingHumanSeat = 0;
        }
        pump(lg);
        return null;
    }

    /* ================= 阶段时限（秒） ================= */

    private int durationFor(String kind) {
        return switch (kind) {
            case "SPEECH", "PK_SPEECH" -> 120;
            // 遗言：给足时间交代信息（与白天发言同为 120 秒）
            case "LAST_WORDS" -> 120;
            case "SHERIFF_SIGNUP" -> 15;
            case "SHERIFF_WITHDRAW" -> 15;   // 退水表态：给候选人留出选择时间
            case "VOTE", "PK_VOTE", "SHERIFF_VOTE" -> 30;
            case "SHERIFF_TRANSFER" -> 30;   // 警徽移交：给警长留出选择时间
            case "SHERIFF_ORDER" -> 20;      // 决定发言顺序：给警长留出选择时间
            default -> 30; // 夜晚行动/开枪
        };
    }
}
