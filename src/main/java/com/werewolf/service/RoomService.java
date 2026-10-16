package com.werewolf.service;

import com.werewolf.model.Room;
import com.werewolf.model.RoomPlayer;
import com.werewolf.model.User;
import com.werewolf.config.VoiceProperties;
import com.werewolf.repo.RoomPlayerRepository;
import com.werewolf.repo.RoomRepository;
import com.werewolf.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

@Service
public class RoomService {

    public static final int MAX_SEATS = 24;
    public static final int MIN_SEATS = 6;

    /**
     * 房间级互斥锁表：roomId -> 锁对象。用于把同一房间的「人数校验 → 计算空位 → 保存座位」
     * 串行化，避免并发加入时分配到同一座位或突破人数上限。
     * 说明：不能直接 synchronized(room) —— 每次事务读到的 Room 实体是不同实例，加锁无效；
     * 统一用本表按 roomId 获取稳定的锁对象。
     * 采用 ReentrantLock 而非 synchronized(锁对象)：synchronized 代码块在方法返回时就释放锁，
     * 而此时 @Transactional 事务尚未提交，别的线程会读不到刚插入的座位行，仍可能重复占座；
     * 因此这里把解锁推迟到当前事务提交/回滚之后（见 withRoomLock）。
     */
    private static final ConcurrentHashMap<Long, ReentrantLock> ROOM_LOCKS = new ConcurrentHashMap<>();

    /** 获取指定房间的稳定锁对象（首次访问时创建）。 */
    private static ReentrantLock roomLock(long roomId) {
        return ROOM_LOCKS.computeIfAbsent(roomId, k -> new ReentrantLock());
    }

    /**
     * 在房间级互斥锁内执行 action，并保证锁延迟到当前事务提交/回滚后才释放，避免“未提交先放锁”造成重复占座。
     * 若当前不在事务中（无事务同步），则在 action 结束后立即释放锁。
     */
    private <T> T withRoomLock(long roomId, Supplier<T> action) {
        ReentrantLock lock = roomLock(roomId);
        lock.lock();
        boolean deferToTx = false;
        try {
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                deferToTx = true;
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                lock.unlock(); // 事务提交或回滚后再放锁
                            }
                        });
            }
            return action.get();
        } finally {
            if (!deferToTx) lock.unlock();
        }
    }

    private final RoomRepository roomRepo;
    private final RoomPlayerRepository playerRepo;
    private final UserRepository userRepo;
    private final PresenceService presence;
    private final RoomMembership membership;
    private final DecorationService decoration;
    private final BoardService boardService;
    private final VoiceProperties vprops;
    private final VipService vipService;
    /** 观战房被解散时需要终止进行中的对局，避免留下无人看管的空转对局。 */
    private final com.werewolf.game.LiveGameService gameService;
    private final SecureRandom random = new SecureRandom();

    public RoomService(RoomRepository roomRepo, RoomPlayerRepository playerRepo,
                       UserRepository userRepo, PresenceService presence, RoomMembership membership,
                       DecorationService decoration, BoardService boardService, VoiceProperties vprops,
                       VipService vipService,
                       com.werewolf.game.LiveGameService gameService) {
        this.roomRepo = roomRepo;
        this.playerRepo = playerRepo;
        this.userRepo = userRepo;
        this.presence = presence;
        this.membership = membership;
        this.decoration = decoration;
        this.boardService = boardService;
        this.vprops = vprops;
        this.vipService = vipService;
        this.gameService = gameService;
    }

    /* ---------- DTO ---------- */

    public record PlayerView(int seat, long userId, String nickname, int avatarId, int level,
                             boolean ready, boolean online, boolean host, boolean ai,
                             String avatarUrl, String nickColor, String frameColor, String title, int vip,
                             String skin3d) {}

    public record RoomView(String roomNo, String status, long hostUserId, int maxSeats,
                           Map<String, Integer> board, String boardName, List<PlayerView> players,
                           boolean voiceMode, boolean anonymous, boolean itemMatch, boolean huntCity,
                           boolean aiOnly) {}

    public record ActionResult(RoomView view, String error) {}

    /* ---------- 查询 ---------- */

    public Optional<Room> findRoomOfUser(long userId) {
        return playerRepo.findByUserId(userId).stream().findFirst().map(p -> roomRepo.findById(p.getRoomId()).orElse(null));
    }

    public Optional<Room> findByRoomNo(String roomNo) {
        return roomRepo.findByRoomNo(roomNo);
    }

    /**
     * 反查某用户正在「观战」的房间（观战者没有 RoomPlayer 座位记录，只能靠房间成员索引）。
     * 用于观战者拉取对局状态 / 恢复观战界面 / 主动退出观战。
     */
    public Optional<Room> findWatchedRoom(long userId) {
        for (Long rid : membership.roomsOf(userId)) {
            Optional<Room> r = roomRepo.findById(rid);
            if (r.isPresent()) return r;
        }
        return Optional.empty();
    }

    /* ---------- 视图 ---------- */

    public RoomView view(Room room) {
        List<RoomPlayer> players = playerRepo.findByRoomIdOrderBySeatNoAsc(room.getId());
        List<PlayerView> views = players.stream()
                .map(p -> {
                    if (p.isAi()) {
                        return new PlayerView(p.getSeatNo(), p.getUserId(), p.getAiName(), 2, 1,
                                true, true, false, true, null, null, null, null, 0, null);
                    }
                    User u = userRepo.findById(p.getUserId()).orElse(null);
                    if (u == null) return null;
                    var dec = decoration.of(u);
                    return new PlayerView(p.getSeatNo(), u.getId(), u.getNickname(), u.getAvatarId(),
                            u.getLevel(), p.isReady(), presence.isOnline(u.getId()), u.getId().equals(room.getHostUserId()),
                            false, (String) dec.get("avatarUrl"), (String) dec.get("nickColor"),
                            (String) dec.get("frameColor"), (String) dec.get("title"), vipService.effectiveLevel(u),
                            (String) dec.get("skin3d"));
                })
                .filter(v -> v != null)
                .toList();
        return new RoomView(room.getRoomNo(), room.getStatus(), room.getHostUserId(), room.getMaxSeats(),
                boardService.parse(room.getBoardConfig()), room.getBoardName(), views,
                room.isVoiceMode(), room.isAnonymous(), room.isItemMatch(), room.isHuntCity(),
                room.isAiOnly());
    }

    /* ---------- 创建 ---------- */

    @Transactional
    public ActionResult create(User host) {
        if (findRoomOfUser(host.getId()).isPresent()) {
            return new ActionResult(null, "你已在其他房间中，请先离开");
        }
        Room room = new Room();
        room.setRoomNo(generateRoomNo());
        room.setHostUserId(host.getId());
        room.setMaxSeats(MAX_SEATS);
        room = roomRepo.save(room);

        RoomPlayer p = new RoomPlayer();
        p.setRoomId(room.getId());
        p.setUserId(host.getId());
        p.setSeatNo(1);
        p.setReady(false);
        playerRepo.save(p);
        membership.add(room.getId(), host.getId());
        return new ActionResult(view(room), null);
    }

    private String generateRoomNo() {
        for (int i = 0; i < 50; i++) {
            String no = String.format("%06d", 100000 + random.nextInt(900000));
            if (!roomRepo.existsByRoomNo(no)) return no;
        }
        throw new IllegalStateException("无法生成唯一房间号");
    }

    /* ================= 全 AI 队局（观战房） ================= */

    /** 观战房可选的板子（预设 id），覆盖 6~16 人的常见配置。 */
    private static final List<String> AI_SHOW_BOARD_IDS = List.of(
            "beginner6", "standard9", "guard10", "classic12", "gods12", "whiteking14", "wolfking15", "sixteen16");
    private static final String AI_SHOW_DEFAULT_BOARD_ID = "classic12";

    /** 观战房可选板子列表（供前端下拉）。 */
    public List<Map<String, Object>> aiShowBoards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BoardService.Preset p : boardService.presets()) {
            if (!AI_SHOW_BOARD_IDS.contains(p.id())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("desc", p.desc());
            m.put("counts", p.counts());
            m.put("total", boardService.total(p.counts()));
            out.add(m);
        }
        return out;
    }

    /** 把观战房板子 id 解析为 counts；未知/为空时回退到默认板子。 */
    public Map<String, Integer> resolveAiShowBoard(String boardId) {
        String id = boardId == null || boardId.isBlank() ? AI_SHOW_DEFAULT_BOARD_ID : boardId;
        for (BoardService.Preset p : boardService.presets()) {
            if (p.id().equals(id)) return p.counts();
        }
        for (BoardService.Preset p : boardService.presets()) {
            if (p.id().equals(AI_SHOW_DEFAULT_BOARD_ID)) return p.counts();
        }
        return Map.of("WEREWOLF", 4, "VILLAGER", 4, "SEER", 1, "WITCH", 1, "HUNTER", 1, "IDIOT", 1);
    }

    /**
     * 创建一间「全 AI 观战房」：房主不入座（作为观战者），整局全部由 AI 出战。
     * 创建后由调用方立即开局（见 AiShowController）。
     */
    @Transactional
    public ActionResult createAiShow(User host, String boardId) {
        if (findRoomOfUser(host.getId()).isPresent()) {
            return new ActionResult(null, "你已在其他房间中，请先离开");
        }
        Map<String, Integer> counts = resolveAiShowBoard(boardId);
        Room room = new Room();
        room.setRoomNo(generateRoomNo());
        room.setHostUserId(host.getId());
        room.setMaxSeats(MAX_SEATS);
        room.setAiOnly(true);
        room.setItemMatch(false);   // 观战房不涉及道具赛
        room.setBoardConfig(boardService.serialize(counts));
        room.setBoardName(boardId == null || boardId.isBlank() ? AI_SHOW_DEFAULT_BOARD_ID : boardId);
        room = roomRepo.save(room);

        // 房主作为“观战席”登记：保留一条座位记录以便 /api/room/my 能定位房间、断线可恢复，
        // 但开局走 allBots 分支，房主不会进入对局座位（即天然观战）。
        RoomPlayer p = new RoomPlayer();
        p.setRoomId(room.getId());
        p.setUserId(host.getId());
        p.setSeatNo(1);
        p.setReady(true);
        playerRepo.save(p);
        membership.add(room.getId(), host.getId());
        return new ActionResult(view(room), null);
    }

    /** 正在进行的全 AI 观战房列表（供大厅展示、一键进入观战）。 */
    public List<Map<String, Object>> listAiShows() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Room r : roomRepo.findAll()) {
            if (!r.isAiOnly()) continue;
            com.werewolf.game.LiveGame lg = gameService.get(r.getId());
            // 只展示「有活跃对局」或「仍有人在看」的观战房，避免把服务重启后残留的僵尸房挂在大厅上
            if (lg == null && membership.membersOf(r.getId()).isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roomNo", r.getRoomNo());
            m.put("status", r.getStatus());
            m.put("boardName", r.getBoardName());
            m.put("hostUserId", r.getHostUserId());
            m.put("hostName", userRepo.findById(r.getHostUserId()).map(User::getNickname).orElse("系统"));
            m.put("watchers", membership.membersOf(r.getId()).size());
            m.put("day", lg != null ? lg.engine.day() : 0);
            m.put("phase", lg != null ? lg.engine.phase().name() : "");
            m.put("total", lg != null ? lg.engine.players().size() : 0);
            m.put("alive", lg != null ? (int) lg.engine.players().stream().filter(x -> x.alive).count() : 0);
            m.put("playing", lg != null && !lg.finished);
            out.add(m);
        }
        return out;
    }

    /* ---------- 加入 ---------- */

    @Transactional
    public ActionResult join(User user, String roomNo) {
        if (roomNo == null || !roomNo.matches("\\d{6}")) {
            return new ActionResult(null, "房间号需为 6 位数字");
        }
        if (findRoomOfUser(user.getId()).isPresent()) {
            return new ActionResult(null, "你已在其他房间中，请先离开");
        }
        Optional<Room> found = roomRepo.findByRoomNo(roomNo);
        if (found.isEmpty()) return new ActionResult(null, "房间不存在");
        Room room = found.get();
        // 全 AI 观战房不开放入座：用房间号/邀请链接进来时自动转为观战，避免“加入被拒”的割裂体验
        if (room.isAiOnly()) {
            membership.add(room.getId(), user.getId());
            return new ActionResult(view(room), null);
        }
        // 房间级互斥：把「状态校验 → 人数上限 → 计算空位 → 保存座位」整段放进临界区，
        // 锁延迟到本事务提交后才释放，避免并发加入时两人拿到同一空位或突破 maxSeats。
        return withRoomLock(room.getId(), () -> {
            if (!"WAITING".equals(room.getStatus())) return new ActionResult(null, "房间已开始游戏");
            long count = playerRepo.countByRoomId(room.getId());
            if (count >= room.getMaxSeats()) return new ActionResult(null, "房间已满");

            int seat = firstFreeSeat(room.getId());
            RoomPlayer p = new RoomPlayer();
            p.setRoomId(room.getId());
            p.setUserId(user.getId());
            p.setSeatNo(seat);
            p.setReady(false);
            playerRepo.save(p);
            // flush 让本次插入在锁内即时落库，配合延迟解锁彻底消除竞态
            playerRepo.flush();
            membership.add(room.getId(), user.getId());
            return new ActionResult(view(room), null);
        });
    }

    private int firstFreeSeat(long roomId) {
        Set<Integer> used = new HashSet<>();
        playerRepo.findByRoomIdOrderBySeatNoAsc(roomId).forEach(p -> used.add(p.getSeatNo()));
        for (int i = 1; i <= MAX_SEATS; i++) if (!used.contains(i)) return i;
        return MAX_SEATS;
    }

    /* ---------- 离开 ---------- */

    @Transactional
    public ActionResult leave(User user) {
        Optional<Room> found = findRoomOfUser(user.getId());
        if (found.isPresent()) return doLeave(found.get(), user.getId());
        // 观战者没有座位记录：只需移出房间成员集合即可（view 返回 null 表示"已无房间"）
        Optional<Room> watched = findWatchedRoom(user.getId());
        if (watched.isPresent()) {
            membership.remove(watched.get().getId(), user.getId());
            return new ActionResult(null, null);
        }
        return new ActionResult(null, "你不在任何房间");
    }

    /** 断线超时或主动离开共用 */
    @Transactional
    public ActionResult doLeave(Room room, long userId) {
        Optional<RoomPlayer> me = playerRepo.findByRoomIdAndUserId(room.getId(), userId);
        if (me.isEmpty()) return new ActionResult(null, "你不在该房间");
        playerRepo.delete(me.get());
        playerRepo.flush();
        // 关键：把离开者从房间广播成员集合移除，否则随后的 room.state 会把 TA 又拉回房间页（表现为“无法退出房间”）
        membership.remove(room.getId(), userId);
        List<RoomPlayer> remaining = playerRepo.findByRoomIdOrderBySeatNoAsc(room.getId());
        if (remaining.isEmpty()) {
            // 房间被清空：若还有进行中的对局（如全 AI 观战房）必须先终止，否则会留下无人看管的空转对局
            try { gameService.forceEndByAdmin(room.getId()); } catch (Exception ignored) {}
            membership.clearRoom(room.getId());
            roomRepo.delete(room);
            return new ActionResult(null, null); // 房间已解散，view 为 null 表示无房间
        }
        if (room.getHostUserId().equals(userId)) {
            room.setHostUserId(remaining.get(0).getUserId());
            room.setUpdatedAt(LocalDateTime.now());
            roomRepo.save(room);
        }
        // 若被踢/离开的用户仍在其他连接中，membership 保留（他可能仍在观战）
        return new ActionResult(view(room), null);
    }

    /* ---------- 准备 ---------- */

    @Transactional
    public ActionResult setReady(User user, boolean ready) {
        Optional<Room> found = findRoomOfUser(user.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        RoomPlayer me = playerRepo.findByRoomIdAndUserId(room.getId(), user.getId()).orElse(null);
        if (me == null) return new ActionResult(null, "你不在该房间");
        me.setReady(ready);
        playerRepo.save(me);
        return new ActionResult(view(room), null);
    }

    /* ---------- 踢人 ---------- */

    @Transactional
    public ActionResult kick(User host, long targetUserId) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以踢人");
        if (host.getId().equals(targetUserId)) return new ActionResult(null, "不能踢自己");
        if (playerRepo.findByRoomIdAndUserId(room.getId(), targetUserId).isEmpty()) {
            return new ActionResult(null, "该玩家不在房间内");
        }
        return doLeave(room, targetUserId);
    }

    /* ---------- 转让房主 ---------- */

    @Transactional
    public ActionResult transfer(User host, long targetUserId) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以转让");
        RoomPlayer target = playerRepo.findByRoomIdAndUserId(room.getId(), targetUserId).orElse(null);
        if (target == null) return new ActionResult(null, "该玩家不在房间内");
        if (target.isAi()) return new ActionResult(null, "不能把房主转让给 AI");
        room.setHostUserId(targetUserId);
        room.setUpdatedAt(LocalDateTime.now());
        roomRepo.save(room);
        return new ActionResult(view(room), null);
    }

    /* ---------- 观战 ---------- */

    /**
     * 观战：允许旁观进行中的对局，但必须先做准入校验——
     * 房号格式合法、房间确实存在（不存在的房间 / 已解散房间一律拒绝）。
     * 加入后把 userId 记入 membership，断线时由 removeSpectator 移除，避免幽灵观战者占位。
     */
    public ActionResult spectate(User user, String roomNo) {
        // 观战也要求房号为 6 位数字，避免用空值/非法值探测房间
        if (roomNo == null || !roomNo.matches("\\d{6}")) {
            return new ActionResult(null, "房间号需为 6 位数字");
        }
        Optional<Room> found = roomRepo.findByRoomNo(roomNo);
        if (found.isEmpty()) return new ActionResult(null, "房间不存在");
        Room room = found.get();
        // 房间存在即视为有效（WAITING / PLAYING 均允许观战）；已被解散的房间在库中不存在，上面已拦截。
        membership.add(room.getId(), user.getId());
        return new ActionResult(view(room), null);
    }

    /**
     * 观战者断线/离开时移除其 membership。
     * 观战者没有 RoomPlayer 座位记录，无法走 doLeave，因此单独提供此方法；
     * 复用 RoomMembership.remove，把该 userId 从房间成员集合中移除，断线后不再收到房间广播。
     */
    public void removeSpectator(long roomId, long userId) {
        membership.remove(roomId, userId);
    }

    /* ---------- 开局（M1 只校验，不真正开始） ---------- */

    @Transactional
    public ActionResult start(User host) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以开局");
        List<RoomPlayer> players = playerRepo.findByRoomIdOrderBySeatNoAsc(room.getId());
        if (players.size() < MIN_SEATS) {
            return new ActionResult(null, "人数不足 " + MIN_SEATS + "，M5 里程碑开放 AI 补位");
        }
        long notReady = players.stream().filter(p -> !p.isReady() && !p.getUserId().equals(host.getId())).count();
        if (notReady > 0) return new ActionResult(null, "还有 " + notReady + " 人未准备");
        return new ActionResult(view(room), "M2 里程碑开放规则引擎后即可正式开局");
    }

    /** 正式开局：置房间状态为 PLAYING，返回房间实体。 */
    @Transactional
    public Optional<Room> markPlaying(long roomId) {
        Optional<Room> r = roomRepo.findById(roomId);
        r.ifPresent(room -> { room.setStatus("PLAYING"); room.setUpdatedAt(LocalDateTime.now()); roomRepo.save(room); });
        return r;
    }

    /** 房主设置板子（预设或自定义）。counts: 角色名->数量。 */
    @Transactional
    public ActionResult setBoard(User host, Map<String, Integer> counts, String boardName) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以设置板子");
        if (!"WAITING".equals(room.getStatus())) return new ActionResult(null, "游戏已开始，板子锁定");
        if (counts == null || counts.isEmpty()) return new ActionResult(null, "板子不能为空");
        String err = boardService.validate(counts);
        if (err != null) return new ActionResult(null, err);
        room.setBoardConfig(boardService.serialize(counts));
        room.setBoardName(boardName == null || boardName.isBlank() ? "custom" : boardName);
        room.setUpdatedAt(LocalDateTime.now());
        roomRepo.save(room);
        return new ActionResult(view(room), null);
    }

    /** 房主设置语音/匿名模式（开局前）。语音模式需服务端已启用语音。 */
    @Transactional
    public ActionResult setMode(User host, boolean voiceMode, boolean anonymous, boolean huntCity) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以设置模式");
        if (!"WAITING".equals(room.getStatus())) return new ActionResult(null, "游戏已开始，模式锁定");
        if (voiceMode && !(vprops.isEnabled() && vprops.isAllowVoiceMode())) {
            return new ActionResult(null, "语音服务未启用（服务端 voice.enabled=false 或不可用）");
        }
        room.setVoiceMode(voiceMode);
        room.setAnonymous(anonymous || voiceMode); // 语音模式默认强制匿名伪装以藏 AI（真人语音也被声纹中继统一）
        room.setHuntCity(huntCity);
        room.setUpdatedAt(LocalDateTime.now());
        roomRepo.save(room);
        return new ActionResult(view(room), null);
    }

    /** 房主设置是否功能道具赛（开局前）。关闭后本局携带的功能道具不生效。 */
    @Transactional
    public ActionResult setItemMatch(User host, boolean itemMatch) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以设置");
        if (!"WAITING".equals(room.getStatus())) return new ActionResult(null, "游戏已开始，设置锁定");
        room.setItemMatch(itemMatch);
        room.setUpdatedAt(LocalDateTime.now());
        roomRepo.save(room);
        return new ActionResult(view(room), null);
    }

    public void syncMembership(long userId) {
        findRoomOfUser(userId).ifPresent(r -> membership.add(r.getId(), userId));
    }

    /** 管理员：列出所有房间概览。 */
    public List<Map<String, Object>> adminListRooms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Room r : roomRepo.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId()); m.put("roomNo", r.getRoomNo()); m.put("status", r.getStatus());
            m.put("maxSeats", r.getMaxSeats()); m.put("hostUserId", r.getHostUserId());
            m.put("hostName", userRepo.findById(r.getHostUserId()).map(User::getNickname).orElse("#" + r.getHostUserId()));
            m.put("playerCount", playerRepo.countByRoomId(r.getId()));
            long onlineCount = playerRepo.findByRoomIdOrderBySeatNoAsc(r.getId()).stream()
                    .filter(p -> !p.isAi() && presence.isOnline(p.getUserId())).count();
            m.put("onlineCount", onlineCount);
            m.put("voiceMode", r.isVoiceMode()); m.put("anonymous", r.isAnonymous()); m.put("itemMatch", r.isItemMatch());
            m.put("aiOnly", r.isAiOnly());
            m.put("boardName", r.getBoardName());
            m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /** 管理员：解散房间（清理成员座位与广播成员集合，并删除房间）。 */
    @Transactional
    public String adminDissolve(long roomId) {
        Optional<Room> found = roomRepo.findById(roomId);
        if (found.isEmpty()) return "房间不存在";
        // 先终止进行中的对局，避免房间删除后留下空转的对局线程
        try { gameService.forceEndByAdmin(roomId); } catch (Exception ignored) {}
        for (RoomPlayer p : playerRepo.findByRoomIdOrderBySeatNoAsc(roomId)) membership.remove(roomId, p.getUserId());
        playerRepo.deleteByRoomId(roomId);
        roomRepo.delete(found.get());
        return null;
    }

    public void dropMembership(long userId) {
        membership.removeEverywhere(userId);
    }

    /* ---------- 补位 AI ---------- */

    private static final java.util.concurrent.atomic.AtomicLong aiIdSeq = new java.util.concurrent.atomic.AtomicLong(-1_000_000_000L);
    /** 补位 AI 昵称池：数量须 ≥ MAX_SEATS(24)，否则满员时会因取模回绕出现重名。 */
    private static final String[] AI_NAMES = {
            "月影", "银狼", "夜莺", "灰羽", "烛火", "雾隐", "孤星", "寒鸦", "暮色", "幽兰", "赤瞳", "青岚",
            "白露", "疏影", "长风", "惊蛰", "子规", "望舒", "南栀", "北辰", "半夏", "流萤", "拾光", "山鬼"
    };

    /**
     * 为补位 AI 挑一个「本房间尚未被其它 AI 占用」的昵称，保证同房不重名。
     * 起始下标由座位号 + 房间号决定（保留原有随机感），随后顺序向后找一个未使用的名字。
     */
    private String pickAiName(long roomId, int seat) {
        Set<String> used = new HashSet<>();
        for (RoomPlayer p : playerRepo.findByRoomIdOrderBySeatNoAsc(roomId)) {
            if (p.isAi() && p.getAiName() != null) used.add(p.getAiName());
        }
        int n = AI_NAMES.length;
        int start = (int) Math.floorMod((long) seat - 1 + roomId, n);
        for (int i = 0; i < n; i++) {
            String cand = AI_NAMES[(start + i) % n];
            if (!used.contains(cand)) return cand;
        }
        // 池已用尽（池大小 == MAX_SEATS，正常不会发生），退回起始名避免 NPE。
        return AI_NAMES[start % n];
    }

    @Transactional
    public ActionResult addAi(User host) {
        Optional<Room> found = findRoomOfUser(host.getId());
        if (found.isEmpty()) return new ActionResult(null, "你不在任何房间");
        Room room = found.get();
        if (!room.getHostUserId().equals(host.getId())) return new ActionResult(null, "只有房主可以添加 AI");
        // 补位 AI 与真人加入竞争同一批空位，复用房间级互斥锁，避免与 join 并发时占同座/超员
        return withRoomLock(room.getId(), () -> {
            if (!"WAITING".equals(room.getStatus())) return new ActionResult(null, "房间已开始游戏");
            long count = playerRepo.countByRoomId(room.getId());
            if (count >= room.getMaxSeats()) return new ActionResult(null, "房间已满");
            int seat = firstFreeSeat(room.getId());
            RoomPlayer p = new RoomPlayer();
            p.setRoomId(room.getId());
            p.setUserId(aiIdSeq.decrementAndGet());
            p.setSeatNo(seat);
            p.setAi(true);
            p.setAiName(pickAiName(room.getId(), seat));
            p.setReady(true);
            playerRepo.save(p);
            playerRepo.flush();
            return new ActionResult(view(room), null);
        });
    }
}
