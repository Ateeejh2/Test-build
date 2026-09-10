package com.atlasdead.wanderbot.gui;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * Modern, low-noise control center for WanderBot.
 *
 * The screen intentionally uses only vanilla 1.8.9 rendering primitives so it
 * stays dependency-free and works with the ForgeGradle setup used by the mod.
 */
public final class ClickGuiScreen extends GuiScreen {
    private static final int MAX_PANEL_WIDTH = 860;
    private static final int MAX_PANEL_HEIGHT = 470;
    private static final int MIN_PANEL_WIDTH = 690;
    private static final int SIDEBAR_WIDTH = 184;
    private static final int HEADER_HEIGHT = 66;
    private static final int ROW_HEIGHT = 42;
    private static final int CONTENT_GAP = 10;

    private static final int BG = 0xF5090C12;
    private static final int SURFACE = 0xFA0E131B;
    private static final int SURFACE_RAISED = 0xFF121924;
    private static final int SURFACE_HOVER = 0xFF182231;
    private static final int BORDER = 0xFF202B3A;
    private static final int BORDER_SOFT = 0xFF18212D;
    private static final int TEXT = 0xFFF1F5FB;
    private static final int TEXT_MUTED = 0xFF8190A5;
    private static final int TEXT_DIM = 0xFF5F6D80;
    private static final int ACCENT = 0xFF6E8DFF;
    private static final int ACCENT_BRIGHT = 0xFF88A3FF;
    private static final int ACCENT_SOFT = 0x336E8DFF;
    private static final int SUCCESS = 0xFF5DE1B0;
    private static final int DANGER = 0xFFFF6B7A;

    private int left;
    private int top;
    private int panelWidth;
    private int panelHeight;
    private Category category = Category.GENERAL;

    private enum Category {
        GENERAL("General", "Runtime controls", "01"),
        RENDER("Render", "World overlays", "02"),
        COMBAT("Combat", "Combat runtime", "03"),
        NAVIGATION("Navigation", "Pathfinding", "04"),
        PIT("Pit", "Megastreak profile", "05"),
        DEBUG("Debug", "Live telemetry", "06");

        final String title;
        final String subtitle;
        final String index;

        Category(String title, String subtitle, String index) {
            this.title = title;
            this.subtitle = subtitle;
            this.index = index;
        }
    }

    @Override
    public void initGui() {
        recalculateLayout();
        buttonList.clear();
        buildCategoryControls();
    }

    private void recalculateLayout() {
        panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, width - 32));
        panelWidth = Math.min(panelWidth, Math.max(320, width - 20));
        panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(390, height - 32));
        panelHeight = Math.min(panelHeight, Math.max(280, height - 20));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
    }

    private int contentX() {
        return left + SIDEBAR_WIDTH + 28;
    }

    private int contentWidth() {
        return Math.max(300, panelWidth - SIDEBAR_WIDTH - 56);
    }

    private int contentY() {
        return top + HEADER_HEIGHT + 24;
    }

    private void buildCategoryControls() {
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
        toggle(10, x, y, "WanderBot", "Master runtime", isBotEnabled(), new Runnable() {
            @Override public void run() {
                if (WanderBotMod.BOT != null) WanderBotMod.BOT.toggle();
                initGui();
            }
        });
        toggleSetting(11, x, y + ROW_HEIGHT, "Navigation", "Movement + path planner", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.navigationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.navigationEnabled = value; }
        });
        toggleSetting(12, x, y + ROW_HEIGHT * 2, "Combat", "Combat decision pipeline", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.combatEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.combatEnabled = value; }
        });
        toggleSetting(13, x, y + ROW_HEIGHT * 3, "Megastreak strategy", "Adaptive streak policy", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.megastreakStrategy; }
            @Override public void set(boolean value) { WanderBotSettings.megastreakStrategy = value; }
        });
        toggleSetting(17, x, y + ROW_HEIGHT * 4, "Force Pit mode", "Keep Pit routing active", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.forcePitMode; }
            @Override public void set(boolean value) { WanderBotSettings.forcePitMode = value; }
        });
        note(15, x, y + ROW_HEIGHT * 5 + 10, "K toggles the bot  •  Right Shift opens this panel");
    }

    private void buildRender(int x, int y) {
        toggleSetting(20, x, y, "Path visualization", "Clean glow + route markers", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showPath; }
            @Override public void set(boolean value) { WanderBotSettings.showPath = value; }
        });
        toggleSetting(21, x, y + ROW_HEIGHT, "Target box", "Minimal target outline", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTarget; }
            @Override public void set(boolean value) { WanderBotSettings.showTarget = value; }
        });
        toggleSetting(22, x, y + ROW_HEIGHT * 2, "Target guide", "Line from player to target", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTargetGuide; }
            @Override public void set(boolean value) { WanderBotSettings.showTargetGuide = value; }
        });
        toggleSetting(23, x, y + ROW_HEIGHT * 3, "HUD", "Compact runtime information", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showHud; }
            @Override public void set(boolean value) { WanderBotSettings.showHud = value; }
        });
        toggleSetting(24, x, y + ROW_HEIGHT * 4, "Debug dashboard", "Extended diagnostics", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.debugDashboard; }
            @Override public void set(boolean value) { WanderBotSettings.debugDashboard = value; }
        });
        note(25, x, y + ROW_HEIGHT * 5 + 10, "Navigation paths use blue; combat paths use coral.");
    }

    private void buildCombat(int x, int y) {
        toggleSetting(30, x, y, "Combat enabled", "Master combat switch", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.combatEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.combatEnabled = value; }
        });
        info(32, x, y + ROW_HEIGHT, "Target scan", "Loaded players", "LIVE");
        toggleSetting(35, x, y + ROW_HEIGHT * 2, "Force Pit mode", "Keep Pit combat policy active", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.forcePitMode; }
            @Override public void set(boolean value) { WanderBotSettings.forcePitMode = value; }
        });
        note(36, x, y + ROW_HEIGHT * 3 + 12, "KillAura state is synchronized through the existing Myau bridge.");
        note(37, x, y + ROW_HEIGHT * 3 + 32, "Range follows the configured swing/combat range.");
    }

    private void buildNavigation(int x, int y) {
        toggleSetting(40, x, y, "Navigation enabled", "Terrain-aware movement", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.navigationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.navigationEnabled = value; }
        });
        cycleInt(41, x, y + ROW_HEIGHT, "Repath interval", "Ticks between route refreshes", 2, 40, new IntSetting() {
            @Override public int get() { return WanderBotSettings.navRepathTicks; }
            @Override public void set(int value) { WanderBotSettings.navRepathTicks = value; }
        });
        cycleDouble(42, x, y + ROW_HEIGHT * 2, "Lookahead", "Steering preview distance", 0.5D, 1.0D, 8.0D, new DoubleSetting() {
            @Override public double get() { return WanderBotSettings.lookaheadDistance; }
            @Override public void set(double value) { WanderBotSettings.lookaheadDistance = value; }
        });
        note(43, x, y + ROW_HEIGHT * 3 + 14, "A* routing, local avoidance and recovery stay enabled.");
    }

    private void buildPit(int x, int y) {
        List<PitStreakCatalog.Definition> modes = PitStreakCatalog.ALL_MEGASTREAKS;
        int gap = 10;
        int columnWidth = (contentWidth() - gap) / 2;
        for (int i = 0; i < modes.size(); i++) {
            final PitStreakCatalog.Definition definition = modes.get(i);
            final String id = definition.id;
            int column = i % 2;
            int row = i / 2;
            int bx = x + column * (columnWidth + gap);
            int by = y + row * 50;
            boolean selected = id.equalsIgnoreCase(WanderBotSettings.megastreakId);
            buttonList.add(ModernButton.select(50 + i, bx, by, columnWidth, 40,
                    definition.displayName, "Lv " + definition.unlockLevel, selected, new Runnable() {
                @Override public void run() {
                    WanderBotSettings.megastreakId = id;
                    if (WanderBotMod.BOT != null) {
                        WanderBotMod.BOT.getPit().getStreakControl().setMegastreak(id);
                    }
                    persistAndRefresh();
                }
            }));
        }

        PitStreakCatalog.Definition selected = PitStreakCatalog.byId(WanderBotSettings.megastreakId);
        String selectedName = selected == null ? WanderBotSettings.megastreakId : selected.displayName;
        note(90, x, y + 4 * 50 + 12, "Selected profile  •  " + selectedName);
    }

    private void buildDebug(int x, int y) {
        if (WanderBotMod.BOT == null) {
            info(70, x, y, "Runtime", "Bot controller unavailable", "OFFLINE");
            return;
        }

        info(71, x, y, "State", String.valueOf(WanderBotMod.BOT.getState()), "LIVE");
        info(72, x, y + ROW_HEIGHT, "Pit mode", String.valueOf(WanderBotMod.BOT.getPitMode()), "");
        info(73, x, y + ROW_HEIGHT * 2, "Target", safe(WanderBotMod.BOT.getTargetName()), "");
        info(74, x, y + ROW_HEIGHT * 3, "Streak",
                WanderBotMod.BOT.getLocalStreak() + "  /  peak " + WanderBotMod.BOT.getPeakStreak(), "");
        info(75, x, y + ROW_HEIGHT * 4, "Target score",
                String.format("%.1f", WanderBotMod.BOT.getTargetScore()), "");
        info(76, x, y + ROW_HEIGHT * 5, "Armor", String.valueOf(WanderBotMod.BOT.getTargetArmor()), "");
    }

    private static String safe(String value) {
        return value == null || value.length() == 0 ? "None" : value;
    }

    private boolean isBotEnabled() {
        return WanderBotMod.BOT != null && WanderBotMod.BOT.isEnabled();
    }

    private void toggleSetting(int id, int x, int y, String label, String detail, final BooleanSetting setting) {
        toggle(id, x, y, label, detail, setting.get(), new Runnable() {
            @Override public void run() {
                setting.set(!setting.get());
                persistAndRefresh();
            }
        });
    }

    private void toggle(int id, int x, int y, String label, String detail, boolean value, Runnable action) {
        buttonList.add(ModernButton.toggle(id, x, y, contentWidth(), 34, label, detail, value, action));
    }

    private void cycleInt(int id, int x, int y, String label, String detail, final int min, final int max,
                          final IntSetting setting) {
        buttonList.add(ModernButton.value(id, x, y, contentWidth(), 34, label, detail,
                String.valueOf(setting.get()), new Runnable() {
            @Override public void run() {
                int next = setting.get() + 1;
                if (next > max) next = min;
                setting.set(next);
                WanderBotSettings.clamp();
                persistAndRefresh();
            }
        }));
    }

    private void cycleDouble(int id, int x, int y, String label, String detail, final double step,
                             final double min, final double max, final DoubleSetting setting) {
        buttonList.add(ModernButton.value(id, x, y, contentWidth(), 34, label, detail,
                format(setting.get()), new Runnable() {
            @Override public void run() {
                double next = setting.get() + step;
                if (next > max + 1.0E-6D) next = min;
                setting.set(next);
                WanderBotSettings.clamp();
                persistAndRefresh();
            }
        }));
    }

    private void info(int id, int x, int y, String label, String detail, String badge) {
        buttonList.add(ModernButton.info(id, x, y, contentWidth(), 34, label, detail, badge));
    }

    private void note(int id, int x, int y, String text) {
        buttonList.add(ModernButton.note(id, x, y, contentWidth(), 24, text));
    }

    private void persistAndRefresh() {
        WanderBotSettings.save();
        initGui();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof ModernButton) {
            ModernButton modern = (ModernButton) button;
            if (modern.action != null) modern.action.run();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        recalculateLayout();
        drawBackdrop();
        drawShell();
        drawCategoryTabs(mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawFooter();
    }

    private void drawBackdrop() {
        drawGradientRect(0, 0, width, height, 0xC8060910, 0xD00A0F18);
        drawRect(0, 0, width, height, 0x22000000);
    }

    private void drawShell() {
        // Soft shadow.
        roundedRect(left - 4, top - 3, left + panelWidth + 4, top + panelHeight + 5, 10, 0x4A000000);
        roundedRect(left - 2, top - 2, left + panelWidth + 2, top + panelHeight + 2, 9, 0x62000000);

        roundedRect(left, top, left + panelWidth, top + panelHeight, 8, BG);
        roundedRect(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, 7, SURFACE);

        // Sidebar and top content header.
        roundedLeftRect(left + 1, top + 1, left + SIDEBAR_WIDTH, top + panelHeight - 1, 7, 0xFF0B1017);
        drawRect(left + SIDEBAR_WIDTH - 1, top + 1, left + SIDEBAR_WIDTH, top + panelHeight - 1, BORDER_SOFT);
        drawRect(left + SIDEBAR_WIDTH, top + HEADER_HEIGHT, left + panelWidth - 1, top + HEADER_HEIGHT + 1, BORDER_SOFT);

        // Brand mark.
        roundedRect(left + 18, top + 16, left + 46, top + 44, 6, ACCENT);
        drawCenteredString(fontRendererObj, "W", left + 32, top + 26, 0xFFFFFFFF);
        drawString(fontRendererObj, "WanderBot", left + 56, top + 18, TEXT);
        drawString(fontRendererObj, "CONTROL", left + 56, top + 33, TEXT_DIM);

        // Active page header.
        drawString(fontRendererObj, category.title, contentX(), top + 18, TEXT);
        drawString(fontRendererObj, category.subtitle, contentX(), top + 36, TEXT_MUTED);

        boolean active = isBotEnabled();
        int pillWidth = 68;
        int pillX = left + panelWidth - pillWidth - 20;
        int pillY = top + 20;
        roundedRect(pillX, pillY, pillX + pillWidth, pillY + 22, 11,
                active ? 0x245DE1B0 : 0x20FF6B7A);
        drawRect(pillX + 8, pillY + 9, pillX + 12, pillY + 13, active ? SUCCESS : DANGER);
        drawString(fontRendererObj, active ? "ACTIVE" : "IDLE", pillX + 18, pillY + 7,
                active ? SUCCESS : 0xFFFF8A95);
    }

    private void drawCategoryTabs(int mouseX, int mouseY) {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            Category item = categories[i];
            int x = left + 14;
            int y = top + 76 + i * 46;
            int w = SIDEBAR_WIDTH - 28;
            boolean selected = category == item;
            boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 36;

            if (selected) {
                roundedRect(x, y, x + w, y + 36, 6, ACCENT_SOFT);
                drawRect(x, y + 8, x + 2, y + 28, ACCENT_BRIGHT);
            } else if (hover) {
                roundedRect(x, y, x + w, y + 36, 6, 0x66141C27);
            }

            drawString(fontRendererObj, item.index, x + 12, y + 14,
                    selected ? ACCENT_BRIGHT : TEXT_DIM);
            drawString(fontRendererObj, item.title, x + 38, y + 14,
                    selected ? TEXT : (hover ? 0xFFCBD4E1 : TEXT_MUTED));
        }
    }

    private void drawFooter() {
        int y = top + panelHeight - 30;
        drawKeycap(left + 18, y, "K");
        drawString(fontRendererObj, "Toggle", left + 44, y + 6, TEXT_DIM);
        drawKeycap(left + SIDEBAR_WIDTH + 28, y, "RSHIFT");
        drawString(fontRendererObj, "Panel", left + SIDEBAR_WIDTH + 79, y + 6, TEXT_DIM);
    }

    private void drawKeycap(int x, int y, String key) {
        int w = fontRendererObj.getStringWidth(key) + 12;
        roundedRect(x, y, x + w, y + 20, 5, 0xFF111925);
        outlineRoundedRect(x, y, x + w, y + 20, 5, BORDER);
        drawString(fontRendererObj, key, x + 6, y + 6, 0xFFB9C5D5);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            int x = left + 14;
            int y = top + 76 + i * 46;
            int w = SIDEBAR_WIDTH - 28;
            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + 36) {
                category = categories[i];
                initGui();
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void handleKeyboardInput() throws IOException {
        super.handleKeyboardInput();
        if (Keyboard.getEventKeyState() && Keyboard.getEventKey() == Keyboard.KEY_ESCAPE) {
            WanderBotSettings.save();
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void onGuiClosed() {
        WanderBotSettings.save();
        super.onGuiClosed();
    }

    private static String format(double value) {
        return String.format("%.2f", value);
    }

    private interface BooleanSetting {
        boolean get();
        void set(boolean value);
    }

    private interface IntSetting {
        int get();
        void set(int value);
    }

    private interface DoubleSetting {
        double get();
        void set(double value);
    }

    private enum ButtonKind { TOGGLE, VALUE, INFO, SELECT, NOTE }

    private static final class ModernButton extends GuiButton {
        final Runnable action;
        final ButtonKind kind;
        final String label;
        final String detail;
        final String value;
        final boolean active;

        private ModernButton(int id, int x, int y, int width, int height, ButtonKind kind,
                             String label, String detail, String value, boolean active, Runnable action) {
            super(id, x, y, width, height, label);
            this.kind = kind;
            this.label = label;
            this.detail = detail == null ? "" : detail;
            this.value = value == null ? "" : value;
            this.active = active;
            this.action = action;
            enabled = action != null;
        }

        static ModernButton toggle(int id, int x, int y, int width, int height, String label,
                                   String detail, boolean active, Runnable action) {
            return new ModernButton(id, x, y, width, height, ButtonKind.TOGGLE,
                    label, detail, "", active, action);
        }

        static ModernButton value(int id, int x, int y, int width, int height, String label,
                                  String detail, String value, Runnable action) {
            return new ModernButton(id, x, y, width, height, ButtonKind.VALUE,
                    label, detail, value, false, action);
        }

        static ModernButton info(int id, int x, int y, int width, int height, String label,
                                 String detail, String badge) {
            return new ModernButton(id, x, y, width, height, ButtonKind.INFO,
                    label, detail, badge, false, null);
        }

        static ModernButton select(int id, int x, int y, int width, int height, String label,
                                   String detail, boolean selected, Runnable action) {
            return new ModernButton(id, x, y, width, height, ButtonKind.SELECT,
                    label, detail, "", selected, action);
        }

        static ModernButton note(int id, int x, int y, int width, int height, String text) {
            return new ModernButton(id, x, y, width, height, ButtonKind.NOTE,
                    text, "", "", false, null);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = action != null && mouseX >= xPosition && mouseX < xPosition + width
                    && mouseY >= yPosition && mouseY < yPosition + height;

            if (kind == ButtonKind.NOTE) {
                mc.fontRendererObj.drawString(label, xPosition, yPosition + 7, TEXT_DIM);
                return;
            }

            int background = hover ? SURFACE_HOVER : SURFACE_RAISED;
            if (kind == ButtonKind.SELECT && active) background = 0x2E6E8DFF;
            roundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6, background);
            outlineRoundedRect(xPosition, yPosition, xPosition + width, yPosition + height, 6,
                    kind == ButtonKind.SELECT && active ? 0x886E8DFF : BORDER_SOFT);

            if (kind == ButtonKind.SELECT) {
                mc.fontRendererObj.drawString(label, xPosition + 12, yPosition + 9,
                        active ? TEXT : 0xFFD7DFEA);
                mc.fontRendererObj.drawString(detail, xPosition + 12, yPosition + 24,
                        active ? 0xFF9DB1FF : TEXT_DIM);
                if (active) {
                    roundedRect(xPosition + width - 26, yPosition + 12,
                            xPosition + width - 12, yPosition + 26, 7, ACCENT);
                    mc.fontRendererObj.drawString("+", xPosition + width - 22, yPosition + 15, 0xFFFFFFFF);
                }
                return;
            }

            mc.fontRendererObj.drawString(label, xPosition + 12, yPosition + 7, TEXT);
            if (detail.length() > 0) {
                int detailX = xPosition + 12 + mc.fontRendererObj.getStringWidth(label) + 10;
                int maxDetailX = xPosition + width - 88;
                if (detailX < maxDetailX) {
                    mc.fontRendererObj.drawString(trim(mc, detail, maxDetailX - detailX),
                            detailX, yPosition + 7, TEXT_DIM);
                }
            }

            if (kind == ButtonKind.TOGGLE) {
                drawToggle(xPosition + width - 48, yPosition + 8, active);
            } else if (kind == ButtonKind.VALUE) {
                drawValuePill(mc, xPosition + width - 68, yPosition + 7, value, hover);
            } else if (kind == ButtonKind.INFO && value.length() > 0) {
                drawValuePill(mc, xPosition + width - 74, yPosition + 7, value, false);
            }
        }

        private static void drawToggle(int x, int y, boolean active) {
            int track = active ? 0xFF4E6FE8 : 0xFF263140;
            roundedRect(x, y, x + 36, y + 18, 9, track);
            int knobX = active ? x + 20 : x + 2;
            roundedRect(knobX, y + 2, knobX + 14, y + 16, 7,
                    active ? 0xFFF8FAFF : 0xFFAEB9C8);
        }

        private static void drawValuePill(Minecraft mc, int x, int y, String text, boolean hover) {
            int w = Math.max(50, mc.fontRendererObj.getStringWidth(text) + 16);
            int right = x + 60;
            int left = right - w;
            roundedRect(left, y, right, y + 20, 5, hover ? 0xFF25344B : 0xFF1B2636);
            mc.fontRendererObj.drawString(text, left + (w - mc.fontRendererObj.getStringWidth(text)) / 2,
                    y + 6, hover ? ACCENT_BRIGHT : 0xFFA7B7CB);
        }

        private static String trim(Minecraft mc, String text, int width) {
            if (width <= 0 || mc.fontRendererObj.getStringWidth(text) <= width) return text;
            String suffix = "...";
            int target = Math.max(0, width - mc.fontRendererObj.getStringWidth(suffix));
            String trimmed = mc.fontRendererObj.trimStringToWidth(text, target);
            return trimmed + suffix;
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

        double rr = r * r;
        for (int y = 0; y < r; y++) {
            double dy = r - y - 0.5D;
            int inset = (int) Math.ceil(r - Math.sqrt(Math.max(0.0D, rr - dy * dy)));
            Gui.drawRect(left + inset, top + y, right - inset, top + y + 1, color);
            Gui.drawRect(left + inset, bottom - y - 1, right - inset, bottom - y, color);
        }
    }
}
