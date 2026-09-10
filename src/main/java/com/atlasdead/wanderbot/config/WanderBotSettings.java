package com.atlasdead.wanderbot.config;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Persisted runtime settings that are actually consumed by WanderBot. */
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
    public static boolean debugDashboard = true;

    public static boolean combatEnabled = true;
    public static boolean navigationEnabled = true;
    public static boolean megastreakStrategy = true;

    public static double combatRange = 3.0D;
    public static double retreatHealth = 0.30D;
    public static int crowdThreshold = 3;

    public static int navRepathTicks = 8;
    public static double lookaheadDistance = 3.5D;

    public static String megastreakId = "overdrive";
    /** Production default keeps the full Pit policy active; enable only to bypass it. */
    public static boolean forcePitMode = false;

    /** Distance at which WanderBot requests Myau combat-aim ownership. */
    public static double killAuraSwingRange = 3.5D;

    /** WanderBot-only rotation variation. Myau combat aim is never modified here. */
    public static boolean humanizationEnabled = true;
    public static float rotationSpeedSD = 0.15F;
    public static float overshootChance = 12.0F;
    public static float distractionChance = 3.0F;

    private static Configuration config;

    public static void load(File configDir) {
        if (configDir == null) return;

        config = new Configuration(new File(configDir, "wanderbot.cfg"));
        config.load();
        loadAll();
        clamp();
        if (config.hasChanged()) config.save();
    }

    private static void loadAll() {
        loadRenderSettings();
        loadBotSettings();
        loadCombatSettings();
        loadNavigationSettings();
        loadPitSettings();
        loadKillAuraSettings();
        loadHumanizationSettings();
    }

    private static void loadRenderSettings() {
        showPath = config.getBoolean("showPath", CAT_RENDER, showPath, "Render pathfinding.");
        showTarget = config.getBoolean("showTarget", CAT_RENDER, showTarget, "Render target box.");
        showTargetGuide = config.getBoolean("showTargetGuide", CAT_RENDER, showTargetGuide, "Render bot-to-target guide.");
        showHud = config.getBoolean("showHud", CAT_RENDER, showHud, "Render compact HUD.");
        debugDashboard = config.getBoolean("debugDashboard", CAT_RENDER, debugDashboard, "Render detailed diagnostics.");
    }

    private static void loadBotSettings() {
        combatEnabled = config.getBoolean("combatEnabled", CAT_BOT, combatEnabled, "Enable combat controller.");
        navigationEnabled = config.getBoolean("navigationEnabled", CAT_BOT, navigationEnabled, "Enable navigation controller.");
        megastreakStrategy = config.getBoolean("megastreakStrategy", CAT_BOT, megastreakStrategy,
                "Enable megastreak strategy weighting.");
    }

    private static void loadCombatSettings() {
        combatRange = config.getFloat("combatRange", CAT_COMBAT, (float) combatRange, 2.5F, 4.0F,
                "Preferred combat distance.");
        retreatHealth = config.getFloat("retreatHealth", CAT_COMBAT, (float) retreatHealth, 0.10F, 0.60F,
                "Self-health retreat threshold ratio.");
        crowdThreshold = config.getInt("crowdThreshold", CAT_COMBAT, crowdThreshold, 1, 8,
                "Nearby eligible opponents that count as crowd pressure.");
    }

    private static void loadNavigationSettings() {
        navRepathTicks = config.getInt("navRepathTicks", CAT_NAVIGATION, navRepathTicks, 2, 40,
                "Combat route replan cadence in ticks.");
        lookaheadDistance = config.getFloat("lookaheadDistance", CAT_NAVIGATION, (float) lookaheadDistance, 1.0F, 8.0F,
                "Path-corridor lookahead distance.");
    }

    private static void loadPitSettings() {
        megastreakId = config.getString("megastreakId", CAT_PIT, megastreakId, "Selected megastreak id.");
        forcePitMode = config.getBoolean("forcePitMode", CAT_PIT, forcePitMode,
                "Bypass scoreboard/event policy and force combat routing.");
    }

    private static void loadKillAuraSettings() {
        // Keep the historical key name for config compatibility. WanderBot only
        // owns the trigger distance; Myau owns its own CPS/FOV/rotation settings.
        killAuraSwingRange = config.getFloat("killAuraSwingRange", CAT_KILLAURA,
                (float) killAuraSwingRange, 2.0F, 6.0F,
                "Distance at which WanderBot requests Myau KillAura ownership.");
    }

    private static void loadHumanizationSettings() {
        humanizationEnabled = config.getBoolean("humanizationEnabled", CAT_HUMANIZATION, humanizationEnabled,
                "Enable conservative variation in WanderBot-owned rotation.");
        rotationSpeedSD = config.getFloat("rotationSpeedSD", CAT_HUMANIZATION, rotationSpeedSD, 0.0F, 1.0F,
                "Rotation speed variation.");
        overshootChance = config.getFloat("overshootChance", CAT_HUMANIZATION, overshootChance, 0.0F, 100.0F,
                "Rotation overshoot chance percent.");
        distractionChance = config.getFloat("distractionChance", CAT_HUMANIZATION, distractionChance, 0.0F, 100.0F,
                "Brief WanderBot navigation-look variation chance percent.");
    }

    /** Reload persisted settings without reconstructing the mod. */
    public static void reload() {
        if (config == null) return;
        config.load();
        loadAll();
        clamp();
    }

    public static void save() {
        if (config == null) return;
        clamp();

        setBoolean(CAT_RENDER, "showPath", showPath);
        setBoolean(CAT_RENDER, "showTarget", showTarget);
        setBoolean(CAT_RENDER, "showTargetGuide", showTargetGuide);
        setBoolean(CAT_RENDER, "showHud", showHud);
        setBoolean(CAT_RENDER, "debugDashboard", debugDashboard);

        setBoolean(CAT_BOT, "combatEnabled", combatEnabled);
        setBoolean(CAT_BOT, "navigationEnabled", navigationEnabled);
        setBoolean(CAT_BOT, "megastreakStrategy", megastreakStrategy);

        setDouble(CAT_COMBAT, "combatRange", combatRange);
        setDouble(CAT_COMBAT, "retreatHealth", retreatHealth);
        setInt(CAT_COMBAT, "crowdThreshold", crowdThreshold);

        setInt(CAT_NAVIGATION, "navRepathTicks", navRepathTicks);
        setDouble(CAT_NAVIGATION, "lookaheadDistance", lookaheadDistance);

        setString(CAT_PIT, "megastreakId", megastreakId);
        setBoolean(CAT_PIT, "forcePitMode", forcePitMode);

        setDouble(CAT_KILLAURA, "killAuraSwingRange", killAuraSwingRange);

        setBoolean(CAT_HUMANIZATION, "humanizationEnabled", humanizationEnabled);
        setDouble(CAT_HUMANIZATION, "rotationSpeedSD", rotationSpeedSD);
        setDouble(CAT_HUMANIZATION, "overshootChance", overshootChance);
        setDouble(CAT_HUMANIZATION, "distractionChance", distractionChance);

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
        debugDashboard = true;

        combatEnabled = true;
        navigationEnabled = true;
        megastreakStrategy = true;

        combatRange = 3.0D;
        retreatHealth = 0.30D;
        crowdThreshold = 3;

        navRepathTicks = 8;
        lookaheadDistance = 3.5D;

        megastreakId = "overdrive";
        forcePitMode = false;
        killAuraSwingRange = 3.5D;

        humanizationEnabled = true;
        rotationSpeedSD = 0.15F;
        overshootChance = 12.0F;
        distractionChance = 3.0F;

        clamp();
        save();
    }

    public static void clamp() {
        combatRange = clamp(combatRange, 2.5D, 4.0D);
        retreatHealth = clamp(retreatHealth, 0.10D, 0.60D);
        crowdThreshold = clamp(crowdThreshold, 1, 8);
        navRepathTicks = clamp(navRepathTicks, 2, 40);
        lookaheadDistance = clamp(lookaheadDistance, 1.0D, 8.0D);
        killAuraSwingRange = clamp(killAuraSwingRange, 2.0D, 6.0D);
        // Navigation should never stop farther away than the point where Myau
        // is asked to take combat aim ownership, otherwise the handoff may never occur.
        killAuraSwingRange = Math.max(killAuraSwingRange, combatRange);

        rotationSpeedSD = (float) clamp(rotationSpeedSD, 0.0D, 1.0D);
        overshootChance = (float) clamp(overshootChance, 0.0D, 100.0D);
        distractionChance = (float) clamp(distractionChance, 0.0D, 100.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
