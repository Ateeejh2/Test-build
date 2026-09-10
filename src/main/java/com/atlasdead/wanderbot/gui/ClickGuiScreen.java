package com.atlasdead.wanderbot.gui;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * Modern control center for WanderBot.
 *
 * The design deliberately stays on vanilla 1.8.9 GUI primitives. It avoids
 * framebuffer blur/shader dependencies, which keeps the screen light and
 * compatible with the ForgeGradle 1.8.9 setup while still presenting a
 * dashboard-like interface.
 */
public final class ClickGuiScreen extends GuiScreen {
    private static final int MAX_PANEL_WIDTH = 930;
    private static final int MAX_PANEL_HEIGHT = 520;
    private static final int MIN_PANEL_WIDTH = 720;
    private static final int MIN_PANEL_HEIGHT = 410;
    private static final int SIDEBAR_WIDTH = 168;
    private static final int HEADER_HEIGHT = 64;
    private static final int CONTENT_PAD = 20;
    private static final int GAP = 10;

    private static final int SHELL = 0xF3080C12;
    private static final int SIDEBAR = 0xF80A0F16;
    private static final int SURFACE = 0xF5111720;
    private static final int SURFACE_RAISED = 0xFA151D28;
    private static final int SURFACE_HOVER = 0xFF1A2534;
    private static final int BORDER = 0xFF243246;
    private static final int BORDER_SOFT = 0xFF1C2736;
    private static final int TEXT = 0xFFF3F7FD;
    private static final int TEXT_SECONDARY = 0xFFB1BED0;
    private static final int TEXT_MUTED = 0xFF7D8DA3;
    private static final int TEXT_DIM = 0xFF566579;
    private static final int ACCENT = 0xFF3987FF;
    private static final int ACCENT_BRIGHT = 0xFF65A3FF;
    private static final int ACCENT_SOFT = 0x303987FF;
    private static final int SUCCESS = 0xFF4EDDA6;
    private static final int WARNING = 0xFFFFC45C;
    private static final int DANGER = 0xFFFF6678;
    private static final int CORAL = 0xFFFF647B;
    private static final int TEAL = 0xFF45D5C4;

    private static final int[][] ROUND_INSET_CACHE = new int[18][];

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private Category category = Category.GENERAL;
    private boolean settingsDirty;
    private long lastDebugRefreshMs;
    private long lastInfoRefreshMs;
    private String cachedAverageTick = "—";
    private SliderButton draggingSlider;

    private enum Category {
        GENERAL("General", "Core runtime controls and global settings", "G"),
        RENDER("Render", "World overlays and on-screen diagnostics", "R"),
        COMBAT("Combat", "Combat ownership, range and safety controls", "C"),
        NAVIGATION("Navigation", "Path refresh cadence and steering preview", "N"),
        PIT("Pit", "Megastreak strategy and Pit routing profile", "P"),
        DEBUG("Debug", "Live runtime telemetry", "D");

        final String title;
        final String subtitle;
        final String glyph;

        Category(String title, String subtitle, String glyph) {
            this.title = title;
            this.subtitle = subtitle;
            this.glyph = glyph;
        }
    }

    @Override
    public void initGui() {
        recalculateLayout();
        rebuildControls();
    }

    private void recalculateLayout() {
        panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, width - 26));
        panelWidth = Math.min(panelWidth, Math.max(360, width - 12));
        panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(MIN_PANEL_HEIGHT, height - 28));
        panelHeight = Math.min(panelHeight, Math.max(300, height - 12));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
    }

    private int contentX() {
        return left + SIDEBAR_WIDTH + CONTENT_PAD;
    }

    private int contentY() {
        return top + HEADER_HEIGHT + 48;
    }

    private int contentWidth() {
        return panelWidth - SIDEBAR_WIDTH - CONTENT_PAD * 2;
    }

    private int contentBottom() {
        return top + panelHeight - 18;
    }

    private void rebuildControls() {
        buttonList.clear();
        draggingSlider = null;
        int x = contentX();
        int y = contentY();
        switch (category) {
            case GENERAL:
                buildGeneral(x, y);
                break;
            case RENDER:
                buildRender(x, y);
                break;
            case COMBAT:
                buildCombat(x, y);
                break;
            case NAVIGATION:
                buildNavigation(x, y);
                break;
            case PIT:
                buildPit(x, y);
                break;
            case DEBUG:
                buildDebug(x, y);
                break;
            default:
                break;
        }
    }

    private void buildGeneral(int x, int y) {
        int total = contentWidth();
        int rightWidth = Math.max(230, Math.min(278, total * 38 / 100));
        int mainWidth = total - rightWidth - GAP;

        addModuleToggle(10, x, y, mainWidth, 54, "WB", 0x2545D5C4,
                "WanderBot", "Master runtime — enables the complete bot", new BooleanSetting() {
            @Override public boolean get() { return isBotEnabled(); }
            @Override public void set(boolean value) {
                if (WanderBotMod.BOT != null && WanderBotMod.BOT.isEnabled() != value) WanderBotMod.BOT.toggle();
            }
        });
        addModuleToggle(11, x, y + 62, mainWidth, 54, "NV", 0x253987FF,
                "Navigation", "Movement + terrain-aware path planner", settingNavigation());
        addModuleToggle(12, x, y + 124, mainWidth, 54, "CB", 0x25FF647B,
                "Combat", "Combat decision pipeline + Myau ownership", settingCombat());
        addModuleToggle(13, x, y + 186, mainWidth, 54, "MS", 0x257D72FF,
                "Megastreak strategy", "Adaptive target and streak policy", settingMegastreak());

        int profileY = y + 250;
        addAction(14, x + 12, profileY + 52, (mainWidth - 44) / 3, 28, "Save", new Runnable() {
            @Override public void run() { WanderBotSettings.save(); settingsDirty = false; }
        });
        addAction(15, x + 22 + (mainWidth - 44) / 3, profileY + 52, (mainWidth - 44) / 3, 28, "Load", new Runnable() {
            @Override public void run() {
                WanderBotSettings.reload();
                syncPitProfile();
                settingsDirty = false;
                rebuildControls();
            }
        });
        addAction(16, x + 32 + ((mainWidth - 44) / 3) * 2, profileY + 52, (mainWidth - 44) / 3, 28, "Reset", new Runnable() {
            @Override public void run() {
                WanderBotSettings.resetDefaults();
                syncPitProfile();
                settingsDirty = false;
                rebuildControls();
            }
        });

        int rx = x + mainWidth + GAP;
        int innerX = rx + 12;
        int innerW = rightWidth - 24;
        addSlider(20, innerX, y + 34, innerW, 48, "Combat range", "Preferred approach distance",
                2.5D, 4.0D, 0.05D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.combatRange; }
            @Override public void set(double value) {
                WanderBotSettings.combatRange = value;
                if (WanderBotSettings.killAuraSwingRange < value) WanderBotSettings.killAuraSwingRange = value;
            }
        }, "%.2f");
        addSlider(21, innerX, y + 86, innerW, 48, "Path update interval", "Combat route refresh cadence",
                2.0D, 40.0D, 1.0D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.navRepathTicks; }
            @Override public void set(double value) { WanderBotSettings.navRepathTicks = (int) Math.round(value); }
        }, "%.0f");
        addCompactToggle(22, innerX, y + 143, innerW, 28, "Smart targeting", settingMegastreak());
        addCompactToggle(23, innerX, y + 174, innerW, 28, "Force Pit routing", settingForcePit());
    }

    private void buildRender(int x, int y) {
        int total = contentWidth();
        int column = (total - GAP) / 2;
        addModuleToggle(30, x, y, column, 58, "PT", 0x253987FF,
                "Path visualization", "Blue navigation + coral combat routes", settingPath());
        addModuleToggle(31, x + column + GAP, y, column, 58, "TG", 0x25FF647B,
                "Target box", "Minimal fill, outline and foot ring", settingTarget());
        addModuleToggle(32, x, y + 68, column, 58, "GL", 0x2545D5C4,
                "Target guide", "Subtle player-to-target guide line", settingTargetGuide());
        addModuleToggle(33, x + column + GAP, y + 68, column, 58, "HD", 0x257D72FF,
                "HUD", "Compact runtime information card", settingHud());
        addModuleToggle(34, x, y + 136, column, 58, "DB", 0x25FFC45C,
                "Debug dashboard", "Extended live diagnostics", settingDebugDashboard());
        addInfo(35, x + column + GAP, y + 136, column, 58,
                "Renderer", "Vanilla 1.8.9 primitives", "LIGHT");
    }

    private void buildCombat(int x, int y) {
        int total = contentWidth();
        int leftWidth = (total - GAP) * 58 / 100;
        int rightWidth = total - leftWidth - GAP;

        addModuleToggle(40, x, y, leftWidth, 56, "CB", 0x25FF647B,
                "Combat enabled", "Master combat switch", settingCombat());
        addSlider(41, x, y + 68, leftWidth, 52, "Combat range", "Preferred path approach distance",
                2.5D, 4.0D, 0.05D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.combatRange; }
            @Override public void set(double value) {
                WanderBotSettings.combatRange = value;
                if (WanderBotSettings.killAuraSwingRange < value) WanderBotSettings.killAuraSwingRange = value;
            }
        }, "%.2f");
        addSlider(42, x, y + 126, leftWidth, 52, "Myau trigger range", "Aim ownership handoff distance",
                2.0D, 6.0D, 0.05D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.killAuraSwingRange; }
            @Override public void set(double value) {
                WanderBotSettings.killAuraSwingRange = Math.max(value, WanderBotSettings.combatRange);
            }
        }, "%.2f");
        addSlider(43, x, y + 184, leftWidth, 52, "Retreat health", "Self-health retreat threshold",
                0.10D, 0.60D, 0.01D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.retreatHealth; }
            @Override public void set(double value) { WanderBotSettings.retreatHealth = value; }
        }, "%.0f%%", 100.0D);
        addSlider(44, x, y + 242, leftWidth, 52, "Crowd threshold", "Nearby eligible players considered pressure",
                1.0D, 8.0D, 1.0D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.crowdThreshold; }
            @Override public void set(double value) { WanderBotSettings.crowdThreshold = (int) Math.round(value); }
        }, "%.0f");

        int rx = x + leftWidth + GAP;
        addInfo(45, rx, y, rightWidth, 58, "Target scan", "All currently loaded players", "LIVE");
        addCompactToggle(46, rx + 12, y + 84, rightWidth - 24, 30, "Humanized WanderBot rotation", settingHumanization());
        addCompactToggle(47, rx + 12, y + 120, rightWidth - 24, 30, "Force Pit mode", settingForcePit());
        addInfo(48, rx, y + 158, rightWidth, 72, "Aim ownership",
                "Acknowledged ownership state", killAuraStatus());
    }

    private void buildNavigation(int x, int y) {
        int total = contentWidth();
        addModuleToggle(50, x, y, total, 58, "NV", 0x253987FF,
                "Navigation enabled", "Terrain-aware movement and combat path following", settingNavigation());
        addSlider(51, x, y + 76, total, 58, "Repath interval", "Ticks between combat route refreshes",
                2.0D, 40.0D, 1.0D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.navRepathTicks; }
            @Override public void set(double value) { WanderBotSettings.navRepathTicks = (int) Math.round(value); }
        }, "%.0f ticks");
        addSlider(52, x, y + 144, total, 58, "Steering lookahead", "Preview distance along the path corridor",
                1.0D, 8.0D, 0.10D, new NumericSetting() {
            @Override public double get() { return WanderBotSettings.lookaheadDistance; }
            @Override public void set(double value) { WanderBotSettings.lookaheadDistance = value; }
        }, "%.2f");
        addInfo(53, x, y + 220, total, 62, "Path state",
                currentPathSummary(), WanderBotSettings.navigationEnabled ? "READY" : "PAUSED");
    }

    private void buildPit(int x, int y) {
        List<PitStreakCatalog.Definition> modes = PitStreakCatalog.ALL_MEGASTREAKS;
        int total = contentWidth();
        int column = (total - GAP) / 2;
        for (int i = 0; i < modes.size(); i++) {
            final PitStreakCatalog.Definition definition = modes.get(i);
            int col = i % 2;
            int row = i / 2;
            int bx = x + col * (column + GAP);
            int by = y + row * 54;
            addSelect(60 + i, bx, by, column, 44, definition.displayName,
                    "Unlock Lv " + definition.unlockLevel,
                    definition.id.equalsIgnoreCase(WanderBotSettings.megastreakId), new Runnable() {
                @Override public void run() {
                    WanderBotSettings.megastreakId = definition.id;
                    syncPitProfile();
                    markDirty();
                    rebuildControls();
                }
            });
        }
    }

    private void buildDebug(int x, int y) {
        int total = contentWidth();
        int column = (total - GAP) / 2;
        if (WanderBotMod.BOT == null) {
            addInfo(80, x, y, total, 64, "Runtime", "Bot controller unavailable", "OFFLINE");
            return;
        }
        addInfo(81, x, y, column, 60, "State",
                String.format("Average tick %.2f ms", WanderBotMod.BOT.getAverageTickMillis()),
                String.valueOf(WanderBotMod.BOT.getState()));
        addInfo(82, x + column + GAP, y, column, 60, "Pit mode",
                String.valueOf(WanderBotMod.BOT.getRuntimeGuardStatus()), String.valueOf(WanderBotMod.BOT.getPitMode()));
        addInfo(83, x, y + 70, column, 60, "Target",
                safe(WanderBotMod.BOT.getTargetName()), isMyauAimOwner() ? "MYAU AIM" : "TRACKING");
        addInfo(84, x + column + GAP, y + 70, column, 60, "Streak",
                WanderBotMod.BOT.getLocalStreak() + " current  •  " + WanderBotMod.BOT.getPeakStreak() + " peak",
                String.valueOf(WanderBotMod.BOT.getStreakTier()));
        addInfo(85, x, y + 140, column, 60, "Target score",
                String.format("%.1f  •  armor %s", WanderBotMod.BOT.getTargetScore(), String.valueOf(WanderBotMod.BOT.getTargetArmor())), "LIVE");
        addInfo(86, x + column + GAP, y + 140, column, 60, "Path",
                currentPathSummary(), WanderBotSettings.navigationEnabled ? "ACTIVE" : "PAUSED");
        addInfo(87, x, y + 210, column, 60, "Client FPS",
                String.valueOf(Minecraft.getDebugFPS()), "FPS");
        addInfo(88, x + column + GAP, y + 210, column, 60, "Loaded players",
                loadedPlayers(), "SCAN");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (category == Category.DEBUG) {
            long now = System.currentTimeMillis();
            if (now - lastDebugRefreshMs >= 250L) {
                lastDebugRefreshMs = now;
                rebuildControls();
            }
        }

        drawBackdrop();
        drawShell(mouseX, mouseY);
        drawCategoryTabs(mouseX, mouseY);
        drawPageHeader();
        drawPageDecorations();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawFooter();
    }

    private void drawBackdrop() {
        drawGradientRect(0, 0, width, height, 0xB9050910, 0xD0080D16);
        drawRect(0, 0, width, height, 0x26000000);
        // Lightweight glass approximation: two translucent strips instead of a shader blur.
        drawRect(0, height / 3, width, height / 3 + 1, 0x100D1C31);
        drawRect(0, height * 2 / 3, width, height * 2 / 3 + 1, 0x100D1C31);
    }

    private void drawShell(int mouseX, int mouseY) {
        roundedRect(left - 7, top - 5, left + panelWidth + 7, top + panelHeight + 8, 12, 0x3C000000);
        roundedRect(left - 3, top - 2, left + panelWidth + 3, top + panelHeight + 4, 10, 0x60000000);
        roundedRect(left, top, left + panelWidth, top + panelHeight, 9, SHELL);
        outlineRoundedRect(left, top, left + panelWidth, top + panelHeight, 9, 0x66466C99);

        roundedLeftRect(left + 1, top + 1, left + SIDEBAR_WIDTH, top + panelHeight - 1, 8, SIDEBAR);
        drawRect(left + SIDEBAR_WIDTH - 1, top + 1, left + SIDEBAR_WIDTH, top + panelHeight - 1, BORDER_SOFT);
        drawRect(left + SIDEBAR_WIDTH, top + HEADER_HEIGHT, left + panelWidth - 1, top + HEADER_HEIGHT + 1, BORDER_SOFT);

        // Brand.
        drawBrandMark(left + 18, top + 17);
        drawString(fontRendererObj, "WANDERBOT", left + 58, top + 17, ACCENT_BRIGHT);
        drawString(fontRendererObj, "CONTROL CENTER", left + 58, top + 32, TEXT_DIM);

        // Version + close button.
        String version = "v" + WanderBotMod.VERSION;
        int versionX = left + panelWidth - 66 - fontRendererObj.getStringWidth(version);
        drawString(fontRendererObj, version, versionX, top + 24, TEXT_MUTED);
        boolean closeHover = isCloseHovered(mouseX, mouseY);
        int cx = left + panelWidth - 27;
        int cy = top + 25;
        if (closeHover) roundedRect(cx - 10, cy - 10, cx + 10, cy + 10, 5, 0x30FF6678);
        drawRect(cx - 5, cy - 1, cx + 6, cy + 1, closeHover ? DANGER : TEXT_MUTED);
        drawRect(cx - 1, cy - 5, cx + 1, cy + 6, closeHover ? DANGER : TEXT_MUTED);
    }

    private void drawPageHeader() {
        int x = contentX();
        drawString(fontRendererObj, category.title, x, top + 78, TEXT);
        drawString(fontRendererObj, category.subtitle, x, top + 94, TEXT_MUTED);

        boolean active = isBotEnabled();
        int pillW = active ? 72 : 58;
        int px = left + panelWidth - CONTENT_PAD - pillW;
        int py = top + 78;
        roundedRect(px, py, px + pillW, py + 20, 10, active ? 0x234EDDA6 : 0x20FF6678);
        drawRect(px + 8, py + 8, px + 12, py + 12, active ? SUCCESS : DANGER);
        drawString(fontRendererObj, active ? "RUNNING" : "IDLE", px + 17, py + 6,
                active ? SUCCESS : 0xFFFF8A98);
    }

    private void drawPageDecorations() {
        if (category == Category.GENERAL) drawGeneralCards();
        if (category == Category.COMBAT) drawCombatSideCard();
        if (category == Category.PIT) drawPitFooterCard();
    }

    private void drawGeneralCards() {
        int x = contentX();
        int y = contentY();
        int total = contentWidth();
        int rightWidth = Math.max(230, Math.min(278, total * 38 / 100));
        int mainWidth = total - rightWidth - GAP;
        int rx = x + mainWidth + GAP;

        // Profile block under the four large module cards.
        int profileY = y + 250;
        drawCard(x, profileY, mainWidth, 95);
        drawString(fontRendererObj, "Profiles", x + 12, profileY + 12, TEXT);
        String profile = PitStreakCatalog.byId(WanderBotSettings.megastreakId) == null
                ? WanderBotSettings.megastreakId
                : PitStreakCatalog.byId(WanderBotSettings.megastreakId).displayName;
        drawString(fontRendererObj, "Current: " + profile, x + 12, profileY + 29, TEXT_MUTED);

        drawCard(rx, y, rightWidth, 212);
        drawString(fontRendererObj, "Performance", rx + 12, y + 12, TEXT);
        drawString(fontRendererObj, "Live controls", rx + rightWidth - 12 - fontRendererObj.getStringWidth("Live controls"),
                y + 12, TEXT_DIM);

        int infoY = y + 222;
        drawCard(rx, infoY, rightWidth, 148);
        drawString(fontRendererObj, "Information", rx + 12, infoY + 12, TEXT);
        drawInfoPair(rx + 12, infoY + 34, "State", isBotEnabled() ? "Running" : "Idle", isBotEnabled() ? SUCCESS : TEXT_MUTED);
        drawInfoPair(rx + 12, infoY + 52, "Aim owner", isMyauAimOwner() ? "Myau" : "WanderBot",
                isMyauAimOwner() ? CORAL : ACCENT_BRIGHT);
        drawInfoPair(rx + 12, infoY + 70, "FPS", String.valueOf(Minecraft.getDebugFPS()), TEXT_SECONDARY);
        drawInfoPair(rx + 12, infoY + 88, "Players", loadedPlayers(), TEXT_SECONDARY);
        drawInfoPair(rx + 12, infoY + 106, "Path nodes", pathNodeCount(), TEXT_SECONDARY);
        drawInfoPair(rx + 12, infoY + 124, "Tick avg", averageTick(), TEXT_SECONDARY);
    }

    private void drawCombatSideCard() {
        int total = contentWidth();
        int leftWidth = (total - GAP) * 58 / 100;
        int rightWidth = total - leftWidth - GAP;
        int rx = contentX() + leftWidth + GAP;
        int y = contentY() + 72;
        drawCard(rx, y, rightWidth, 164);
        drawString(fontRendererObj, "Ownership", rx + 12, y + 12, TEXT);
        drawString(fontRendererObj, "WanderBot keeps locomotion while Myau owns combat aim.",
                rx + 12, y + 34, TEXT_MUTED);
        drawStatusDot(rx + 12, y + 61, "WanderBot movement", WanderBotSettings.navigationEnabled, ACCENT_BRIGHT);
        drawStatusDot(rx + 12, y + 82, "Myau combat aim", isMyauAimOwner(), CORAL);
        drawStatusDot(rx + 12, y + 103, "Combat pipeline", WanderBotSettings.combatEnabled, SUCCESS);
    }

    private void drawPitFooterCard() {
        int x = contentX();
        int y = Math.min(contentBottom() - 60, contentY() + 4 * 54 + 8);
        if (y <= contentY()) return;
        drawCard(x, y, contentWidth(), 52);
        PitStreakCatalog.Definition selected = PitStreakCatalog.byId(WanderBotSettings.megastreakId);
        String name = selected == null ? WanderBotSettings.megastreakId : selected.displayName;
        drawString(fontRendererObj, "Selected profile", x + 12, y + 10, TEXT_MUTED);
        drawString(fontRendererObj, name, x + 12, y + 27, ACCENT_BRIGHT);
        drawString(fontRendererObj, "Changes apply immediately", x + contentWidth() - 12 - fontRendererObj.getStringWidth("Changes apply immediately"),
                y + 27, TEXT_DIM);
    }

    private void drawCategoryTabs(int mouseX, int mouseY) {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            Category item = categories[i];
            int x = left + 12;
            int y = top + 74 + i * 43;
            int w = SIDEBAR_WIDTH - 24;
            boolean selected = category == item;
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 34;

            if (selected) {
                roundedRect(x, y, x + w, y + 34, 6, ACCENT_SOFT);
                drawRect(x, y + 7, x + 2, y + 27, ACCENT_BRIGHT);
            } else if (hover) {
                roundedRect(x, y, x + w, y + 34, 6, 0x5A141D28);
            }

            int iconX = x + 9;
            int iconY = y + 6;
            roundedRect(iconX, iconY, iconX + 22, iconY + 22, 5, selected ? 0x303987FF : 0x201A2534);
            drawCenteredString(fontRendererObj, item.glyph, iconX + 11, iconY + 7,
                    selected ? ACCENT_BRIGHT : TEXT_MUTED);
            drawString(fontRendererObj, item.title, x + 40, y + 13,
                    selected ? TEXT : (hover ? TEXT_SECONDARY : TEXT_MUTED));
        }
    }

    private void drawFooter() {
        int y = top + panelHeight - 28;
        drawKeycap(left + 16, y, "K");
        drawString(fontRendererObj, "Toggle", left + 40, y + 6, TEXT_DIM);
        drawKeycap(left + SIDEBAR_WIDTH + CONTENT_PAD, y, "RSHIFT");
        drawString(fontRendererObj, "Close panel", left + SIDEBAR_WIDTH + CONTENT_PAD + 53, y + 6, TEXT_DIM);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (isCloseHovered(mouseX, mouseY) && button == 0) {
            closeScreen();
            return;
        }

        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            int x = left + 12;
            int y = top + 74 + i * 43;
            int w = SIDEBAR_WIDTH - 24;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 34) {
                category = categories[i];
                rebuildControls();
                return;
            }
        }

        for (Object object : buttonList) {
            if (object instanceof SliderButton) {
                SliderButton slider = (SliderButton) object;
                if (slider.contains(mouseX, mouseY) && button == 0) {
                    slider.setFromMouse(mouseX);
                    draggingSlider = slider;
                    markDirty();
                    return;
                }
            }
        }

        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingSlider != null && clickedMouseButton == 0) {
            draggingSlider.setFromMouse(mouseX);
            markDirty();
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        draggingSlider = null;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof ActionButton) {
            ActionButton modern = (ActionButton) button;
            if (modern.action != null) modern.action.run();
            return;
        }
        if (button instanceof ToggleButton) {
            ToggleButton toggle = (ToggleButton) button;
            toggle.setting.set(!toggle.setting.get());
            markDirty();
            return;
        }
        if (button instanceof SelectButton) {
            SelectButton select = (SelectButton) button;
            if (select.action != null) select.action.run();
        }
    }

    @Override
    public void handleKeyboardInput() throws IOException {
        super.handleKeyboardInput();
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) closeScreen();
    }

    @Override
    public void onGuiClosed() {
        if (settingsDirty) WanderBotSettings.save();
        settingsDirty = false;
        super.onGuiClosed();
    }

    private void closeScreen() {
        if (settingsDirty) WanderBotSettings.save();
        settingsDirty = false;
        mc.displayGuiScreen(null);
    }

    private void markDirty() {
        settingsDirty = true;
        WanderBotSettings.clamp();
    }

    private void syncPitProfile() {
        if (WanderBotMod.BOT != null) {
            WanderBotMod.BOT.getPit().getStreakControl().setMegastreak(WanderBotSettings.megastreakId);
        }
    }

    private void addModuleToggle(int id, int x, int y, int w, int h, String glyph, int tileColor,
                                 String label, String detail, BooleanSetting setting) {
        buttonList.add(new ToggleButton(id, x, y, w, h, label, detail, glyph, tileColor, setting, true));
    }

    private void addCompactToggle(int id, int x, int y, int w, int h, String label, BooleanSetting setting) {
        buttonList.add(new ToggleButton(id, x, y, w, h, label, "", "", 0, setting, false));
    }

    private void addSlider(int id, int x, int y, int w, int h, String label, String detail,
                           double min, double max, double step, NumericSetting setting, String format) {
        buttonList.add(new SliderButton(id, x, y, w, h, label, detail, min, max, step, setting, format, 1.0D));
    }

    private void addSlider(int id, int x, int y, int w, int h, String label, String detail,
                           double min, double max, double step, NumericSetting setting, String format, double displayScale) {
        buttonList.add(new SliderButton(id, x, y, w, h, label, detail, min, max, step, setting, format, displayScale));
    }

    private void addAction(int id, int x, int y, int w, int h, String label, Runnable action) {
        buttonList.add(new ActionButton(id, x, y, w, h, label, action));
    }

    private void addInfo(int id, int x, int y, int w, int h, String label, String detail, String badge) {
        buttonList.add(new InfoButton(id, x, y, w, h, label, detail, badge));
    }

    private void addSelect(int id, int x, int y, int w, int h, String label, String detail,
                           boolean selected, Runnable action) {
        buttonList.add(new SelectButton(id, x, y, w, h, label, detail, selected, action));
    }

    private BooleanSetting settingNavigation() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.navigationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.navigationEnabled = value; }
        };
    }

    private BooleanSetting settingCombat() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.combatEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.combatEnabled = value; }
        };
    }

    private BooleanSetting settingMegastreak() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.megastreakStrategy; }
            @Override public void set(boolean value) { WanderBotSettings.megastreakStrategy = value; }
        };
    }

    private BooleanSetting settingForcePit() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.forcePitMode; }
            @Override public void set(boolean value) { WanderBotSettings.forcePitMode = value; }
        };
    }

    private BooleanSetting settingHumanization() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.humanizationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.humanizationEnabled = value; }
        };
    }

    private BooleanSetting settingPath() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showPath; }
            @Override public void set(boolean value) { WanderBotSettings.showPath = value; }
        };
    }

    private BooleanSetting settingTarget() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTarget; }
            @Override public void set(boolean value) { WanderBotSettings.showTarget = value; }
        };
    }

    private BooleanSetting settingTargetGuide() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTargetGuide; }
            @Override public void set(boolean value) { WanderBotSettings.showTargetGuide = value; }
        };
    }

    private BooleanSetting settingHud() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showHud; }
            @Override public void set(boolean value) { WanderBotSettings.showHud = value; }
        };
    }

    private BooleanSetting settingDebugDashboard() {
        return new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.debugDashboard; }
            @Override public void set(boolean value) { WanderBotSettings.debugDashboard = value; }
        };
    }

    private boolean isBotEnabled() {
        return WanderBotMod.BOT != null && WanderBotMod.BOT.isEnabled();
    }

    private boolean isMyauAimOwner() {
        return WanderBotMod.BOT != null && WanderBotMod.BOT.isMyauAimOwner();
    }

    private String killAuraStatus() {
        return WanderBotMod.BOT == null ? "OFF" : WanderBotMod.BOT.getKillAuraStatus();
    }

    private String averageTick() {
        long now = System.currentTimeMillis();
        if (now - lastInfoRefreshMs >= 250L) {
            lastInfoRefreshMs = now;
            cachedAverageTick = WanderBotMod.BOT == null
                    ? "—" : String.format("%.2f ms", WanderBotMod.BOT.getAverageTickMillis());
        }
        return cachedAverageTick;
    }

    private String currentPathSummary() {
        if (WanderBotMod.BOT == null) return "No runtime path";
        Path path = WanderBotMod.BOT.getPath();
        if (path == null) return "No active route";
        return path.getIndex() + " / " + path.size() + " nodes";
    }

    private String pathNodeCount() {
        if (WanderBotMod.BOT == null) return "0";
        Path path = WanderBotMod.BOT.getPath();
        return path == null ? "0" : String.valueOf(path.size());
    }

    private String loadedPlayers() {
        if (mc == null || mc.theWorld == null || mc.theWorld.playerEntities == null) return "0";
        return String.valueOf(mc.theWorld.playerEntities.size());
    }

    private String safe(String value) {
        return value == null || value.length() == 0 ? "None" : value;
    }

    private boolean isCloseHovered(int mouseX, int mouseY) {
        int cx = left + panelWidth - 27;
        int cy = top + 25;
        return mouseX >= cx - 11 && mouseX <= cx + 11 && mouseY >= cy - 11 && mouseY <= cy + 11;
    }

    private void drawBrandMark(int x, int y) {
        // Geometric W made only from vanilla rectangles.
        roundedRect(x, y, x + 30, y + 30, 7, 0x183987FF);
        drawRect(x + 5, y + 6, x + 9, y + 22, ACCENT_BRIGHT);
        drawRect(x + 10, y + 15, x + 14, y + 25, ACCENT);
        drawRect(x + 15, y + 10, x + 19, y + 22, ACCENT_BRIGHT);
        drawRect(x + 20, y + 6, x + 24, y + 18, ACCENT);
    }

    private void drawCard(int x, int y, int w, int h) {
        roundedRect(x, y, x + w, y + h, 7, SURFACE);
        outlineRoundedRect(x, y, x + w, y + h, 7, BORDER_SOFT);
    }

    private void drawInfoPair(int x, int y, String key, String value, int valueColor) {
        drawString(fontRendererObj, key, x, y, TEXT_DIM);
        int valueX = x + 74;
        drawString(fontRendererObj, trim(value, Math.max(20, contentWidth() / 3)), valueX, y, valueColor);
    }

    private void drawStatusDot(int x, int y, String label, boolean active, int activeColor) {
        drawRect(x, y + 3, x + 5, y + 8, active ? activeColor : TEXT_DIM);
        drawString(fontRendererObj, label, x + 12, y, active ? TEXT_SECONDARY : TEXT_DIM);
        String state = active ? "ON" : "OFF";
        drawString(fontRendererObj, state, x + 150, y, active ? activeColor : TEXT_DIM);
    }

    private void drawKeycap(int x, int y, String key) {
        int w = fontRendererObj.getStringWidth(key) + 12;
        roundedRect(x, y, x + w, y + 20, 5, 0xFF111925);
        outlineRoundedRect(x, y, x + w, y + 20, 5, BORDER);
        drawString(fontRendererObj, key, x + 6, y + 6, TEXT_SECONDARY);
    }

    private static String trim(String text, int maxPixels) {
        Minecraft mc = Minecraft.getMinecraft();
        if (text == null) return "";
        if (mc.fontRendererObj.getStringWidth(text) <= maxPixels) return text;
        String suffix = "...";
        int target = Math.max(0, maxPixels - mc.fontRendererObj.getStringWidth(suffix));
        return mc.fontRendererObj.trimStringToWidth(text, target) + suffix;
    }

    private interface BooleanSetting {
        boolean get();
        void set(boolean value);
    }

    private interface NumericSetting {
        double get();
        void set(double value);
    }

    private static final class ToggleButton extends GuiButton {
        final String label;
        final String detail;
        final String glyph;
        final int tileColor;
        final BooleanSetting setting;
        final boolean moduleStyle;
        float visualProgress;

        ToggleButton(int id, int x, int y, int width, int height, String label, String detail,
                     String glyph, int tileColor, BooleanSetting setting, boolean moduleStyle) {
            super(id, x, y, width, height, label);
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.glyph = glyph == null ? "" : glyph;
            this.tileColor = tileColor;
            this.setting = setting;
            this.moduleStyle = moduleStyle;
            this.visualProgress = setting.get() ? 1.0F : 0.0F;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = contains(mouseX, mouseY);
            boolean active = setting.get();
            float target = active ? 1.0F : 0.0F;
            visualProgress += (target - visualProgress) * 0.24F;

            if (moduleStyle) {
                int bg = hover ? SURFACE_HOVER : SURFACE_RAISED;
                roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 7, bg);
                outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 7,
                        active ? 0x663987FF : BORDER_SOFT);
                roundedRect(xPosition + 12, yPosition + 11, xPosition + 44, yPosition + 43, 8,
                        active ? tileColor : 0x251A2534);
                if (glyph.length() > 0) {
                    int gx = xPosition + 28 - mc.fontRendererObj.getStringWidth(glyph) / 2;
                    mc.fontRendererObj.drawString(glyph, gx, yPosition + 22, active ? TEXT : TEXT_MUTED);
                }
                mc.fontRendererObj.drawString(label, xPosition + 56, yPosition + 12, TEXT);
                mc.fontRendererObj.drawString(trim(detail, width - 130), xPosition + 56, yPosition + 29, TEXT_MUTED);
                drawToggle(xPosition + width - 52, yPosition + 18, visualProgress, active);
            } else {
                mc.fontRendererObj.drawString(label, xPosition, yPosition + 9, hover ? TEXT : TEXT_SECONDARY);
                drawToggle(xPosition + width - 38, yPosition + 6, visualProgress, active);
            }
        }

        boolean contains(int mx, int my) {
            return mx >= xPosition && mx < xPosition + width && my >= yPosition && my < yPosition + height;
        }

        private static void drawToggle(int x, int y, float progress, boolean active) {
            int track = active ? 0xFF347FF5 : 0xFF283444;
            roundedRect(x, y, x + 40, y + 20, 10, track);
            int knobX = x + 2 + Math.round(progress * 20.0F);
            roundedRect(knobX, y + 2, knobX + 16, y + 18, 8,
                    active ? 0xFFF8FAFF : 0xFFA8B4C5);
        }
    }

    private static final class SliderButton extends GuiButton {
        final String label;
        final String detail;
        final double min;
        final double max;
        final double step;
        final NumericSetting setting;
        final String valueFormat;
        final double displayScale;

        SliderButton(int id, int x, int y, int width, int height, String label, String detail,
                     double min, double max, double step, NumericSetting setting,
                     String valueFormat, double displayScale) {
            super(id, x, y, width, height, label);
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.min = min;
            this.max = max;
            this.step = step;
            this.setting = setting;
            this.valueFormat = valueFormat;
            this.displayScale = displayScale;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = contains(mouseX, mouseY);
            int bg = hover ? SURFACE_HOVER : SURFACE;
            roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6, bg);
            outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6, BORDER_SOFT);

            mc.fontRendererObj.drawString(label, xPosition + 10, yPosition + 8, TEXT);
            String value = String.format(valueFormat, setting.get() * displayScale);
            mc.fontRendererObj.drawString(value,
                    xPosition + width - 10 - mc.fontRendererObj.getStringWidth(value), yPosition + 8, ACCENT_BRIGHT);
            if (detail.length() > 0) mc.fontRendererObj.drawString(trim(detail, width - 20), xPosition + 10, yPosition + 22, TEXT_DIM);

            int trackLeft = xPosition + 10;
            int trackRight = xPosition + width - 10;
            int trackY = yPosition + height - 10;
            roundedRect(trackLeft, trackY, trackRight, trackY + 4, 2, 0xFF283548);
            double fraction = (setting.get() - min) / Math.max(0.000001D, max - min);
            fraction = Math.max(0.0D, Math.min(1.0D, fraction));
            int fillRight = trackLeft + (int) Math.round((trackRight - trackLeft) * fraction);
            if (fillRight > trackLeft) roundedRect(trackLeft, trackY, fillRight, trackY + 4, 2, ACCENT);
            int knob = Math.max(trackLeft, Math.min(trackRight - 8, fillRight - 4));
            roundedRect(knob, trackY - 2, knob + 8, trackY + 6, 4, hover ? ACCENT_BRIGHT : ACCENT);
        }

        void setFromMouse(int mouseX) {
            int trackLeft = xPosition + 10;
            int trackRight = xPosition + width - 10;
            double fraction = (mouseX - trackLeft) / (double) Math.max(1, trackRight - trackLeft);
            fraction = Math.max(0.0D, Math.min(1.0D, fraction));
            double raw = min + (max - min) * fraction;
            double snapped = step <= 0.0D ? raw : Math.round((raw - min) / step) * step + min;
            setting.set(Math.max(min, Math.min(max, snapped)));
            WanderBotSettings.clamp();
        }

        boolean contains(int mx, int my) {
            return mx >= xPosition && mx < xPosition + width && my >= yPosition && my < yPosition + height;
        }
    }

    private static final class ActionButton extends GuiButton {
        final String label;
        final Runnable action;

        ActionButton(int id, int x, int y, int width, int height, String label, Runnable action) {
            super(id, x, y, width, height, label);
            this.label = label;
            this.action = action;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = mouseX >= xPosition && mouseX < xPosition + width
                    && mouseY >= yPosition && mouseY < yPosition + height;
            roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 5,
                    hover ? 0xFF24344B : 0xFF192334);
            outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 5,
                    hover ? 0x804A8DFF : BORDER_SOFT);
            int tx = xPosition + (width - mc.fontRendererObj.getStringWidth(label)) / 2;
            mc.fontRendererObj.drawString(label, tx, yPosition + (height - 8) / 2, hover ? TEXT : TEXT_SECONDARY);
        }
    }

    private static final class InfoButton extends GuiButton {
        final String label;
        final String detail;
        final String badge;

        InfoButton(int id, int x, int y, int width, int height, String label, String detail, String badge) {
            super(id, x, y, width, height, label);
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.badge = badge == null ? "" : badge;
            enabled = false;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 7, SURFACE);
            outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 7, BORDER_SOFT);
            mc.fontRendererObj.drawString(label, xPosition + 12, yPosition + 11, TEXT);
            mc.fontRendererObj.drawString(trim(detail, width - 24), xPosition + 12, yPosition + 30, TEXT_MUTED);
            if (badge.length() > 0) {
                int bw = mc.fontRendererObj.getStringWidth(badge) + 14;
                int bx = xPosition + width - bw - 10;
                roundedRect(bx, yPosition + 8, bx + bw, yPosition + 25, 8, 0x203987FF);
                int tx = bx + (bw - mc.fontRendererObj.getStringWidth(badge)) / 2;
                mc.fontRendererObj.drawString(badge, tx, yPosition + 13, badge.contains("OFF") ? TEXT_DIM : ACCENT_BRIGHT);
            }
        }
    }

    private static final class SelectButton extends GuiButton {
        final String label;
        final String detail;
        final boolean selected;
        final Runnable action;

        SelectButton(int id, int x, int y, int width, int height, String label, String detail,
                     boolean selected, Runnable action) {
            super(id, x, y, width, height, label);
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.selected = selected;
            this.action = action;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = mouseX >= xPosition && mouseX < xPosition + width
                    && mouseY >= yPosition && mouseY < yPosition + height;
            int bg = selected ? 0x303987FF : (hover ? SURFACE_HOVER : SURFACE_RAISED);
            roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6, bg);
            outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6,
                    selected ? 0x883987FF : BORDER_SOFT);
            mc.fontRendererObj.drawString(label, xPosition + 12, yPosition + 9, selected ? TEXT : TEXT_SECONDARY);
            mc.fontRendererObj.drawString(detail, xPosition + 12, yPosition + 25,
                    selected ? 0xFF9FC1FF : TEXT_DIM);
            if (selected) {
                roundedRect(xPosition + width - 25, yPosition + 13, xPosition + width - 11, yPosition + 27, 7, ACCENT);
                String mark = "+";
                int tx = xPosition + width - 18 - mc.fontRendererObj.getStringWidth(mark) / 2;
                mc.fontRendererObj.drawString(mark, tx, yPosition + 16, 0xFFFFFFFF);
            }
        }
    }

    private static void roundedLeftRect(int left, int top, int right, int bottom, int radius, int color) {
        roundedRect(left, top, right + radius, bottom, radius, color);
        Gui.drawRect(right, top, right + radius, bottom, color);
    }

    private static void outlineRoundedRect(int left, int top, int right, int bottom, int radius, int color) {
        int r = Math.max(1, radius);
        Gui.drawRect(left + r, top, right - r, top + 1, color);
        Gui.drawRect(left + r, bottom - 1, right - r, bottom, color);
        Gui.drawRect(left, top + r, left + 1, bottom - r, color);
        Gui.drawRect(right - 1, top + r, right, bottom - r, color);
        Gui.drawRect(left + 1, top + 2, left + 2, top + r, color);
        Gui.drawRect(right - 2, top + 2, right - 1, top + r, color);
        Gui.drawRect(left + 1, bottom - r, left + 2, bottom - 2, color);
        Gui.drawRect(right - 2, bottom - r, right - 1, bottom - 2, color);
    }

    private static void roundedRect(int left, int top, int right, int bottom, int radius, int color) {
        if (right <= left || bottom <= top) return;
        int maxRadius = Math.min((right - left) / 2, (bottom - top) / 2);
        int r = Math.max(0, Math.min(radius, maxRadius));
        if (r == 0) {
            Gui.drawRect(left, top, right, bottom, color);
            return;
        }
        Gui.drawRect(left + r, top, right - r, bottom, color);
        Gui.drawRect(left, top + r, right, bottom - r, color);
        int[] insets = roundedInsets(r);
        for (int y = 0; y < r; y++) {
            int inset = insets[y];
            Gui.drawRect(left + inset, top + y, right - inset, top + y + 1, color);
            Gui.drawRect(left + inset, bottom - y - 1, right - inset, bottom - y, color);
        }
    }

    private static int[] roundedInsets(int radius) {
        int r = Math.max(0, Math.min(radius, ROUND_INSET_CACHE.length - 1));
        int[] cached = ROUND_INSET_CACHE[r];
        if (cached != null) return cached;
        cached = new int[r];
        double rr = r * r;
        for (int y = 0; y < r; y++) {
            double dy = r - y - 0.5D;
            cached[y] = (int) Math.ceil(r - Math.sqrt(Math.max(0.0D, rr - dy * dy)));
        }
        ROUND_INSET_CACHE[r] = cached;
        return cached;
    }
}
