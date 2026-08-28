package com.atlasdead.wanderbot.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Persisted runtime settings shared by the ClickGUI and bot controllers. */
public final class WanderBotSettings {
    private WanderBotSettings() {}

    public static boolean showPath = true;
    public static boolean showTarget = true;
    public static boolean showTargetGuide = true;
    public static boolean showHud = true;
    public static boolean combatEnabled = true;
    public static boolean navigationEnabled = true;
    public static boolean megastreakStrategy = true;

    public static double combatRange = 3.0D;
    public static double targetScanRange = 30.0D;
    public static double retreatHealth = 0.30D;
    public static int crowdThreshold = 3;
    public static int navRepathTicks = 8;
    public static double lookaheadDistance = 3.5D;
    public static boolean debugDashboard = true;
    public static String megastreakId = "overdrive";

    // Combat Phase Controller
    public static double escapeEvalRadius = 20.0D;
    public static int escapeCandidateCount = 12;
    public static double chaserDetectArc = 180.0D;
    public static double chaserMinDistance = 5.0D;
    public static double chaserMaxDistance = 15.0D;
    public static double chaserDotThreshold = 0.7D;
    public static int chaserPersistence = 5;
    public static double bowMinDistance = 8.0D;
    public static double bowMaxDistance = 30.0D;
    public static double bowMinHealth = 0.4D;
    public static int recoverDelay = 10;
    public static double threatThreshold = 80.0D;

    // Humanization
    public static boolean forcePitMode = false;
    public static boolean humanizationEnabled = true;
    public static float aimErrorSD = 2.5F;
    public static float rotationSpeedSD = 0.15F;
    public static float overshootChance = 12.0F;
    public static float distractionChance = 3.0F;
    public static float attackHesitationChance = 8.0F;
    public static float attackMissChance = 5.0F;
    public static float sprintToggleChance = 6.0F;
    public static float microPauseChance = 4.0F;
    public static float strafeChance = 15.0F;

    private static Configuration config;

    public static void load(File configDir) {
        if (configDir == null) return;
        config = new Configuration(new File(configDir, "wanderbot.cfg"));
        config.load();
        showPath = config.getBoolean("showPath", "render", showPath, "Render pathfinding.");
        showTarget = config.getBoolean("showTarget", "render", showTarget, "Render target box.");
        showTargetGuide = config.getBoolean("showTargetGuide", "render", showTargetGuide, "Render bot to target guide.");
        showHud = config.getBoolean("showHud", "render", showHud, "Render HUD.");
        debugDashboard = config.getBoolean("debugDashboard", "render", debugDashboard, "Render detailed debug dashboard.");
        combatEnabled = config.getBoolean("combatEnabled", "bot", combatEnabled, "Enable combat controller.");
        navigationEnabled = config.getBoolean("navigationEnabled", "bot", navigationEnabled, "Enable navigation controller.");
        megastreakStrategy = config.getBoolean("megastreakStrategy", "bot", megastreakStrategy, "Enable megastreak strategy weighting.");
        combatRange = config.getFloat("combatRange", "combat", (float) combatRange, 2.5F, 4.0F, "Preferred attack range.");
        targetScanRange = config.getFloat("targetScanRange", "combat", (float) targetScanRange, 8.0F, 32.0F, "Target acquisition range.");
        retreatHealth = config.getFloat("retreatHealth", "combat", (float) retreatHealth, 0.10F, 0.60F, "Self-health retreat threshold ratio.");
        crowdThreshold = config.getInt("crowdThreshold", "combat", crowdThreshold, 1, 8, "Nearby eligible players that count as crowd pressure.");
        navRepathTicks = config.getInt("navRepathTicks", "navigation", navRepathTicks, 2, 40, "Navigation replan cadence.");
        lookaheadDistance = config.getFloat("lookaheadDistance", "navigation", (float) lookaheadDistance, 1.0F, 8.0F, "Navigation lookahead distance.");
        megastreakId = config.getString("megastreakId", "pit", megastreakId, "Selected megastreak id.");
        escapeEvalRadius = config.getFloat("escapeEvalRadius", "combat", (float) escapeEvalRadius, 8.0F, 40.0F, "Escape candidate evaluation radius.");
        escapeCandidateCount = config.getInt("escapeCandidateCount", "combat", escapeCandidateCount, 4, 24, "Number of escape candidates.");
        chaserDetectArc = config.getFloat("chaserDetectArc", "combat", (float) chaserDetectArc, 60.0F, 360.0F, "Chaser detection arc degrees.");
        chaserMinDistance = config.getFloat("chaserMinDistance", "combat", (float) chaserMinDistance, 2.0F, 10.0F, "Minimum chaser detection distance.");
        chaserMaxDistance = config.getFloat("chaserMaxDistance", "combat", (float) chaserMaxDistance, 8.0F, 30.0F, "Maximum chaser detection distance.");
        chaserDotThreshold = config.getFloat("chaserDotThreshold", "combat", (float) chaserDotThreshold, 0.3F, 0.95F, "Chaser approach dot threshold.");
        chaserPersistence = config.getInt("chaserPersistence", "combat", chaserPersistence, 2, 12, "Chaser persistence ticks.");
        bowMinDistance = config.getFloat("bowMinDistance", "combat", (float) bowMinDistance, 4.0F, 15.0F, "Minimum bow distance.");
        bowMaxDistance = config.getFloat("bowMaxDistance", "combat", (float) bowMaxDistance, 15.0F, 50.0F, "Maximum bow distance.");
        bowMinHealth = config.getFloat("bowMinHealth", "combat", (float) bowMinHealth, 0.1F, 0.8F, "Minimum health ratio for bow.");
        recoverDelay = config.getInt("recoverDelay", "combat", recoverDelay, 3, 30, "Recovery delay ticks.");
        threatThreshold = config.getFloat("threatThreshold", "combat", (float) threatThreshold, 20.0F, 150.0F, "Threat score retreat threshold.");
        forcePitMode = config.getBoolean("forcePitMode", "bot", forcePitMode, "Bypass PitMode check for testing.");
        if (config.hasChanged()) config.save();
    }

    public static void save() {
        if (config == null) return;
        config.get("showPath", "render", showPath).set(showPath);
        config.get("showTarget", "render", showTarget).set(showTarget);
        config.get("showTargetGuide", "render", showTargetGuide).set(showTargetGuide);
        config.get("showHud", "render", showHud).set(showHud);
        config.get("debugDashboard", "render", debugDashboard).set(debugDashboard);
        config.get("combatEnabled", "bot", combatEnabled).set(combatEnabled);
        config.get("navigationEnabled", "bot", navigationEnabled).set(navigationEnabled);
        config.get("megastreakStrategy", "bot", megastreakStrategy).set(megastreakStrategy);
        config.get("combat", "combatRange", (float) combatRange).set((float) combatRange);
        config.get("combat", "targetScanRange", (float) targetScanRange).set((float) targetScanRange);
        config.get("combat", "retreatHealth", (float) retreatHealth).set((float) retreatHealth);
        config.get("combat", "crowdThreshold", crowdThreshold).set(crowdThreshold);
        config.get("navigation", "navRepathTicks", navRepathTicks).set(navRepathTicks);
        config.get("navigation", "lookaheadDistance", (float) lookaheadDistance).set((float) lookaheadDistance);
        config.get("pit", "megastreakId", megastreakId).set(megastreakId);
        config.get("combat", "escapeEvalRadius", (float) escapeEvalRadius).set((float) escapeEvalRadius);
        config.get("combat", "escapeCandidateCount", escapeCandidateCount).set(escapeCandidateCount);
        config.get("combat", "chaserDetectArc", (float) chaserDetectArc).set((float) chaserDetectArc);
        config.get("combat", "chaserMinDistance", (float) chaserMinDistance).set((float) chaserMinDistance);
        config.get("combat", "chaserMaxDistance", (float) chaserMaxDistance).set((float) chaserMaxDistance);
        config.get("combat", "chaserDotThreshold", (float) chaserDotThreshold).set((float) chaserDotThreshold);
        config.get("combat", "chaserPersistence", chaserPersistence).set(chaserPersistence);
        config.get("combat", "bowMinDistance", (float) bowMinDistance).set((float) bowMinDistance);
        config.get("combat", "bowMaxDistance", (float) bowMaxDistance).set((float) bowMaxDistance);
        config.get("combat", "bowMinHealth", (float) bowMinHealth).set((float) bowMinHealth);
        config.get("combat", "recoverDelay", recoverDelay).set(recoverDelay);
        config.get("combat", "threatThreshold", (float) threatThreshold).set((float) threatThreshold);
        config.get("bot", "forcePitMode", forcePitMode).set(forcePitMode);
        config.get("humanization", "humanizationEnabled", humanizationEnabled).set(humanizationEnabled);
        config.save();
    }

    public static void resetDefaults() {
        showPath = true;
        showTarget = true;
        showTargetGuide = true;
        showHud = true;
        combatEnabled = true;
        navigationEnabled = true;
        megastreakStrategy = true;
        combatRange = 3.0D;
        targetScanRange = 30.0D;
        retreatHealth = 0.30D;
        crowdThreshold = 3;
        navRepathTicks = 8;
        lookaheadDistance = 3.5D;
        debugDashboard = true;
        megastreakId = "overdrive";
        escapeEvalRadius = 20.0D;
        escapeCandidateCount = 12;
        chaserDetectArc = 180.0D;
        chaserMinDistance = 5.0D;
        chaserMaxDistance = 15.0D;
        chaserDotThreshold = 0.7D;
        chaserPersistence = 5;
        bowMinDistance = 8.0D;
        bowMaxDistance = 30.0D;
        bowMinHealth = 0.4D;
        recoverDelay = 10;
        threatThreshold = 80.0D;
        forcePitMode = false;
        clamp();
        save();
    }

    public static void clamp() {
        combatRange = Math.max(2.5D, Math.min(4.0D, combatRange));
        targetScanRange = Math.max(8.0D, Math.min(32.0D, targetScanRange));
        retreatHealth = Math.max(0.10D, Math.min(0.60D, retreatHealth));
        crowdThreshold = Math.max(1, Math.min(8, crowdThreshold));
        navRepathTicks = Math.max(2, Math.min(40, navRepathTicks));
        lookaheadDistance = Math.max(1.0D, Math.min(8.0D, lookaheadDistance));
        escapeEvalRadius = Math.max(8.0D, Math.min(40.0D, escapeEvalRadius));
        escapeCandidateCount = Math.max(4, Math.min(24, escapeCandidateCount));
        chaserMaxDistance = Math.max(8.0D, Math.min(30.0D, chaserMaxDistance));
        chaserMinDistance = Math.max(2.0D, Math.min(10.0D, chaserMinDistance));
        bowMinDistance = Math.max(4.0D, Math.min(15.0D, bowMinDistance));
        bowMaxDistance = Math.max(15.0D, Math.min(50.0D, bowMaxDistance));
        recoverDelay = Math.max(3, Math.min(30, recoverDelay));
    }
}
