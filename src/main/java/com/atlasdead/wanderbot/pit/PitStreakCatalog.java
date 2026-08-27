package com.atlasdead.wanderbot.pit;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Complete Pit killstreak/megastreak catalogue used by the control layer. */
public final class PitStreakCatalog {
    private PitStreakCatalog() { }

    public enum Category { KILLSTREAK, MEGASTREAK, PASSIVE }

    public static final class Definition {
        public final String id;
        public final String displayName;
        public final Category category;
        public final int triggerKills;
        public final int unlockLevel;
        public final int slotCost;
        public final int effectRank;
        public final int riskRank;
        public final int deathReward;
        public final String specialRule;

        public Definition(String id, String displayName, Category category,
                          int triggerKills, int unlockLevel, int slotCost) {
            this(id, displayName, category, triggerKills, unlockLevel, slotCost, 0, 0, 0, "");
        }

        public Definition(String id, String displayName, Category category,
                          int triggerKills, int unlockLevel, int slotCost,
                          int effectRank, int riskRank, int deathReward, String specialRule) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.triggerKills = triggerKills;
            this.unlockLevel = unlockLevel;
            this.slotCost = slotCost;
            this.effectRank = effectRank;
            this.riskRank = riskRank;
            this.deathReward = deathReward;
            this.specialRule = specialRule == null ? "" : specialRule;
        }
    }
    public static final Definition SECOND_GAPPLE = new Definition("second_gapple", "Second Gapple", Category.KILLSTREAK, 3, 10, 1);
    public static final Definition EXPLICIOUS = new Definition("explicious", "Explicious", Category.KILLSTREAK, 3, 20, 1);
    public static final Definition ARQUEBUSIER = new Definition("arquebusier", "Arquebusier", Category.KILLSTREAK, 3, 50, 1);
    public static final Definition FIGHT_OR_FLIGHT = new Definition("fight_or_flight", "Fight or Flight", Category.KILLSTREAK, 5, 50, 1);
    public static final Definition HEROS_HASTE = new Definition("heros_haste", "Hero's Haste", Category.KILLSTREAK, 5, 100, 1);
    public static final Definition FEAST = new Definition("feast", "Feast", Category.KILLSTREAK, 7, 30, 1);
    public static final Definition COUNTER_STRIKE = new Definition("counter_strike", "Counter-Strike", Category.KILLSTREAK, 7, 40, 1);
    public static final Definition SPONGESTEVE = new Definition("spongesteve", "Spongesteve", Category.KILLSTREAK, 25, 70, 1);

    public static final Definition RR = new Definition("r_and_r", "R&R", Category.KILLSTREAK, 3, 40, 1);
    public static final Definition TOUGH_SKIN = new Definition("tough_skin", "Tough Skin", Category.KILLSTREAK, 5, 30, 1);
    public static final Definition TACTICAL_RETREAT = new Definition("tactical_retreat", "Tactical Retreat", Category.KILLSTREAK, 7, 50, 1);
    public static final Definition MONSTER = new Definition("monster", "Monster", Category.KILLSTREAK, 25, 40, 1);

    public static final Definition PUNGENT = new Definition("pungent", "Pungent", Category.KILLSTREAK, 7, 50, 1);
    public static final Definition GLASS_PICKAXE = new Definition("glass_pickaxe", "Glass Pickaxe", Category.KILLSTREAK, 7, 60, 1);
    public static final Definition AURA_OF_PROTECTION = new Definition("aura_of_protection", "Aura of Protection", Category.KILLSTREAK, 10, 50, 1);
    public static final Definition ICE_CUBE = new Definition("ice_cube", "Ice Cube", Category.KILLSTREAK, 10, 60, 1);

    public static final Definition KHANATE = new Definition("khanate", "Khanate", Category.KILLSTREAK, 3, 60, 1);
    public static final Definition RUSH = new Definition("rush", "Rush", Category.KILLSTREAK, 5, 110, 1);
    public static final Definition GOLD_NANO_FACTORY = new Definition("gold_nano_factory", "Gold Nano-factory", Category.KILLSTREAK, 7, 50, 1);

    public static final Definition LEECH = new Definition("leech", "Leech", Category.KILLSTREAK, 3, 70, 1);
    public static final Definition ASSURED_STRIKE = new Definition("assured_strike", "Assured Strike", Category.KILLSTREAK, 7, 80, 1);
    public static final Definition APOSTLE_TO_RNGESUS = new Definition("apostle_to_rngesus", "Apostle to RNGesus", Category.KILLSTREAK, 25, 100, 1);

    public static final Definition SUPER_STREAKER = new Definition("super_streaker", "Super Streaker", Category.KILLSTREAK, 10, 80, 1);
    public static final Definition GOLD_STACK = new Definition("gold_stack", "Gold Stack", Category.PASSIVE, 10, 90, 0);
    public static final Definition XP_STACK = new Definition("xp_stack", "XP Stack", Category.PASSIVE, 10, 90, 0);

    public static final Definition OVERDRIVE = new Definition("overdrive", "Overdrive", Category.MEGASTREAK, 50, 10, 1, 0, 0, 0, "Speed I; +50% XP from kills; +100% gold from kills; incoming true-damage scaling above 50");
    public static final Definition BEASTMODE = new Definition("beastmode", "Beastmode", Category.MEGASTREAK, 50, 30, 1, 3, 10, 10000, "Diamond Helmet; +25% damage; +50% XP from kills; +75% gold from kills; scaling incoming damage above 50");
    public static final Definition HERMIT = new Definition("hermit", "Hermit", Category.MEGASTREAK, 50, 50, 1, 4, 20, 20000, "Resistance I; true-damage immunity with Gamble exception; bedrock rewards; scaling gold/XP; pre-trigger Slowness I");
    public static final Definition HIGHLANDER = new Definition("highlander", "Highlander", Category.MEGASTREAK, 50, 60, 1, 7, 40, 30000, "Speed I; +110% gold from kills; +33% damage to bountied players; bounty-related death reward");
    public static final Definition MAGNUM_OPUS = new Definition("magnum_opus", "Magnum Opus", Category.MEGASTREAK, 50, 70, 1, 10, 60, 40000, "Triggers at 50; immediately dies; grants renown and repairs Mystic Well item lives");
    public static final Definition TO_THE_MOON = new Definition("to_the_moon", "To the Moon", Category.MEGASTREAK, 100, 80, 1, 14, 150, 50000, "+20% XP; +100 max XP per kill; escalating incoming damage and true-damage pressure at higher streaks");
    public static final Definition UBERSTREAK = new Definition("uberstreak", "Uberstreak", Category.MEGASTREAK, 100, 90, 1, 20, 50, 50000, "+50% mystic find chance; progressively harsher penalties at 100/200/300/400 kills; Uberdrop reward at 400+");

    public static final List<Definition> ALL_KILLSTREAKS = Collections.unmodifiableList(Arrays.asList(
            SECOND_GAPPLE, EXPLICIOUS, ARQUEBUSIER, FIGHT_OR_FLIGHT, HEROS_HASTE, FEAST,
            COUNTER_STRIKE, SPONGESTEVE, RR, TOUGH_SKIN, TACTICAL_RETREAT, MONSTER,
            PUNGENT, GLASS_PICKAXE, AURA_OF_PROTECTION, ICE_CUBE, KHANATE, RUSH,
            GOLD_NANO_FACTORY, LEECH, ASSURED_STRIKE, APOSTLE_TO_RNGESUS, SUPER_STREAKER,
            GOLD_STACK, XP_STACK
    ));

    public static final List<Definition> ALL_MEGASTREAKS = Collections.unmodifiableList(Arrays.asList(
            OVERDRIVE, BEASTMODE, HERMIT, HIGHLANDER, MAGNUM_OPUS, TO_THE_MOON, UBERSTREAK
    ));

    public static Definition byId(String id) {
        if (id == null) return null;
        for (Definition definition : ALL_KILLSTREAKS) if (definition.id.equalsIgnoreCase(id)) return definition;
        for (Definition definition : ALL_MEGASTREAKS) if (definition.id.equalsIgnoreCase(id)) return definition;
        return null;
    }
}
