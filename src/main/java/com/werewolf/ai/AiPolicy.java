package com.werewolf.ai;

import com.werewolf.game.GameAction;
import com.werewolf.rules.*;
import com.werewolf.rules.sim.RandomPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 玩家决策：夜晚/投票类走"提示词→LLM→JSON 动作"，发言类走"提示词→LLM→文本"。
 * 任何失败（网络/超时/非法 JSON/非法目标）都降级为 RandomPolicy 的合法动作，保证对局不卡。
 */
@Component
public class AiPolicy {

    private static final Logger log = LoggerFactory.getLogger(AiPolicy.class);
    private final AiService ai;
    private final RandomPolicy fallback = new RandomPolicy();
    private static final Pattern TARGET_P = Pattern.compile("\"target\"\\s*:\\s*(\\d+)");

    // ---- 轻量熔断：上游连续出现限流/不可用(429/503/超时/网络)时，短时间内不再请求上游，直接降级 ----
    /** 连续失败阈值：达到即开启熔断。 */
    private static final int BREAKER_THRESHOLD = 3;
    /** 熔断冷却时长（约 30s）。 */
    private static final long BREAKER_COOLDOWN_MS = 30_000L;
    private final AtomicInteger upstreamFailStreak = new AtomicInteger(0);
    private volatile long breakerOpenUntil = 0L;

    public AiPolicy(AiService ai) {
        this.ai = ai;
    }

    /** 为当前行动者产出动作。 */
    public GameAction decide(GameEngine g, int seat) { return decide(g, seat, false, null); }

    public GameAction decide(GameEngine g, int seat, boolean voice) { return decide(g, seat, voice, null); }

    /** 为当前行动者产出动作；voice=true 发言一次说完；showOf=匿名显示号置换（AI 全程用显示号思考/发言，动作目标也返回显示号）。 */
    public GameAction decide(GameEngine g, int seat, boolean voice, int[] showOf) {
        Player me = g.bySeat(seat);
        String kind = g.currentActionKind();
        try {
            if (kind.equals("SPEECH") || kind.equals("PK_SPEECH") || kind.equals("LAST_WORDS")) {
                GameAction a = new GameAction();
                a.text = decideSpeech(g, seat, kind, me, voice, showOf);
                return a;
            }
            List<Integer> legalReal = legalTargets(g, seat, kind);
            List<Integer> legalShow = mapToShow(kind, legalReal, showOf);
            if (breakerOpen()) {
                log.warn("AI 座位{} 上游熔断中，{} 决策直接降级随机", seat, kind);
                return randomAction(g, seat, kind, showOf);
            }
            String style = PromptLibrary.styleFor(seat, g.day());
            String user = PromptLibrary.actionUser(g, seat, kind, legalShow, style, showOf);
            List<AiProvider.Message> msgs = List.of(
                    new AiProvider.Message("system", PromptLibrary.systemPrompt(me.role)),
                    new AiProvider.Message("user", user));
            String reply = ai.chat(msgs);
            recordUpstreamOk();
            // 防泄漏：推理型模型可能只回一段英文思考（没有 JSON/目标），此时不应从思考里瞎抓数字，
            // 直接降级为合法随机动作，避免"AI 用思考过程做决策"。
            if (looksLikeReasoningOnly(reply)) {
                log.warn("AI 座位{} 仅返回思考过程(reply={})，降级随机", seat, brief(reply));
                return randomAction(g, seat, kind, showOf);
            }
            Integer target = parseTarget(reply); // 显示号空间
            if (target == null || !legalShow.contains(target)) {
                log.warn("AI 座位{} 目标非法(reply={}, target={})，降级随机", seat, brief(reply), target);
                return randomAction(g, seat, kind, showOf);
            }
            return toAction(kind, target); // 返回显示号目标，交由上层映射回真实座位
        } catch (Exception e) {
            recordUpstreamFailure(e);
            log.warn("AI 座位{} {} 决策异常，降级随机：{}", seat, kind, e.toString());
            return randomAction(g, seat, kind, showOf);
        }
    }

    private String decideSpeech(GameEngine g, int seat, String kind, Player me, boolean voice, int[] showOf) {
        if (breakerOpen()) {
            log.warn("AI 座位{} 上游熔断中，{} 发言直接走兜底台词（不再请求上游）", seat, kind);
            return fallbackSpeech(g, seat, kind);
        }
        String style = PromptLibrary.styleFor(seat, g.day() * 7L + 3);
        String tag = kind.equals("LAST_WORDS") ? "LAST_WORDS" : "SPEECH";
        String extra = kind.equals("LAST_WORDS")
                ? "发表遗言（你已出局，身份已不重要、无需再保密）：请结合你自己的真实身份来交代——"
                  + "如预言家可报昨晚验人结果、女巫可说用药情况、猎人可留开枪指示、狼人可坦白或继续甩锅带节奏；"
                  + "总之按对你阵营最有利的方式，用真实身份说清楚你想留下的信息"
                : (kind.equals("PK_SPEECH") ? "在平票 PK 中拉票发言" : "白天发言");
        int cap = voice ? 220 : 200;
        String baseUser = PromptLibrary.speechUser(g, seat, tag, style, extra, voice, showOf)
                + PhraseDedup.promptBlock(g, seat, showOf);
        try {
            // 关键：模型可能把“思考过程”（英文 CoT / thinking 标签 / JSON / 半截句）当成发言返回，
            // 这里统一清洗成简体中文口语，避免内心推理泄漏到全场对局记录。
            String text = SpeechSanitizer.clean(ai.chat(List.of(
                    new AiProvider.Message("system", PromptLibrary.systemPrompt(me.role)),
                    new AiProvider.Message("user", baseUser))), cap);
            recordUpstreamOk();
            if (text.isEmpty() || SpeechSanitizer.hasReasoningMarkers(text)) {
                // 疑似只输出了思考过程/英文/分析腔：重试一次，强约束“只输出一句简体中文口语”。
                log.warn("AI 座位{} 发言疑似思考过程/分析腔泄漏(残留={})，已拦截并重试", seat, brief(text));
                String harden = baseUser + "\n【重要】你上一次的输出无效（疑似英文思考过程、内心推理或“首先/其次/综上”类分析腔，已作废）。"
                        + "现在只输出一句简体中文的口语发言：不要英文、不要解释思考过程、不要分条分析、不要 JSON、不要引号。"
                        + "直接说结论和怀疑，像真人打字聊天一样。\n";
                text = SpeechSanitizer.clean(ai.chat(List.of(
                        new AiProvider.Message("system", PromptLibrary.systemPrompt(me.role)),
                        new AiProvider.Message("user", harden))), cap);
            }
            if (text.isEmpty() || text.matches("^(过|过麦|跳过|（过）|无|没有|没信息.*?)$")) return "过";

            // 反雷同：与本人最近发言或全场发言过于像 → 带上“已被说过的原句”作为负例，要求换新角度重说一次。
            if (PhraseDedup.tooSimilar(g, seat, text)) {
                List<String> own = PhraseDedup.ownSpeeches(g, seat, 3);
                log.info("AI 座位{} 发言与历史雷同，要求重说一次", seat);
                String redo = baseUser + "\n【重说】你刚才那句与本局已有发言雷同（等于水麦，已作废）：\n"
                        + own.stream().map(s -> "  已说过：" + s).reduce("", (a, b) -> a + b + "\n")
                        + "现在换一个全新的切入点、全新的句式重说一句：不许复用上面的开头、结论和口头禅，"
                        + "必须给出一个此前没出现过的信息点或判断。\n";
                String again = SpeechSanitizer.clean(ai.chat(List.of(
                        new AiProvider.Message("system", PromptLibrary.systemPrompt(me.role)),
                        new AiProvider.Message("user", redo))), cap);
                if (!again.isEmpty() && !PhraseDedup.tooSimilar(g, seat, again)) {
                    text = again;
                } else if (own.size() >= 2) {
                    // 连着两次都躲不开重复：像真人一样直接过麦，也不硬凑一句复读
                    log.info("AI 座位{} 二次仍雷同，按“无新信息过麦”处理", seat);
                    return "过";
                }
            }
            // 润色：按座位固定语癖（不同 AI 口头禅不重叠），并避开该座位刚用过的填充词
            Set<String> avoid = new HashSet<>();
            for (String s : PhraseDedup.ownSpeeches(g, seat, 2)) avoid.addAll(Humanizer.fillersIn(s));
            text = Humanizer.humanize(text, new Random(seat * 131L + g.day() * 17L + System.nanoTime() % 1000),
                    PhraseDedup.personaOf(seat), avoid);
            return text.length() > cap ? text.substring(0, cap) : text;
        } catch (Exception e) {
            // 上游不可用（限流/超时/网络）时不能让全员复读同一句：走多样化兜底台词，并把原因记进日志。
            recordUpstreamFailure(e);
            log.warn("AI 座位{} 发言生成失败（{}），改用多样化兜底台词：{}", seat, kind, e.toString());
            return fallbackSpeech(g, seat, kind);
        }
    }

    /** 白天/PK 发言兜底池（AI 上游不可用时使用）。 */
    private static final String[] SPEECH_FALLBACK = {
            "我先听听后面的发言，暂时过。", "这把信息不多，我先观望一轮。", "我的票先攥着，听完再说。",
            "前面几位的话我记下了，回头对一对票型。", "我没什么新信息，别在我身上浪费票。",
            "先理一理场子，下一轮我再表态。", "我这轮不抢话，听听后置位怎么说。",
            "场面还乱，我先站一个我看得懂的好人。", "谁在带节奏我先记着，别急着推我。",
    };
    /** 遗言兜底池（同样保证 8 句以上，避免降级时全员一句）。 */
    private static final String[] LAST_WORDS_FALLBACK = {
            "就到这里吧，大家加油。", "我的信息都用完了，看你们的了。", "别在我身上纠结票，去找第二匹狼。",
            "记住刚才那个票型，答案就在里面。", "我走了，剩下的交给你们盘。", "狼坑我留在心里了，你们慢慢对。",
            "这局到此为止，但愿你们别站错边。", "最后提醒一句，别被人带节奏。",
    };

    /**
     * 兜底发言：按 座位 + 天数 + 该座位已发言次数 轮转选句（同一座位不会连续两句相同），
     * 再叠加该座位的固定语癖润色，保证降级时听起来仍是不同的人。
     */
    private String fallbackSpeech(GameEngine g, int seat, String kind) {
        boolean lastWords = "LAST_WORDS".equals(kind);
        String[] pool = lastWords ? LAST_WORDS_FALLBACK : SPEECH_FALLBACK;
        int spoken = 0;
        for (GameEvent ev : g.events()) if ("SPEECH".equals(ev.type()) && ev.actorSeat() == seat) spoken++;
        int idx = (int) Math.floorMod((long) seat * 7 + g.day() * 13L + spoken, pool.length);
        // 与该座位最近一条发言重复时顺延一位，保证同局连续兜底不重样
        List<String> own = PhraseDedup.ownSpeeches(g, seat, 1);
        if (!own.isEmpty() && pool[idx].equals(own.get(0))) idx = (idx + 1) % pool.length;
        String base = pool[idx];
        if (lastWords) return base;
        return Humanizer.humanize(base, new Random(seat * 131L + g.day() * 17L + spoken),
                PhraseDedup.personaOf(seat), Set.of());
    }

    // ==================== 上游轻量熔断 ====================

    /** 熔断是否处于打开状态；到期自动关闭并清零计数。 */
    private boolean breakerOpen() {
        long until = breakerOpenUntil;
        if (until == 0L) return false;
        if (System.currentTimeMillis() >= until) {
            breakerOpenUntil = 0L;
            upstreamFailStreak.set(0);
            return false;
        }
        return true;
    }

    /** 上游调用成功：清空连续失败计数并关闭熔断。 */
    private void recordUpstreamOk() {
        upstreamFailStreak.set(0);
        breakerOpenUntil = 0L;
    }

    /** 记录一次上游失败；若属限流/不可用类且连续达阈值，则开启约 30s 熔断。 */
    private void recordUpstreamFailure(Throwable e) {
        if (!isUpstreamUnavailable(e)) return;
        int streak = upstreamFailStreak.incrementAndGet();
        if (streak >= BREAKER_THRESHOLD) {
            breakerOpenUntil = System.currentTimeMillis() + BREAKER_COOLDOWN_MS;
            upstreamFailStreak.set(0);
            log.warn("AI 上游连续 {} 次限流/不可用，开启熔断 {}s：期间直接走兜底/随机，不再请求上游",
                    streak, BREAKER_COOLDOWN_MS / 1000);
        }
    }

    /** 判定异常是否属于“上游不可用”（限流 429 / 服务不可用 503/502/504 / 超时 / 网络 IO）。 */
    private boolean isUpstreamUnavailable(Throwable e) {
        int guard = 0;
        for (Throwable t = e; t != null && guard++ < 8; t = t.getCause()) {
            String m = (String.valueOf(t) + " " + String.valueOf(t.getMessage())).toLowerCase(Locale.ROOT);
            if (m.contains("429") || m.contains("rate_limit") || m.contains("rate limit")
                    || m.contains("too many requests") || m.contains("503") || m.contains("502") || m.contains("504")
                    || m.contains("service unavailable") || m.contains("no_healthy_account")
                    || m.contains("超时") || m.contains("timeout") || m.contains("timed out")
                    || m.contains("connect") || m.contains("connection") || m.contains("reset")
                    || m.contains("unknownhost") || m.contains("网络") || m.contains("ioexception")
                    || t instanceof java.net.SocketTimeoutException || t instanceof java.net.ConnectException
                    || t instanceof java.net.UnknownHostException) {
                return true;
            }
        }
        return false;
    }

    /** 典型“思考过程”关键词（中英混合）：出现这些多为模型在输出推理而非最终决策。 */
    private static final Pattern REASONING_MARKERS = Pattern.compile(
            "(?is)首先|其次|然后|接下来|让我|我们要|我将|可以考虑|分析(一下|如下|过程)?|推理|思考|步骤|综上所述|因此|综上|"
            + "\\b\\d+\\s*[.、)]\\s|let me|let's|reasoning|thinking|analysis|current state|game state|step\\s*\\d|chain of thought|think step");
    /** 明确的行动语句特征：出现则更可能是有效决策（含 JSON 字段、目标、投票/查验等动作词）。 */
    private static final Pattern ACTION_HINT = Pattern.compile(
            "(?is)\"target\"\\s*:|\\{\\s*\".*\"|投票|投给|放逐|查验|刀|击杀|守护|救|毒|开枪|禁言|诽谤|上警|弃票|过麦|target");

    /**
     * 是否看起来只是模型的思考过程（无结构化动作、且思考关键词占比高）。
     * 收紧策略：不再以“是否含任意中文”作为放过条件（中文思考过程同样会泄漏），
     * 而是：有 JSON 结构或含明确行动语句 → 视为有效；否则统计思考关键词出现次数，
     * 出现>=1 个典型思考标记、且没有行动语句时判为“仅思考过程”。
     */
    private boolean looksLikeReasoningOnly(String reply) {
        if (reply == null || reply.isBlank()) return true;
        if (reply.contains("{") && reply.contains("}")) return false; // 有 JSON 结构，正常解析
        if (ACTION_HINT.matcher(reply).find()) return false;          // 含明确行动语句，视为有效决策
        // 无行动语句时：命中典型思考过程关键词即判定为思考泄漏（不再以“含中文”放过，中文思考同样会泄漏）
        return REASONING_MARKERS.matcher(reply).find();
    }

    private Integer parseTarget(String reply) {
        if (reply == null) return null;
        Matcher m = TARGET_P.matcher(reply);
        if (m.find()) return Integer.parseInt(m.group(1));
        Matcher n = Pattern.compile("\\b(\\d+)\\b").matcher(reply); // 兜底：抓第一个数字
        return n.find() ? Integer.parseInt(n.group(1)) : null;
    }

    private GameAction toAction(String kind, int target) {
        GameAction a = new GameAction();
        if (kind.equals("SHERIFF_SIGNUP")) { a.signup = target == 1; return a; }
        a.target = target;
        return a;
    }

    /** 降级：用随机策略为该阶段生成合法动作（返回显示号空间，与 LLM 路径一致）。 */
    private GameAction randomAction(GameEngine g, int seat, String kind, int[] showOf) {
        Random rnd = new Random();
        Player me = g.bySeat(seat);
        GameAction a = new GameAction();
        switch (kind) {
            case "GUARD" -> a.target = fallback.guardTarget(g, me, rnd);
            case "WOLF_KILL" -> a.target = fallback.wolfKill(g, me, rnd);
            case "WITCH" -> { int[] wp = fallback.witch(g, me, rnd); a.saveTarget = wp[0]; a.poisonTarget = wp[1]; }
            case "SEER_CHECK" -> a.target = fallback.seerTarget(g, me, rnd);
            case "CROW" -> a.target = fallback.crowTarget(g, me, rnd);
            case "SILENCER" -> a.target = fallback.silencerTarget(g, me, rnd);
            case "SHOOT" -> a.target = fallback.shootTarget(g, me, rnd);
            case "SHERIFF_SIGNUP" -> a.signup = fallback.sheriffSignup(g, me, rnd);
            case "SHERIFF_VOTE" -> a.target = fallback.sheriffVote(g, me, new ArrayList<>(g.sheriffCandidates()), rnd);
            case "VOTE", "PK_VOTE" -> a.target = fallback.exileVote(g, me, rnd);
            case "SPEECH", "PK_SPEECH", "LAST_WORDS" -> {
                // 白狼王 AI：白天有一定概率选择自爆（用与 LiveGameService 约定的文本标记触发）
                if (g.canBlowUp(seat) && rnd.nextDouble() < 0.15) {
                    List<Integer> goodTargets = g.alive().stream()
                            .filter(p -> p.seat != seat && !p.isWolf()).map(p -> p.seat).toList();
                    if (!goodTargets.isEmpty()) {
                        a.text = com.werewolf.game.LiveGameService.BLOWUP_MARKER;
                        a.target = goodTargets.get(rnd.nextInt(goodTargets.size()));
                        break;
                    }
                }
                a.text = "我先过，听后置位。";
            }
            default -> a.target = 0;
        }
        // 真实座位 -> 显示号（0 与报名标志不变）
        a.target = sh(showOf, a.target);
        a.saveTarget = sh(showOf, a.saveTarget);
        a.poisonTarget = sh(showOf, a.poisonTarget);
        return a;
    }

    private static int sh(int[] showOf, int seat) {
        return (showOf == null || seat <= 0 || seat >= showOf.length) ? seat : showOf[seat];
    }

    /** 合法目标列表 真实->显示（报名类 0/1 标志不映射）。 */
    private List<Integer> mapToShow(String kind, List<Integer> real, int[] showOf) {
        if (showOf == null || kind.equals("SHERIFF_SIGNUP")) return real;
        List<Integer> out = new ArrayList<>();
        for (int t : real) out.add(sh(showOf, t));
        return out;
    }

    /** 当前阶段的合法目标集合（含 0 表示可放弃，若适用）。 */
    public List<Integer> legalTargets(GameEngine g, int seat, String kind) {
        List<Integer> alive = g.alive().stream().map(p -> p.seat).toList();
        List<Integer> out = new ArrayList<>();
        switch (kind) {
            case "GUARD" -> { Player me = g.bySeat(seat); for (int s : alive) if (s != me.guardLastTarget) out.add(s); out.add(0); }
            case "WOLF_KILL" -> { out.addAll(g.aliveGood().stream().map(p -> p.seat).toList()); out.add(0); }
            case "WITCH" -> {
                Player w = g.bySeat(seat);
                if (w.witchSaveAvailable && g.nightWolfKill() != 0) out.add(g.nightWolfKill());
                if (w.witchPoisonAvailable) for (int s : alive) if (s != seat) out.add(s);
                out.add(0);
            }
            case "SEER_CHECK" -> { for (int s : alive) if (s != seat) out.add(s); }
            case "CROW" -> { out.addAll(alive); out.add(0); }
            case "SILENCER" -> { Player me = g.bySeat(seat); for (int s : alive) if (s != me.silencerLastTarget) out.add(s); out.add(0); }
            case "SHOOT" -> { for (int s : alive) if (s != seat) out.add(s); out.add(0); }
            case "SHERIFF_SIGNUP" -> { out.add(1); out.add(0); }
            case "SHERIFF_VOTE" -> { out.addAll(g.sheriffCandidates()); out.add(0); }
            case "VOTE" -> { Player me = g.bySeat(seat); for (Player p : g.alive()) { if (p.seat == seat || p.idiotRevealed) continue; if (me.isWolf() && p.isWolf()) continue; out.add(p.seat); } out.add(0); }
            case "PK_VOTE" -> { Player me = g.bySeat(seat); for (int c : g.tiebreakCandidates()) { Player cp = g.bySeat(c); if (me.isWolf() && cp != null && cp.isWolf()) continue; out.add(c); } out.add(0); }
            default -> out.add(0);
        }
        // 去重
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private String brief(String s) { return s == null ? "null" : (s.length() > 80 ? s.substring(0, 80) : s); }
}
