// tools/uireflow/DedupEngineTest.java —— 引擎级验证：真实 GameEngine 事件流上的去重与提示词链路
// 运行：javac -encoding UTF-8 -cp target/classes -d target/deduptest tools/uireflow/DedupEngineTest.java
//      java -cp "target/classes;target/deduptest" DedupEngineTest
import com.werewolf.ai.PhraseDedup;
import com.werewolf.ai.PromptLibrary;
import com.werewolf.ai.Humanizer;
import com.werewolf.rules.*;
import com.werewolf.rules.sim.RandomPolicy;

import java.util.*;

/** 用真实 GameEngine（而非 mock 列表）验证：历史提取、雷同判定、高频套话、提示词注入。 */
public class DedupEngineTest {

    static int pass = 0, fail = 0;
    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name + (detail.isEmpty() ? "" : "  [" + detail + "]")); }
        else { fail++; System.out.println("  FAIL " + name + "  → " + detail); }
    }

    /** 有角色的夜晚阶段才提交；缺角色时跳过（引擎本身也会跳过，这里只是防御）。 */
    static boolean has(GameEngine g, Role r) {
        for (Player p : g.players()) if (p.role == r) return true;
        return false;
    }

    /** 把对局推到白天发言阶段：完全按 SimulationDriver 的公开 API 驱动。 */
    static GameEngine toDayPhase() {
        // 标准 3狼3神3民：1狼1民局会在夜里直接触发屠边判负，根本到不了白天
        GameEngine g = new GameEngine(List.of(
                Role.WEREWOLF, Role.WEREWOLF, Role.WEREWOLF,
                Role.SEER, Role.WITCH, Role.HUNTER,
                Role.VILLAGER, Role.VILLAGER, Role.VILLAGER), false);
        RandomPolicy rp = new RandomPolicy();
        Random rnd = new Random(42);
        for (int guard = 0; guard < 300 && g.phase() != GamePhase.DAY_SPEAK; guard++) {
            GamePhase p = g.phase();
            switch (p) {
                case NIGHT_GUARD -> { if (has(g, Role.GUARD)) g.submitGuard(rp.guardTarget(g, g.first(Role.GUARD), rnd)); }
                case NIGHT_WOLF -> {
                    int seat = g.nextWolfToAct();
                    if (seat == 0) return g;
                    g.submitWolfKill(seat, rp.wolfKill(g, g.bySeat(seat), rnd));
                }
                case NIGHT_WITCH -> { if (has(g, Role.WITCH)) { int[] w = rp.witch(g, g.first(Role.WITCH), rnd); g.submitWitch(w[0], w[1]); } }
                case NIGHT_SEER -> { if (has(g, Role.SEER)) g.submitSeer(rp.seerTarget(g, g.first(Role.SEER), rnd)); }
                case NIGHT_CROW -> { if (has(g, Role.CROW)) g.submitCrow(rp.crowTarget(g, g.first(Role.CROW), rnd)); }
                case NIGHT_SILENCER -> { if (has(g, Role.SILENCER)) g.submitSilencer(rp.silencerTarget(g, g.first(Role.SILENCER), rnd)); }
                case SHOOT -> g.submitShoot(g.currentShooter(), rp.shootTarget(g, g.bySeat(g.currentShooter()), rnd));
                case LAST_WORDS -> g.submitLastWords(g.currentLastWords(), "（遗言）");
                case SHERIFF_ELECTION -> {
                    if (g.sheriffSignupActive()) g.submitSheriffSignup(g.sheriffSignupCursor(), rp.sheriffSignup(g, g.bySeat(g.sheriffSignupCursor()), rnd));
                    else {
                        int voter = g.nextSheriffVoter();
                        if (voter == 0) return g;
                        g.submitSheriffVote(voter, rp.sheriffVote(g, g.bySeat(voter), new ArrayList<>(g.sheriffCandidates()), rnd));
                    }
                }
                default -> {
                    if (!g.resolveStuckPhase()) return g;   // 兜底：卡住时让引擎自行推进一次
                }
            }
        }
        return g;
    }

    public static void main(String[] args) {
        GameEngine g = toDayPhase();
        check(g.phase() == GamePhase.DAY_SPEAK, "对局已推进到白天发言阶段", "phase=" + g.phase());
        if (g.phase() != GamePhase.DAY_SPEAK) { System.out.println("无法进入发言阶段，跳过"); System.exit(1); }

        // 按引擎给的顺序让存活玩家依次发言：故意让 1 号重复自己、让多人重复同一句套话
        String[] scripted = {
                "我觉得3号有问题，他昨晚一直没表态，票型也说不清。",
                "那个…我这边没什么信息，先听后置位再说。",
                "我也先听后置位，没啥能补充的。",
                "那我也先听后置位吧，过。",
                "我倾向查杀2号，他的逻辑链最短。",
        };
        int idx = 0, spoken = 0;
        List<Integer> order = new ArrayList<>(g.speakingOrder());
        for (int seat : order) {
            if (idx >= scripted.length) break;
            Player p = g.bySeat(seat);
            if (p == null || !p.alive) continue;
            if (g.currentSpeaker() != seat) continue;
            g.submitSpeech(seat, scripted[idx++]);
            spoken++;
        }
        check(spoken >= 4, "已向引擎注入多条真实发言", "条数=" + spoken + " 事件数=" + g.events().size());

        System.out.println("== 1. 历史提取 ==");
        List<String> own = PhraseDedup.ownSpeeches(g, order.get(0), 5);
        check(!own.isEmpty() && own.get(0).equals(scripted[0]), "按座位取到本人发言（倒序）", "首条=" + (own.isEmpty() ? "空" : own.get(0)));
        check(PhraseDedup.ownSpeeches(g, 99, 5).isEmpty(), "不存在的座位返回空而非异常", "");

        System.out.println("== 2. 雷同判定（真实事件流） ==");
        int firstSeat = order.get(0);
        check(PhraseDedup.tooSimilar(g, firstSeat, scripted[0]), "原样重复自己 → 判为雷同", "");
        check(PhraseDedup.tooSimilar(g, firstSeat, scripted[0].replace("。", "！")), "只换标点 → 判为雷同", "");
        check(!PhraseDedup.tooSimilar(g, firstSeat, "换个角度：昨晚刀口在神职身上，4号你警徽流怎么规划？"),
                "全新角度与新信息点 → 不误伤", "");
        int thirdSeat = order.get(2);
        check(PhraseDedup.tooSimilar(g, thirdSeat, scripted[1]), "复读别人的整句 → 判为雷同", "");

        System.out.println("== 3. 本局高频套话 ==");
        List<String> over = PhraseDedup.overusedPhrases(g, 4);
        check(!over.isEmpty(), "识别出被 ≥3 条发言复用的片段", "over=" + over);
        check(over.stream().anyMatch(p -> p.contains("听后置") || p.contains("先听后")), "「先听后置位」被点名", "over=" + over);

        System.out.println("== 4. 提示词注入 ==");
        String block = PhraseDedup.promptBlock(g, firstSeat, null);
        check(block.contains("反雷同"), "含反雷同硬约束段", "");
        check(block.contains("× ") && block.contains(scripted[0]), "把本人原话作为禁止复用的负例列出", "");
        check(block.contains("本轮开场方式指定为"), "给出本轮指定开场策略", "");
        check(block.contains("新的】信息点"), "要求必须给新信息点", "");
        String user = PromptLibrary.speechUser(g, firstSeat, "SPEECH", PromptLibrary.styleFor(firstSeat, 1L), "白天发言", false, null) + block;
        check(user.contains("反雷同") && user.length() > 400, "拼进发言提示词后长度与内容正常", "len=" + user.length());
        // 首轮（无任何发言）不应注入空块
        GameEngine fresh = toDayPhase();
        check(PhraseDedup.promptBlock(fresh, order.get(0), null).isEmpty(), "开局无人发言时不注入冗余反雷同段", "");

        System.out.println("== 5. 开场策略轮换 ==");
        Set<String> directives = new HashSet<>();
        for (int d = 0; d < 6; d++) directives.add(PhraseDedup.openingDirective(g, order.get(d % order.size())));
        check(directives.size() >= 2, "不同座位拿到不同开场策略", "种数=" + directives.size());

        System.out.println("== 6. 润色与去重协同 ==");
        Set<String> avoid = Humanizer.fillersIn(scripted[1]);
        check(!avoid.isEmpty(), "能从真实发言里识别已用填充词", "avoid=" + avoid);
        int stacked = 0;
        for (int i = 0; i < 300; i++) {
            String out = Humanizer.humanize(scripted[4], new Random(i), PhraseDedup.personaOf(order.get(i % order.size())), avoid);
            int lead = 0;
            for (String f : avoid) if (out.startsWith(f)) lead++;
            if (out.startsWith("嗯…那个") || out.startsWith("那个…就是") || lead >= 2) stacked++;
        }
        check(stacked == 0, "300 次润色无“填充词堆叠”", "堆叠次数=" + stacked);

        System.out.println("\n结果：通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
