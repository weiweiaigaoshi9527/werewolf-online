package com.werewolf.rules.sim;

import com.werewolf.rules.*;

import java.util.*;

/** 用策略驱动一整局，返回结果。任何异常/死锁都会被捕获并标记为失败。 */
public class SimulationDriver {

    public record Result(boolean ok, Faction winner, int steps, int days, List<GameEvent> events, String error) {}

    public static Result run(GameEngine g, PlayerPolicy policy, long seed) {
        Random rnd = new Random(seed);
        int steps = 0;
        final int maxSteps = 20000;
        GamePhase lastPhase = g.phase();
        int samePhaseCount = 0;
        try {
            while (!g.isGameOver()) {
                if (++steps > maxSteps) return fail(g, "DEADLOCK: 超过 maxSteps");
                GamePhase p = g.phase();
                // 同一阶段连续无进展过多 → 判定死锁
                if (p == lastPhase) { if (++samePhaseCount > 200) return fail(g, "DEADLOCK: 阶段 " + p + " 无进展"); }
                else { samePhaseCount = 0; lastPhase = p; }

                switch (p) {
                    case NIGHT_GUARD -> g.submitGuard(policy.guardTarget(g, g.first(Role.GUARD), rnd));
                    case NIGHT_WOLF -> {
                        int seat = g.nextWolfToAct();
                        if (seat == 0) return fail(g, "DEADLOCK: NIGHT_WOLF 无待动狼");
                        g.submitWolfKill(seat, policy.wolfKill(g, g.bySeat(seat), rnd));
                    }
                    case NIGHT_WITCH -> {
                        int[] wp = policy.witch(g, g.first(Role.WITCH), rnd);
                        g.submitWitch(wp[0], wp[1]);
                    }
                    case NIGHT_SEER -> g.submitSeer(policy.seerTarget(g, g.first(Role.SEER), rnd));
                    case NIGHT_CROW -> g.submitCrow(policy.crowTarget(g, g.first(Role.CROW), rnd));
                    case NIGHT_SILENCER -> g.submitSilencer(policy.silencerTarget(g, g.first(Role.SILENCER), rnd));
                    case SHOOT -> {
                        int seat = g.currentShooter();
                        g.submitShoot(seat, policy.shootTarget(g, g.bySeat(seat), rnd));
                    }
                    case LAST_WORDS -> g.submitLastWords(g.currentLastWords(), "（遗言）");
                    case SHERIFF_ELECTION -> {
                        if (g.sheriffSignupActive()) {
                            int seat = g.sheriffSignupCursor();
                            g.submitSheriffSignup(seat, policy.sheriffSignup(g, g.bySeat(seat), rnd));
                        } else {
                            int voter = g.nextSheriffVoter();
                            if (voter == 0) return fail(g, "DEADLOCK: 警长投票无选民");
                            g.submitSheriffVote(voter, policy.sheriffVote(g, g.bySeat(voter),
                                    new ArrayList<>(g.sheriffCandidates()), rnd));
                        }
                    }
                    case DAY_SPEAK -> g.submitSpeech(g.currentSpeaker(), "（发言）");
                    case DAY_VOTE -> {
                        int voter = g.nextExileVoter();
                        if (voter == 0) return fail(g, "DEADLOCK: 放逐投票无选民");
                        g.submitVote(voter, policy.exileVote(g, g.bySeat(voter), rnd));
                    }
                    case DAY_VOTE_TIEBREAK -> {
                        int speaker = g.nextTiebreakSpeaker();
                        if (speaker != 0) {
                            g.submitTiebreakSpeech(speaker, "（PK 发言）");
                        } else {
                            int voter = g.nextExileVoter();
                            if (voter == 0) return fail(g, "DEADLOCK: PK 无选民");
                            g.submitTiebreakVote(voter, policy.exileVote(g, g.bySeat(voter), rnd));
                        }
                    }
                    default -> { return fail(g, "UNHANDLED phase " + p); }
                }
            }
            return new Result(true, g.winner(), steps, g.day(), g.events(), null);
        } catch (Exception e) {
            System.err.println("=== 对局异常栈 ===");
            e.printStackTrace();
            return fail(g, "EXCEPTION: " + e);
        }
    }

    private static Result fail(GameEngine g, String err) {
        return new Result(false, null, -1, g.day(), g.events(), err);
    }
}
