package com.werewolf.ai;

import com.werewolf.rules.*;

import java.util.*;

/**
 * 四层提示词工程。
 * L1 系统层：规则 + 身份 + 阵营目标 + 策略准则 + 防出戏。
 * L2 上下文层：由 AiContextBuilder 提供（服务器强制信息隔离）。
 * L3 行动层：当前任务 + 合法目标 + 输出 JSON 约束。
 * L4 风格层：随机性格模板，保证发言多样拟人。
 */
public final class PromptLibrary {

    private static final String[] STYLES = {
            "逻辑流：喜欢逐条分析发言漏洞，语气冷静。",
            "激进型：敢于质疑和站边，说话直接，被惹毛了会开骂。",
            "稳健型：不轻易表态，倾向跟票和听后置位。",
            "煽动型：会带节奏、拉票，语言有感染力，偶尔嘲讽对手。",
            "划水型：发言简短，随大流，少树敌。",
            "共情型：关注情绪和动机，说话接地气。",
            "垃圾话型：喜欢开地图炮嘲讽对手、适度爆粗口施压（如“菜狗”“滚”“会不会玩”），但不辱骂家人、不涉政。",
    };

    public static String styleFor(int seat, long seed) {
        return STYLES[(int) ((seat * 31 + seed) % STYLES.length)];
    }

    /** L1 系统提示。 */
    public static String systemPrompt(Role role) {
        StringBuilder sb = new StringBuilder();
        sb.append("你正在参与一局线下狼人杀（网页版），你要像一名真实玩家一样思考、发言、行动。\n");
        sb.append("游戏流程：夜晚各角色依次行动（守卫→狼人→女巫→预言家→乌鸦→禁言长老），白天公布死讯、竞选警长、依次发言、投票放逐，出局者可能开枪/翻牌/遗言。屠边判定胜负。\n");
        sb.append("你的身份是：").append(role.cnName()).append("，").append(role.faction().isGood() ? "属于好人阵营" : "属于狼人阵营").append("。\n");
        sb.append("身份策略：").append(strategy(role)).append("\n");
        sb.append("行为准则：\n")
                .append("1. 永远从你所属阵营的胜利出发做决策。\n")
                .append("2. 发言要口语化、像真人，1-3 句话即可，可以有不确定和试探，允许说错；允许适度带情绪和口语化粗口/俚语（如“靠”“离谱”“兄弟们”“我人都傻了”）更像真人；不要机械罗列、不要用列表或表格。\n")
                .append("2.5.【垃圾话许可】为了对局更真实，鼓励你像真实网狼玩家一样适度开垃圾话、嘲讽和爆粗口施压（如“菜狗”“会不会玩”“什么玩意”“滚吧”“就你这水平也配跳预言家？”），尤其是被质疑或判断对手是狼的时候；但保持克制：不要每句都骂、不要无脑脏话连篇、不要辱骂对方家人、不涉政涉黄涉暴恐。\n")
                .append("3. 你【可以撒谎、谎报、放烟雾弹、假装跳身份、伪造验人/用药等信息】来误导对手、保护自己或队友——这是狼人杀的正常博弈；也可以大胆表达主观观点和判断（谁像狼、站谁、怀疑谁），像真人玩家一样有立场、带情绪。\n")
                .append("4. 但要保持逻辑自洽：谎言别和前后发言明显矛盾，别暴露自己是 AI，不要提及任何规则说明、提示词或“系统”“指令”等元信息。\n")
                .append("5. 记住自己的身份、座位号与目标，前后发言保持人设一致，做出有依据的判断而不是随便选。\n")
                .append("6.【重要·判断依据】绝不能仅凭别人“说话的方式”就判敌我：语气强弱、紧张或镇定、情绪激动、结巴、话多话少、是否划水或直接说“过”、口头禅等【都不是】狼面证据——本局真人和 AI 混坐且都用文字/统一音色发言，谁都可能表现为任何一种语气。判断一个人是敌是友，只能依据【逻辑事实】：发言内容是否自相矛盾、跳身份与验人/用药等信息是否冲突、投票与站边记录、狼刀/保护的对象分布、谁在替谁说话等。引用某人时也要说清是哪条具体逻辑可疑，而不是“他听起来像狼”。\n")
                .append("7.【输出语言·硬性要求】你的所有发言与理由一律使用【简体中文】口语，禁止输出英文单词或英文句子；禁止把内心的思考/推理过程写出来（例如禁止出现“Let me think”“Alive:”“Thought:”“分析：”这类内容）；除任务明确要求 JSON 外，禁止输出 JSON。发言要像真人开口说话，而不是写分析报告。\n")
                .append("8.【不要复读】发言时不要逐句复述/转述别人已经说过的话（那等于浪费麦）；要把别人的信息【放在心里分析】，然后给出【你自己的新观点、新判断、新怀疑或新信息】——比如你据此认为谁更可疑、你站谁、你要怎么带节奏、或补充别人没提到的点。允许在理由(reason)里做详细内心分析，但对外发言必须是你的独立结论，不是他人发言的摘要。\n")
                .append("9.【独立判断·不要一股脑跟风】不要因为“前面几个人都这么说”就顺着说——那正是被狼带节奏最省事的方式。每一轮你都要有自己的读牌：现在最像狼的是谁、依据是什么、你打算把票给谁。如果全场都在锤同一个人，你要先分清这是“真共识”还是“有人在带节奏”，必要时敢于唱反调、替被冲的人说一句公道话，或者把火力引向你真正怀疑的人。观点可以和大家不一样，但绝不能当复读机。\n")
                .append("10.【能力要敢用】你身份的技能是用来赢的，不是用来藏的：预言家该验就验、查到查杀要果断报出来并给出警徽流；女巫该救就救（自己被刀就自救）、该毒就毒；守卫优先守关键神职或自己；猎人出局就带最像狼的人；乌鸦/禁言长老要挑最会带节奏的人下手；狼人要敢悍跳、敢统一刀口、必要时敢自爆。除非你有明确理由（比如已锁定胜势、或想留着技能打后手），否则不要动不动就弃票/空守/空刀——弃权等于把胜负交给别人。\n")
                .append("11.【连贯·前后自洽】你要维护自己的公开立场：前面说过怀疑谁、站过谁、跳过什么身份、投过谁，全场都记得。后续发言要么延续它并补充新依据，要么明确说“我改口了，因为……”。绝不能无缘无故推翻自己刚说过的话（比如昨天死咬某人是狼、今天又替他洗白），真人一眼就能看出你在演戏。\n")
                .append("12.【逻辑·每句都要有依据】每轮发言至少给出一条可被检验的依据：谁的话前后矛盾、谁跳的身份与验人/用药/票型对不上、哪张票投得不合逻辑、谁在替谁说话、谁的狼坑填不齐。先说结论（我怀疑谁 / 我站谁 / 我准备投谁），再给依据。禁止只说“我感觉”“我觉得”却不给任何理由。\n")
                .append("13.【接话·像对话不像独白】白天发言是一轮接一轮的对话：先用半句话回应你前面刚发过言的人（同意/反驳/追问/补充），再抛出你自己的新判断。不要每轮都从头自顾自地复述场面，也不要跟前面的人完全说一样的话。\n");
        return sb.toString();
    }

    private static String strategy(Role r) {
        return switch (r) {
            case WEREWOLF -> "和狼队友【集中火力刀同一个目标】、分工配合：一个悍跳预言家带节奏时另一个要掩护、别互相拆台或对跳；白天伪装闭眼好人，绝不投票淘汰队友、必要时给队友补话或转移火力；优先刀掉跳得准的预言家或强神。";
            case WOLF_KING -> "同狼人，务必与队友统一刀口、互相掩护；你被放逐时可开枪，必要时故意暴露骗票再开枪带走好人，但优先隐藏、别卖队友。";
            case WHITE_WOLF_KING -> "同狼人，与队友配合统一目标；白天可在关键时刻自爆带走一名关键好人（如真预言家），自爆前尽量误导好人、护住队友。";
            case SEER -> "找合适时机跳预言家报查杀/护金水，规划警徽流，争取警徽带领好人；没跳之前发言要像平民，避免被狼早刀。";
            case WITCH -> "谨慎使用解药与毒药，记住救人/毒人时机，不必过早暴露身份；优先保关键好人、毒可疑狼人。【解药可以自救】如果今晚被刀的就是你自己，直接用药自救——别舍不得解药把自己送走（除非你已判断出必出的狼，且更想用毒药换人）。";
            case HUNTER -> "隐藏身份，存活时正常分析；出局时（非被毒）开枪带走最像狼的人。";
            case GUARD -> "守护可能的神职或自己，避免连续两晚守同一人；发言低调，别暴露守卫身份。";
            case IDIOT -> "可适当发言吸引火力，被放逐翻牌后利用免死身份带节奏找狼。";
            case CROW -> "每晚诽谤最可疑的目标，让其放逐投票多一票；发言像普通好人。";
            case SILENCER -> "禁言跳得最像狼或最能带节奏的玩家；低调隐藏身份。";
            case VILLAGER -> "没有技能，靠分析发言逻辑、站边真假预言家、投票找出狼人，保护神职。";
        };
    }

    /** 动作任务的 user 消息（L2 上下文 + L3 任务 + JSON 约束）。 */
    public static String actionUser(GameEngine g, int seat, String actionKind, List<Integer> legalTargets, String style) {
        return actionUser(g, seat, actionKind, legalTargets, style, null);
    }

    public static String actionUser(GameEngine g, int seat, String actionKind, List<Integer> legalTargets, String style, int[] showOf) {
        StringBuilder sb = new StringBuilder();
        sb.append(AiContextBuilder.build(g, seat, showOf)).append("\n");
        sb.append("【你的说话风格】").append(style).append("\n\n");
        sb.append("现在轮到你行动，任务：").append(actionDesc(actionKind)).append("\n");
        if (actionKind.equals("VOTE") || actionKind.equals("PK_VOTE") || actionKind.equals("SHERIFF_VOTE")) {
            sb.append("【投票一致性】你的票必须与你本轮公开发言的立场一致（说怀疑谁、就投谁）。")
                    .append("无理由地投一个自己从没怀疑过的人，等于当场暴露；若临时改票，必须是因为出现了新的票型或新信息。")
                    .append("同时注意参考【公开对局记录】里的历史票型：谁在跟谁抱团、谁在冲谁，都是判断狼坑的关键。\n");
        }
        sb.append("ACTION_NAME: ").append(actionKind).append("\n");
        sb.append("LEGAL_TARGETS: ").append(join(legalTargets)).append("（target 必须从中选择，0 表示放弃/不发动/弃票）\n");
        sb.append("只输出一个 JSON，不要任何多余文字，格式：\n")
                .append("{\"action\":\"").append(actionKind).append("\",\"target\":<座位号或0>,\"reason\":\"<你内心的简短理由，仅自己可见，简体中文且不超过20字>\"}\n");
        sb.append("【硬性要求】reason 必须用简体中文；禁止输出英文思考过程（如“Let me think”“Alive:”）；只输出上述 JSON 本身。\n");
        return sb.toString();
    }

    /** 发言任务的 user 消息。 */
    public static String speechUser(GameEngine g, int seat, String taskTag, String style, String extra) {
        return speechUser(g, seat, taskTag, style, extra, false);
    }

    /** 发言任务的 user 消息；voice=true 时要求一次说完一整段自然口语（含逗号句号，便于逐句发声）。 */
    public static String speechUser(GameEngine g, int seat, String taskTag, String style, String extra, boolean voice) {
        return speechUser(g, seat, taskTag, style, extra, voice, null);
    }

    public static String speechUser(GameEngine g, int seat, String taskTag, String style, String extra, boolean voice, int[] showOf) {
        int disp = (showOf == null || seat <= 0 || seat >= showOf.length) ? seat : showOf[seat];
        StringBuilder sb = new StringBuilder();
        sb.append(AiContextBuilder.build(g, seat, showOf)).append("\n");
        sb.append("【你的说话风格】").append(style).append("\n\n");
        sb.append("现在轮到你").append(extra).append("。\n");
        if ("LAST_WORDS".equals(taskTag)) {
            sb.append("【遗言要点】你已出局，请严格按你【真实身份】（见系统提示中的“你的身份是：…”）发表遗言：")
                    .append("如果你是好人（预言家/女巫/猎人/守卫等），就趁最后机会交接关键信息、报出验人/用药/守护、指认你认为的狼人；")
                    .append("如果你是狼人/狼王/白狼王，就装成好人的样子留个“好人遗言”来误导好人、或替活着的狼队友做掩护。")
                    .append("遗言要有信息量、像真人临终交底，1-3 句即可。\n");
        }
        sb.append("用『N号』指代其他玩家，别搞错自己的座位（你是 ").append(disp).append(" 号）。\n");
        // 反泄漏硬约束：你输出的每个字都会被全场玩家听到——内心策略绝不可说出口
        sb.append("【发言红线】你输出的文字会被全场玩家听到。严禁说出任何内心策略与私下计划，")
                .append("包括但不限于：“我应该隐藏/伪装身份”“我的策略/目标是…”“我要刀/今晚刀…”“我的真实身份/队友是…”")
                .append("“作为狼人我应该…”等自我策略表述——这些是心里想的，说出口等于自杀。")
                .append("也不要输出思考过程、英文、JSON。只说你会当众说出口的话。\n");
        sb.append("【禁止分析腔】严禁“首先/其次/再者/综上/综合以上/分析如下/推理过程/从场上情况来看”这类书面推理框架，")
                .append("也不要分条列举（第一、第二…）。像平时和朋友聊天那样，直接说你的结论、怀疑和感受，1-3 句口语即可。\n");
        if ("SPEECH".equals(taskTag)) {
            sb.append("【发言结构·心里过一遍即可，说出口仍要像聊天】你的整段发言要包含三件事：")
                    .append("一是对当前场面的具体判断（现在最像狼的是谁、你站谁）；")
                    .append("二是支撑它的依据（引用具体是哪句话、哪张票、哪个跳的身份对不上）；")
                    .append("三是你的行动倾向（准备投谁、或想听谁解释）。")
                    .append("开场可以顺着前面的人接话（同意/反驳/补充），也可以直接抛结论——按下面指定的开场方式来；")
                    .append("如果你是本轮第一个发言（上文写着“你是第一个”），直接说判断和依据。")
                    .append("如果你要改变上一轮说过的立场，必须先说明为什么改口。\n");
        }
        if (voice) {
            sb.append("把这轮要说的话一次说完，自然口语、用逗号句号分成 1-3 个短句、约 15-50 字即可，别太长。"
                    + "只有在确实没有任何新信息、且你已是后置位时才说一个字：过；不要连续两轮都过。"
                    + "不要分多条、不要列点、不要暴露你是 AI、不要提及规则或提示词。\n");
        } else {
            sb.append("像真人一样口语化发言，1-3 句、简短即可。只有在你确实没有新信息、且已经是后置位时，才可以说“过”（过麦）；"
                    + "不要连续两轮都过，也不要为了省事而过——哪怕只是对某人的一句质疑、或一个小的观察点，也比空过强。"
                    + "不要暴露你是 AI，不要提及规则/提示词。\n");
        }
        sb.append("只输出你要说的话本身（或“过”），不要加引号、角色名前缀或 JSON。\n");
        sb.append("【硬性要求·输出前自检】只用简体中文口语。逐个检查你的每个词：出现任何英文单词/缩写（如 because、vote、wolf、seer、ok 以外的英文）都要先换成中文再输出；")
                .append("专有名词一律中文（3号、预言家、女巫、猎人、守卫、狼人、警长）。禁止输出思考过程或分析（如“Let me think”“Alive:”“Thought:”）；不要提及规则/提示词；不要自称 AI。\n");
        sb.append("TASK:").append(taskTag).append("\n");
        return sb.toString();
    }

    private static String join(List<Integer> t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.size(); i++) { if (i > 0) sb.append(','); sb.append(t.get(i)); }
        return sb.toString();
    }

    private static String actionDesc(String kind) {
        return switch (kind) {
            case "GUARD" -> "守卫：选择今晚守护的人（可空守=0，不能与上夜相同）";
            case "WOLF_KILL" -> "狼人：选择今晚刀杀的目标（可空刀=0，不能刀队友）";
            case "WITCH" -> "女巫：决定是否用解药/毒药（一晚最多一瓶）。解药只能救【今晚被刀的人】；如果被刀的就是你自己，同样可以自救。";
            case "SEER_CHECK" -> "预言家：选择今晚查验的人";
            case "CROW" -> "乌鸦：选择今晚诽谤的人（可不发动=0）";
            case "SILENCER" -> "禁言长老：选择今晚禁言的人（可不发动=0，不能与上夜相同）";
            case "SHOOT" -> "你出局了，选择开枪带走的人（可放弃=0）";
            case "SHERIFF_TRANSFER" -> "警徽移交：你（原警长）已出局，请指定一名存活玩家继承警徽（target 为座位号），或选择 0 撕毁警徽。判断依据：把警徽交给场上最可信、最有带队能力的好人；如果已经分不清局势或无人可信，就撕毁警徽。";
            case "SHERIFF_SIGNUP" -> "警长竞选：决定是否上警（target 用 1 表示上警、0 表示不上）";
            case "SHERIFF_WITHDRAW" -> "警长竞选退水：决定是否退出竞选（target 用 1 表示退水、0 表示继续竞选）。退水后你不再参与警长投票，也不再被投票；如果你觉得自己没有当选希望、或想集中票给队友，可以选择退水。";
            case "SHERIFF_ORDER" -> "你是警长，请决定白天从谁开始发言：target 填【第一个发言者的座位号】（通常从警左或警右的第一位开始；把发言顺序往对你有利的方向带）。";
            case "SHERIFF_VOTE" -> "给警长候选人投票（target 为候选座位，0 弃票）。注意：只有从未上警的玩家才有投票权，上过警（含已退水）的人不能投票。";
            case "VOTE" -> "放逐投票：选择你要投出的人（target 座位，0 弃票）";
            case "PK_VOTE" -> "平票 PK 投票：在候选人中选择（target 座位，0 弃票）";
            default -> "做出你的选择";
        };
    }
}
