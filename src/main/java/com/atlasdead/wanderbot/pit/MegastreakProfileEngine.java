package com.atlasdead.wanderbot.pit;

/**
 * High-level strategy profile for the selected Pit megastreak. This class does
 * not trigger the megastreak; it only exposes strategic preferences to combat.
 */
public final class MegastreakProfileEngine {
    public static final class Profile {
        public final String id;
        public final String name;
        public final int threshold;
        public final double riskTolerance;
        public final double targetAggression;
        public final double preserveBias;
        public final double preferredDistanceBias;
        public final boolean wantsHighStreak;
        public final boolean deathTriggered;
        public final boolean escalatingDamage;
        public final boolean specialPreTriggerState;
        public final boolean rewardOnDeath;

        Profile(String id, String name, int threshold, double riskTolerance,
                double targetAggression, double preserveBias, double preferredDistanceBias,
                boolean wantsHighStreak, boolean deathTriggered,
                boolean escalatingDamage, boolean specialPreTriggerState, boolean rewardOnDeath) {
            this.id = id;
            this.name = name;
            this.threshold = threshold;
            this.riskTolerance = riskTolerance;
            this.targetAggression = targetAggression;
            this.preserveBias = preserveBias;
            this.preferredDistanceBias = preferredDistanceBias;
            this.wantsHighStreak = wantsHighStreak;
            this.deathTriggered = deathTriggered;
            this.escalatingDamage = escalatingDamage;
            this.specialPreTriggerState = specialPreTriggerState;
            this.rewardOnDeath = rewardOnDeath;
        }
    }

    private MegastreakProfileEngine() { }

    public static Profile profile(PitStreakCatalog.Definition definition) {
        if (definition == null) return profile("none", "None", 50, 0.50D, 0.50D, 0.50D, 0.0D, false, false, false, false, false);
        String id = definition.id;
        if ("overdrive".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.72D, 0.78D, 0.35D, 0.0D, true, false, true, false, true);
        if ("beastmode".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.78D, 0.82D, 0.30D, -0.05D, true, false, true, false, true);
        if ("hermit".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.55D, 0.48D, 0.62D, 0.15D, true, false, true, true, false);
        if ("highlander".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.48D, 0.52D, 0.72D, 0.18D, true, false, true, false, true);
        if ("magnum_opus".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.42D, 0.40D, 0.90D, 0.22D, true, true, false, false, true);
        if ("to_the_moon".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.58D, 0.58D, 0.64D, 0.12D, true, false, true, false, false);
        if ("uberstreak".equalsIgnoreCase(id)) return profile(id, definition.displayName, definition.triggerKills, 0.52D, 0.60D, 0.76D, 0.16D, true, false, true, false, true);
        return profile(id, definition.displayName, definition.triggerKills, 0.50D, 0.50D, 0.50D, 0.0D, false, false, false, false, false);
    }

    public static double adjustedThreatCost(Profile profile, double rawThreat) {
        if (profile == null) return rawThreat;
        return rawThreat * (1.0D + profile.preserveBias * 0.75D);
    }

    public static double adjustedReward(Profile profile, double rawReward) {
        if (profile == null) return rawReward;
        return rawReward * (0.85D + profile.targetAggression * 0.30D);
    }

    private static Profile profile(String id, String name, int threshold, double riskTolerance,
                                   double targetAggression, double preserveBias, double distanceBias,
                                   boolean wantsHighStreak, boolean deathTriggered,
                boolean escalatingDamage, boolean specialPreTriggerState, boolean rewardOnDeath) {
        return new Profile(id, name, threshold, riskTolerance, targetAggression,
                preserveBias, distanceBias, wantsHighStreak, deathTriggered,
                escalatingDamage, specialPreTriggerState, rewardOnDeath);
    }
}
