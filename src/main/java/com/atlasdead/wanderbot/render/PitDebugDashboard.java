package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import com.atlasdead.wanderbot.pit.CombatTelemetry;
import com.atlasdead.wanderbot.pit.PitEventDetector;
import com.atlasdead.wanderbot.pit.PitMasterDecisionEngine;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Locale;

/** Modern grouped diagnostics panel with a 250 ms text snapshot cache. */
public final class PitDebugDashboard {
    private static final int TEXT = 0xFFEAF0F8;
    private static final int MUTED = 0xFF8998AC;
    private static final int DIM = 0xFF5C6C80;
    private static final int ACCENT = 0xFF4B91FF;
    private static final int CORAL = 0xFFFF647B;
    private static final long CACHE_MS = 250L;
    private static final int[][] ROUND_INSETS = new int[10][];

    private long lastRefresh;
    private Snapshot snapshot = Snapshot.empty();

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!WanderBotSettings.debugDashboard) return;
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled()) return;

        refresh(bot);

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        int x = 8;
        int y = WanderBotSettings.showHud ? 94 : 8;
        int w = 292;
        int h = 198;
        roundedRect(x - 3, y - 3, x + w + 3, y + h + 4, 7, 0x32000000);
        roundedRect(x, y, x + w, y + h, 6, 0xD70A1018);
        outline(x, y, x + w, y + h, 0x6A2B415D);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + 3, ACCENT);

        font.drawString("LIVE DIAGNOSTICS", x + 10, y + 11, ACCENT);
        font.drawString(snapshot.tickTime,
                x + w - 10 - font.getStringWidth(snapshot.tickTime), y + 11, MUTED);

        int yy = y + 29;
        row(font, x + 10, yy, "Runtime", snapshot.runtime, TEXT); yy += 14;
        row(font, x + 10, yy, "Guard", snapshot.guard, MUTED); yy += 14;
        row(font, x + 10, yy, "Zone", snapshot.zone, MUTED); yy += 18;
        divider(x + 10, yy, w - 20); yy += 8;
        row(font, x + 10, yy, "Target", snapshot.target, TEXT); yy += 14;
        row(font, x + 10, yy, "Combat", snapshot.combat, CORAL); yy += 14;
        row(font, x + 10, yy, "Distance", snapshot.distance, MUTED); yy += 14;
        row(font, x + 10, yy, "Aim", snapshot.aim, MUTED); yy += 18;
        divider(x + 10, yy, w - 20); yy += 8;
        row(font, x + 10, yy, "Decision", snapshot.decision, TEXT); yy += 14;
        row(font, x + 10, yy, "Search", snapshot.search, MUTED); yy += 14;
        row(font, x + 10, yy, "Streak", snapshot.streak, MUTED); yy += 14;
        row(font, x + 10, yy, "Myau", snapshot.myau, MUTED); yy += 14;
        row(font, x + 10, yy, "Event", snapshot.event, MUTED);
    }

    private void refresh(BotController bot) {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_MS) return;
        lastRefresh = now;

        PitMasterDecisionEngine.Decision decision = bot.getPit().getMasterDecision().getLastDecision();
        CombatTelemetry telemetry = bot.getPit().getCombatTelemetry();
        PitEventDetector.Result eventState = bot.getPit().getEventState();
        PitStreakCatalog.Definition mega = bot.getPit().getStreakControl().getActiveMegastreak();
        CombatExecutionController.State cs = bot.getPit().getCombatExecutor().getLastState();

        String tickTime = f(bot.getAverageTickMillis()) + " ms";
        String runtime = bot.getPitMode() + "  /  " + bot.getState();
        String guard = bot.getRuntimeGuardStatus();
        String zone = bot.getPit().getMap().zone() + "  H " + f(bot.getPit().getMap().hazard())
                + "  O " + f(bot.getPit().getMap().openness());
        String target = safe(bot.getTargetName()) + "  score " + f(bot.getTargetScore());
        String combat = telemetry == null ? "none" : String.valueOf(telemetry.action);
        String distance = f(telemetry == null ? 0.0D : telemetry.distance)
                + "  threat " + f(telemetry == null ? 0.0D : telemetry.threatScore);
        String aim = "yaw " + f(cs.yawError) + "  pitch " + f(cs.pitchError)
                + "  aligned " + yesNo(cs.aligned);
        String master = decision == null ? "none" : String.valueOf(decision.action);
        String search = bot.getTargetSearchStatus()
                + "  L" + bot.getPit().getTargets().getLastLoadedPlayers()
                + " U" + bot.getPit().getTargets().getLastUsableCandidates()
                + " N" + bot.getPit().getTargets().getLastLocalCandidates();
        String streak = bot.getLocalStreak() + "  peak " + bot.getPeakStreak()
                + "  " + safe(mega == null ? null : mega.displayName);
        String myau = bot.getKillAuraStatus() + (bot.isMyauAimOwner() ? "  owns aim" : "");
        String event = eventState == null ? "NONE" : eventState.eventName;

        snapshot = new Snapshot(tickTime, runtime, guard, zone, target, combat,
                distance, aim, master, search, streak, myau, event);
    }

    private static void row(FontRenderer font, int x, int y, String key, String value, int valueColor) {
        font.drawString(key, x, y, DIM);
        font.drawString(trim(font, value, 220), x + 62, y, valueColor);
    }

    private static void divider(int x, int y, int width) {
        Gui.drawRect(x, y, x + width, y + 1, 0x44283749);
    }

    private static String trim(FontRenderer font, String text, int width) {
        if (text == null) return "";
        if (font.getStringWidth(text) <= width) return text;
        String suffix = "...";
        return font.trimStringToWidth(text, Math.max(0, width - font.getStringWidth(suffix))) + suffix;
    }

    private static String safe(String s) { return s == null || s.length() == 0 ? "none" : s; }
    private static String yesNo(boolean b) { return b ? "YES" : "NO"; }
    private static String f(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    private static void outline(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private static void roundedRect(int left, int top, int right, int bottom, int radius, int color) {
        Gui.drawRect(left + radius, top, right - radius, bottom, color);
        Gui.drawRect(left, top + radius, right, bottom - radius, color);
        int[] insets = roundInsets(radius);
        for (int i = 0; i < radius; i++) {
            int inset = insets[i];
            Gui.drawRect(left + inset, top + i, right - inset, top + i + 1, color);
            Gui.drawRect(left + inset, bottom - i - 1, right - inset, bottom - i, color);
        }
    }

    private static int[] roundInsets(int radius) {
        int safe = Math.max(1, Math.min(radius, ROUND_INSETS.length - 1));
        if (ROUND_INSETS[safe] != null) return ROUND_INSETS[safe];
        int[] values = new int[safe];
        for (int i = 0; i < safe; i++) {
            values[i] = (int) Math.ceil(safe - Math.sqrt(Math.max(0.0D,
                    safe * safe - (safe - i - 0.5D) * (safe - i - 0.5D))));
        }
        ROUND_INSETS[safe] = values;
        return values;
    }

    private static final class Snapshot {
        final String tickTime;
        final String runtime;
        final String guard;
        final String zone;
        final String target;
        final String combat;
        final String distance;
        final String aim;
        final String decision;
        final String search;
        final String streak;
        final String myau;
        final String event;

        Snapshot(String tickTime, String runtime, String guard, String zone,
                 String target, String combat, String distance, String aim,
                 String decision, String search, String streak, String myau, String event) {
            this.tickTime = tickTime;
            this.runtime = runtime;
            this.guard = guard;
            this.zone = zone;
            this.target = target;
            this.combat = combat;
            this.distance = distance;
            this.aim = aim;
            this.decision = decision;
            this.search = search;
            this.streak = streak;
            this.myau = myau;
            this.event = event;
        }

        static Snapshot empty() {
            return new Snapshot("0.00 ms", "OFF", "NONE", "unknown",
                    "none", "none", "0.00", "none", "none", "IDLE  L0 U0 N0", "0", "OFF", "NONE");
        }
    }
}
