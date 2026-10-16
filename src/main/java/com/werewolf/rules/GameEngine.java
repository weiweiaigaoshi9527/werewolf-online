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

public class GameEngine implements java.io.Serializable {

    private static final long serialVersionUID = 1L;
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
    /** 本局所有【上过警】的座位（退水后仍保留）：只有从未上警的人才具备警长投票权。 */
    private final Set<Integer> sheriffSignups = new LinkedHashSet<Integer>();
    private final Map<Integer, Integer> sheriffVotes = new HashMap<Integer, Integer>();
    private int sheriffSeat = 0;
    /** 待移交的警徽：警长死亡后记录其座位，进入 SHERIFF_TRANSFER 阶段由本人决定去向（0=无需移交）。 */
    private int pendingSheriffTransfer = 0;
    /** 警徽移交完成后的去向："DEATHS"=回到死亡结算流程（默认）；"NIGHT"=直接入夜（自爆触发时用）。 */
    private String afterTransferResume = "DEATHS";
    /** 是否等待警长决定白天发言顺序（警左/警右）。 */
    private boolean pendingSheriffOrder = false;
    private boolean sheriffElectionDone = false;
    private int sheriffSignupCursor = 0;
    /** 警长竞选发言顺序（仅上警的候选人，按座位）；为空表示不在发言环节。 */
    private List<Integer> sheriffSpeechOrder = new ArrayList<Integer>();
    private int sheriffSpeechIndex = 0;
    /** 退水环节：上警的候选人依次决定是否退出竞选（退水者不参与投票），按座位排序。 */
    private List<Integer> sheriffWithdrawOrder = new ArrayList<Integer>();
    private int sheriffWithdrawIndex = 0;
    private boolean sheriffWithdrawDone = false;
    private List<Integer> speakingOrder = new ArrayList<Integer>();
    private int speakingIndex = 0;
    private final Map<Integer, Integer> exileVotes = new LinkedHashMap<Integer, Integer>();
    private List<Integer> tiebreakCandidates = new ArrayList<Integer>();
    private boolean inTiebreak = false;
    private final Deque<Integer> pendingShooters = new ArrayDeque<Integer>();
    private int shooterCursor = 0;
    private final Deque<Integer> lastWordsQueue = new ArrayDeque<Integer>();
    private final List<Integer> dawnDeaths = new ArrayList<Integer>();
    private final List<Integer> nightDeaths = new ArrayList<Integer>();
    private String afterDeathResume = "DAY";

    public GameEngine(BoardConfig board, List<Long> userIds, long seed, boolean huntByBorder) {
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

        // 1) 随机挑出狼位，其余进好人
        List<Integer> wolfSeats = new ArrayList<>();
        List<Integer> goodSeats = new ArrayList<>();
        List<Integer> rest = new ArrayList<>();
        for (int s = 1; s <= total; s++) rest.add(s);
        Collections.shuffle(rest, new Random(seed));
        for (int s : rest) {
            if (wolfSeats.size() < wolfCount) wolfSeats.add(s);
            else goodSeats.add(s);
        }
        // 2) 按阵营拆角色池并分配
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
        this.record("GAME_START", 0, 0, "board=" + String.valueOf(board) + " huntByBorder=" + huntByBorder);
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
            case SHERIFF_TRANSFER -> {
                // 兜底：无人可交则撕毁警徽（正常情况下由警长本人决定）
                this.pendingSheriffTransfer = 0;
                this.sheriffSeat = 0;
                this.proceedAfterDeaths();
                return true;
            }
            case SHERIFF_ORDER -> {
                // 兜底：默认从最小存活座位开始发言（正常情况下由警长本人决定）
                this.pendingSheriffOrder = false;
                this.startSpeakingFrom(0);
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
                // 兜底：无人可行动 → 直接结算（候选人全退水/无选民时结果即「本轮无警长」）
                this.sheriffWithdrawDone = true;
                this.resolveSheriff();
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
        int bestCount = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        ArrayList<Integer> top = new ArrayList<Integer>();
        for (Map.Entry<Integer, Integer> e : tally.entrySet()) {
            if (e.getValue() == bestCount) top.add(e.getKey());
        }
        Collections.sort(top);
        // 平票处理：按「天数 + 候选集合」做确定性随机，保证同一局结果可复现（快照恢复/回放一致），
        // 同时避免固定偏向最小座位号。原实现依赖 HashMap 迭代顺序，结果不可复现。
        int best;
        if (top.isEmpty()) best = 0;
        else if (top.size() == 1) best = top.get(0);
        else best = top.get(new Random(this.day * 131L + (long) top.hashCode()).nextInt(top.size()));
        this.nightWolfKill = best;
        if (top.size() > 1) {
            this.record("NIGHT_ACTION", 0, best, "WOLF_KILL_TIE");
        }
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
            if (poisonTarget == w.seat) {
                throw new IllegalActionException("女巫不能毒自己");
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
        if (allowShoot && (p.role == Role.HUNTER || p.role == Role.WOLF_KING)) {
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
        // 警长死亡后先移交警徽（由警长本人决定去向）。若此刻胜负已定则直接丢弃，避免结算后还弹移交界面。
        if (this.pendingSheriffTransfer != 0) {
            if (this.detectWinner() != null) {
                this.pendingSheriffTransfer = 0;
            } else {
                this.phase = GamePhase.SHERIFF_TRANSFER;
                return;
            }
        }
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
            this.kill(target, "SHOT", true);
            // 连环枪：被枪杀的猎人 / 狼王同样可以再开枪（被毒不可开枪的规则由 kill 的 allowShoot 控制）
            Player victim = this.bySeat.get(target);
            if (victim != null && victim.canShootOnDeath) {
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
        this.sheriffSignups.clear();
        this.sheriffVotes.clear();
        this.sheriffSpeechOrder = new ArrayList<Integer>();
        this.sheriffSpeechIndex = 0;
        this.sheriffWithdrawOrder = new ArrayList<Integer>();
        this.sheriffWithdrawIndex = 0;
        this.sheriffWithdrawDone = false;
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
            this.sheriffSignups.add(seat);   // 上过警即失去投票权（即使之后退水）
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
                // 报名结束后进入「竞选发言」（仅候选人发言），随后是「退水」环节，最后才投票
                this.beginSheriffSpeeches();
            }
        }
    }

    /** 警长竞选发言：仅上警的候选人依次发言（顺序按座位），未上警者不发言。 */
    private void beginSheriffSpeeches() {
        List<Integer> order = new ArrayList<>();
        for (Player p : this.alive()) if (this.sheriffCandidates.contains(p.seat)) order.add(p.seat);
        this.sheriffSpeechOrder = order;
        this.sheriffSpeechIndex = 0;
        this.phase = GamePhase.SHERIFF_ELECTION;
    }

    /** 是否处于「警长竞选发言」环节（发言未走完，尚未开始投票）。 */
    public boolean sheriffSpeechActive() {
        return this.phase == GamePhase.SHERIFF_ELECTION && !this.sheriffSpeechOrder.isEmpty()
                && this.sheriffSpeechIndex < this.sheriffSpeechOrder.size();
    }

    /** 当前应发言的竞选者/选民座位（0=不在发言环节）。 */
    public int currentSheriffSpeaker() {
        return this.sheriffSpeechActive() ? this.sheriffSpeechOrder.get(this.sheriffSpeechIndex) : 0;
    }

    /** 当前竞选发言顺序（供前端展示）。 */
    public List<Integer> sheriffSpeechOrder() {
        return Collections.unmodifiableList(this.sheriffSpeechOrder);
    }

    private void advanceSheriffSpeech() {
        ++this.sheriffSpeechIndex;
        while (this.sheriffSpeechIndex < this.sheriffSpeechOrder.size()
                && !this.bySeat.get((Object)Integer.valueOf((int)this.sheriffSpeechOrder.get((int)this.sheriffSpeechIndex))).alive) {
            ++this.sheriffSpeechIndex;
        }
        // 发言全部走完 → 进入「退水」环节
        if (this.sheriffSpeechIndex >= this.sheriffSpeechOrder.size()) {
            this.beginSheriffWithdraw();
        }
    }

    /** 退水环节：所有存活候选人依次选择是否退出竞选；退水者不再参与投票。 */
    private void beginSheriffWithdraw() {
        List<Integer> order = new ArrayList<>();
        for (Player p : this.alive()) if (this.sheriffCandidates.contains(p.seat)) order.add(p.seat);
        this.sheriffWithdrawOrder = order;
        this.sheriffWithdrawIndex = 0;
        this.sheriffWithdrawDone = false;
        this.phase = GamePhase.SHERIFF_ELECTION;
        if (order.isEmpty()) {
            this.sheriffWithdrawDone = true;
            this.afterSheriffWithdraw();
        }
    }

    /** 是否处于「退水」环节（候选人依次表态中）。 */
    public boolean sheriffWithdrawActive() {
        return this.phase == GamePhase.SHERIFF_ELECTION && !this.sheriffWithdrawDone
                && this.sheriffWithdrawIndex < this.sheriffWithdrawOrder.size();
    }

    /** 当前轮到的退水表态者座位（0=不在退水环节）。 */
    public int currentSheriffWithdrawer() {
        return this.sheriffWithdrawActive() ? this.sheriffWithdrawOrder.get(this.sheriffWithdrawIndex) : 0;
    }

    /** 候选人退水与否（true=退出竞选，此后不再参与警长投票）。 */
    public void submitSheriffWithdraw(int seat, boolean withdraw) {
        this.require(GamePhase.SHERIFF_ELECTION, "非警长竞选");
        if (!this.sheriffWithdrawActive()) {
            throw new IllegalActionException("不在退水环节");
        }
        if (seat != this.currentSheriffWithdrawer()) {
            throw new IllegalActionException("退水顺序错误");
        }
        if (withdraw) {
            this.sheriffCandidates.remove(seat);
            this.record("SHERIFF_WITHDRAW", seat, 0, "退水");
        }
        this.advanceSheriffWithdraw();
    }

    private void advanceSheriffWithdraw() {
        ++this.sheriffWithdrawIndex;
        while (this.sheriffWithdrawIndex < this.sheriffWithdrawOrder.size()
                && !this.bySeat.get((Object)Integer.valueOf((int)this.sheriffWithdrawOrder.get((int)this.sheriffWithdrawIndex))).alive) {
            ++this.sheriffWithdrawIndex;
        }
        if (this.sheriffWithdrawIndex >= this.sheriffWithdrawOrder.size()) {
            this.sheriffWithdrawDone = true;
            this.afterSheriffWithdraw();
        }
    }

    /** 退水环节结束：候选人已全部表态。没有候选人可投、或没有合法选民时直接结算，否则进入投票。 */
    private void afterSheriffWithdraw() {
        if (this.nextSheriffVoter() == 0) {
            this.resolveSheriff();
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
        if (this.sheriffSpeechActive()) {
            throw new IllegalActionException("竞选发言尚未结束");
        }
        if (!this.sheriffWithdrawDone) {
            throw new IllegalActionException("退水环节尚未结束");
        }
        Player v = this.bySeat.get(voterSeat);
        if (v == null || !v.alive) {
            throw new IllegalActionException("投票者无效");
        }
        if (this.sheriffSignups.contains(voterSeat)) {
            throw new IllegalActionException("上过警的人无投票权");
        }
        if (target != 0 && !this.sheriffCandidates.contains(target)) {
            throw new IllegalActionException("投票目标非候选人");
        }
        this.sheriffVotes.put(voterSeat, target);
        long voters = this.alive().stream().filter(p -> !this.sheriffSignups.contains(p.seat)).count();
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
        this.inTiebreak = false;
        this.tiebreakCandidates.clear();
        this.exileVotes.clear();
        // 原版规则：有警长时先由警长决定从警左还是警右开始发言，再进入发言环节
        if (this.sheriffSeat != 0) {
            Player sp = this.bySeat.get(this.sheriffSeat);
            if (sp != null && sp.alive) {
                this.pendingSheriffOrder = true;
                this.phase = GamePhase.SHERIFF_ORDER;
                return;
            }
        }
        this.startSpeakingFrom(0);
    }

    /** 是否正在等待警长决定白天发言顺序。 */
    public boolean pendingSheriffOrder() {
        return this.pendingSheriffOrder;
    }

    /**
     * 警长决定白天发言顺序：{@code startSeat} 为「第一个发言的人」，其后按座位号递增依次发言。
     * startSeat=0 表示无警长，从最小存活座位开始（保持原有行为）。
     */
    public void submitSheriffOrder(int startSeat) {
        this.require(GamePhase.SHERIFF_ORDER, "非警长决定发言顺序阶段");
        if (!this.pendingSheriffOrder) {
            throw new IllegalActionException("当前无需决定发言顺序");
        }
        if (startSeat != 0) {
            Player t = this.bySeat.get(startSeat);
            if (t == null || !t.alive) {
                throw new IllegalActionException("起始发言座位无效");
            }
        }
        this.pendingSheriffOrder = false;
        this.startSpeakingFrom(startSeat);
    }

    /** 按给定起点生成发言顺序并进入白天发言阶段。 */
    private void startSpeakingFrom(int startSeat) {
        List<Integer> aliveSeats = this.alive().stream().map(p -> p.seat).sorted().toList();
        int n = aliveSeats.size();
        int start = 0;
        if (startSeat != 0 && n > 0) {
            int idx = aliveSeats.indexOf(startSeat);
            if (idx >= 0) start = idx;
        }
        ArrayList<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < n; ++i) {
            order.add(aliveSeats.get((start + i) % n));
        }
        this.speakingOrder = order;
        this.speakingIndex = 0;
        this.phase = GamePhase.DAY_SPEAK;
    }

    public void submitSpeech(int seat, String text) {
        // 警长竞选发言：复用同一套发言记录，走完再投票
        if (this.phase == GamePhase.SHERIFF_ELECTION) {
            int cur = this.currentSheriffSpeaker();
            if (cur == 0 || seat != cur) {
                throw new IllegalActionException("不是该玩家发言");
            }
            Player sp = this.bySeat.get(seat);
            if (sp == null || !sp.canSpeak()) {
                this.record("SPEECH", seat, 0, "[被禁言，过麦]");
            } else {
                this.record("SPEECH", seat, 0, text == null ? "" : text);
            }
            this.advanceSheriffSpeech();
            return;
        }
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
            // 已翻牌的白痴免疫放逐，诽谤加票对他无效（否则可能把他推成最高票）
            if (p.idiotRevealed) continue;
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
        if (p2.role == Role.IDIOT && p2.idiotRevealed) {
            // 已翻牌的白痴免疫放逐：即使被推成最高票也不再出局（标准规则）
            this.record("VOTE_RESULT", 0, 0, "白痴已翻牌，无人被放逐");
            this.inTiebreak = false;
            this.endDay();
            return;
        }
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

    /**
     * 警长死亡：收回警徽并登记「待移交」，由死亡警长本人决定交给谁（或撕毁）。
     * 真正的交接在 SHERIFF_TRANSFER 阶段由 {@link #submitSheriffTransfer(int)} 完成，
     * 保证与原版一致——警徽去向由警长决定，而不是系统随机分配。
     */
    private void handleSheriffDeath() {
        int dead = this.sheriffSeat;
        if (dead == 0) return;
        Player p = this.bySeat.get(dead);
        if (p != null) p.isSheriff = false;
        this.sheriffSeat = 0;
        this.pendingSheriffTransfer = dead;
    }

    /** 当前是否有待移交的警徽（死亡警长座位，0=无）。 */
    public int pendingSheriffTransfer() {
        return this.pendingSheriffTransfer;
    }

    /**
     * 警徽移交：由死亡警长指定一名存活玩家继承（target>0），或撕毁警徽（target=0）。
     */
    public void submitSheriffTransfer(int target) {
        this.require(GamePhase.SHERIFF_TRANSFER, "非警徽移交阶段");
        int from = this.pendingSheriffTransfer;
        if (from == 0) {
            throw new IllegalActionException("当前无需移交警徽");
        }
        if (target != 0) {
            if (target == from) {
                throw new IllegalActionException("不能把警徽交给自己");
            }
            Player t = this.bySeat.get(target);
            if (t == null || !t.alive) {
                throw new IllegalActionException("警徽移交目标无效");
            }
            this.sheriffSeat = target;
            t.isSheriff = true;
            this.record("SHERIFF_WIN", target, 0, "警徽移交");
        } else {
            this.sheriffSeat = 0;
            this.record("SHERIFF_WIN", 0, 0, "警徽撕毁");
        }
        this.pendingSheriffTransfer = 0;
        if ("NIGHT".equals(this.afterTransferResume)) {
            this.afterTransferResume = "DEATHS";
            this.beginNight();
        } else {
            this.proceedAfterDeaths();
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
        if (p.role == Role.IDIOT && p.idiotRevealed) {
            // 已翻牌的白痴免疫放逐（PK 轮同样适用）
            this.record("VOTE_RESULT", 0, 0, "白痴已翻牌，无人被放逐");
            this.endDay();
            return;
        }
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

    /**
     * 狼人白天自爆：直接入夜（跳过当天剩余的发言/投票），自爆者出局并公开身份。
     * 白狼王自爆时必须带走一名玩家（target>0）；普通狼人自爆不带人（target 必须为 0）。
     */
    public void submitWolfBlowUp(int seat, int target) {
        Player p = this.bySeat.get(seat);
        if (p == null || !p.alive || !p.isWolf()) {
            throw new IllegalActionException("只有存活的狼人才能自爆");
        }
        if (this.phase != GamePhase.DAY_SPEAK && this.phase != GamePhase.SHERIFF_ELECTION && this.phase != GamePhase.DAY_VOTE) {
            throw new IllegalActionException("只能在白天自爆");
        }
        boolean white = p.role == Role.WHITE_WOLF_KING;
        if (white && !p.whiteWolfCanBlowUp) {
            throw new IllegalActionException("本局已自爆过");
        }
        if (!white && target != 0) {
            throw new IllegalActionException("普通狼人自爆不能带走玩家");
        }
        if (white && target == 0) {
            throw new IllegalActionException("白狼王自爆必须带走一名玩家");
        }
        if (target != 0) {
            Player t = this.bySeat.get(target);
            if (t == null || !t.alive) {
                throw new IllegalActionException("自爆目标无效");
            }
            if (target == seat) {
                throw new IllegalActionException("自爆目标不能是自己");
            }
        }
        if (white) {
            p.whiteWolfCanBlowUp = false;
        }
        p.alive = false;
        p.deathCause = "BLOWUP";
        p.deathDay = this.day;
        this.record("WHITE_WOLF_BLOWUP", seat, target, white ? "白狼王自爆" : "狼人自爆");
        // 自爆者本人若是警长，同样触发警徽流
        if (seat == this.sheriffSeat) {
            this.handleSheriffDeath();
        }
        if (target != 0) {
            this.kill(target, "BLOWUP", false);
        }
        if (this.checkWin()) {
            return;
        }
        // 自爆者是警长时，先由他决定警徽去向，再进入黑夜
        if (this.pendingSheriffTransfer != 0) {
            this.afterTransferResume = "NIGHT";
            this.phase = GamePhase.SHERIFF_TRANSFER;
            return;
        }
        this.beginNight();
    }

    /** 纯判定：当前局面是否已分出胜负（不修改任何状态，供「是否需要移交警徽」等流程预判）。 */
    private Faction detectWinner() {
        if (this.winner != null) {
            return this.winner;
        }
        boolean wolvesAlive = !this.aliveWolves().isEmpty();
        boolean godsAlive = this.alive().stream().anyMatch(p -> p.role.isGod());
        boolean villagersAlive = this.alive().stream().anyMatch(p -> p.role.isVillager());
        if (!wolvesAlive) {
            return Faction.VILLAGER;
        }
        if (this.aliveWolves().size() >= this.aliveGood().size()) {
            return Faction.WOLF;
        }
        if (this.huntByBorder
                && (this.borderGodsExist && !godsAlive || this.borderVillagersExist && !villagersAlive)) {
            return Faction.WOLF; // 屠边：仅当开局存在的神/民被全灭才判狼胜（缺神或缺民的板子不误判）
        }
        return null;
    }

    private boolean checkWin() {
        Faction w = this.detectWinner();
        if (w != null && this.winner == null) {
            this.winner = w;
            this.phase = GamePhase.GAME_OVER;
            this.record("GAME_OVER", 0, 0, "winner=" + (w == Faction.WOLF ? "狼人" : "好人"));
        }
        return this.winner != null;
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
            case SHERIFF_TRANSFER: {
                return this.pendingSheriffTransfer;   // 由死亡警长本人决定警徽去向
            }
            case SHERIFF_ORDER: {
                return this.pendingSheriffOrder ? this.sheriffSeat : 0;   // 由警长本人决定发言顺序
            }
            case LAST_WORDS: {
                return this.currentLastWords();
            }
            case SHERIFF_ELECTION: {
                if (this.sheriffSignupActive()) {
                    return this.sheriffSignupCursor;
                }
                int sp = this.currentSheriffSpeaker();   // 竞选发言环节：轮到该玩家发言
                if (sp != 0) {
                    return sp;
                }
                int wd = this.currentSheriffWithdrawer();   // 退水环节：轮到该候选人表态
                if (wd != 0) {
                    return wd;
                }
                return this.nextSheriffVoter();
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
            case GamePhase.SHERIFF_TRANSFER -> "SHERIFF_TRANSFER";
            case GamePhase.SHERIFF_ORDER -> "SHERIFF_ORDER";
            case GamePhase.LAST_WORDS -> "LAST_WORDS";
            case GamePhase.SHERIFF_ELECTION -> {
                if (this.sheriffSignupActive()) {
                    yield "SHERIFF_SIGNUP";
                }
                if (this.currentSheriffSpeaker() != 0) {
                    yield "SPEECH";          // 竞选发言环节
                }
                if (this.currentSheriffWithdrawer() != 0) {
                    yield "SHERIFF_WITHDRAW";   // 退水环节
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

    /** 白天阶段、存活狼人可发起自爆（白狼王每局限一次且自爆带走一人；普通狼人自爆不带人）。 */
    public boolean canBlowUp(int seat) {
        if (this.phase != GamePhase.DAY_SPEAK && this.phase != GamePhase.SHERIFF_ELECTION
                && this.phase != GamePhase.DAY_VOTE) {
            return false;
        }
        Player p = this.bySeat.get(seat);
        if (p == null || !p.alive || !p.isWolf()) {
            return false;
        }
        return p.role != Role.WHITE_WOLF_KING || p.whiteWolfCanBlowUp;
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
        if (this.phase != GamePhase.SHERIFF_ELECTION || this.sheriffSignupActive()
                || this.sheriffSpeechActive() || this.sheriffWithdrawActive()
                || this.sheriffCandidates.isEmpty()) {
            return 0;
        }
        // 只有【从未上警】的存活玩家才有警长投票权（上过警或已退水者都不能投票）
        return this.alive().stream().map(p -> p.seat)
                .filter(s -> !this.sheriffSignups.contains(s) && !this.sheriffVotes.containsKey(s))
                .findFirst().orElse(0);
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
