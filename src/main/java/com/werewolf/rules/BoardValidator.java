package com.werewolf.rules;

/** 板子合法性校验。 */
public final class BoardValidator {

    public static final int MIN_TOTAL = 6;
    public static final int MAX_TOTAL = 24;
    public static final int MAX_WOLVES = 6;

    private BoardValidator() {}

    /**
     * 返回 null 表示合法，否则返回错误信息。
     * 保持向后兼容：等价于 {@link #validate(BoardConfig, boolean)} 且 huntByBorder=false，
     * 即不启用屠边专属校验。BoardService.validate 等既有调用方继续可用。
     */
    public static String validate(BoardConfig b) {
        return validate(b, false);
    }

    /**
     * 扩展校验：在原有校验基础上追加「屠边玩法」专属约束。
     *
     * @param b           板子配置
     * @param huntByBorder 是否为屠边玩法；只有显式传 true 时才校验神职/平民是否齐备，
     *                     避免影响非屠边板子的合法配置
     * @return null 表示合法，否则返回错误信息（错误文案可直接用于前端展示）
     */
    public static String validate(BoardConfig b, boolean huntByBorder) {
        // ---- 原有通用校验，保持不变 ----
        if (b.isEmpty()) return "板子为空";
        int total = b.total();
        if (total < MIN_TOTAL || total > MAX_TOTAL) {
            return "总人数需为 " + MIN_TOTAL + "-" + MAX_TOTAL + "，当前 " + total;
        }
        int wolves = b.wolfCount();
        if (wolves < 1) return "至少需要 1 名狼人";
        if (wolves > MAX_WOLVES) return "狼人至多 " + MAX_WOLVES + " 名";
        int good = total - wolves;
        if (wolves >= good) return "狼人数必须小于好人数（平衡性）";

        // ---- 屠边玩法专属校验 ----
        // 屠边胜负判定依赖「神全灭或民全灭」，若板子开局就缺少神职或平民，
        // 会导致该阵营在开局即被判为“已被屠尽”而直接判狼胜，故必须拦在板子校验阶段。
        if (huntByBorder) {
            if (b.godCount() == 0) return "屠边玩法至少需要 1 名神职";
            if (b.villagerCount() == 0) return "屠边玩法至少需要 1 名平民";
        }
        return null;
    }

    /** 不启用屠边专属校验的合法性判断（向后兼容）。 */
    public static boolean isValid(BoardConfig b) {
        return validate(b) == null;
    }
}
