package com.werewolf.ai;

import java.util.*;

/**
 * 让 AI 发言更像真人：口语填充词、停顿/结巴、情绪词、偶尔错别字/近音字。
 * 只用于发言文本润色，不改变语义主体；概率化，避免每句都改。
 *
 * 反重复设计（旧版每局都在“嗯…”“说实话”之间循环，一桌人共用一个小池子）：
 *   1) 语癖按座位分池（{@link #personaOf} 给出 0~7），不同 AI 的口头禅不重叠；
 *   2) 调用方可传入该座位最近已用过的词（{@code avoid}），本次不再复用；
 *   3) 各装饰概率整体下调，且一次最多叠加 2 处，避免“每句都被加工过”的机械感；
 *   4) 原文已以填充词/语气词开头时不再前缀填充词。
 */
public final class Humanizer {

    private Humanizer() {}

    /** 8 套语癖池：每套内部自成风格，池间几乎不重叠。 */
    private static final String[][] PERSONA_FILLERS = {
            {"嗯…", "唔…", "呃…", "这个…", "怎么说呢，", "我寻思，"},
            {"那个…", "就是…", "反正…", "说白了，", "讲真，", "我跟你讲，"},
            {"emmm…", "嗐…", "哎…", "我的我的，", "先说清楚，", "我直说了，"},
            {"我感觉吧，", "我个人觉得，", "我倾向于，", "说实话，", "坦白讲，"},
            {"我这边啊，", "我看到的，", "我这边信息是，", "先说我，", "我补一句，"},
            {"行吧，", "得嘞，", "OK 那我说了，", "那我直说，", "我摊牌了，"},
            {"有点意思，", "我有点慌，", "我人都麻了，", "我脑壳疼，", "我有点上头，"},
            {"别急，", "等一下，", "我先捋一下，", "让我说完，", "我插一句，"},
    };

    /** 兜底池（persona 越界时使用）。 */
    private static final String[] FILLERS = PERSONA_FILLERS[0];

    private static final String[] EMOS = {
            "真的", "说实话", "我有点慌", "笑死", "离谱", "有点东西", "绷不住了",
            "绝了", "服了", "我人才", "我看不懂", "就很怪", "我急了", "别搞",
            "我信了", "我怀疑人生", "这波啊", "属于是", "我直接好家伙", "有点东西啊",
            "我心态崩了", "我有点想笑", "整不会了", "我沉默了", "我麻了", "我血压上来了",
    };

    /**
     * 原文若已以这些“口头禅/语气词”开头，就不再叠加填充词（避免“嗯…那个…就是…”式堆叠）。
     * 注意：只列真正的填充前缀，不能列“我/说/先”这类常用首字——否则绝大多数发言都会被判定为
     * “已带填充词”，润色就永远不生效（第一版就踩了这个坑）。
     */
    private static final String[] LEADING_MARKS = {
            "嗯", "唔", "呃", "嗐", "哎", "emmm", "OK",
            "那个", "就是", "反正", "说白了", "讲真", "我跟你讲", "怎么说呢", "我寻思",
            "我的我的", "先说清楚", "我直说", "我感觉吧", "我个人觉得", "我倾向于", "说实话", "坦白讲",
            "我这边啊", "先说我", "我补一句", "行吧", "得嘞", "那我直说", "我摊牌", "有点意思",
            "我有点", "别急", "等一下", "我先捋", "让我说完", "我插一句"
    };

    // 近音/形近替换（语音识别常见错法），保持可读
    private static final String[][] HOMOPHONE = {
            {"我", "窝"}, {"你", "泥"}, {"的", "得"}, {"了", "啦"}, {"很", "狠"},
            {"什么", "神马"}, {"狼人", "狼银"}, {"预言家", "语言家"}, {"是不是", "是不四"}, {"过", "锅"},
    };

    public static String humanize(String text, Random r) {
        return humanize(text, r, 0, Set.of());
    }

    /**
     * @param persona 语癖池编号（0~7），由 {@link PhraseDedup#personaOf(int)} 按座位给出
     * @param avoid   该座位最近已用过的填充词，本次跳过
     */
    public static String humanize(String text, Random r, int persona, Set<String> avoid) {
        if (text == null || text.isBlank() || text.equals("过")) return text;
        StringBuilder sb = new StringBuilder(text.trim());
        int budget = 2;                                   // 一次最多两处装饰

        // 约 26% 概率在开头加口语填充词（按座位语癖取，且不重复刚用过的）
        if (budget > 0 && r.nextInt(100) < 26 && !startsWithMark(sb)) {
            String f = pickFiller(persona, avoid, r);
            if (f != null) { sb.insert(0, f); budget--; }
        }
        // 约 16% 概率插入情绪词
        if (budget > 0 && r.nextInt(100) < 16) {
            int i = sb.lastIndexOf("。");
            if (i <= 0) i = sb.length();
            sb.insert(Math.max(0, i), "，" + EMOS[r.nextInt(EMOS.length)]);
            budget--;
        }
        // 约 10% 概率结巴（重复首字），首字是数字/标点时不重复（避免“111号”这类）
        char first = sb.charAt(0);
        if (budget > 0 && r.nextInt(100) < 10 && sb.length() > 2 && Character.isDefined(first)
                && !Character.isDigit(first) && first != '…' && first != '，') {
            sb.insert(1, "" + first + first);
            budget--;
        }
        // 约 22% 概率做一次近音/错别字替换（最多 1 处，避免过度）
        if (budget > 0 && r.nextInt(100) < 22) {
            List<String[]> pairs = new ArrayList<>(Arrays.asList(HOMOPHONE));
            Collections.shuffle(pairs, r);
            for (String[] pair : pairs) {
                int pos = sb.indexOf(pair[0]);
                if (pos >= 0) { sb.replace(pos, pos + pair[0].length(), pair[1]); budget--; break; }
            }
        }
        // 约 14% 概率把某个句号改成省略号（意犹未尽/被打断感）
        if (budget > 0 && r.nextInt(100) < 14) {
            int idx = sb.lastIndexOf("。");
            if (idx > 0 && idx < sb.length() - 1) sb.replace(idx, idx + 1, "…");
        }
        String out = sb.toString();
        if (out.length() > 140) out = out.substring(0, 140);
        return out;
    }

    private static boolean startsWithMark(StringBuilder sb) {
        for (String m : LEADING_MARKS) if (sb.indexOf(m) == 0) return true;
        return false;
    }

    /** 从该座位的语癖池里挑一个没用过的填充词；全被排除时返回 null（本次不加填充词）。 */
    private static String pickFiller(int persona, Set<String> avoid, Random r) {
        String[] pool = (persona >= 0 && persona < PERSONA_FILLERS.length) ? PERSONA_FILLERS[persona] : FILLERS;
        List<String> cand = new ArrayList<>(Arrays.asList(pool));
        cand.removeAll(avoid == null ? Set.of() : avoid);
        if (cand.isEmpty()) return null;
        Collections.shuffle(cand, r);
        return cand.get(0);
    }

    /** 供去重使用：识别文本里出现的已知填充词（该座位上一条发言用过的，下次避开）。 */
    public static Set<String> fillersIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        for (String[] pool : PERSONA_FILLERS) {
            for (String f : pool) if (text.startsWith(f) || text.contains(f)) out.add(f);
        }
        return out;
    }
}
