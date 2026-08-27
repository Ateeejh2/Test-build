package com.atlasdead.wanderbot.pit;

/**
 * Immutable per-tick Pit state snapshot. Keeping one snapshot per tick prevents
 * different consumers from observing different rule/streak values in the same frame.
 */
public final class PitRuntimeSnapshot {
    public final long tick;
    public final PitMode mode;
    public final PitStateDetector.Result scoreboard;
    public final PitEventDetector.Result event;
    public final PitRulesEngine.State rules;
    public final int streak;
    public final PitStreakCatalog.Definition megastreak;
    public final PitMapIntelligence.Zone zone;
    public final double mapHazard;
    public final double openness;
    public final boolean targetAvailable;
    public final String targetName;

    public PitRuntimeSnapshot(long tick,
                              PitMode mode,
                              PitStateDetector.Result scoreboard,
                              PitEventDetector.Result event,
                              PitRulesEngine.State rules,
                              int streak,
                              PitStreakCatalog.Definition megastreak,
                              PitMapIntelligence.Zone zone,
                              double mapHazard,
                              double openness,
                              boolean targetAvailable,
                              String targetName) {
        this.tick = tick;
        this.mode = mode;
        this.scoreboard = scoreboard;
        this.event = event;
        this.rules = rules;
        this.streak = streak;
        this.megastreak = megastreak;
        this.zone = zone;
        this.mapHazard = mapHazard;
        this.openness = openness;
        this.targetAvailable = targetAvailable;
        this.targetName = targetName;
    }

    public static PitRuntimeSnapshot empty() {
        return new PitRuntimeSnapshot(0L, PitMode.OFF, null, PitEventDetector.Result.none(), null,
                0, null, PitMapIntelligence.Zone.UNKNOWN, 0.0D, 0.0D, false, null);
    }
}
