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

    public static double combatRange = 3.35D;
    public static double targetScanRange = 30.0D;
    public static double retreatHealth = 0.30D;
    public static int crowdThreshold = 3;
    public static int navRepathTicks = 8;
    public static double lookaheadDistance = 3.5D;
    public static boolean debugDashboard = true;
    public static String megastreakId = "overdrive";

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
        config.getCategory("combat").get("combatRange").set(combatRange);
        config.getCategory("combat").get("targetScanRange").set(targetScanRange);
        config.getCategory("combat").get("retreatHealth").set(retreatHealth);
        config.getCategory("combat").get("crowdThreshold").set(crowdThreshold);
        config.getCategory("navigation").get("navRepathTicks").set(navRepathTicks);
        config.getCategory("navigation").get("lookaheadDistance").set(lookaheadDistance);
        config.getCategory("pit").get("megastreakId").set(megastreakId);
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
        combatRange = 3.35D;
        targetScanRange = 30.0D;
        retreatHealth = 0.30D;
        crowdThreshold = 3;
        navRepathTicks = 8;
        lookaheadDistance = 3.5D;
        debugDashboard = true;
        megastreakId = "overdrive";
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
    }
}
