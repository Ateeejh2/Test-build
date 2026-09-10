package com.atlasdead.wanderbot.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Persisted runtime settings shared by the ClickGUI and bot controllers. */
public final class WanderBotSettings {
    private static final String CAT_RENDER = "render";
    private static final String CAT_BOT = "bot";
    private static final String CAT_COMBAT = "combat";
    private static final String CAT_NAVIGATION = "navigation";
    private static final String CAT_PIT = "pit";
    private static final String CAT_KILLAURA = "killaura";
    private static final String CAT_HUMANIZATION = "humanization";

    private WanderBotSettings() {}

    public static boolean showPath = true;
    public static boolean showTarget = true;
    public static boolean showTargetGuide = true;
    public static boolean showHud = true;
    public static boolean combatEnabled = true;
    public static boolean navigationEnabled = true;
    public static boolean megastreakStrategy = true;

    public static double combatRange = 3.0D;
    /** Full loaded-player scan. The UI/config accepts this as a large practical maximum. */
    public static double targetScanRange = 1000000.0D;
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

    // KillAura settings (Myau pattern)
    public static double killAuraAttackRange = 3.0D;
    public static double killAuraSwingRange = 3.5D;
    public static int killAuraMinCPS = 12;
    public static int killAuraMaxCPS = 14;
    public static int killAuraRotationMode = 2; // 0=NONE, 1=LEGIT, 2=SILENT, 3=LOCK_VIEW
    public static int killAuraMoveFixMode = 1;  // 0=NONE, 1=SILENT, 2=STRICT
    public static double killAuraSmoothing = 0.0D;
    public static boolean killAuraThroughWalls = true;
    public static int killAuraFOV = 360;

    // Humanization
    public static boolean forcePitMode = true;
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

        loadRenderSettings();
        loadBotSettings();
        loadCombatSettings();
        loadNavigationSettings();
        loadPitSettings();
        loadKillAuraSettings();

        // Preserve the existing runtime-testing behavior.
        forcePitMode = true;
        targetScanRange = 1000000.0D;
        clamp();

        if (config.hasChanged()) config.save();
    }

    private static void loadRenderSettings() {
        showPath = config.getBoolean("showPath", CAT_RENDER, showPath, "Render pathfinding.");
        showTarget = config.getBoolean("showTarget", CAT_RENDER, showTarget, "Render target box.");
        showTargetGuide = config.getBoolean("showTargetGuide", CAT_RENDER, showTargetGuide, "Render bot to target guide.");
        showHud = config.getBoolean("showHud", CAT_RENDER, showHud, "Render HUD.");
        debugDashboard = config.getBoolean("debugDashboard", CAT_RENDER, debugDashboard, "Render detailed debug dashboard.");
    }

    private static void loadBotSettings() {
        combatEnabled = config.getBoolean("combatEnabled", CAT_BOT, combatEnabled, "Enable combat controller.");
        navigationEnabled = config.getBoolean("navigationEnabled", CAT_BOT, navigationEnabled, "Enable navigation controller.");
        megastreakStrategy = config.getBoolean("megastreakStrategy", CAT_BOT, megastreakStrategy, "Enable megastreak strategy weighting.");
    }

    private static void loadCombatSettings() {
        combatRange = config.getFloat("combatRange", CAT_COMBAT, (float) combatRange, 2.5F, 4.0F, "Preferred attack range.");
        targetScanRange = config.getFloat("targetScanRange", CAT_COMBAT, (float) targetScanRange, 1.0F, 1000000.0F,
                "Target acquisition range; WanderBot scans all loaded players.");
        retreatHealth = config.getFloat("retreatHealth", CAT_COMBAT, (float) retreatHealth, 0.10F, 0.60F,
                "Self-health retreat threshold ratio.");
        crowdThreshold = config.getInt("crowdThreshold", CAT_COMBAT, crowdThreshold, 1, 8,
                "Nearby eligible players that count as crowd pressure.");
        escapeEvalRadius = config.getFloat("escapeEvalRadius", CAT_COMBAT, (float) escapeEvalRadius, 8.0F, 40.0F,
                "Escape candidate evaluation radius.");
        escapeCandidateCount = config.getInt("escapeCandidateCount", CAT_COMBAT, escapeCandidateCount, 4, 24,
                "Number of escape candidates.");
        chaserDetectArc = config.getFloat("chaserDetectArc", CAT_COMBAT, (float) chaserDetectArc, 60.0F, 360.0F,
                "Chaser detection arc degrees.");
        chaserMinDistance = config.getFloat("chaserMinDistance", CAT_COMBAT, (float) chaserMinDistance, 2.0F, 10.0F,
                "Minimum chaser detection distance.");
        chaserMaxDistance = config.getFloat("chaserMaxDistance", CAT_COMBAT, (float) chaserMaxDistance, 8.0F, 30.0F,
                "Maximum chaser detection distance.");
        chaserDotThreshold = config.getFloat("chaserDotThreshold", CAT_COMBAT, (float) chaserDotThreshold, 0.3F, 0.95F,
                "Chaser approach dot threshold.");
        chaserPersistence = config.getInt("chaserPersistence", CAT_COMBAT, chaserPersistence, 2, 12,
                "Chaser persistence ticks.");
        bowMinDistance = config.getFloat("bowMinDistance", CAT_COMBAT, (float) bowMinDistance, 4.0F, 15.0F,
                "Minimum bow distance.");
        bowMaxDistance = config.getFloat("bowMaxDistance", CAT_COMBAT, (float) bowMaxDistance, 15.0F, 50.0F,
                "Maximum bow distance.");
        bowMinHealth = config.getFloat("bowMinHealth", CAT_COMBAT, (float) bowMinHealth, 0.1F, 0.8F,
                "Minimum health ratio for bow.");
        recoverDelay = config.getInt("recoverDelay", CAT_COMBAT, recoverDelay, 3, 30,
                "Recovery delay ticks.");
        threatThreshold = config.getFloat("threatThreshold", CAT_COMBAT, (float) threatThreshold, 20.0F, 150.0F,
                "Threat score retreat threshold.");
    }

    private static void loadNavigationSettings() {
        navRepathTicks = config.getInt("navRepathTicks", CAT_NAVIGATION, navRepathTicks, 2, 40,
                "Navigation replan cadence.");
        lookaheadDistance = config.getFloat("lookaheadDistance", CAT_NAVIGATION, (float) lookaheadDistance, 1.0F, 8.0F,
                "Navigation lookahead distance.");
    }

    private static void loadPitSettings() {
        megastreakId = config.getString("megastreakId", CAT_PIT, megastreakId, "Selected megastreak id.");
    }

    private static void loadKillAuraSettings() {
        killAuraAttackRange = config.getFloat("killAuraAttackRange", CAT_KILLAURA, (float) killAuraAttackRange, 2.0F, 6.0F,
                "KillAura attack range.");
        killAuraSwingRange = config.getFloat("killAuraSwingRange", CAT_KILLAURA, (float) killAuraSwingRange, 2.0F, 6.0F,
                "KillAura swing range.");
        killAuraMinCPS = config.getInt("killAuraMinCPS", CAT_KILLAURA, killAuraMinCPS, 1, 20,
                "KillAura minimum CPS.");
        killAuraMaxCPS = config.getInt("killAuraMaxCPS", CAT_KILLAURA, killAuraMaxCPS, 1, 20,
                "KillAura maximum CPS.");
        killAuraRotationMode = config.getInt("killAuraRotationMode", CAT_KILLAURA, killAuraRotationMode, 0, 3,
                "KillAura rotation mode.");
        killAuraMoveFixMode = config.getInt("killAuraMoveFixMode", CAT_KILLAURA, killAuraMoveFixMode, 0, 2,
                "KillAura move fix mode.");
        killAuraSmoothing = config.getFloat("killAuraSmoothing", CAT_KILLAURA, (float) killAuraSmoothing, 0.0F, 1.0F,
                "KillAura rotation smoothing.");
        killAuraThroughWalls = config.getBoolean("killAuraThroughWalls", CAT_KILLAURA, killAuraThroughWalls,
                "KillAura attack through walls.");
        killAuraFOV = config.getInt("killAuraFOV", CAT_KILLAURA, killAuraFOV, 30, 360,
                "KillAura FOV limit.");
    }

    public static void save() {
        if (config == null) return;

        setBoolean(CAT_RENDER, "showPath", showPath);
        setBoolean(CAT_RENDER, "showTarget", showTarget);
        setBoolean(CAT_RENDER, "showTargetGuide", showTargetGuide);
        setBoolean(CAT_RENDER, "showHud", showHud);
        setBoolean(CAT_RENDER, "debugDashboard", debugDashboard);

        setBoolean(CAT_BOT, "combatEnabled", combatEnabled);
        setBoolean(CAT_BOT, "navigationEnabled", navigationEnabled);
        setBoolean(CAT_BOT, "megastreakStrategy", megastreakStrategy);

        setDouble(CAT_COMBAT, "combatRange", combatRange);
        setDouble(CAT_COMBAT, "targetScanRange", targetScanRange);
        setDouble(CAT_COMBAT, "retreatHealth", retreatHealth);
        setInt(CAT_COMBAT, "crowdThreshold", crowdThreshold);
        setDouble(CAT_COMBAT, "escapeEvalRadius", escapeEvalRadius);
        setInt(CAT_COMBAT, "escapeCandidateCount", escapeCandidateCount);
        setDouble(CAT_COMBAT, "chaserDetectArc", chaserDetectArc);
        setDouble(CAT_COMBAT, "chaserMinDistance", chaserMinDistance);
        setDouble(CAT_COMBAT, "chaserMaxDistance", chaserMaxDistance);
        setDouble(CAT_COMBAT, "chaserDotThreshold", chaserDotThreshold);
        setInt(CAT_COMBAT, "chaserPersistence", chaserPersistence);
        setDouble(CAT_COMBAT, "bowMinDistance", bowMinDistance);
        setDouble(CAT_COMBAT, "bowMaxDistance", bowMaxDistance);
        setDouble(CAT_COMBAT, "bowMinHealth", bowMinHealth);
        setInt(CAT_COMBAT, "recoverDelay", recoverDelay);
        setDouble(CAT_COMBAT, "threatThreshold", threatThreshold);

        setInt(CAT_NAVIGATION, "navRepathTicks", navRepathTicks);
        setDouble(CAT_NAVIGATION, "lookaheadDistance", lookaheadDistance);
        setString(CAT_PIT, "megastreakId", megastreakId);

        // forcePitMode is intentionally not persisted.
        setBoolean(CAT_HUMANIZATION, "humanizationEnabled", humanizationEnabled);

        setDouble(CAT_KILLAURA, "killAuraAttackRange", killAuraAttackRange);
        setDouble(CAT_KILLAURA, "killAuraSwingRange", killAuraSwingRange);
        setInt(CAT_KILLAURA, "killAuraMinCPS", killAuraMinCPS);
        setInt(CAT_KILLAURA, "killAuraMaxCPS", killAuraMaxCPS);
        setInt(CAT_KILLAURA, "killAuraRotationMode", killAuraRotationMode);
        setInt(CAT_KILLAURA, "killAuraMoveFixMode", killAuraMoveFixMode);
        setDouble(CAT_KILLAURA, "killAuraSmoothing", killAuraSmoothing);
        setBoolean(CAT_KILLAURA, "killAuraThroughWalls", killAuraThroughWalls);
        setInt(CAT_KILLAURA, "killAuraFOV", killAuraFOV);

        config.save();
    }

    private static void setBoolean(String category, String key, boolean value) {
        config.get(category, key, value).set(value);
    }

    private static void setInt(String category, String key, int value) {
        config.get(category, key, value).set(value);
    }

    private static void setDouble(String category, String key, double value) {
        config.get(category, key, (float) value).set((float) value);
    }

    private static void setString(String category, String key, String value) {
        config.get(category, key, value).set(value);
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
        targetScanRange = 1000000.0D;
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
        forcePitMode = true;
        clamp();
        save();
    }

    public static void clamp() {
        combatRange = clamp(combatRange, 2.5D, 4.0D);
        targetScanRange = clamp(targetScanRange, 1.0D, 1000000.0D);
        retreatHealth = clamp(retreatHealth, 0.10D, 0.60D);
        crowdThreshold = clamp(crowdThreshold, 1, 8);
        navRepathTicks = clamp(navRepathTicks, 2, 40);
        lookaheadDistance = clamp(lookaheadDistance, 1.0D, 8.0D);
        escapeEvalRadius = clamp(escapeEvalRadius, 8.0D, 40.0D);
        escapeCandidateCount = clamp(escapeCandidateCount, 4, 24);
        chaserMaxDistance = clamp(chaserMaxDistance, 8.0D, 30.0D);
        chaserMinDistance = clamp(chaserMinDistance, 2.0D, 10.0D);
        bowMinDistance = clamp(bowMinDistance, 4.0D, 15.0D);
        bowMaxDistance = clamp(bowMaxDistance, 15.0D, 50.0D);
        recoverDelay = clamp(recoverDelay, 3, 30);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
