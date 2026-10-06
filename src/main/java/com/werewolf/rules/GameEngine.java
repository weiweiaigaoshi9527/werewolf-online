package com.werewolf.rules;

import com.werewolf.rules.BoardConfig;
import com.werewolf.rules.BoardValidator;
import com.werewolf.rules.Faction;
import com.werewolf.rules.GameEvent;
import com.werewolf.rules.GamePhase;
import com.werewolf.rules.Player;
import com.werewolf.rules.Role;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class GameEngine {
    public final boolean huntByBorder;
    /** 屠边判定前提：开局时场上是否存在神职 / 平民。
     *  若某类角色开局就不存在，则不能以“该类全灭”判定狼胜（否则缺神/缺民的板子会开局即狼胜）。 */
    private boolean borderGodsExist = false;
    private boolean borderVillagersExist = false;
    private final List<Player> players = new ArrayList<Player>();
    private final Map<Integer, Player> bySeat = new HashMap<Integer, Player>();
    private GamePhase phase = GamePhase.SETUP;
    private int day = 0;
    private final List<GameEvent> events = new ArrayList<GameEvent>();
    private Faction winner = null;
    private int guardTarget = 0;
    private final Map<Integer, Integer> wolfVotes = new LinkedHashMap<Integer, Integer>();
    private final Set<Integer> wolvesActed = new HashSet<Integer>();
    private int witchSaveTarget = 0;
    private int witchPoisonTarget = 0;
    private int seerTarget = 0;
    private int crowTarget = 0;

    /** 乌鸦今晚的污蔑目标（0=未发动）。天亮后供乌鸦本人私密查看。 */
    public int crowTarget() {
        return this.crowTarget;
    }
    private int silencerTarget = 0;
    private int nightWolfKill = 0;
    private final Set<Integer> sheriffCandidates = new LinkedHashSet<Integer>();
    private final Map<Integer, Integer> sheriffVotes = new HashMap<Integer, Integer>();
    private int sheriffSeat = 0;
    private boolean sheriffElectionDone = false;
    private int sheriffSignupCursor = 0;
    private List<Integer> speakingOrder = new ArrayList<Integer>();
    private int speakingIndex = 0;
    private final Map<Integer, Integer> exileVotes = new LinkedHashMap<Integer, Integer>();
    private List<Integer> tiebreakCandidates = new ArrayList<Integer>();
    private boolean inTiebreak = false;
    private final Deque<Integer> pendingShooters = new ArrayDeque<Integer>();
    private int shooterCursor = 0;
    private final Deque<Integer> lastWordsQueue = new ArrayDeque<Integer>();
    private final List<int[]> duoPairs = new ArrayList<int[]>();
    private final List<Integer> dawnDeaths = new ArrayList<Integer>();
    private final List<Integer> nightDeaths = new ArrayList<Integer>();
    private String afterDeathResume = "DAY";

    public GameEngine(BoardConfig board, List<Long> userIds, long seed, boolean huntByBorder) {
        this(board, userIds, seed, huntByBorder, List.of());
    }

    /** 双人模式构造：duos 为组队对子的座位号（1-based），保证每对成员同阵营。 */
    public GameEngine(BoardConfig board, List<Long> userIds, long seed, boolean huntByBorder, List<int[]> duos) {
        String err = BoardValidator.validate(board);
        if (err != null) {
            throw new IllegalArgumentException("板子非法: " + err);
        }
        if (userIds.size() != board.total()) {
            throw new IllegalArgumentException("玩家数与板子不符");
        }
        this.huntByBorder = huntByBorder;
        int total = userIds.size();
        int wolfCount = board.wolfCount();

        // 1) 组队对子优先安排到同一阵营（有 2 个狼位就整对进狼队，否则整对进好人）
        List<Integer> wolfSeats = new ArrayList<>();
        List<Integer> goodSeats = new ArrayList<>();
        Set<Integer> placed = new HashSet<>();
        List<int[]> pairs = duos == null ? List.of() : duos;
        for (int[] d : pairs) if (d != null && d.length >= 2) duoPairs.add(new int[]{d[0], d[1]});
        for (int[] d : pairs) {
            if (d == null || d.length < 2) continue;
            int a = d[0], b = d[1];
            if (a == b || a < 1 || a > total || b < 1 || b > total) continue;
            if (placed.contains(a) || placed.contains(b)) continue;
            if (wolfSeats.size() + 2 <= wolfCount) {
                wolfSeats.add(a);
                wolfSeats.add(b);
            } else {
                goodSeats.add(a);
                goodSeats.add(b);
            }
            placed.add(a);
            placed.add(b);
        }
        // 2) 剩余座位随机填充狼位，其余进好人
        List<Integer> rest = new ArrayList<>();
        for (int s = 1; s <= total; s++) {
            if (!placed.contains(s)) rest.add(s);
        }
        Collections.shuffle(rest, new Random(seed));
        for (int s : rest) {
            if (wolfSeats.size() < wolfCount) wolfSeats.add(s);
            else goodSeats.add(s);
        }
        // 3) 按阵营拆角色池并分配
        List<Role> wolfRoles = new ArrayList<>();
        List<Role> goodRoles = new ArrayList<>();
        board.counts().forEach((r, c) -> {
            for (int i = 0; i < c; ++i) {
                if (r.isWolf()) wolfRoles.add(r);
                else goodRoles.add(r);
            }
        });
        Random r1 = new Random(seed);
        Collections.shuffle(wolfSeats, r1);
        Collections.shuffle(wolfRoles, r1);
        Collections.shuffle(goodSeats, r1);
        Collections.shuffle(goodRoles, r1);
        Map<Integer, Role> seatRole = new HashMap<>();
        for (int i = 0; i < wolfSeats.size(); ++i) seatRole.put(wolfSeats.get(i), wolfRoles.get(i));
        for (int i = 0; i < goodSeats.size(); ++i) seatRole.put(goodSeats.get(i), goodRoles.get(i));
        for (int i = 0; i < userIds.size(); ++i) {
            Player p = new Player(i + 1, userIds.get(i), seatRole.get(i + 1));
            this.players.add(p);
            this.bySeat.put(p.seat, p);
        }
        this.record("GAME_START", 0, 0, "board=" + String.valueOf(board) + " huntByBorder=" + huntByBorder
                + " duos=" + (pairs.isEmpty() ? "0" : pairs.size()));
        // 记录开局是否存在神/民，供屠边判定使用（避免缺神或缺民的板子被误判为“屠边达成”）
        this.borderGodsExist = board.godCount() > 0;
        this.borderVillagersExist = board.villagerCount() > 0;
        this.beginNight();
    }

    public GameEngine(List<Role> seatRoles, boolean huntByBorder) {
        this.huntByBorder = huntByBorder;
        for (int i = 0; i < seatRoles.size(); ++i) {
            Player p = new Player(i + 1, 1000 + i, seatRoles.get(i));
            this.players.add(p);
            this.bySeat.put(p.seat, p);
        }
        this.record("GAME_START", 0, 0, "test board, huntByBorder=" + huntByBorder);
        // 与正式构造保持一致：记录开局是否存在神/民（测试板子同样按此判定屠边）
        this.borderGodsExist = this.players.stream().anyMatch(p -> p.role.isGod());
        this.borderVillagersExist = this.players.stream().anyMatch(p -> p.role.isVillager());
        this.beginNight();
    }

    public void applyItems(Map<Integer, String> seatItem, long seed) {
        Random r = new Random(seed ^ 0xA5A5L);
        block16: for (Map.Entry<Integer, String> e : seatItem.entrySet()) {
            Player holder = this.bySeat.get(e.getKey());
            if (holder == null || !holder.alive) continue;
            switch (e.getValue()) {
                case "SHIELD": {
                    holder.shielded = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带护盾");
                    break;
                }
                case "SHIELD_FULL": {
                    holder.shieldedFull = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带守护水晶");
                    break;
                }
                case "REVEAL": {
                    List<Player> others = this.alive().stream().filter(p -> p.seat != holder.seat).toList();
                    if (others.isEmpty()) continue block16;
                    Player t = others.get(r.nextInt(others.size()));
                    this.record("ITEM_REVEAL", holder.seat, t.seat, t.isWolf() ? "狼人" : "好人");
                    break;
                }
                case "SILENCE": {
                    List<Player> others = this.alive().stream().filter(p -> p.seat != holder.seat).toList();
                    if (others.isEmpty()) continue block16;
                    Player t = others.get(r.nextInt(others.size()));
                    t.silencedToday = true;
                    this.record("ITEM_EFFECT", holder.seat, t.seat, "沉默卡使 " + t.seat + " 号首日禁言");
                    break;
                }
                case "DOUBLE_VOTE": {
                    holder.doubleVote = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带铁票（下次放逐投票计两票）");
                    break;
                }
                case "IMMUNE": {
                    holder.immuneExile = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带替死娃娃（首次被放逐免死）");
                    break;
                }
                case "REVIVE": {
                    holder.reviveAvailable = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带复活卡");
                    break;
                }
                case "IRON": {
                    holder.ironSkin = true;
                    this.record("ITEM_EFFECT", holder.seat, 0, "携带铁布衫");
                    break;
                }
            }
        }
    }

    /** 双人组队的原始对子（真实座位号，1-based）。 */
    public List<int[]> duos() { return duoPairs; }

    public GamePhase phase() {
        return this.phase;
    }

    public int day() {
        return this.day;
    }

    public boolean isGameOver() {
        return this.phase == GamePhase.GAME_OVER;
    }

    public Faction winner() {
        return this.winner;
    }

    public List<Player> players() {
        return Collections.unmodifiableList(this.players);
    }

    public List<GameEvent> events() {
        return Collections.unmodifiableList(this.events);
    }

    public int sheriffSeat() {
        return this.sheriffSeat;
    }

    public Player bySeat(int seat) {
        return this.bySeat.get(seat);
    }

    public List<Player> alive() {
        return this.players.stream().filter(p -> p.alive).toList();
    }

    public List<Integer> nightDeaths() {
        return new ArrayList<Integer>(this.nightDeaths);
    }

    public List<Player> aliveWolves() {
        return this.alive().stream().filter(Player::isWolf).toList();
    }

    public List<Player> aliveGood() {
        return this.alive().stream().filter(Player::isGood).toList();
    }

    public Player first(Role role) {
        return this.players.stream().filter(p -> p.role == role).findFirst().orElse(null);
    }

    public int witchSaveTarget() {
        return this.witchSaveTarget;
    }

    public int witchPoisonTarget() {
        return this.witchPoisonTarget;
    }

    public int guardTarget() {
        return this.guardTarget;
    }

    public int nightWolfKill() {
        return this.nightWolfKill;
    }

    public int seerTarget() {
        return this.seerTarget;
    }

    public int currentShooter() {
        return this.shooterCursor;
    }

    public int currentSpeaker() {
        return this.speakingIndex < this.speakingOrder.size() ? this.speakingOrder.get(this.speakingIndex) : 0;
    }

    public List<Integer> speakingOrder() {
        return Collections.unmodifiableList(this.speakingOrder);
    }

    public boolean inTiebreak() {
        return this.inTiebreak;
    }

    public List<Integer> tiebreakCandidates() {
        return Collections.unmodifiableList(this.tiebreakCandidates);
    }

    public Set<Integer> sheriffCandidates() {
        return Collections.unmodifiableSet(this.sheriffCandidates);
    }

    public int sheriffSignupCursor() {
        return this.sheriffSignupCursor;
    }

    public boolean sheriffElectionDone() {
        return this.sheriffElectionDone;
    }

    public int currentLastWords() {
        return this.lastWordsQueue.peek() == null ? 0 : this.lastWordsQueue.peek();
    }

    /** 自愈：当前阶段没有任何合法行动者（currentActor()==0）时，尝试直接推进流程，避免整局永久卡死。
     *  返回 true 表示已推进（调用方应重新循环推进），false 表示确实无法判定如何推进（保留告警）。 */
    public boolean resolveStuckPhase() {
        switch (this.phase) {
            case SHOOT, LAST_WORDS -> {
                this.proceedAfterDeaths();
                return true;
            }
            case DAY_SPEAK -> {
                this.advanceSpeaking();
                return true;
            }
            case DAY_VOTE -> {
                this.resolveExile();
                return true;
            }
            case DAY_VOTE_TIEBREAK -> {
                this.resolveTiebreak();
                return true;
            }
            case SHERIFF_ELECTION -> {
                if (this.sheriffCandidates.isEmpty()) {
                    this.sheriffElectionDone = true;
                    this.beginSpeaking();
                } else {
                    this.resolveSheriff();
                }
                return true;
            }
            default -> {
                if (this.phase.isNight()) {
                    this.advanceToNextNightPhase();
                    return true;
                }
                return false;
            }
        }
    }

    private void beginNight() {
        ++this.day;
        this.guardTarget = 0;
        this.wolfVotes.clear();
        this.wolvesActed.clear();
        this.witchSaveTarget = 0;
        this.witchPoisonTarget = 0;
        this.seerTarget = 0;
        this.crowTarget = 0;
        this.silencerTarget = 0;
        this.nightWolfKill = 0;
        this.dawnDeaths.clear();
        for (Player p : this.players) {
            p.silencedToday = false;
            p.accusedByCrow = false;
        }
        this.advanceToNextNightPhase();
    }

    private void advanceToNextNightPhase() {
        if (this.hasAliveRole(Role.GUARD)) {
            this.phase = GamePhase.NIGHT_GUARD;
            return;
        }
        if (!this.aliveWolves().isEmpty()) {
            this.phase = GamePhase.NIGHT_WOLF;
            return;
        }
        if (this.hasAliveWitch()) {
            this.phase = GamePhase.NIGHT_WITCH;
            return;
        }
        if (this.hasAliveSeer()) {
            this.phase = GamePhase.NIGHT_SEER;
            return;
        }
        if (this.hasAliveRole(Role.CROW)) {
            this.phase = GamePhase.NIGHT_CROW;
            return;
        }
        if (this.hasAliveRole(Role.SILENCER)) {
            this.phase = GamePhase.NIGHT_SILENCER;
            return;
        }
        this.resolveNight();
    }

    private boolean hasAliveRole(Role r) {
        return this.alive().stream().anyMatch(p -> p.role == r);
    }

    private boolean hasAliveWitch() {
        Player w = this.first(Role.WITCH);
        return w != null && w.alive && (w.witchSaveAvailable || w.witchPoisonAvailable);
    }

    private boolean hasAliveSeer() {
        Player s = this.first(Role.SEER);
        return s != null && s.alive;
    }

    public void submitGuard(int target) {
        this.require(GamePhase.NIGHT_GUARD, "非守卫行动阶段");
        Player g = this.first(Role.GUARD);
        if (g == null || !g.alive) {
            throw new IllegalActionException("守卫不在场");
        }
        if (!(target == 0 || this.bySeat.get(target) != null && this.bySeat.get((Object)Integer.valueOf((int)target)).alive)) {
            throw new IllegalActionException("守护目标无效");
        }
        if (target != 0 && target == g.guardLastTarget) {
            throw new IllegalActionException("守卫不能连续两晚守同一人");
        }
        this.guardTarget = target;
        g.guardLastTarget = target;
        this.record("NIGHT_ACTION", g.seat, target, "GUARD");
        this.advancePastGuard();
    }

    private void advancePastGuard() {
        if (!this.aliveWolves().isEmpty()) {
            this.phase = GamePhase.NIGHT_WOLF;
        } else {
            this.advanceAfterWolf();
        }
    }

    public void submitWolfKill(int wolfSeat, int target) {
        Player t;
        this.require(GamePhase.NIGHT_WOLF, "非狼人行动阶段");
        Player w = this.bySeat.get(wolfSeat);
        if (w == null || !w.alive || !w.isWolf()) {
            throw new IllegalActionException("不是存活狼人");
        }
        if (this.wolvesActed.contains(wolfSeat)) {
            throw new IllegalActionException("该狼已提交");
        }
        if (target != 0 && ((t = this.bySeat.get(target)) == null || !t.alive || t.isWolf())) {
            throw new IllegalActionException("刀人目标无效(不能自刀/刀狼队友)");
        }
        this.wolfVotes.put(wolfSeat, target);
        this.wolvesActed.add(wolfSeat);
        this.record("NIGHT_ACTION", wolfSeat, target, "WOLF_VOTE");
        if (this.wolvesActed.size() >= this.aliveWolves().size()) {
            this.resolveWolfMajority();
            this.advanceAfterWolf();
        }
    }

    private void resolveWolfMajority() {
        HashMap<Integer, Integer> tally = new HashMap<Integer, Integer>();
        for (int t : this.wolfVotes.values()) {
            if (t == 0) continue;
            tally.merge(t, 1, Integer::sum);
        }
        int best = 0;
        int bestCount = 0;
        for (Map.Entry e : tally.entrySet()) {
            if ((Integer)e.getValue() <= bestCount) continue;
            best = (Integer)e.getKey();
            bestCount = (Integer)e.getValue();
        }
        this.nightWolfKill = best;
        this.record("NIGHT_ACTION", 0, best, "WOLF_KILL_RESOLVED");
    }

    private void advanceAfterWolf() {
        if (this.hasAliveWitch()) {
            this.phase = GamePhase.NIGHT_WITCH;
        } else {
            this.advanceAfterWitch();
        }
    }

    public void submitWitch(int saveTarget, int poisonTarget) {
        Player t;
        this.require(GamePhase.NIGHT_WITCH, "非女巫行动阶段");
        Player w = this.first(Role.WITCH);
        if (w == null || !w.alive) {
            throw new IllegalActionException("女巫不在场");
        }
        if (saveTarget != 0 && poisonTarget != 0) {
            throw new IllegalActionException("女巫同一晚只能用一瓶药");
        }
        if (saveTarget != 0) {
            if (!w.witchSaveAvailable) {
                throw new IllegalActionException("解药已用完");
            }
            if (saveTarget != this.nightWolfKill) {
                throw new IllegalActionException("解药只能救当晚被刀者");
            }
            t = this.bySeat.get(saveTarget);
            if (t == null || !t.alive) {
                throw new IllegalActionException("救人目标无效");
            }
            this.witchSaveTarget = saveTarget;
            w.witchSaveAvailable = false;
            this.record("NIGHT_ACTION", w.seat, saveTarget, "WITCH_SAVE");
        }
        if (poisonTarget != 0) {
            if (!w.witchPoisonAvailable) {
                throw new IllegalActionException("毒药已用完");
            }
            t = this.bySeat.get(poisonTarget);
            if (t == null || !t.alive) {
                throw new IllegalActionException("毒人目标无效");
            }
            this.witchPoisonTarget = poisonTarget;
            w.witchPoisonAvailable = false;
            this.record("NIGHT_ACTION", w.seat, poisonTarget, "WITCH_POISON");
        }
        this.advanceAfterWitch();
    }

    private void advanceAfterWitch() {
        if (this.hasAliveSeer()) {
            this.phase = GamePhase.NIGHT_SEER;
        } else {
            this.advanceAfterSeer();
        }
    }

    public void submitSeer(int target) {
        this.require(GamePhase.NIGHT_SEER, "非预言家行动阶段");
        Player s = this.first(Role.SEER);
        if (s == null || !s.alive) {
            throw new IllegalActionException("预言家不在场");
        }
        Player t = this.bySeat.get(target);
        if (t == null || !t.alive) {
            throw new IllegalActionException("查验目标无效");
        }
        this.seerTarget = target;
        s.seerChecked.add(target);
        String result = t.isWolf() ? "WOLF" : "GOOD";
        this.record("NIGHT_ACTION", s.seat, target, "SEER_CHECK=" + result);
        this.advanceAfterSeer();
    }

    private void advanceAfterSeer() {
        if (this.hasAliveRole(Role.CROW)) {
            this.phase = GamePhase.NIGHT_CROW;
        } else {
            this.advanceAfterCrow();
        }
    }

    public void submitCrow(int target) {
        Player t;
        this.require(GamePhase.NIGHT_CROW, "非乌鸦行动阶段");
        Player c = this.first(Role.CROW);
        if (c == null || !c.alive) {
            throw new IllegalActionException("乌鸦不在场");
        }
        if (!(target == 0 || (t = this.bySeat.get(target)) != null && t.alive)) {
            throw new IllegalActionException("诽谤目标无效");
        }
        this.crowTarget = target;
        if (target != 0) {
            this.bySeat.get((Object)Integer.valueOf((int)target)).accusedByCrow = true;
        }
        this.record("NIGHT_ACTION", c.seat, target, "CROW_ACCUSE");
        this.advanceAfterCrow();
    }

    private void advanceAfterCrow() {
        if (this.hasAliveRole(Role.SILENCER)) {
            this.phase = GamePhase.NIGHT_SILENCER;
        } else {
            this.resolveNight();
        }
    }

    public void submitSilencer(int target) {
        Player t;
        this.require(GamePhase.NIGHT_SILENCER, "非禁言长老行动阶段");
        Player e = this.first(Role.SILENCER);
        if (e == null || !e.alive) {
            throw new IllegalActionException("禁言长老不在场");
        }
        if (target != 0 && target == e.silencerLastTarget) {
            throw new IllegalActionException("不能连续两晚禁同一人");
        }
        if (!(target == 0 || (t = this.bySeat.get(target)) != null && t.alive)) {
            throw new IllegalActionException("禁言目标无效");
        }
        this.silencerTarget = target;
        e.silencerLastTarget = target;
        if (target != 0) {
            this.bySeat.get((Object)Integer.valueOf((int)target)).silencedToday = true;
        }
        this.record("NIGHT_ACTION", e.seat, target, "SILENCER");
        this.resolveNight();
    }

    private void resolveNight() {
        Player t;
        this.record("NIGHT_RESOLVE", 0, 0, "wolfKill=" + this.nightWolfKill + " guard=" + this.guardTarget + " save=" + this.witchSaveTarget + " poison=" + this.witchPoisonTarget);
        if (this.nightWolfKill != 0) {
            boolean saved;
            boolean guarded = this.guardTarget == this.nightWolfKill;
            boolean bl = saved = this.witchSaveTarget == this.nightWolfKill;
            if (guarded && saved) {
                this.kill(this.nightWolfKill, "WOLF", true);
            } else if (guarded) {
                this.record("NIGHT_RESOLVE", 0, this.nightWolfKill, "PROTECTED_BY_GUARD");
            } else if (saved) {
                this.record("NIGHT_RESOLVE", 0, this.nightWolfKill, "SAVED_BY_WITCH");
            } else {
                this.kill(this.nightWolfKill, "WOLF", true);
            }
        }
        if (this.witchPoisonTarget != 0 && (t = this.bySeat.get(this.witchPoisonTarget)) != null && t.alive) {
            this.kill(this.witchPoisonTarget, "POISON", false);
        }
        this.phase = GamePhase.DAWN;
        this.processDawn();
    }

    private void kill(int seat, String cause, boolean allowShoot) {
        Player p = this.bySeat.get(seat);
        if (p == null || !p.alive) {
            return;
        }
        if (p.shielded && "WOLF".equals(cause)) {
            p.shielded = false;
            this.record("ITEM_EFFECT", seat, 0, "护盾抵挡了今晚的刀");
            return;
        }
        if (p.shieldedFull && ("WOLF".equals(cause) || "POISON".equals(cause))) {
            p.shieldedFull = false;
            this.record("ITEM_EFFECT", seat, 0, "守护水晶挡下了这一击");
            return;
        }
        if (p.reviveAvailable) {
            p.reviveAvailable = false;
            this.record("ITEM_EFFECT", seat, 0, "复活卡发动，原地复活");
            return;
        }
        p.alive = false;
        p.deathCause = cause;
        p.deathDay = this.day;
        p.canShootOnDeath = false;
        if (allowShoot && p.role == Role.HUNTER) {
            p.canShootOnDeath = true;
        }
        this.dawnDeaths.add(seat);
        this.record("PLAYER_DIED", seat, 0, cause);
        // 警长在夜晚/枪杀等任意路径死亡，都要走警徽流（移交或撕毁），否则警徽凭空消失
        if (seat == this.sheriffSeat) {
            this.handleSheriffDeath();
        }
    }

    private void processDawn() {
        this.afterDeathResume = "DAY";
        this.record("DAWN_ANNOUNCE", 0, 0, "deaths=" + String.valueOf(this.dawnDeaths));
        this.nightDeaths.clear();
        this.nightDeaths.addAll(this.dawnDeaths);
        for (int seat : this.dawnDeaths) {
            Player p = this.bySeat.get(seat);
            if (!p.canShootOnDeath) continue;
            this.pendingShooters.add(seat);
        }
        if (this.day == 1) {
            for (int seat : this.dawnDeaths) {
                this.lastWordsQueue.add(seat);
            }
        }
        this.proceedAfterDeaths();
    }

    private void proceedAfterDeaths() {
        if (!this.pendingShooters.isEmpty()) {
            this.shooterCursor = this.pendingShooters.poll();
            this.phase = GamePhase.SHOOT;
            return;
        }
        this.shooterCursor = 0;
        if (!this.lastWordsQueue.isEmpty()) {
            this.phase = GamePhase.LAST_WORDS;
            return;
        }
        if (this.checkWin()) {
            return;
        }
        if ("NIGHT".equals(this.afterDeathResume)) {
            this.beginNight();
        } else {
            this.enterDay();
        }
    }

    public void submitShoot(int shooterSeat, int target) {
        this.require(GamePhase.SHOOT, "非开枪阶段");
        if (shooterSeat != this.shooterCursor) {
            throw new IllegalActionException("不是该玩家开枪");
        }
        Player s = this.bySeat.get(shooterSeat);
        if (target != 0) {
            Player t = this.bySeat.get(target);
            if (t == null || !t.alive) {
                throw new IllegalActionException("开枪目标无效");
            }
            boolean chain = s.role == Role.HUNTER || s.role == Role.WOLF_KING;
            this.kill(target, "SHOT", true);
            if (this.bySeat.get((Object)Integer.valueOf((int)target)).role == Role.HUNTER) {
                this.pendingShooters.add(target);
            }
            this.record("SHOOT", shooterSeat, target, s.role.cnName() + "开枪");
        } else {
            this.record("SHOOT", shooterSeat, 0, "放弃开枪");
        }
        this.proceedAfterDeaths();
    }

    public void submitLastWords(int seat, String text) {
        int cur;
        this.require(GamePhase.LAST_WORDS, "非遗言阶段");
        int n = cur = this.lastWordsQueue.peek() == null ? 0 : this.lastWordsQueue.peek();
        if (seat != cur) {
            throw new IllegalActionException("不是该玩家遗言");
        }
        this.lastWordsQueue.poll();
        this.record("LAST_WORDS", seat, 0, text == null ? "" : text);
        this.proceedAfterDeaths();
    }

    private void enterDay() {
        if (!this.sheriffElectionDone && this.day == 1) {
            this.beginSheriffElection();
        } else {
            this.beginSpeaking();
        }
    }

    private void beginSheriffElection() {
        this.phase = GamePhase.SHERIFF_ELECTION;
        this.sheriffCandidates.clear();
        this.sheriffVotes.clear();
        this.sheriffSignupCursor = 1;
        while (this.sheriffSignupCursor <= this.players.size() && !this.bySeat.get((Object)Integer.valueOf((int)this.sheriffSignupCursor)).alive) {
            ++this.sheriffSignupCursor;
        }
        if (this.sheriffSignupCursor > this.players.size()) {
            this.sheriffElectionDone = true;
            this.beginSpeaking();
        }
    }

    public void submitSheriffSignup(int seat, boolean join) {
        this.require(GamePhase.SHERIFF_ELECTION, "非警长竞选");
        if (seat != this.sheriffSignupCursor) {
            throw new IllegalActionException("报名顺序错误");
        }
        Player p = this.bySeat.get(seat);
        if (p != null && p.alive && join) {
            this.sheriffCandidates.add(seat);
        }
        this.record("SHERIFF_SIGNUP", seat, 0, join ? "上警" : "不上");
        this.advanceSignup();
    }

    private void advanceSignup() {
        do {
            ++this.sheriffSignupCursor;
        } while (this.sheriffSignupCursor <= this.players.size() && !this.bySeat.get((Object)Integer.valueOf((int)this.sheriffSignupCursor)).alive);
        if (this.sheriffSignupCursor > this.players.size()) {
            if (this.sheriffCandidates.isEmpty()) {
                this.sheriffElectionDone = true;
                this.beginSpeaking();
            } else {
                // 全员上警（没有合法选民）时直接结算，避免无人可投票导致对局永久卡死
                long voters = this.alive().stream().filter(p -> !this.sheriffCandidates.contains(p.seat)).count();
                if (voters == 0L) {
                    this.resolveSheriff();
                } else {
                    this.phase = GamePhase.SHERIFF_ELECTION;
                }
            }
        }
    }

    public void submitSheriffVote(int voterSeat, int target) {
        this.require(GamePhase.SHERIFF_ELECTION, "非警长竞选");
        if (this.sheriffCandidates.isEmpty()) {
            throw new IllegalActionException("无候选人");
        }
        if (this.sheriffSignupCursor <= this.players.size()) {
            throw new IllegalActionException("报名尚未结束");
        }
        Player v = this.bySeat.get(voterSeat);
        if (v == null || !v.alive) {
            throw new IllegalActionException("投票者无效");
        }
        if (this.sheriffCandidates.contains(voterSeat)) {
            throw new IllegalActionException("候选人无投票权");
        }
        if (target != 0 && !this.sheriffCandidates.contains(target)) {
            throw new IllegalActionException("投票目标非候选人");
        }
        this.sheriffVotes.put(voterSeat, target);
        long voters = this.alive().stream().filter(p -> !this.sheriffCandidates.contains(p.seat)).count();
        if ((long)this.sheriffVotes.size() >= voters) {
            this.resolveSheriff();
        }
    }

    private void resolveSheriff() {
        this.recordVote(this.sheriffVotes, "警长投票");
        HashMap<Integer, Integer> tally = new HashMap<Integer, Integer>();
        for (int t : this.sheriffVotes.values()) {
            if (t == 0) continue;
            tally.merge(t, 1, Integer::sum);
        }
        int best = 0;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry e : tally.entrySet()) {
            if ((Integer)e.getValue() > bestCount) {
                best = (Integer)e.getKey();
                bestCount = (Integer)e.getValue();
                tie = false;
                continue;
            }
            if ((Integer)e.getValue() != bestCount) continue;
            tie = true;
        }
        this.sheriffElectionDone = true;
        if (best != 0 && !tie) {
            this.sheriffSeat = best;
            this.bySeat.get((Object)Integer.valueOf((int)best)).isSheriff = true;
            this.record("SHERIFF_WIN", best, 0, "当选警长");
        } else {
            this.record("SHERIFF_WIN", 0, 0, "平票或无当选，本轮无警长");
        }
        this.beginSpeaking();
    }

    private void beginSpeaking() {
        this.phase = GamePhase.DAY_SPEAK;
        this.inTiebreak = false;
        this.tiebreakCandidates.clear();
        this.exileVotes.clear();
        this.speakingOrder = this.buildSpeakingOrder();
        this.speakingIndex = 0;
    }

    private List<Integer> buildSpeakingOrder() {
        int start;
        List<Integer> aliveSeats = this.alive().stream().map(p -> p.seat).sorted().toList();
        int n = aliveSeats.size();
        if (n == 0) {
            return List.of();
        }
        if (this.sheriffSeat != 0 && this.alive().stream().anyMatch(p -> p.seat == this.sheriffSeat)) {
            int sIdx = aliveSeats.indexOf(this.sheriffSeat);
            boolean rightward = new Random(this.day * 31 + 7).nextBoolean();
            start = rightward ? (sIdx + 1) % n : (sIdx - 1 + n) % n;
        } else {
            start = 0;
        }
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < n; ++i) {
            order.add(aliveSeats.get((start + i) % n));
        }
        return order;
    }

    public void submitSpeech(int seat, String text) {
        this.require(GamePhase.DAY_SPEAK, "非发言阶段");
        int cur = this.currentSpeaker();
        if (seat != cur) {
            throw new IllegalActionException("不是该玩家发言");
        }
        Player p = this.bySeat.get(seat);
        if (!p.canSpeak()) {
            this.record("SPEECH", seat, 0, "[被禁言，过麦]");
        } else {
            this.record("SPEECH", seat, 0, text == null ? "" : text);
        }
        this.advanceSpeaking();
    }

    private void advanceSpeaking() {
        ++this.speakingIndex;
        while (this.speakingIndex < this.speakingOrder.size() && !this.bySeat.get((Object)this.speakingOrder.get((int)this.speakingIndex)).alive) {
            ++this.speakingIndex;
        }
        if (this.speakingIndex >= this.speakingOrder.size()) {
            this.phase = GamePhase.DAY_VOTE;
            this.exileVotes.clear();
            if (this.alive().stream().noneMatch(Player::canVote)) {
                this.resolveExile();
            }
        }
    }

    public void submitVote(int voterSeat, int target) {
        Player t;
        this.require(GamePhase.DAY_VOTE, "非投票阶段");
        Player v = this.bySeat.get(voterSeat);
        if (v == null || !v.canVote()) {
            throw new IllegalActionException("无投票权");
        }
        if (target != 0 && ((t = this.bySeat.get(target)) == null || !t.alive || t.idiotRevealed)) {
            throw new IllegalActionException("投票目标无效");
        }
        this.exileVotes.put(voterSeat, target);
        long voters = this.alive().stream().filter(Player::canVote).count();
        if ((long)this.exileVotes.size() >= voters) {
            this.resolveExile();
        }
    }

    private void recordVote(Map<Integer, Integer> votes, String label) {
        ArrayList<Integer> voters = new ArrayList<Integer>(votes.keySet());
        Collections.sort(voters);
        ArrayList<String> parts = new ArrayList<>();
        Iterator iterator = voters.iterator();
        while (iterator.hasNext()) {
            int v;
            int t = votes.get(v = ((Integer)iterator.next()).intValue());
            parts.add(v + "→" + (t == 0 ? "弃" : t + "号"));
        }
        this.record("VOTE_DETAIL", 0, 0, label + " " + String.join("  ", parts));
    }

    private Map<Integer, Double> tallyVotes(Map<Integer, Integer> votes) {
        HashMap<Integer, Double> tally = new HashMap<Integer, Double>();
        for (Map.Entry<Integer, Integer> e : votes.entrySet()) {
            int voter = e.getKey();
            int target = e.getValue();
            if (target == 0) continue;
            double w = voter == this.sheriffSeat ? 1.5 : 1.0;
            Player vp = this.bySeat.get(voter);
            if (vp != null && vp.doubleVote) {
                w *= 2.0;
            }
            tally.merge(target, w, Double::sum);
        }
        for (Player p : this.alive()) {
            if (!p.accusedByCrow) continue;
            tally.merge(p.seat, 1.0, Double::sum);
        }
        return tally;
    }

    /** 消耗本局已生效过的“铁票”（一次生效后失效）。仅在真正产生放逐结果的轮次调用。 */
    private void consumeDoubleVotes() {
        for (Player vp : this.players) {
            if (vp.doubleVote) {
                vp.doubleVote = false;
                this.record("ITEM_EFFECT", vp.seat, 0, "铁票生效：本次投票计两票");
            }
        }
    }

    private void resolveExile() {
        this.recordVote(this.exileVotes, "放逐投票");
        Map<Integer, Double> tally = this.tallyVotes(this.exileVotes);
        List<Integer> maxSeats = this.maxKeys(tally);
        if (maxSeats.isEmpty()) {
            this.record("VOTE_RESULT", 0, 0, "无人被放逐");
            this.endDay();
            return;
        }
        if (maxSeats.size() > 1) {
            if (!this.inTiebreak) {
                this.inTiebreak = true;
                this.tiebreakCandidates = maxSeats;
                this.phase = GamePhase.DAY_VOTE_TIEBREAK;
                this.exileVotes.clear();
                this.record("VOTE_TIE", 0, 0, "平票 PK: " + String.valueOf(maxSeats));
                long tvoters = this.alive().stream().filter(p -> p.canVote() && !this.tiebreakCandidates.contains(p.seat)).count();
                if (tvoters == 0L) {
                    this.resolveTiebreak();
                }
                return;
            }
            this.record("VOTE_RESULT", 0, 0, "PK 后仍平票，无人出局");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
        int out = maxSeats.get(0);
        Player p2 = this.bySeat.get(out);
        if (p2.role == Role.IDIOT && !p2.idiotRevealed) {
            p2.idiotRevealed = true;
            this.record("IDIOT_REVEAL", out, 0, "白痴翻牌免死，失去投票权");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
        // 道具·复活卡：被放逐时原地复活（保留全部权利）
        if (p2.reviveAvailable) {
            p2.reviveAvailable = false;
            this.record("ITEM_EFFECT", out, 0, "复活卡发动，原地复活");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
        // 道具·铁布衫：首次被放逐免疫出局（保留投票权）
        if (p2.ironSkin) {
            p2.ironSkin = false;
            this.record("ITEM_EFFECT", out, 0, "铁布衫挡下了本次放逐");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
        if (p2.immuneExile) {
            p2.immuneExile = false;
            this.record("ITEM_EFFECT", out, 0, "替死娃娃挡下了本次放逐");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
        this.exileVotes.clear();
        this.record("VOTE_RESULT", out, 0, "放逐出局");
        // 铁票：只在“真正产生放逐结果”的轮次消耗（平票进 PK 不消耗，避免被白费）
        this.consumeDoubleVotes();
        this.dawnDeaths.clear();
        p2.alive = false;
        p2.deathCause = "EXILE";
        p2.deathDay = this.day;
        p2.canShootOnDeath = p2.role == Role.HUNTER || p2.role == Role.WOLF_KING;
        this.record("PLAYER_DIED", out, 0, "EXILE");
        if (p2.canShootOnDeath) {
            this.pendingShooters.add(out);
        }
        this.lastWordsQueue.add(out);
        if (out == this.sheriffSeat) {
            this.handleSheriffDeath();
        }
        this.afterDeathResume = "NIGHT";
        this.proceedAfterDeaths();
    }

    private void handleSheriffDeath() {
        List<Integer> others = this.alive().stream().map(p -> p.seat).filter(s -> s != this.sheriffSeat).toList();
        this.bySeat.get((Object)Integer.valueOf((int)this.sheriffSeat)).isSheriff = false;
        if (!others.isEmpty() && new Random(this.day * 13 + 3).nextBoolean()) {
            int next;
            this.sheriffSeat = next = others.get(new Random(this.day * 17 + 5).nextInt(others.size())).intValue();
            this.bySeat.get((Object)Integer.valueOf((int)next)).isSheriff = true;
            this.record("SHERIFF_WIN", next, 0, "警徽移交");
        } else {
            this.sheriffSeat = 0;
            this.record("SHERIFF_WIN", 0, 0, "警徽撕毁");
        }
    }

    public void submitTiebreakSpeech(int seat, String text) {
        this.require(GamePhase.DAY_VOTE_TIEBREAK, "非 PK 阶段");
        if (!this.tiebreakCandidates.contains(seat)) {
            throw new IllegalActionException("非 PK 候选人");
        }
        Player p = this.bySeat.get(seat);
        if (!p.canSpeak()) {
            throw new IllegalActionException("被禁言");
        }
        this.record("SPEECH", seat, 0, "PK: " + text);
    }

    public void submitTiebreakVote(int voterSeat, int target) {
        this.require(GamePhase.DAY_VOTE_TIEBREAK, "非 PK 阶段");
        Player v = this.bySeat.get(voterSeat);
        if (v == null || !v.canVote()) {
            throw new IllegalActionException("无投票权");
        }
        if (this.tiebreakCandidates.contains(voterSeat)) {
            throw new IllegalActionException("PK 候选人无投票权");
        }
        if (target != 0 && !this.tiebreakCandidates.contains(target)) {
            throw new IllegalActionException("目标非 PK 候选人");
        }
        this.exileVotes.put(voterSeat, target);
        long voters = this.alive().stream().filter(p -> p.canVote() && !this.tiebreakCandidates.contains(p.seat)).count();
        if ((long)this.exileVotes.size() >= voters) {
            this.resolveTiebreak();
        }
    }

    private void resolveTiebreak() {
        this.recordVote(this.exileVotes, "PK 投票");
        // PK 结算复用统一计票规则：警长 1.5 票 + 铁票双倍 + 乌鸦诽谤 +1，与其他放逐轮保持一致
        Map<Integer, Double> tally = this.tallyVotes(this.exileVotes);
        List<Integer> maxSeats = this.maxKeys(tally);
        this.inTiebreak = false;
        if (maxSeats.size() != 1) {
            this.record("VOTE_RESULT", 0, 0, "PK 后仍平票，无人出局");
            this.endDay();
            return;
        }
        int out = maxSeats.get(0);
        Player p = this.bySeat.get(out);
        if (p.role == Role.IDIOT && !p.idiotRevealed) {
            p.idiotRevealed = true;
            this.record("IDIOT_REVEAL", out, 0, "白痴翻牌免死");
            this.endDay();
            return;
        }
        // 道具·复活卡：PK 放逐同样生效
        if (p.reviveAvailable) {
            p.reviveAvailable = false;
            this.record("ITEM_EFFECT", out, 0, "复活卡发动，原地复活");
            this.endDay();
            return;
        }
        // 道具·铁布衫：PK 放逐同样生效
        if (p.ironSkin) {
            p.ironSkin = false;
            this.record("ITEM_EFFECT", out, 0, "铁布衫挡下了本次放逐");
            this.endDay();
            return;
        }
        // 道具·替死娃娃：首次被放逐免死（PK 放逐同样生效，与常规放逐路径一致）
        if (p.immuneExile) {
            p.immuneExile = false;
            this.record("ITEM_EFFECT", out, 0, "替死娃娃挡下了本次放逐");
            this.endDay();
            return;
        }
        this.record("VOTE_RESULT", out, 0, "PK 放逐出局");
        this.consumeDoubleVotes();
        this.dawnDeaths.clear();
        p.alive = false;
        p.deathCause = "EXILE";
        p.deathDay = this.day;
        p.canShootOnDeath = p.role == Role.HUNTER || p.role == Role.WOLF_KING;
        this.record("PLAYER_DIED", out, 0, "EXILE");
        if (p.canShootOnDeath) {
            this.pendingShooters.add(out);
        }
        this.lastWordsQueue.add(out);
        if (out == this.sheriffSeat) {
            this.handleSheriffDeath();
        }
        this.afterDeathResume = "NIGHT";
        this.proceedAfterDeaths();
    }

    private void endDay() {
        if (this.checkWin()) {
            return;
        }
        this.beginNight();
    }

    public void submitWhiteWolfBlowUp(int seat, int target) {
        Player p = this.bySeat.get(seat);
        if (p == null || !p.alive || p.role != Role.WHITE_WOLF_KING) {
            throw new IllegalActionException("非白狼王");
        }
        if (!p.whiteWolfCanBlowUp) {
            throw new IllegalActionException("本局已自爆过");
        }
        if (this.phase != GamePhase.DAY_SPEAK && this.phase != GamePhase.SHERIFF_ELECTION && this.phase != GamePhase.DAY_VOTE) {
            throw new IllegalActionException("只能在白天自爆");
        }
        Player t = this.bySeat.get(target);
        if (t == null || !t.alive) {
            throw new IllegalActionException("自爆目标无效");
        }
        if (target == seat) {
            throw new IllegalActionException("自爆目标不能是自己");
        }
        p.whiteWolfCanBlowUp = false;
        p.alive = false;
        p.deathCause = "BLOWUP";
        p.deathDay = this.day;
        this.record("WHITE_WOLF_BLOWUP", seat, target, "白狼王自爆");
        // 白狼王本人若是警长，同样触发警徽流
        if (seat == this.sheriffSeat) {
            this.handleSheriffDeath();
        }
        this.kill(target, "BLOWUP", false);
        if (this.checkWin()) {
            return;
        }
        this.beginNight();
    }

    private boolean checkWin() {
        if (this.winner != null) {
            return true;
        }
        boolean wolvesAlive = !this.aliveWolves().isEmpty();
        boolean godsAlive = this.alive().stream().anyMatch(p -> p.role.isGod());
        boolean villagersAlive = this.alive().stream().anyMatch(p -> p.role.isVillager());
        Faction w = null;
        if (!wolvesAlive) {
            w = Faction.VILLAGER;
        } else if (this.aliveWolves().size() >= this.aliveGood().size()) {
            w = Faction.WOLF;
        } else if (this.huntByBorder
                && (this.borderGodsExist && !godsAlive || this.borderVillagersExist && !villagersAlive)) {
            w = Faction.WOLF; // 屠边：仅当开局存在的神/民被全灭才判狼胜（缺神或缺民的板子不误判）
        }
        if (w != null) {
            this.winner = w;
            this.phase = GamePhase.GAME_OVER;
            this.record("GAME_OVER", 0, 0, "winner=" + (w == Faction.WOLF ? "狼人" : "好人"));
            return true;
        }
        return false;
    }

    private void require(GamePhase expected, String msg) {
        if (this.phase != expected) {
            throw new IllegalActionException(msg + " (当前 " + String.valueOf((Object)this.phase) + ")");
        }
    }

    private List<Integer> maxKeys(Map<Integer, Double> m) {
        double max = m.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        if (max <= 0.0) {
            return List.of();
        }
        ArrayList<Integer> out = new ArrayList<Integer>();
        m.forEach((k, v) -> {
            if (Math.abs(v - max) < 1.0E-9) {
                out.add((Integer)k);
            }
        });
        Collections.sort(out);
        return out;
    }

    private void record(String type, int actor, int target, String detail) {
        this.events.add(new GameEvent(this.events.size(), this.phase, this.day, type, actor, target, detail));
    }

    public int currentActor() {
        switch (this.phase) {
            case NIGHT_GUARD: {
                Player p = this.first(Role.GUARD);
                return p != null && p.alive ? p.seat : 0;
            }
            case NIGHT_WOLF: {
                return this.nextWolfToAct();
            }
            case NIGHT_WITCH: {
                Player p = this.first(Role.WITCH);
                return p != null && p.alive ? p.seat : 0;
            }
            case NIGHT_SEER: {
                Player p = this.first(Role.SEER);
                return p != null && p.alive ? p.seat : 0;
            }
            case NIGHT_CROW: {
                Player p = this.first(Role.CROW);
                return p != null && p.alive ? p.seat : 0;
            }
            case NIGHT_SILENCER: {
                Player p = this.first(Role.SILENCER);
                return p != null && p.alive ? p.seat : 0;
            }
            case SHOOT: {
                return this.currentShooter();
            }
            case LAST_WORDS: {
                return this.currentLastWords();
            }
            case SHERIFF_ELECTION: {
                return this.sheriffSignupActive() ? this.sheriffSignupCursor : this.nextSheriffVoter();
            }
            case DAY_SPEAK: {
                return this.currentSpeaker();
            }
            case DAY_VOTE: {
                return this.nextExileVoter();
            }
            case DAY_VOTE_TIEBREAK: {
                int s = this.nextTiebreakSpeaker();
                return s != 0 ? s : this.nextExileVoter();
            }
        }
        return 0;
    }

    public String currentActionKind() {
        return switch (this.phase) {
            case GamePhase.NIGHT_GUARD -> "GUARD";
            case GamePhase.NIGHT_WOLF -> "WOLF_KILL";
            case GamePhase.NIGHT_WITCH -> "WITCH";
            case GamePhase.NIGHT_SEER -> "SEER_CHECK";
            case GamePhase.NIGHT_CROW -> "CROW";
            case GamePhase.NIGHT_SILENCER -> "SILENCER";
            case GamePhase.SHOOT -> "SHOOT";
            case GamePhase.LAST_WORDS -> "LAST_WORDS";
            case GamePhase.SHERIFF_ELECTION -> {
                if (this.sheriffSignupActive()) {
                    yield "SHERIFF_SIGNUP";
                }
                yield "SHERIFF_VOTE";
            }
            case GamePhase.DAY_SPEAK -> "SPEECH";
            case GamePhase.DAY_VOTE -> "VOTE";
            case GamePhase.DAY_VOTE_TIEBREAK -> {
                if (this.nextTiebreakSpeaker() != 0) {
                    yield "PK_SPEECH";
                }
                yield "PK_VOTE";
            }
            default -> "NONE";
        };
    }

    public boolean isWolfTurn(int seat) {
        return this.phase == GamePhase.NIGHT_WOLF && this.bySeat.get(seat) != null && this.bySeat.get((Object)Integer.valueOf((int)seat)).alive && this.bySeat.get(seat).isWolf() && !this.wolvesActed.contains(seat);
    }

    /** 白天阶段、该座位为存活且未自爆过的白狼王时，可发起自爆（供服务层/AI 作为附加可选动作）。 */
    public boolean canBlowUp(int seat) {
        if (this.phase != GamePhase.DAY_SPEAK && this.phase != GamePhase.SHERIFF_ELECTION
                && this.phase != GamePhase.DAY_VOTE) {
            return false;
        }
        Player p = this.bySeat.get(seat);
        return p != null && p.alive && p.role == Role.WHITE_WOLF_KING && p.whiteWolfCanBlowUp;
    }

    public int nextWolfToAct() {
        if (this.phase != GamePhase.NIGHT_WOLF) {
            return 0;
        }
        return this.aliveWolves().stream().map(p -> p.seat).filter(s -> !this.wolvesActed.contains(s)).findFirst().orElse(0);
    }

    public boolean sheriffSignupActive() {
        return this.phase == GamePhase.SHERIFF_ELECTION && this.sheriffSignupCursor <= this.players.size();
    }

    public int nextSheriffVoter() {
        if (this.phase != GamePhase.SHERIFF_ELECTION || this.sheriffSignupActive() || this.sheriffCandidates.isEmpty()) {
            return 0;
        }
        return this.alive().stream().map(p -> p.seat).filter(s -> !this.sheriffCandidates.contains(s) && !this.sheriffVotes.containsKey(s)).findFirst().orElse(0);
    }

    public int nextExileVoter() {
        if (this.phase == GamePhase.DAY_VOTE) {
            return this.alive().stream().filter(Player::canVote).map(p -> p.seat).filter(s -> !this.exileVotes.containsKey(s)).findFirst().orElse(0);
        }
        if (this.phase == GamePhase.DAY_VOTE_TIEBREAK) {
            return this.alive().stream().filter(p -> p.canVote() && !this.tiebreakCandidates.contains(p.seat)).map(p -> p.seat).filter(s -> !this.exileVotes.containsKey(s)).findFirst().orElse(0);
        }
        return 0;
    }

    public int nextTiebreakSpeaker() {
        if (this.phase != GamePhase.DAY_VOTE_TIEBREAK) {
            return 0;
        }
        for (int c : this.tiebreakCandidates) {
            boolean spoke;
            Player p = this.bySeat.get(c);
            if (!p.canSpeak() || (spoke = this.events.stream().anyMatch(e -> e.type().equals("SPEECH") && e.actorSeat() == c && e.day() == this.day && e.phase() == GamePhase.DAY_VOTE_TIEBREAK))) continue;
            return c;
        }
        return 0;
    }

    public static class IllegalActionException
    extends RuntimeException {
        public IllegalActionException(String msg) {
            super(msg);
        }
    }
}
