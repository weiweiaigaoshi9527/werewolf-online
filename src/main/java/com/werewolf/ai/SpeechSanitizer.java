package com.werewolf.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 发言文本清洗器。
 * <p>
 * 目的：防止「推理型模型」把内心思考过程（英文 CoT、thinking 标签、JSON 片段、被 max_tokens
 * 截断的半句话）当成对局发言泄漏到全场。
 * <p>
 * 判定原则：一局狼人杀的公开发言必须是简体中文口语。因此这里做三件事：
 * 1) 剥离 思考标签 / 代码围栏 / JSON 包装，只取真正的“话”；
 * 2) 逐句过滤：只保留以中文为主、且不含元信息（AI/提示词/思考过程等）的句子；
 * 3) 若清洗后为空，返回空串，由上层判定为「过」，绝不把英文思考原样播报出去。
 */
public final class SpeechSanitizer {

    private SpeechSanitizer() {}

    /** 代码围栏 ```...``` */
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)```.*?(?:```|$)");
    /** 特殊控制 token：<｜end▁of▁thinking｜> 之类 */
    private static final Pattern SPECIAL_TOKEN = Pattern.compile("<[｜|][^｜|\\n]{0,40}[｜|]>");
    /** 成对的英文思考标签（think/thinking/reasoning/analysis/...） */
    private static final Pattern THINK_PAIR =
            Pattern.compile("(?is)<[\\s｜|]*(think|thinking|reasoning|analysis|scratchpad|thought)[\\s｜|]*>.*?<[\\s｜|]*/[\\s｜|]*\\1[\\s｜|]*>");
    /** 未闭合的英文思考标签：从标签起到结尾 */
    private static final Pattern THINK_OPEN =
            Pattern.compile("(?is)<[\\s｜|]*(think|thinking|reasoning|analysis|scratchpad|thought)[\\s｜|]*>.*$");
    /** 中文思考块：【思考】/【思路】… 到行尾 */
    private static final Pattern CN_THINK = Pattern.compile("(?s)【(思考|思路|内心|推理|分析)】.*?(?=\\n|$)");
    /** JSON 包装：{"speech":"..."} / {"text":"..."} / {"content":"..."} */
    private static final Pattern JSON_FIELD = Pattern.compile(
            "(?is)\"(speech|text|content|reply|answer|say)\"\\s*:\\s*\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    /** 说话人前缀：2号：/ 2号发言：/ 发言：/ 遗言： */
    private static final Pattern LEADING_LABEL = Pattern.compile(
            "^\\s*(?:\\d{1,2}\\s*号\\s*)?(?:发言|遗言|说|默)?\\s*[:：]\\s*");
    /** 元信息/推理关键词：命中即丢弃该句（仅保留明确的推理/元信息标记，避免误伤正常发言） */
    private static final Pattern META = Pattern.compile(
            "(?i)(let me|let's|alive\\s*[:：]|reasoning|thinking|analysis|scratchpad|as an ai|"
                    + "current state|game state|step\\s*\\d|作为\\s*ai|语言模型|大模型|提示词|系统提示|"
                    + "思考过程|内心独白|我的推理|我作为)");
    /** 中文内心策略泄漏：公开发言绝不该出现"自我身份策略/私下行动计划"——命中即丢弃该句。
     *  只匹配自我指涉的策略表述，不误伤"他一直在隐藏身份"这类针对他人的正常发言。 */
    private static final Pattern STRATEGY_LEAK = Pattern.compile(
            "(我|我们)(要|应该|需要|得|打算|准备|必须)(隐藏|伪装|掩饰|扮)|"
                    + "为了(隐藏|伪装|掩饰)(自己|我的?身份)|"
                    + "我的(真实身份|队友|任务|策略|目标|计划|推理)|"
                    + "我要?刀杀?|今晚(我们?|我)刀|我是狼人[，。]|我不应该说|说出来就(输|暴露)|"
                    + "作为(狼人|好人|神职)(我)?(应该|需要|要|得)|"
                    + "(悍跳|倒钩|深水|抿身份|冲票|抗推)");
    /** 中文"分析腔/推理脚手架"：对局后期上下文变长，模型容易输出书面推理框架而非口语发言。
     *  这些短语命中即丢弃该句（公开发言应是口语结论，不是分析过程）。 */
    private static final Pattern META_CN = Pattern.compile(
            "综上|综合(以上|前面|全场|来看|判断|各?方信息)|分析(一下|如下|过程|一下局势|一下场)|"
                    + "推理(如下|过程|一下)|梳理(一下|一下场面)|盘一下逻辑|"
                    + "从(场上|当前|盘面|局势)(情况|来看|分析|出发)|已知(条件|信息)|信息点[:：]|"
                    + "结论(是|如下)|如上分析|我的?(判断依据|分析如下|思考如下)|让我(来|先|分析|梳理)");
    /** 句首推理脚手架：以这些词开头（后跟标点）的句子是典型的分条分析，不是口语发言。 */
    private static final Pattern REASON_START = Pattern.compile(
            "^\\s*(首先|其次|再者|再次|接着|最后一点?|其一|其二|其三|第[一二三四五12345](步|点|条|，|,|、|：|:))");
    /** 句子切分：中文句末标点 / 英文句末标点+空白 / 换行 */
    private static final Pattern SENT_SPLIT = Pattern.compile("(?<=[。！？!?…；;])|(?<=[.!?])(?=\\s)|[\\n\\r]+");
    /** 句末标点 */
    private static final Pattern END_PUNCT = Pattern.compile("[。！？!?…；;]");
    /** 悬空的连接词结尾（说明被截断） */
    private static final Pattern DANGLING_TAIL = Pattern.compile("(所以|但是|因为|然后|而且|不过|其实|就是|还有|如果|虽然|可是)$");

    /**
     * 清洗一行 AI 发言。
     *
     * @param raw 模型原始输出
     * @param cap 最长字数
     * @return 干净的简体中文发言；无法提取到有效中文时返回空串
     */
    public static String clean(String raw, int cap) {
        if (raw == null || raw.isBlank()) return "";

        String s = raw;
        s = CODE_FENCE.matcher(s).replaceAll(" ");
        s = SPECIAL_TOKEN.matcher(s).replaceAll(" ");
        s = THINK_PAIR.matcher(s).replaceAll(" ");
        s = THINK_OPEN.matcher(s).replaceAll(" ");
        s = CN_THINK.matcher(s).replaceAll(" ");
        // 转义序列还原（模型有时返回 JSON 字符串里的 \n \"）
        s = s.replace("\\n", " ").replace("\\r", " ").replace("\\t", " ").replace("\\\"", "\"");

        // 若整体是 JSON 包装，优先取其中的话术字段
        Matcher jm = JSON_FIELD.matcher(s);
        if (jm.find()) s = jm.group(2);

        // 去掉外层引号/大括号/方括号与角色名前缀
        s = s.replaceAll("^[\\s\"'“”‘’「」『』{}<>\\[\\]]+", "");
        s = s.replaceAll("[\\s\"'“”‘’「」『』{}<>\\[\\]]+$", "");
        s = LEADING_LABEL.matcher(s).replaceFirst("");
        // 压平换行/制表/连续空白
        s = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replaceAll("\\s{2,}", " ").trim();
        if (s.isEmpty()) return "";

        // 逐句过滤：只保留中文为主且不含元信息/内心策略泄漏的句子
        List<String> kept = new ArrayList<>();
        for (String sent : SENT_SPLIT.split(s)) {
            String t = sent.trim();
            if (t.isEmpty()) continue;
            if (META.matcher(t).find()) continue;
            if (META_CN.matcher(t).find()) continue;
            if (REASON_START.matcher(t).find()) continue;
            if (STRATEGY_LEAK.matcher(t).find()) continue;
            if (!looksChinese(t)) continue;
            kept.add(t);
        }
        String out = String.join("", kept).trim();
        if (out.isEmpty()) return "";
        // 被模型截断（结尾停在连接词）时补省略号，避免播报出半句话
        if (DANGLING_TAIL.matcher(out).find()) out = out + "…";

        // 长度裁剪：优先在句末标点处断开，避免留下半句话
        if (out.length() > cap) {
            String cut = out.substring(0, cap);
            Matcher em = END_PUNCT.matcher(cut);
            int lastEnd = -1;
            while (em.find()) lastEnd = em.end();
            if (lastEnd >= cap / 2) {
                cut = cut.substring(0, lastEnd);
            } else {
                cut = cut.replaceAll("[，,、\\s]+$", "") + "…";
            }
            out = cut;
        }
        return out.trim();
    }

    /** 是否仍带明显“分析腔/推理过程”痕迹（供上层判断是否需要加重试）。 */
    public static boolean hasReasoningMarkers(String text) {
        if (text == null || text.isBlank()) return false;
        return META.matcher(text).find() || META_CN.matcher(text).find() || REASON_START.matcher(text).find();
    }

    /** 是否“以中文为主”：中文字符占比 ≥ 0.28（排除纯英文/英中混杂的思考片段）。 */
    private static boolean looksChinese(String t) {
        int total = 0, cjk = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) continue;
            total++;
            if (isCjk(c)) cjk++;
        }
        if (total == 0 || cjk == 0) return false;
        return (double) cjk / total >= 0.28;
    }

    private static boolean isCjk(char c) {
        // 含中文常用区、扩展区、兼容区，以及中文标点范围内判断
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }
}
