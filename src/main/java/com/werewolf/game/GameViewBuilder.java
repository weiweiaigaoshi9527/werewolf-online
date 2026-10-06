package com.werewolf.game;

import com.werewolf.config.VoiceProperties;
import com.werewolf.rules.GameEngine;
import com.werewolf.rules.GameEvent;
import com.werewolf.rules.GamePhase;
import com.werewolf.rules.Player;
import com.werewolf.rules.Role;

import java.util.*;

/**
 * 构建"某玩家视角"的对局视图，强制信息隔离：
 * - 公开：座位存活/死亡、发言、投票结果、死讯、警长、游戏结束亮牌；
 * - 私有：自己的身份；狼人见队友；预言家见自己的验人结果；女巫见自己用药状态；守卫见自己守护目标。
 * 夜晚他人动作（WOLF_VOTE/SEER_CHECK/WITCH_*）绝不进入他人 feed。
 * 匿名模式（anonymous）：全员头像/昵称/称号固定为统一伪装人格，并隐藏 bot 标志，令 AI 无从分辨。
 */
public final class GameViewBuilder {

    private static final Set<String> PUBLIC_FEED = Set.of(
            GameEvent.DAWN_ANNOUNCE, GameEvent.SHERIFF_SIGNUP, GameEvent.SHERIFF_WIN,
            GameEvent.LAST_WORDS, GameEvent.SPEECH, GameEvent.VOTE_RESULT, GameEvent.VOTE_DETAIL, GameEvent.VOTE_TIE,
            GameEvent.PLAYER_DIED, GameEvent.SHOOT, GameEvent.IDIOT_REVEAL,
            GameEvent.WHITE_WOLF_BLOWUP, GameEvent.GAME_OVER);

    /** 夜晚私密行动阶段：不向他人暴露是谁在行动。 */
    private static final Set<String> PRIVATE_ACTOR_PHASES = Set.of(
            "NIGHT_GUARD", "NIGHT_WOLF", "NIGHT_WITCH", "NIGHT_SEER", "NIGHT_CROW", "NIGHT_SILENCER");

    public static Map<String, Object> build(GameEngine g, Map<Integer, SeatInfo> seats, int viewerSeat) {
        return build(g, seats, viewerSeat, false, false, null, null);
    }

    public static Map<String, Object> build(GameEngine g, Map<Integer, SeatInfo> seats, int viewerSeat,
                                            boolean anonymous, boolean voiceMode, VoiceProperties props, int[] showOf) {
        boolean over = g.isGameOver();
        // 信息隔离：只有“对局结束”才亮全部身份。观战席(viewerSeat==0)默认按普通玩家视角下发公开信息，
        // 避免任意登录用户进入任意房间观战即可看到全部身份与夜晚私密行动（作弊/信息泄露）。
        // 如需赛后复盘式的上帝视角，请查看回放（/api/game/replay，已校验仅参与者可访问）。
        boolean omniscient = over;
        Set<Integer> revealed = revealedSeats(g);
        List<Map<String, Object>> seatViews = new ArrayList<>();
        for (SeatInfo si : seats.values()) {
            Player p = g.bySeat(si.seat);
            int disp = sh(showOf, si.seat);
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("seat", disp);
            sv.put("vip", si.vip);
            if (anonymous && props != null) {
                sv.put("nickname", disp + "号");
                sv.put("avatarId", props.anonAvatar(si.seat));
                sv.put("avatarUrl", null);
                sv.put("nickColor", props.getAnonNickColor());
                sv.put("frameColor", props.getAnonFrameColor());
                sv.put("title", null);
                sv.put("bot", false); // 匿名：绝不下发真实 bot 标志
            } else {
                sv.put("nickname", si.nickname);
                sv.put("avatarId", si.avatarId);
                sv.put("avatarUrl", si.avatarUrl);
                sv.put("nickColor", si.nickColor);
                sv.put("frameColor", si.frameColor);
                sv.put("title", si.title);
                sv.put("bot", si.bot);
            }
            sv.put("alive", p.alive);
            sv.put("isSheriff", p.isSheriff);
            sv.put("silenced", p.silencedToday);
            sv.put("idiotRevealed", p.idiotRevealed);
            sv.put("userId", anonymous ? null : si.userId); // 非匿名可点击查看档案（机器人为负数id→随机档案）
            // 角色可见性：自己 / 已公开翻牌（白痴、白狼王自爆）/ 游戏结束。
            // 注意：死亡本身不公开身份（狼人杀标准规则），否则夜晚被刀者身份会被即时泄露，破坏信息结构。
            boolean showRole = omniscient || si.seat == viewerSeat || revealed.contains(si.seat);
            if (showRole) sv.put("role", p.role.cnName());
            seatViews.add(sv);
        }
        // 卡片按“显示号”升序排列（匿名打乱号码后，界面上仍按 1号、2号… 顺序展示）
        seatViews.sort(Comparator.comparingInt(sv -> ((Number) sv.get("seat")).intValue()));

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("roomNo", g.day() >= 0 ? null : null); // placeholder, overwritten by caller
        GamePhase ph = g.phase();
        int actor = g.currentActor();
        boolean isActor = actor == viewerSeat;
        // 夜晚各角色行动是私密信息：除行动者本人外，不下发是谁在行动（避免通过绿光/“等待X号行动”暴露狼人/预言家等身份）
        boolean privateActor = PRIVATE_ACTOR_PHASES.contains(ph.name()) && !isActor;
        view.put("phase", ph.name());
        view.put("day", g.day());
        view.put("actionKind", privateActor ? "NIGHT" : g.currentActionKind());
        view.put("currentActor", privateActor ? 0 : sh(showOf, actor));
        view.put("mySeat", sh(showOf, viewerSeat));
        view.put("myTurn", isActor && !over);
        view.put("sheriffSeat", sh(showOf, g.sheriffSeat()));
        view.put("anonymous", anonymous);
        view.put("voiceMode", voiceMode);
        view.put("voiceAvailable", props != null && props.isEnabled()); // 语音服务可用：AI 文本会转语音、可收旁白/字幕
        if (ph == GamePhase.DAY_VOTE_TIEBREAK) {   // 平票 PK：只允许在平票候选人之间投票
            List<Integer> pk = new ArrayList<>();
            for (int s : g.tiebreakCandidates()) pk.add(sh(showOf, s));
            view.put("pkCandidates", pk);
        }
        view.put("seats", seatViews);
        // 对局记录：观战/结束=全知；普通玩家=公开；狼人额外可见狼队内部刀口投票（仅 WOLF_VOTE/刀口结算，不含验人/用药等他人私密）
        Player viewer = viewerSeat != 0 ? g.bySeat(viewerSeat) : null;
        boolean viewerWolf = !over && viewer != null && viewer.isWolf();
        view.put("feed", omniscient ? allFeed(g, showOf) : feed(g, false, viewerWolf, showOf));
        view.put("myInfo", myInfo(g, viewerSeat, over, showOf));
        if (over) {
            view.put("winner", g.winner() == com.werewolf.rules.Faction.WOLF ? "狼人阵营" : "好人阵营");
        }
        return view;
    }

    private static Set<Integer> revealedSeats(GameEngine g) {
        Set<Integer> s = new HashSet<>();
        // 狼人杀惯例：出局即翻牌，死者身份对全场公开（与 AI 视角的公开信息一致）
        for (Player p : g.players()) if (!p.alive) s.add(p.seat);
        for (GameEvent e : g.events()) {
            if (e.type().equals(GameEvent.IDIOT_REVEAL) || e.type().equals(GameEvent.WHITE_WOLF_BLOWUP)) {
                s.add(e.actorSeat());
            }
        }
        return s;
    }

    private static List<Map<String, Object>> publicFeed(GameEngine g, int[] showOf) { return feed(g, false, false, showOf); }

    private static List<Map<String, Object>> allFeed(GameEngine g, int[] showOf) { return feed(g, true, false, showOf); }

    private static List<Map<String, Object>> feed(GameEngine g, boolean all, boolean wolfViewer, int[] showOf) {
        List<Map<String, Object>> feed = new ArrayList<>();
        for (GameEvent e : g.events()) {
            boolean wolfVote = wolfViewer && isWolfVote(e);
            if (!all && !PUBLIC_FEED.contains(e.type()) && !wolfVote) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", e.day());
            m.put("type", e.type());
            m.put("actor", sh(showOf, e.actorSeat()));
            m.put("target", sh(showOf, e.targetSeat()));
            String detail = e.detail();
            if (GameEvent.DAWN_ANNOUNCE.equals(e.type())) {
                // #4：天亮播报改为“存活玩家：X X …”（用显示号）
                detail = "存活玩家：" + g.alive().stream().map(p -> sh(showOf, p.seat) + "号").reduce((a, b) -> a + " " + b).orElse("无");
            } else if (GameEvent.VOTE_DETAIL.equals(e.type())) {
                detail = remapNumbers(detail, showOf); // 投票去向里的号码也置换
            }
            m.put("detail", detail);
            feed.add(m);
        }
        if (feed.size() > 80) feed = feed.subList(feed.size() - 80, feed.size());
        return feed;
    }

    /** 是否狼队内部刀口相关事件（仅这两类对狼人可见，其它夜晚动作不外泄）。 */
    private static boolean isWolfVote(GameEvent e) {
        if (!GameEvent.NIGHT_ACTION.equals(e.type()) || e.detail() == null) return false;
        return e.detail().startsWith("WOLF_VOTE") || e.detail().startsWith("WOLF_KILL_RESOLVED");
    }

    /** 真实座位 -> 显示号（showOf 为 null 时原样返回）。 */
    private static int sh(int[] showOf, int seat) {
        return (showOf == null || seat <= 0 || seat >= showOf.length) ? seat : showOf[seat];
    }

    /** 把字符串里出现的座位号按 showOf 置换（仅用于 VOTE_DETAIL 这类只含座位号的文本）。 */
    private static String remapNumbers(String s, int[] showOf) {
        if (s == null || showOf == null) return s;
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("\\d+").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mt.find()) {
            int n = Integer.parseInt(mt.group());
            mt.appendReplacement(sb, String.valueOf(sh(showOf, n)));
        }
        mt.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, Object> myInfo(GameEngine g, int viewerSeat, boolean over, int[] showOf) {
        Map<String, Object> info = new LinkedHashMap<>();
        Player me = g.bySeat(viewerSeat);
        if (me == null) return info;
        info.put("role", me.role.cnName());
        info.put("faction", me.role.faction().name());
        info.put("alive", me.alive);
        info.put("accused", me.accusedByCrow);

        // 双人组队：告诉玩家自己的队友显示座位（服务端只保证同阵营，不泄露队友具体角色）
        for (int[] d : g.duos()) {
            if (d[0] == viewerSeat || d[1] == viewerSeat) {
                info.put("partner", sh(showOf, d[0] == viewerSeat ? d[1] : d[0]));
                break;
            }
        }

        // 狼人队友
        if (me.isWolf()) {
            info.put("teammates", g.players().stream().filter(p -> p.isWolf() && p.seat != viewerSeat)
                    .map(p -> p.seat).toList());
        }
        // 预言家验人结果
        if (me.role == Role.SEER || over) {
            List<Map<String, Object>> checks = new ArrayList<>();
            for (GameEvent e : g.events()) {
                if (e.type().equals(GameEvent.NIGHT_ACTION) && e.actorSeat() == viewerSeat
                        && e.detail() != null && e.detail().startsWith("SEER_CHECK=")) {
                    checks.add(Map.of("seat", e.targetSeat(), "result",
                            e.detail().endsWith("WOLF") ? "狼人" : "好人", "day", e.day()));
                }
            }
            if (me.role == Role.SEER) info.put("seerChecks", checks);
        }
        // 女巫药
        if (me.role == Role.WITCH || over) {
            info.put("witchSaveAvailable", me.witchSaveAvailable);
            info.put("witchPoisonAvailable", me.witchPoisonAvailable);
            if (g.phase() == com.werewolf.rules.GamePhase.NIGHT_WITCH) {
                info.put("killedTonight", g.nightWolfKill()); // 女巫本应知道当晚被刀者
            }
        }
        // 守卫
        if (me.role == Role.GUARD || over) {
            info.put("guardLastTarget", me.guardLastTarget);
        }
        // 乌鸦：本人可见今晚污蔑了谁（私密反馈，修复"污蔑像没生效"的观感问题）
        if (me.role == Role.CROW) {
            info.put("accusedSeat", g.crowTarget());
        }
        // 功能道具·查杀卡：把持有者用查杀卡揭示的目标阵营回给他本人（修复此前查杀结果不显示的 bug）
        GameEvent rev = null;
        for (GameEvent e : g.events()) {
            if (e.type().equals(GameEvent.ITEM_REVEAL) && e.actorSeat() == viewerSeat) rev = e;
        }
        if (rev != null) {
            info.put("itemReveal", Map.of("seat", rev.targetSeat(), "result", rev.detail() == null ? "" : rev.detail()));
        }
        if (me.doubleVote) info.put("itemDoubleVote", true);
        if (me.immuneExile) info.put("itemImmune", true);
        return info;
    }
}
