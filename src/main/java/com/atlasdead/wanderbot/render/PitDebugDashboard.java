package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.CombatTelemetry;
import com.atlasdead.wanderbot.pit.PitEventDetector;
import com.atlasdead.wanderbot.pit.PitMasterDecisionEngine;
import com.atlasdead.wanderbot.pit.PitRuntimeSnapshot;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Compact in-game diagnostics for the private Pit test environment. */
public final class PitDebugDashboard {
    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        if (!WanderBotSettings.debugDashboard) return;
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        PitRuntimeSnapshot snap = bot.getPit().getRuntimeSnapshot();
        PitMasterDecisionEngine.Decision decision = bot.getPit().getMasterDecision().getLastDecision();
        CombatTelemetry telemetry = bot.getPit().getCombatTelemetry();
        PitEventDetector.Result eventState = bot.getPit().getEventState();
        PitStreakCatalog.Definition mega = bot.getPit().getStreakControl().getActiveMegastreak();

        int x = 6, y = 6;
        int width = 285;
        int rows = 16;
        drawPanel(x - 4, y - 4, width, rows * 11 + 10, 0xA910141B);
        drawAccent(x - 4, y - 4, width, 2, bot.isEnabled());

        int c = 0xE9EEF5;
        int muted = 0x9AA7B8;
        line(font, "WANDERBOT  /  DEBUG", x, y, c);
        line(font, "Mode: " + bot.getPitMode() + "   State: " + bot.getState(), x, y += 11, c);
        line(font, "Zone: " + bot.getPit().getMap().zone(), x, y += 11, c);
        line(font, "Hazard: " + f(bot.getPit().getMap().hazard()) + "  Open: " + f(bot.getPit().getMap().openness()), x, y += 11, c);
        line(font, "Decision: " + (decision == null ? "none" : decision.action), x, y += 11, c);
        line(font, "Reason: " + compact(decision == null ? "none" : decision.reason, 39), x, y += 11, muted);
        line(font, "Target: " + safe(decision == null ? null : decision.targetName), x, y += 11, c);
        line(font, "Target score: " + f(bot.getTargetScore()) + "  Armor: " + safe(String.valueOf(bot.getTargetArmor())), x, y += 11, muted);
        line(font, "Combat: " + (telemetry == null ? "none" : telemetry.action), x, y += 11, c);
        line(font, "Distance: " + f(telemetry == null ? 0 : telemetry.distance) + "  Threat: " + f(telemetry == null ? 0 : telemetry.threatScore), x, y += 11, muted);
        line(font, "Crowd: " + (telemetry == null ? 0 : telemetry.crowdPressure) + "  LOS: " + yesNo(telemetry != null && telemetry.visible), x, y += 11, muted);
        line(font, "Streak: " + bot.getLocalStreak() + "  Peak: " + bot.getPeakStreak() + "  Tier: " + bot.getStreakTier(), x, y += 11, c);
        line(font, "Mega: " + safe(mega == null ? null : mega.displayName), x, y += 11, c);
        line(font, "Event: " + (eventState == null ? "NONE" : eventState.eventName), x, y += 11, c);
        line(font, "Path: " + pathInfo(bot), x, y += 11, muted);
        line(font, "Guard: " + bot.getRuntimeGuardStatus(), x, y += 11, c);
    }

    private void line(FontRenderer font, String text, int x, int y, int color) {
        font.drawStringWithShadow(text, x, y, color);
    }

    private void drawPanel(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, bottom, color);
    }

    private void drawAccent(int x, int y, int width, int height, boolean enabled) {
        Gui.drawRect(x, y, x + width, y + height, enabled ? 0xFF7E9BFF : 0xFF616B78);
    }

    private String pathInfo(BotController bot) {
        if (bot.getPath() == null || bot.getPath().getNodes() == null) return "none";
        return bot.getPath().getIndex() + "/" + bot.getPath().getNodes().size();
    }

    private String safe(String s) { return s == null || s.length() == 0 ? "none" : s; }
    private String yesNo(boolean b) { return b ? "YES" : "NO"; }
    private String f(double d) { return String.format("%.2f", d); }
    private String compact(String s, int max) {
        if (s == null) return "none";
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 3)) + "...";
    }
}
