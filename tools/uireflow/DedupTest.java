// tools/uireflow/DedupTest.java —— PhraseDedup / Humanizer 的独立验证（不依赖 Spring 与对局引擎）
// 运行：javac -cp target/classes -d target/deduptest tools/uireflow/DedupTest.java && java -cp target/classes;target/deduptest DedupTest
import com.werewolf.ai.Humanizer;
import com.werewolf.ai.PhraseDedup;

import java.util.*;

public class DedupTest {

    static int pass = 0, fail = 0;
    static void check(boolean ok, String name, String detail) {
        if (ok) { pass++; System.out.println("  PASS " + name + (detail.isEmpty() ? "" : "  [" + detail + "]")); }
        else { fail++; System.out.println("  FAIL " + name + "  → " + detail); }
    }

    public static void main(String[] args) {
        System.out.println("== 1. 相似度判定 ==");
        List<String> own = List.of("嗯…我觉得3号有问题，他刚才的发言前后矛盾。");
        List<String> none = List.of();
        check(PhraseDedup.tooSimilar(own, none, "嗯…我觉得3号有问题,他刚才的发言前后矛盾。"),
                "同一句只换标点 → 判为重复", "");
        check(!PhraseDedup.tooSimilar(own, none, "说实话我怀疑4号，他一直在替3号说话，票型也能看出来。"),
                "换了角度与新论据 → 判为不重复", "");
        check(PhraseDedup.tooSimilar(own, none, "嗯…我反而觉得5号更像狼，他从头到尾没表过态。"),
                "沿用同一开场（嗯…我）→ 判为重复", "开场指纹命中");

        System.out.println("== 2. 复读别人的检测 ==");
        List<String> others = List.of("建议警徽给到验人清晰的预言家，其他人别乱跳。");
        check(PhraseDedup.tooSimilar(none, others, "我的建议是警徽给到验人清晰的预言家，别人别乱跳。"),
                "换个前缀复读别人整句 → 判为重复", "");
        check(!PhraseDedup.tooSimilar(none, others, "我今晚重点看2号的投票，他如果保狼我就跟他对着投。"),
                "无关的新判断 → 不误伤", "");

        System.out.println("== 3. 本局高频套话统计 ==");
        List<String> speeches = new ArrayList<>(List.of(
                "我先听后置位再说，我这边没什么信息。",
                "我也先听后置位，没啥能补充的。",
                "那我也先听后置位吧，过。",
                "后置位都讲完了，我还是先听后置位。",
                "我觉得2号逻辑有问题，他验人和票型对不上。"));
        List<String> over = PhraseDedup.overused(speeches, 4);
        check(!over.isEmpty(), "识别出被反复说的片段", "top=" + over);
        check(over.stream().anyMatch(p -> p.contains("先听后置") || p.contains("听后置位")),
                "「先听后置位」这类水麦句被点名", "over=" + over);
        List<String> diverse = List.of("票型上1号在保5号", "我怀疑2号的跳法", "昨晚的刀口指向神职", "警徽流应该给验人清晰的");
        check(PhraseDedup.overused(diverse, 4).isEmpty(), "各不相同的发言不误报套话", "");

        System.out.println("== 4. 归一化与相似度基元 ==");
        check(PhraseDedup.norm("3号、5号：过！").equals("##过"), "座位号折成 # 且不被标点规则误删", "norm=" + PhraseDedup.norm("3号、5号：过！"));
        double same = PhraseDedup.similarity("我觉得他是狼", "我觉得他是狼");
        double diff = PhraseDedup.similarity("我觉得他是狼", "今晚刀口应该在预言家身上");
        check(same > 0.99 && diff < 0.2, "相同句≈1、无关句≈0", String.format("same=%.2f diff=%.2f", same, diff));

        System.out.println("== 5. Humanizer 反重复效果 ==");
        // 旧算法基线：全局 7 个填充词、45% 概率
        String[] oldPool = {"嗯…", "那个…", "就是…", "emmm…", "怎么说呢，", "我感觉吧，", "其实吧，"};
        Map<String, Integer> oldHit = new HashMap<>(), newHit = new HashMap<>();
        int oldFiller = 0, newFiller = 0, stacked = 0;
        Random r = new Random(20261003L);
        String base = "今晚的刀口应该落在跳身份的人身上，2号票型有问题。";
        for (int i = 0; i < 600; i++) {
            if (r.nextInt(100) < 45) { String f = oldPool[r.nextInt(oldPool.length)]; oldFiller++; oldHit.merge(f, 1, Integer::sum); }
            String out = Humanizer.humanize(base, new Random(i * 7L + 13), PhraseDedup.personaOf(1 + (i % 12)), Set.of());
            for (String f : oldPool) if (out.startsWith(f)) { newFiller++; newHit.merge(f, 1, Integer::sum); }
            // 装饰堆叠检测：开头连续两个填充词视为过度加工
            int hits = 0;
            for (String f : oldPool) if (out.startsWith(f)) hits++;
            if (out.indexOf("…") == 1 && out.startsWith("嗯") && Character.isDigit(out.charAt(0)) == false && hits >= 2) stacked++;
        }
        // 全量语癖池（8 套）用于统计新算法实际用到的填充词种类
        String[] allPool = {"嗯…","唔…","呃…","这个…","怎么说呢，","我寻思，","那个…","就是…","反正…","说白了，","讲真，","我跟你讲，","emmm…","嗐…","哎…","我的我的，","先说清楚，","我直说了，","我感觉吧，","我个人觉得，","我倾向于，","说实话，","坦白讲，","我这边啊，","我看到的，","我这边信息是，","先说我，","我补一句，","行吧，","得嘞，","那我直说，","我摊牌了，","有点意思，","我有点慌","我人都麻了，","我脑壳疼，","我有点上头，","别急，","等一下，","我先捋一下，","让我说完，","我插一句，"};
        Map<String, Integer> allHit = new HashMap<>();
        int allFiller = 0;
        for (int i = 0; i < 600; i++) {
            String out = Humanizer.humanize(base, new Random(i * 7L + 13), PhraseDedup.personaOf(1 + (i % 12)), Set.of());
            for (String f : allPool) if (out.startsWith(f)) { allFiller++; allHit.merge(f, 1, Integer::sum); }
        }
        double oldShare = (double) oldHit.values().stream().mapToInt(Integer::intValue).max().orElse(0) / Math.max(1, oldFiller);
        double newShare = (double) newHit.values().stream().mapToInt(Integer::intValue).max().orElse(0) / Math.max(1, newFiller);
        check(allFiller < oldFiller, "填充词出现次数下降（45%→26% 量级）", "旧=" + oldFiller + " 新=" + allFiller);
        check(allFiller > 0, "新算法仍会适度加填充词（没有一刀切成 0）", "次数=" + allFiller);
        check(allHit.size() >= 12 && allFiller > 0, "新算法跨座位用到更多种类的填充词", "种数=" + allHit.size() + " 次数=" + allFiller);
        double newConc = (double) allHit.values().stream().mapToInt(Integer::intValue).max().orElse(0) / Math.max(1, allFiller);
        check(newConc < oldShare, "新算法最热填充词集中度低于旧算法（不再满桌同一个词）", String.format("旧=%.0f%% 新=%.0f%%", oldShare * 100, newConc * 100));

        System.out.println("== 5b. 口头禅识别 ==");
        check("嗯…".equals(PhraseDedup.leadingFiller("嗯…我觉得吧")), "识别行首填充词 嗯…", "");
        check(PhraseDedup.leadingFiller("2号票型有问题") == null, "普通陈述句不误判为带口头禅", "");
        check(PhraseDedup.tooSimilar(List.of("嗯…我觉得3号像狼。"), List.of(), "嗯…我反而认为5号更可疑。"),
                "同一口头禅起头即视为重复（哪怕内容不同）", "");

        System.out.println("== 6. Humanizer 尊重 avoid 与语癖分池 ==");
        String out1 = "";
        for (int i = 0; i < 200; i++) {
            out1 = Humanizer.humanize("我怀疑4号。", new Random(i), 0, Set.of("嗯…", "唔…", "呃…", "这个…", "怎么说呢，", "我寻思，"));
            if (out1.startsWith("嗯…") || out1.startsWith("唔…")) break;
        }
        check(!(out1.startsWith("嗯…") || out1.startsWith("唔…")), "传入 avoid 后不再复用该座位刚用过的填充词", "样本=" + out1);
        Set<Integer> personas = new TreeSet<>();
        for (int seat = 1; seat <= 16; seat++) personas.add(PhraseDedup.personaOf(seat));
        check(personas.size() >= 4, "同桌 16 个座位被分到多套语癖", "覆盖=" + personas);
        check(Humanizer.humanize("过", new Random(1), 0, Set.of()).equals("过"), "过麦不被加装饰", "");
        check(Humanizer.fillersIn("那个…我觉得有戏").contains("那个…"), "能从原文识别已用填充词", "");

        System.out.println("\n结果：通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }
}
