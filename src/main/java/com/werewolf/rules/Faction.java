package com.werewolf.rules;

/** 三大阵营：狼人、村民（民）、神职（神）。好人 = 村民 + 神职。 */
public enum Faction {
    WOLF, VILLAGER, GOD;

    public boolean isGood() { return this != WOLF; }
}
