package com.werewolf.rules;

import java.util.LinkedHashMap;
import java.util.Map;

/** 板子配置：角色 -> 数量。 */
public class BoardConfig {

    private final Map<Role, Integer> counts = new LinkedHashMap<>();

    public BoardConfig put(Role role, int count) {
        if (count < 0) throw new IllegalArgumentException("角色数量不能为负: " + role);
        if (count == 0) { counts.remove(role); return this; }
        if (role != Role.WEREWOLF && role != Role.VILLAGER && count > 1) {
            throw new IllegalArgumentException("角色 " + role + " 每种至多 1 名");
        }
        counts.put(role, count);
        return this;
    }

    public int countOf(Role role) { return counts.getOrDefault(role, 0); }
    public Map<Role, Integer> counts() { return Map.copyOf(counts); }
    public int total() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    public int wolfCount() { return counts.entrySet().stream().filter(e -> e.getKey().isWolf()).mapToInt(Map.Entry::getValue).sum(); }
    public int godCount() { return counts.entrySet().stream().filter(e -> e.getKey().isGod()).mapToInt(Map.Entry::getValue).sum(); }
    public int villagerCount() { return counts.getOrDefault(Role.VILLAGER, 0); }

    public boolean isEmpty() { return counts.isEmpty(); }

    public static BoardConfig preset12() {
        // 经典 12 人预女猎白：4 狼 4 民 预言家 女巫 猎人 白痴
        return new BoardConfig()
                .put(Role.WEREWOLF, 4)
                .put(Role.VILLAGER, 4)
                .put(Role.SEER, 1).put(Role.WITCH, 1).put(Role.HUNTER, 1).put(Role.IDIOT, 1);
    }

    public static BoardConfig preset9() {
        // 9 人标准：3 狼 3 民 预女猎
        return new BoardConfig()
                .put(Role.WEREWOLF, 3)
                .put(Role.VILLAGER, 3)
                .put(Role.SEER, 1).put(Role.WITCH, 1).put(Role.HUNTER, 1);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        counts.forEach((r, c) -> sb.append(r.cnName()).append("x").append(c).append(" "));
        return sb.append("}").toString();
    }
}
