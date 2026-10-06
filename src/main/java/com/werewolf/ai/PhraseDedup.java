package com.werewolf.ai;

import com.werewolf.rules.GameEngine;
import com.werewolf.rules.GameEvent;

import java.util.*;

/**
 * 发言去重：从对局事件流（无需额外状态，天然按局隔离）统计“谁说过什么”，
 * 给提示词注入反雷同素材，并对新发言做相似度判定。
 *
 * 解决的三类重复：
 *   1) 同一个 AI 每轮都用同一套开场/口头禅（“嗯…我觉得”“说实话”）；
 *   2) 不同 AI 之间互相复读同一句结论式套话（“先听后置位”“我保留怀疑”）；
 *   3) 本局已被说烂的短语被反复复用。
 */
public final class PhraseDedup {

    private PhraseDedup() {}

    /** 相似度阈值：自己跟自己（同座位相邻发言）。 */
    private static final double SELF_SIM = 0.50;
    /** 包含度阈值：与全场任何一条发言重叠到这种程度，基本就是复读别人。 */
    private static final double OTHER_SIM = 0.50;
    /** 视为“本局高频套话”的最小出现条数。 */
    private static final int OVERUSED_MIN = 3;

    /* ==================== 文本归一与相似度 ==================== */

    /** 归一化：先去标点/空白/表情，再把“N号”等座位指代整体折成 #，只留句式骨架。 */
    public static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。、！？；：“”‘’（）《》…—｜·~～]+", "")
                .replaceAll("\\d+号?", "#")
                .replaceAll("[^\\p{IsHan}a-z#]", "");
        return t;
    }

    /** 字符 n-gram 集合（中文按字切，n=3）。 */
    static Set<String> grams(String normalized, int n) {
        Set<String> out = new HashSet<>();
        if (normalized.length() < n) {
            if (!normalized.isEmpty()) out.add(normalized);
            return out;
        }
        for (int i = 0; i + n <= normalized.length(); i++) out.add(normalized.substring(i, i + n));
        return out;
    }

    /** Jaccard 相似度（0~1）。 */
    public static double similarity(String a, String b) {
        Set<String> x = grams(norm(a), 3), y = grams(norm(b), 3);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        Set<String> union = new HashSet<>(x);
        union.addAll(y);
        return (double) inter.size() / union.size();
    }

    /**
     * 包含度：交集 / 较短一方。用于抓“把别人那句原样搬过来、只加个前缀”的复读——
     * 这种情况 Jaccard 会被稀释到 0.4 左右（漏判），包含度仍能到 0.6+。
     */
    public static double containment(String a, String b) {
        Set<String> x = grams(norm(a), 3), y = grams(norm(b), 3);
        if (x.isEmpty() || y.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return (double) inter.size() / Math.min(x.size(), y.size());
    }

    /** 开场指纹：前若干个字，用于抓“每句都以同一个词开头”。 */
    public static String opening(String text, int len) {
        String n = norm(text);
        return n.length() <= len ? n : n.substring(0, len);
    }

    /* ==================== 从事件流取历史发言 ==================== */

    /** 本座位最近 n 条发言原文（不含本轮，按时间倒序）。 */
    public static List<String> ownSpeeches(GameEngine g, int seat, int n) {
        List<String> out = new ArrayList<>();
        List<GameEvent> evs = g.events();
        for (int i = evs.size() - 1; i >= 0 && out.size() < n; i--) {
            GameEvent e = evs.get(i);
            if (isSpeech(e.type()) && e.actorSeat() == seat && e.detail() != null && !e.detail().isBlank()) {
                out.add(e.detail());
            }
        }
        return out;
    }

    /** 全场最近 n 条发言原文（按时间倒序，排除某座位）。 */
    public static List<String> recentSpeeches(GameEngine g, int n, int excludeSeat) {
        List<String> out = new ArrayList<>();
        List<GameEvent> evs = g.events();
        for (int i = evs.size() - 1; i >= 0 && out.size() < n; i--) {
            GameEvent e = evs.get(i);
            if (isSpeech(e.type()) && e.actorSeat() != excludeSeat && e.detail() != null && !e.detail().isBlank()) {
                out.add(e.detail());
            }
        }
        return out;
    }

    /** 全场全部发言（用于高频套话统计）。 */
    private static List<String> allSpeeches(GameEngine g) {
        List<String> out = new ArrayList<>();
        for (GameEvent e : g.events()) {
            if (isSpeech(e.type()) && e.detail() != null && !e.detail().isBlank()) out.add(e.detail());
        }
        return out;
    }

    private static boolean isSpeech(String type) {
        return GameEvent.SPEECH.equals(type) || GameEvent.LAST_WORDS.equals(type);
    }

    /* ==================== 本局高频套话 ==================== */

    /**
     * 本局被反复说烂的短语：统计所有发言的 4~6 字片段，出现在 ≥{@code OVERUSED_MIN} 条
     * 不同发言里的片段入选，并做“长片段吞并短片段”的去冗余，返回最多 top 条。
     */
    public static List<String> overusedPhrases(GameEngine g, int top) {
        return overused(allSpeeches(g), top);
    }

    /** 纯函数版高频套话统计。 */
    public static List<String> overused(List<String> speeches, int top) {
        if (speeches == null || speeches.size() < OVERUSED_MIN) return List.of();

        Map<String, Integer> count = new LinkedHashMap<>();
        for (String s : speeches) {
            String n = norm(s);
            Set<String> seen = new HashSet<>();
            for (int len = 4; len <= 6; len++) {
                for (int i = 0; i + len <= n.length(); i++) {
                    String gram = n.substring(i, i + len);
                    if (gram.indexOf('#') >= 0) continue;          // 含座位号的不是“套话”，是具体指人
                    seen.add(gram);
                }
            }
            for (String gram : seen) count.merge(gram, 1, Integer::sum);
        }

        List<String> cand = new ArrayList<>();
        count.entrySet().stream()
                .filter(en -> en.getValue() >= OVERUSED_MIN)
                .sorted((a, b) -> b.getValue() != a.getValue()
                        ? b.getValue() - a.getValue()
                        : b.getKey().length() - a.getKey().length())
                .forEach(en -> cand.add(en.getKey()));

        // 长片段优先，被已选片段包含的短片段丢弃
        List<String> picked = new ArrayList<>();
        for (String c : cand) {
            boolean covered = picked.stream().anyMatch(p -> p.contains(c));
            if (!covered) picked.add(c);
            if (picked.size() >= top) break;
        }
        return picked;
    }

    /* ==================== 雷同判定 ==================== */

    /** 新发言是否与历史过于雷同（自己重复自己，或复读别人）。 */
    public static boolean tooSimilar(GameEngine g, int seat, String candidate) {
        return tooSimilar(ownSpeeches(g, seat, 4), recentSpeeches(g, 8, seat), candidate);
    }

    /** 纯函数版：便于脱离对局引擎单测。own=本人最近若干条，others=全场最近若干条。 */
    public static boolean tooSimilar(List<String> own, List<String> others, String candidate) {
        if (candidate == null || candidate.isBlank()) return false;
        for (String s : own) if (similarity(candidate, s) >= SELF_SIM) return true;
        for (String s : others) if (containment(candidate, s) >= OTHER_SIM) return true;
        if (own.isEmpty()) return false;
        String last = own.get(0);
        // 开场指纹相同（哪怕内容不同，读起来仍像复读机）
        if (!opening(candidate, 3).isEmpty() && opening(candidate, 3).equals(opening(last, 3))) return true;
        // 两条都以同一个口头禅/填充词起头，也算重复
        String fc = leadingFiller(candidate), fl = leadingFiller(last);
        return fc != null && fc.equals(fl);
    }

    /** 取开头的口头禅/填充词（“嗯…”“那个…”“说实话”等），没有则返回 null。 */
    public static String leadingFiller(String text) {
        if (text == null) return null;
        String t = text.trim();
        for (String f : FILLER_MARKS) if (t.startsWith(f)) return f;
        return null;
    }

    /** 已知的开头填充词/口头禅（与 Humanizer 的语癖池保持一致的常见子集）。 */
    private static final String[] FILLER_MARKS = {
            "嗯…", "嗯", "唔…", "唔", "呃…", "呃", "嗐…", "哎…", "emmm…", "那个…", "那个", "就是…", "就是",
            "怎么说呢，", "我寻思，", "说白了，", "讲真，", "我跟你讲，", "反正…", "我的我的，", "先说清楚，",
            "我直说了，", "我感觉吧，", "我个人觉得，", "我倾向于，", "说实话，", "坦白讲，", "我这边啊，",
            "先说我，", "我补一句，", "行吧，", "得嘞，", "那我直说，", "我摊牌了，", "有点意思，", "我有点慌",
            "我人都麻了，", "我脑壳疼，", "我有点上头，", "别急，", "等一下，", "我先捋一下，", "让我说完，", "我插一句，"
    };

    /** 该座位最近用过的开场指纹（供 Humanizer / 提示词避开）。 */
    public static Set<String> recentOpenings(GameEngine g, int seat, int n) {
        Set<String> out = new HashSet<>();
        for (String s : ownSpeeches(g, seat, n)) out.add(opening(s, 4));
        return out;
    }

    /* ==================== 提示词素材 ==================== */

    /**
     * 注入发言提示词的“反雷同”块：本人最近说了什么、本局哪些话已被说烂、本轮要求换哪种开场。
     * 没有任何历史时返回空串，避免给首轮玩家无谓的长提示。
     */
    public static String promptBlock(GameEngine g, int seat, int[] showOf) {
        List<String> own = ownSpeeches(g, seat, 3);
        List<String> over = overusedPhrases(g, 4);
        if (own.isEmpty() && over.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int disp = (showOf == null || seat <= 0 || seat >= showOf.length) ? seat : showOf[seat];
        sb.append("【反雷同·硬性】你已经是第 ").append(own.size() + 1).append(" 次发言了，绝不许重复自己说过的话。\n");
        if (!own.isEmpty()) {
            sb.append("你在本局已经说过（这些句式与结论禁止再来一遍，也不许只换个标点）：\n");
            for (String s : own) sb.append("  × ").append(trim(s)).append("\n");
        }
        if (!over.isEmpty()) {
            sb.append("本局这些说法已被反复提到，再说就是水麦，除非你有新论据否则避开：")
                    .append(String.join("、", over)).append("。\n");
        }
        sb.append("本轮开场方式指定为：").append(openingDirective(g, seat)).append("\n");
        sb.append("并且必须给出一个【新的】信息点或判断（新怀疑、新站边、新逻辑链、新诉求），不能只是把上面的话换个说法。\n");
        // 让模型知道“别人刚说过什么”，才谈得上不复读
        List<String> others = recentSpeeches(g, 3, seat);
        if (!others.isEmpty()) {
            sb.append("最近别人已说过（不要转述、不要摘要，给你自己的独立结论）：");
            for (String s : others) sb.append("「").append(trim(s)).append("」");
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 本轮开场策略：按座位+天数+该座位本局发言序号稳定轮换，保证同一个人不会连续两次同一种开场。 */
    public static String openingDirective(GameEngine g, int seat) {
        String[] pool = {
                "直接抛结论（先说你判谁像狼，再补一句理由）",
                "先抛一个别人没注意的逻辑疑点",
                "用一句极短的盘点开头，然后立刻给新判断",
                "明确表态站边某人，并说清为什么站他",
                "反问你怀疑对象一个具体问题（当场要答案）",
                "先承认自己上一轮的判断可能有误，再给修正后的判断",
                "用一个假设句推进（“如果X是狼，那么…”）",
                "从票型切入（谁投了谁、谁在保谁）",
                "先给自己本轮定调（“我这轮只说一件事”），只说一件事",
                "点名要求某人下一轮必须回应的具体问题",
                "从自己的身份/信息出发交代一点新东西",
                "用一句短促的口语吐槽开头（别用“嗯/那个/就是/其实”这类万能填充词）",
        };
        int spoken = ownSpeeches(g, seat, 99).size();
        int idx = Math.floorMod(seat * 7 + g.day() * 13 + spoken, pool.length);
        // 与上一条发言用的是同一种开场 → 往后挪一位
        if (spoken > 0) {
            int prev = Math.floorMod(seat * 7 + g.day() * 13 + (spoken - 1), pool.length);
            if (prev == idx) idx = Math.floorMod(idx + 1, pool.length);
        }
        return pool[idx];
    }

    /**
     * 每个座位固定一套“语癖”（填充词/口头禅池），既让不同 AI 之间听起来不像同一个人，
     * 也避免全局共用一个小池子导致整桌人都在“嗯…”。
     */
    public static int personaOf(int seat) {
        return Math.floorMod(seat * 31 + 7, 8);
    }

    private static String trim(String s) {
        String t = s.replaceAll("[\\r\\n]+", " ").trim();
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }
}
