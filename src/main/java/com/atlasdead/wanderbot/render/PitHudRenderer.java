package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Compact glass-style runtime HUD with cached display strings. */
public final class PitHudRenderer {
    private static final int TEXT = 0xFFF2F6FC;
    private static final int MUTED = 0xFF8291A6;
    private static final int DIM = 0xFF58677A;
    private static final int ACCENT = 0xFF4B91FF;
    private static final int SUCCESS = 0xFF4EDDA6;
    private static final int CORAL = 0xFFFF647B;
    private static final int WARNING = 0xFFFFC45C;
    private static final long CACHE_MS = 200L;
    private static final int[][] ROUND_INSETS = new int[10][];

    private long lastRefresh;
    private String owner = "NAV AIM";
    private String target = "None";
    private String streak = "0  peak 0";
    private String mode = "OFF";
    private boolean myauOwner;
    private boolean myauFault;

    @SubscribeEvent
    public void render(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled() || !WanderBotSettings.showHud) return;

        refresh(bot);

        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer font = mc.fontRendererObj;
        int x = 8;
        int y = 8;
        int w = 224;
        int h = 76;

        roundedRect(x - 3, y - 3, x + w + 3, y + h + 4, 7, 0x39000000);
        roundedRect(x, y, x + w, y + h, 6, 0xD90A1018);
        outline(x, y, x + w, y + h, 0x772B415D);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + 3, ACCENT);

        font.drawString("WANDERBOT", x + 10, y + 11, ACCENT);
        int ownerColor = myauFault ? WARNING : (myauOwner ? CORAL : SUCCESS);
        int badgeW = font.getStringWidth(owner) + 12;
        int badgeBg = myauFault ? 0x24FFC45C : (myauOwner ? 0x24FF647B : 0x244EDDA6);
        roundedRect(x + w - badgeW - 9, y + 8, x + w - 9, y + 24, 7, badgeBg);
        font.drawString(owner, x + w - badgeW - 3, y + 12, ownerColor);

        font.drawString("Target", x + 10, y + 34, DIM);
        font.drawString(trim(font, target, 102), x + 52, y + 34, TEXT);
        font.drawString("Streak", x + 10, y + 49, DIM);
        font.drawString(streak, x + 52, y + 49, TEXT);
        font.drawString("Mode", x + 10, y + 64, DIM);
        font.drawString(trim(font, mode, 160), x + 52, y + 64, MUTED);
    }

    private void refresh(BotController bot) {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < CACHE_MS) return;
        lastRefresh = now;

        myauOwner = bot.isMyauAimOwner();
        String bridge = bot.getKillAuraStatus();
        myauFault = bridge != null && bridge.startsWith("SYNC");
        owner = myauFault ? "MYAU SYNC" : (myauOwner ? "MYAU AIM" : "NAV AIM");
        String name = bot.getTargetName();
        target = name == null ? "None" : name;
        streak = bot.getLocalStreak() + "  peak " + bot.getPeakStreak();
        mode = String.valueOf(bot.getPitMode()) + " / " + String.valueOf(bot.getState());
    }

    private static String trim(FontRenderer font, String text, int width) {
        if (text == null) return "";
        if (font.getStringWidth(text) <= width) return text;
        String suffix = "...";
        return font.trimStringToWidth(text, Math.max(0, width - font.getStringWidth(suffix))) + suffix;
    }

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
}
