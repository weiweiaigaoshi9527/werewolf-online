package com.werewolf.service;

import com.werewolf.model.ItemDefinition;
import com.werewolf.repo.ItemDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 启动时初始化装饰商品目录（幂等：仅当表为空时插入）。 */
@Component
public class ShopCatalog implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShopCatalog.class);
    private final ItemDefinitionRepository repo;

    public ShopCatalog(ItemDefinitionRepository repo) {
        this.repo = repo;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = 0;
        // 头像（解锁 avatarId 13-16，前端 emoji 库扩展支持）
        n += add("AVATAR", "av_fox", "灵狐", "一只狡黠的月夜灵狐", 150, "13");
        n += add("AVATAR", "av_dragon", "苍龙", "盘踞村庄上空的苍龙", 200, "14");
        n += add("AVATAR", "av_witch", "魔女", "神秘的银月魔女", 250, "15");
        n += add("AVATAR", "av_ghost", "孤魂", "游荡在墓园的孤魂", 200, "16");
        // 头像框
        n += add("FRAME", "fr_candle", "烛光框", "摇曳烛光的暖金边框", 150, "#e8a33d");
        n += add("FRAME", "fr_moon", "银月框", "清冷月光凝成的银边", 300, "#cdd6f4");
        n += add("FRAME", "fr_blood", "血月框", "血月之夜的猩红诅咒之框", 500, "#a4243b");
        n += add("FRAME", "fr_emerald", "翡翠框", "幽潭翡翠凝成的墨绿边框", 420, "#2f9e6f");
        n += add("FRAME", "fr_aurora", "极光框", "流转极光的梦幻彩边", 900, "#7aa2ff");
        n += add("FRAME", "fr_void", "虚空框", "吞噬光线的深渊黑框", 1200, "#12121a");
        // 称号
        n += add("TITLE", "ti_noble", "夜之贵族", "月夜村庄的世袭贵族", 300, "夜之贵族");
        n += add("TITLE", "ti_wolfbane", "狼王克星", "令狼王闻风丧胆", 500, "狼王克星");
        n += add("TITLE", "ti_saint", "十连好人", "连续十局好人不倒", 800, "十连好人");
        n += add("TITLE", "ti_oracle", "神谕者", "仿佛能听见夜晚的低语", 700, "神谕者");
        n += add("TITLE", "ti_shadow", "影舞者", "在月光照不到的地方起舞", 600, "影舞者");
        n += add("TITLE", "ti_lastsupper", "最后的晚餐", "总坐在狼的那一边", 1000, "最后的晚餐");
        // 昵称颜色
        n += add("NICKCOLOR", "nc_purple", "紫罗兰", "神秘的紫色昵称", 250, "#9d8cff");
        n += add("NICKCOLOR", "nc_gold", "鎏金", "闪耀的金色昵称", 350, "#e8a33d");
        n += add("NICKCOLOR", "nc_crimson", "猩红", "如血的猩红昵称", 250, "#e88c9d");
        n += add("NICKCOLOR", "nc_aqua", "碧波", "清透的青碧色昵称", 280, "#5fd6c8");
        n += add("NICKCOLOR", "nc_rose", "绯樱", "娇粉的樱花色昵称", 280, "#ff9ec7");
        // 功能道具（对局内生效，M6）
        n += add("FUNCTION", "fn_reveal", "查杀卡", "开局揭示一名随机玩家的真实阵营（仅自己可见）", 500, "REVEAL");
        n += add("FUNCTION", "fn_shield", "护盾", "免疫本局首次狼刀（不挡毒杀/放逐/枪杀）", 800, "SHIELD");
        n += add("FUNCTION", "fn_silence", "沉默卡", "使一名随机对手首日白天禁言", 300, "SILENCE");
        n += add("FUNCTION", "fn_double", "双倍积分卡", "本局金币与经验翻倍", 200, "DOUBLE");
        n += add("FUNCTION", "fn_doublevote", "铁票", "你本局第一次放逐投票将计为两票", 450, "DOUBLE_VOTE");
        n += add("FUNCTION", "fn_doll", "替死娃娃", "首次被放逐时替你挡下，免死一次", 900, "IMMUNE");
        n += add("FUNCTION", "fn_expdouble", "双倍经验卡", "本局经验翻倍（金币不翻倍）", 160, "EXPDOUBLE");
        n += add("FUNCTION", "fn_shield2", "守护水晶", "免疫本局首次狼刀与首次毒杀", 1200, "SHIELD_FULL");
        n += add("FUNCTION", "fn_revive", "复活卡", "首次死亡时原地复活（夜刀/毒/放逐/枪均可，保留全部权利）", 1000, "REVIVE");
        n += add("FUNCTION", "fn_iron", "铁布衫", "首次被放逐时免疫出局，保留投票权", 600, "IRON");
        // 更多装饰
        n += add("AVATAR", "av_pumpkin", "南瓜灵", "提着南瓜灯的捣蛋鬼", 180, "17");
        n += add("AVATAR", "av_bat", "夜蝠", "盘旋在钟楼顶的夜蝠", 220, "18");
        n += add("AVATAR", "av_owl", "守望枭", "彻夜守望村庄的猫头鹰", 260, "19");
        n += add("AVATAR", "av_skull", "白骨", "从坟地里爬出来的老骨头", 300, "20");
        n += add("TITLE", "ti_butcher", "屠城者", "以数量规则碾过村庄的传说", 1000, "屠城者");
        n += add("TITLE", "ti_dreamer", "造梦师", "编织月夜梦境的人", 900, "造梦师");
        n += add("FRAME", "fr_gold", "鎏金框", "熔金浇铸的华贵边框", 700, "#f0c060");
        n += add("FRAME", "fr_frost", "霜蓝框", "结着寒霜的幽蓝边框", 640, "#6fb7ff");
        n += add("TITLE", "ti_nightking", "夜之王", "月夜之下，唯我独尊", 1500, "夜之王");
        n += add("TITLE", "ti_lone", "孤影", "独来独往的夜行者", 620, "孤影");
        n += add("NICKCOLOR", "nc_neon", "霓虹", "炫目的霓虹粉紫昵称", 400, "#ff6fd8");
        if (n > 0) log.info("商品目录补种完成，新增 {} 件", n);
    }

    /** 按 code 幂等插入：已存在则跳过。返回 1=新增，0=已存在。 */
    private int add(String type, String code, String name, String desc, int price, String asset) {
        if (repo.findByCode(code).isPresent()) return 0;
        ItemDefinition it = new ItemDefinition();
        it.setType(type); it.setCode(code); it.setName(name);
        it.setDescription(desc); it.setPrice(price); it.setAsset(asset);
        repo.save(it);
        return 1;
    }
}
