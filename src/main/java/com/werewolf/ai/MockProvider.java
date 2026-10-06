package com.werewolf.ai;

import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线 Mock Provider（baseUrl 以 mock:// 开头时启用）。
 * 用于在无外网/无 key 环境下端到端验证 AI 异步链路、提示词解析与 JSON 动作解析。
 * 它读取提示词中的 ACTION_NAME / LEGAL_TARGETS 标记，返回合法动作 JSON 或拟真发言。
 */
public class MockProvider implements AiProvider {

    private final Random rnd = new Random();
    private static final String[] SPEECHES = {
            "我是好人，昨晚信息不多，先听后置位。",
            "前面那位跳得有点急，我保留一点怀疑。",
            "我这边没什么信息，过。",
            "建议警徽给到验人清晰的预言家。",
            "这个发言逻辑不太通，我倾向于再听一轮。",
            "我是平民，没有技能，只能靠投票。",
    };
    private static final String[] LAST_WORDS = {
            "唉，我是真预言家，你们被我骗了……不对，我是好人，走了。",
            "我是平民，倒牌了，大家加油。",
    };

    @Override
    public String chat(List<Message> messages, AiOptions o) throws Exception {
        // 模拟思考延迟
        Thread.sleep(150 + rnd.nextInt(300));
        String user = messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        if (user.contains("TASK:SPEECH") || user.contains("TASK:LAST_WORDS")) {
            String[] pool = user.contains("TASK:LAST_WORDS") ? LAST_WORDS : SPEECHES;
            return pool[rnd.nextInt(pool.length)];
        }
        // 动作任务：解析 ACTION_NAME 与 LEGAL_TARGETS
        Matcher an = Pattern.compile("ACTION_NAME:\\s*(\\w+)").matcher(user);
        String action = an.find() ? an.group(1) : "PASS";
        Matcher lt = Pattern.compile("LEGAL_TARGETS:\\s*([0-9,\\s]+)").matcher(user);
        int target = 0;
        if (lt.find()) {
            String[] parts = lt.group(1).split(",");
            java.util.List<Integer> opts = new java.util.ArrayList<>();
            for (String p : parts) { p = p.trim(); if (!p.isEmpty()) opts.add(Integer.parseInt(p)); }
            if (!opts.isEmpty()) target = opts.get(rnd.nextInt(opts.size()));
        }
        return "{\"action\":\"" + action + "\",\"target\":" + target + ",\"reason\":\"mock 决策\"}";
    }
}
