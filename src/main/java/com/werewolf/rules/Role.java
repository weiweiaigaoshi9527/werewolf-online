package com.werewolf.rules;

/** 11 种角色（标准扩展集）。 */
public enum Role {
    WEREWOLF("狼人", Faction.WOLF),
    WOLF_KING("狼王", Faction.WOLF),
    WHITE_WOLF_KING("白狼王", Faction.WOLF),
    VILLAGER("村民", Faction.VILLAGER),
    SEER("预言家", Faction.GOD),
    WITCH("女巫", Faction.GOD),
    HUNTER("猎人", Faction.GOD),
    GUARD("守卫", Faction.GOD),
    IDIOT("白痴", Faction.GOD),
    CROW("乌鸦", Faction.GOD),
    SILENCER("禁言长老", Faction.GOD);

    private final String cnName;
    private final Faction faction;

    Role(String cnName, Faction faction) {
        this.cnName = cnName;
        this.faction = faction;
    }

    public String cnName() { return cnName; }
    public Faction faction() { return faction; }
    public boolean isWolf() { return faction == Faction.WOLF; }
    public boolean isGod() { return faction == Faction.GOD; }
    public boolean isVillager() { return faction == Faction.VILLAGER; }

    /** 夜晚是否有主动行动（决定行动顺序） */
    public boolean hasNightAction() {
        return this == GUARD || this == WEREWOLF || this == WOLF_KING || this == WHITE_WOLF_KING
                || this == WITCH || this == SEER || this == CROW || this == SILENCER;
    }
}
