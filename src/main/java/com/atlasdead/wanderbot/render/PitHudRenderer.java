package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.pit.PitMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Small diagnostic HUD for the private Pit test environment. */
public class PitHudRenderer {
    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled() || !WanderBotSettings.showHud) return;

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        PitMode mode = bot.getPitMode();
        String target = bot.getTargetName();
        String line1 = "WanderBot | Pit: " + mode;
        String line2 = "Target: " + (target == null ? "none" : target);
        String line3 = "Streak: " + bot.getLocalStreak() + " [" + bot.getStreakTier() + "] peak=" + bot.getPeakStreak();
        String line4 = "Target score: " + bot.getTargetScore();
        String line5 = "Armor: " + bot.getTargetArmor();
        String line6 = "Nav: " + bot.getState();
        String lineMap = "Map: " + bot.getPit().getMap().zone() + " hazard=" + String.format("%.2f", bot.getPit().getMap().hazard()) + " open=" + String.format("%.2f", bot.getPit().getMap().openness());
        font.drawStringWithShadow(line1, 4, 4, 0xFFFFFF);
        font.drawStringWithShadow(line2, 4, 15, 0xFFFFFF);
        font.drawStringWithShadow(line3, 4, 26, 0xFFFFFF);
        font.drawStringWithShadow(line4, 4, 37, 0xFFFFFF);
        font.drawStringWithShadow(line5, 4, 48, 0xFFFFFF);
        font.drawStringWithShadow(line6, 4, 59, 0xFFFFFF);
        font.drawStringWithShadow(lineMap, 4, 70, 0xFFFFFF);
        if (WanderBotSettings.debugDashboard) {
            String line7 = "Combat: " + (WanderBotSettings.combatEnabled ? "ON" : "OFF") + " range=" + String.format("%.2f", WanderBotSettings.combatRange);
            String line8 = "Target scan: " + String.format("%.1f", WanderBotSettings.targetScanRange) + " crowd=" + WanderBotSettings.crowdThreshold;
            String mega = bot.getPit().getStreakControl().getActiveMegastreak() == null ? "none" : bot.getPit().getStreakControl().getActiveMegastreak().displayName;
            String line9 = "Mega: " + mega + " remaining=" + bot.getPit().getStreakControl().killsRemaining(bot.getLocalStreak());
            font.drawStringWithShadow(line7, 4, 81, 0xFFFFFF);
            font.drawStringWithShadow(line8, 4, 92, 0xFFFFFF);
            font.drawStringWithShadow(line9, 4, 103, 0xFFFFFF);
        }
    }
}
