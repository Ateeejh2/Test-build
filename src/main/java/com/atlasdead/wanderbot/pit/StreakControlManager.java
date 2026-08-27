package com.atlasdead.wanderbot.pit;

import java.util.List;

/** Controls the selected Pit megastreak only. Regular killstreaks are not bot-controlled. */
public class StreakControlManager {
    private PitStreakCatalog.Definition activeMegastreak = PitStreakCatalog.OVERDRIVE;
    private String lastTriggeredId = "";
    private long lastTriggeredKill;

    public void reset() {
        activeMegastreak = PitStreakCatalog.OVERDRIVE;
        lastTriggeredId = "";
        lastTriggeredKill = 0L;
    }

    public boolean setMegastreak(String id) {
        PitStreakCatalog.Definition definition = PitStreakCatalog.byId(id);
        if (definition == null || definition.category != PitStreakCatalog.Category.MEGASTREAK) return false;
        activeMegastreak = definition;
        return true;
    }

    public boolean setMegastreak(PitStreakCatalog.Definition definition) {
        return definition != null && setMegastreak(definition.id);
    }

    public PitStreakCatalog.Definition getActiveMegastreak() { return activeMegastreak; }

    public List<PitStreakCatalog.Definition> getAvailableMegastreaks() {
        return PitStreakCatalog.ALL_MEGASTREAKS;
    }

    public boolean megastreakReached(int streak) {
        return activeMegastreak != null && streak >= activeMegastreak.triggerKills;
    }

    /** Number of kills remaining before the selected megastreak threshold. */
    public int killsRemaining(int streak) {
        if (activeMegastreak == null) return Integer.MAX_VALUE;
        return Math.max(0, activeMegastreak.triggerKills - Math.max(0, streak));
    }

    public void markTrigger(PitStreakCatalog.Definition definition, long streak) {
        if (definition == null) return;
        lastTriggeredId = definition.id;
        lastTriggeredKill = streak;
    }

    public long getLastTriggeredKill() { return lastTriggeredKill; }
    public String getLastTriggeredId() { return lastTriggeredId; }

    public boolean isMegastreakMode() { return activeMegastreak != null; }

    /** Returns configured regular killstreaks whose trigger threshold is reached. */
    public List<PitStreakCatalog.Definition> evaluateTriggers(int streak) {
        java.util.ArrayList<PitStreakCatalog.Definition> triggered = new java.util.ArrayList<PitStreakCatalog.Definition>();
        int value = Math.max(0, streak);
        for (PitStreakCatalog.Definition definition : PitStreakCatalog.ALL_KILLSTREAKS) {
            if (definition.category == PitStreakCatalog.Category.KILLSTREAK && value >= definition.triggerKills) {
                triggered.add(definition);
            }
        }
        return triggered;
    }

    public String summary() {
        return "megastreak=" + (activeMegastreak == null ? "none" : activeMegastreak.displayName);
    }
}
