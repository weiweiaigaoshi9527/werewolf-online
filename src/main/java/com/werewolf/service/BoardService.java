package com.werewolf.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.werewolf.rules.BoardConfig;
import com.werewolf.rules.BoardValidator;
import com.werewolf.rules.Faction;
import com.werewolf.rules.Role;
import org.springframework.stereotype.Service;

import java.util.*;

/** 板子系统：预设板子、角色元数据、合法性校验、与 Room 存储用的 JSON 互转。 */
@Service
public class BoardService {

    private final ObjectMapper mapper = new ObjectMapper();

    public record Preset(String id, String name, String desc, Map<String, Integer> counts) {}
    public record RoleMeta(String role, String cnName, String faction, int max, String desc) {}

    /** 各身份的技能/玩法说明（供前端“身份说明”展示）。 */
    public static final Map<String, String> ROLE_DESC = Map.ofEntries(
            Map.entry("WEREWOLF", "狼人：每晚与狼队商议刀杀一名玩家；白天伪装好人、投票放逐好人。狼全灭好人或屠边即胜。"),
            Map.entry("WOLF_KING", "狼王：狼人的一种，被放逐或开枪时可带走一名玩家（被毒则不能开枪）。"),
            Map.entry("WHITE_WOLF_KING", "白狼王：狼人的一种，白天可自爆并立即带走一名玩家，随后进入黑夜。"),
            Map.entry("SEER", "预言家：每晚查验一名玩家的阵营（好人/狼人）。是好人的核心信息来源。"),
            Map.entry("WITCH", "女巫：全局各一瓶解药与毒药。解药救当晚被刀者，毒药可毒杀一人（同一晚最多用一瓶）。"),
            Map.entry("HUNTER", "猎人：出局时（非被毒）可开枪带走一名玩家。"),
            Map.entry("GUARD", "守卫：每晚守护一名玩家使其免疫狼刀，不能连续两晚守同一人。同守同救会导致目标死亡（奶穿）。"),
            Map.entry("IDIOT", "白痴：被放逐投票选中时可翻牌亮身份免死，但此后失去投票权、保留发言。"),
            Map.entry("CROW", "乌鸦：每晚可诅咒一名玩家，使其当天的放逐投票多算一票（可不发动）。"),
            Map.entry("SILENCER", "禁言长老：每晚可禁言一名玩家，使其白天无法发言与语音（不能连续两晚同一人，可不发动）。"),
            Map.entry("VILLAGER", "村民：没有技能，依靠分析发言、逻辑与投票找出狼人，保护神职。")
    );

    /** 角色元数据（供前端渲染自定义面板）：狼/民可多，其余神职/特殊每种≤1。 */
    public List<RoleMeta> roleMeta() {
        List<RoleMeta> list = new ArrayList<>();
        for (Role r : Role.values()) {
            int max;
            if (r == Role.WEREWOLF) max = 6;              // 普通狼上限（狼队合计还受 BoardValidator.MAX_WOLVES 约束）
            else if (r == Role.VILLAGER) max = 16;        // 平民可大量填充，支撑大板人数
            else max = 1;
            list.add(new RoleMeta(r.name(), r.cnName(), r.faction().name(), max, ROLE_DESC.getOrDefault(r.name(), "")));
        }
        return list;
    }

    /** 预设板子：覆盖 6~24 人的常见配置。 */
    public List<Preset> presets() {
        List<Preset> p = new ArrayList<>();
        p.add(new Preset("beginner6", "6人新手局", "2狼 2民 预言家 女巫", map(
                Role.WEREWOLF, 2, Role.VILLAGER, 2, Role.SEER, 1, Role.WITCH, 1)));
        p.add(new Preset("seven7", "7人进阶局", "2狼 2民 预女猎", map(
                Role.WEREWOLF, 2, Role.VILLAGER, 2, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1)));
        p.add(new Preset("eight8", "8人快节奏", "3狼 2民 预女猎", map(
                Role.WEREWOLF, 3, Role.VILLAGER, 2, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1)));
        p.add(new Preset("standard9", "9人标准局", "3狼 3民 预女猎", map(
                Role.WEREWOLF, 3, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1)));
        p.add(new Preset("guard10", "10人守卫局", "3狼 3民 预女猎守", map(
                Role.WEREWOLF, 3, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1)));
        p.add(new Preset("eleven11", "11人狼鸦局", "3狼 4民 预女猎守 + 乌鸦", map(
                Role.WEREWOLF, 3, Role.VILLAGER, 4, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.CROW, 1)));
        p.add(new Preset("classic12", "12人预女猎白", "4狼 4民 预女猎白痴", map(
                Role.WEREWOLF, 4, Role.VILLAGER, 4, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.IDIOT, 1)));
        p.add(new Preset("gods12", "12人全神板", "4狼 3民 预女猎守白", map(
                Role.WEREWOLF, 4, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1)));
        p.add(new Preset("thirteen13", "13人守卫局", "4狼 4民 预女猎守白", map(
                Role.WEREWOLF, 4, Role.VILLAGER, 4, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1)));
        p.add(new Preset("fourteen14", "14人禁言局", "4狼 5民 预女猎守白 + 禁言长老", map(
                Role.WEREWOLF, 4, Role.VILLAGER, 5, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1, Role.SILENCER, 1)));
        p.add(new Preset("wolfking15", "15人狼王局", "4狼+狼王 5民 预女猎守白", map(
                Role.WEREWOLF, 4, Role.WOLF_KING, 1, Role.VILLAGER, 5, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1)));
        p.add(new Preset("sixteen16", "16人狼鸦局", "5狼 5民 预女猎守白 + 乌鸦", map(
                Role.WEREWOLF, 5, Role.VILLAGER, 5, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1, Role.CROW, 1)));
        p.add(new Preset("eighteen18", "18人神民局", "5狼 6民 预女猎守白鸦禁", map(
                Role.WEREWOLF, 5, Role.VILLAGER, 6, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1, Role.CROW, 1, Role.SILENCER, 1)));
        p.add(new Preset("twenty20", "20人大乱斗", "6狼 6民 预女猎守白鸦禁", map(
                Role.WEREWOLF, 6, Role.VILLAGER, 6, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1, Role.CROW, 1, Role.SILENCER, 1)));
        p.add(new Preset("twentyfour24", "24人超巨局", "6狼 11民 预女猎守白鸦禁", map(
                Role.WEREWOLF, 6, Role.VILLAGER, 11, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1, Role.IDIOT, 1, Role.CROW, 1, Role.SILENCER, 1)));
        // ===== 扩充板子（特色阵容）=====
        p.add(new Preset("wolfking9", "9人狼王局", "2狼+狼王 3民 预女猎", map(
                Role.WEREWOLF, 2, Role.WOLF_KING, 1, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1)));
        p.add(new Preset("fourgods10", "10人四神局", "3狼 3民 预女守白", map(
                Role.WEREWOLF, 3, Role.VILLAGER, 3, Role.SEER, 1, Role.WITCH, 1, Role.GUARD, 1, Role.IDIOT, 1)));
        p.add(new Preset("crow12", "12人乌鸦禁言局", "4狼 4民 预女鸦禁", map(
                Role.WEREWOLF, 4, Role.VILLAGER, 4, Role.SEER, 1, Role.WITCH, 1, Role.CROW, 1, Role.SILENCER, 1)));
        p.add(new Preset("whiteking14", "14人白狼王局", "4狼+白狼王 5民 预女猎守", map(
                Role.WEREWOLF, 4, Role.WHITE_WOLF_KING, 1, Role.VILLAGER, 5, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1)));
        p.add(new Preset("hunting16", "16人猎守大局", "5狼 7民 预女猎守", map(
                Role.WEREWOLF, 5, Role.VILLAGER, 7, Role.SEER, 1, Role.WITCH, 1, Role.HUNTER, 1, Role.GUARD, 1)));
        return p;
    }

    /** 校验自定义/预设板子，返回 null=合法。 */
    public String validate(Map<String, Integer> counts) {
        try {
            BoardConfig b = toBoard(counts);
            return BoardValidator.validate(b);
        } catch (Exception e) {
            return "板子配置非法：" + e.getMessage();
        }
    }

    public BoardConfig toBoard(Map<String, Integer> counts) {
        BoardConfig b = new BoardConfig();
        for (var e : counts.entrySet()) {
            int c = e.getValue();
            if (c > 0) b.put(Role.valueOf(e.getKey()), c);
        }
        return b;
    }

    public int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public String serialize(Map<String, Integer> counts) {
        try { return mapper.writeValueAsString(counts); } catch (Exception e) { return null; }
    }

    /** 解析 Room 存的板子 JSON；空/异常返回 null。 */
    public Map<String, Integer> parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            Map<String, Integer> m = mapper.readValue(json, new TypeReference<LinkedHashMap<String, Integer>>() {});
            return m.isEmpty() ? null : m;
        } catch (Exception e) {
            return null;
        }
    }

    /** 交替传入 Role, count 构造有序 map。 */
    private Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(((Role) kv[i]).name(), (Integer) kv[i + 1]);
        }
        return m;
    }
}
