package com.atlasdead.wanderbot.gui;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.PitStreakCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/** Persistent control center for WanderBot runtime settings. */
public final class ClickGuiScreen extends GuiScreen {
    private static final int PANEL_WIDTH = 820;
    private static final int PANEL_HEIGHT = 500;
    private static final int SIDEBAR_WIDTH = 170;
    private static final int CONTENT_WIDTH = 360;
    private static final int ROW_HEIGHT = 42;

    private int left;
    private int top;
    private Category category = Category.GENERAL;

    private enum Category {
        GENERAL("General", "Runtime configuration"),
        RENDER("Render", "Visuals & telemetry"),
        COMBAT("Combat", "Combat + KillAura"),
        NAVIGATION("Navigation", "Pathfinding controls"),
        PIT("Pit", "Megastreak selector"),
        DEBUG("Debug", "Runtime configuration");

        final String title;
        final String subtitle;

        Category(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    @Override
    public void initGui() {
        recalculatePosition();
        buttonList.clear();
        buildCategoryControls();
    }

    private void recalculatePosition() {
        left = Math.max(18, (width - PANEL_WIDTH) / 2);
        top = Math.max(18, (height - PANEL_HEIGHT) / 2);
    }

    private void buildCategoryControls() {
        int x = left + SIDEBAR_WIDTH + 30;
        int y = top + 82;

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
        toggle(10, x, y, "Bot", isBotEnabled(), new Runnable() {
            @Override public void run() {
                if (WanderBotMod.BOT != null) WanderBotMod.BOT.toggle();
            }
        });
        toggleSetting(11, x, y + ROW_HEIGHT, "Navigation", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.navigationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.navigationEnabled = value; }
        });
        toggleSetting(12, x, y + ROW_HEIGHT * 2, "Combat", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.combatEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.combatEnabled = value; }
        });
        toggleSetting(13, x, y + ROW_HEIGHT * 3, "Megastreak Strategy", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.megastreakStrategy; }
            @Override public void set(boolean value) { WanderBotSettings.megastreakStrategy = value; }
        });
        toggleSetting(17, x, y + ROW_HEIGHT * 4, "Force Pit Mode", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.forcePitMode; }
            @Override public void set(boolean value) { WanderBotSettings.forcePitMode = value; }
        });
        info(15, x, y + ROW_HEIGHT * 5 + 2, "K = Bot toggle    Right Shift = GUI");
    }

    private void buildRender(int x, int y) {
        toggleSetting(20, x, y, "Pathfinding", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showPath; }
            @Override public void set(boolean value) { WanderBotSettings.showPath = value; }
        });
        toggleSetting(21, x, y + ROW_HEIGHT, "Target Box", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTarget; }
            @Override public void set(boolean value) { WanderBotSettings.showTarget = value; }
        });
        toggleSetting(22, x, y + ROW_HEIGHT * 2, "Target Guide", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showTargetGuide; }
            @Override public void set(boolean value) { WanderBotSettings.showTargetGuide = value; }
        });
        toggleSetting(23, x, y + ROW_HEIGHT * 3, "HUD", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.showHud; }
            @Override public void set(boolean value) { WanderBotSettings.showHud = value; }
        });
        toggleSetting(24, x, y + ROW_HEIGHT * 4, "Debug Dashboard", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.debugDashboard; }
            @Override public void set(boolean value) { WanderBotSettings.debugDashboard = value; }
        });
    }

    private void buildCombat(int x, int y) {
        toggleSetting(30, x, y, "Combat Enabled", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.combatEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.combatEnabled = value; }
        });
        info(32, x, y + ROW_HEIGHT, "Target Scan   loaded players");
        toggleSetting(35, x, y + ROW_HEIGHT * 2, "Force Pit Mode", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.forcePitMode; }
            @Override public void set(boolean value) { WanderBotSettings.forcePitMode = value; }
        });
        info(36, x, y + ROW_HEIGHT * 3 + 2, "KillAura is synchronized through Myau commands");
        info(37, x, y + ROW_HEIGHT * 3 + 24, "Range uses the configured KillAura swing/combat range");
    }

    private void buildNavigation(int x, int y) {
        toggleSetting(40, x, y, "Navigation Enabled", new BooleanSetting() {
            @Override public boolean get() { return WanderBotSettings.navigationEnabled; }
            @Override public void set(boolean value) { WanderBotSettings.navigationEnabled = value; }
        });
        cycleInt(41, x, y + ROW_HEIGHT, "Repath Ticks", 2, 40, new IntSetting() {
            @Override public int get() { return WanderBotSettings.navRepathTicks; }
            @Override public void set(int value) { WanderBotSettings.navRepathTicks = value; }
        });
        cycleDouble(42, x, y + ROW_HEIGHT * 2, "Lookahead", 0.5D, 1.0D, 8.0D, new DoubleSetting() {
            @Override public double get() { return WanderBotSettings.lookaheadDistance; }
            @Override public void set(double value) { WanderBotSettings.lookaheadDistance = value; }
        });
        info(43, x, y + ROW_HEIGHT * 3 + 14, "Terrain-aware A* / avoidance / recovery remain enabled.");
    }

    private void buildPit(int x, int y) {
        List<PitStreakCatalog.Definition> modes = PitStreakCatalog.ALL_MEGASTREAKS;
        for (int i = 0; i < modes.size(); i++) {
            final PitStreakCatalog.Definition definition = modes.get(i);
            final String id = definition.id;
            String suffix = id.equalsIgnoreCase(WanderBotSettings.megastreakId) ? "   ✓" : "";
            buttonList.add(new ValueButton(50 + i, x, y + i * 38, CONTENT_WIDTH, 28,
                    definition.displayName + suffix, new Runnable() {
                @Override public void run() {
                    WanderBotSettings.megastreakId = id;
                    if (WanderBotMod.BOT != null) {
                        WanderBotMod.BOT.getPit().getStreakControl().setMegastreak(id);
                    }
                    persistAndRefresh();
                }
            }));
        }
        info(90, x + 390, y, "Selected: " + WanderBotSettings.megastreakId);
    }

    private void buildDebug(int x, int y) {
        info(70, x, y, "Runtime Dashboard");
        if (WanderBotMod.BOT == null) {
            info(71, x, y + ROW_HEIGHT, "Bot unavailable");
            return;
        }

        info(71, x, y + ROW_HEIGHT, "State: " + WanderBotMod.BOT.getState());
        info(72, x, y + ROW_HEIGHT * 2, "Pit: " + WanderBotMod.BOT.getPitMode());
        info(73, x, y + ROW_HEIGHT * 3, "Target: " + String.valueOf(WanderBotMod.BOT.getTargetName()));
        info(74, x, y + ROW_HEIGHT * 4,
                "Streak: " + WanderBotMod.BOT.getLocalStreak() + " / peak " + WanderBotMod.BOT.getPeakStreak());
        info(75, x, y + ROW_HEIGHT * 5,
                "Target Score: " + String.format("%.1f", WanderBotMod.BOT.getTargetScore()));
        info(76, x, y + ROW_HEIGHT * 6, "Armor: " + WanderBotMod.BOT.getTargetArmor());
    }

    private boolean isBotEnabled() {
        return WanderBotMod.BOT != null && WanderBotMod.BOT.isEnabled();
    }

    private void toggleSetting(int id, int x, int y, String label, final BooleanSetting setting) {
        toggle(id, x, y, label, setting.get(), new Runnable() {
            @Override public void run() {
                setting.set(!setting.get());
                persistAndRefresh();
            }
        });
    }

    private void toggle(int id, int x, int y, String label, boolean value, Runnable action) {
        buttonList.add(new ValueButton(id, x, y, CONTENT_WIDTH, 28,
                label + "   " + (value ? "ON" : "OFF"), action));
    }

    private void cycleInt(int id, int x, int y, String label, final int min, final int max,
                          final IntSetting setting) {
        buttonList.add(new ValueButton(id, x, y, CONTENT_WIDTH, 28,
                label + "   " + setting.get(), new Runnable() {
            @Override public void run() {
                int next = setting.get() + 1;
                if (next > max) next = min;
                setting.set(next);
                WanderBotSettings.clamp();
                persistAndRefresh();
            }
        }));
    }

    private void cycleDouble(int id, int x, int y, String label, final double step,
                             final double min, final double max, final DoubleSetting setting) {
        buttonList.add(new ValueButton(id, x, y, CONTENT_WIDTH, 28,
                label + "   " + format(setting.get()), new Runnable() {
            @Override public void run() {
                double next = setting.get() + step;
                if (next > max + 1.0E-6D) next = min;
                setting.set(next);
                WanderBotSettings.clamp();
                persistAndRefresh();
            }
        }));
    }

    private void info(int id, int x, int y, String text) {
        buttonList.add(new ValueButton(id, x, y, CONTENT_WIDTH, 28, text, null));
    }

    private void persistAndRefresh() {
        WanderBotSettings.save();
        initGui();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button instanceof ValueButton) {
            ValueButton valueButton = (ValueButton) button;
            if (valueButton.action != null) valueButton.action.run();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        recalculatePosition();

        drawRect(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xF20D1118);
        drawRect(left, top, left + SIDEBAR_WIDTH, top + PANEL_HEIGHT, 0xF211151C);
        drawRect(left + SIDEBAR_WIDTH, top, left + PANEL_WIDTH, top + 64, 0xF2181E27);

        drawString(fontRendererObj, "WANDERBOT", left + 22, top + 20, 0xFFFFFFFF);
        drawString(fontRendererObj, "Control Center", left + 22, top + 36, 0xFF8C98AA);
        drawString(fontRendererObj, category.title, left + SIDEBAR_WIDTH + 30, top + 19, 0xFFFFFFFF);
        drawString(fontRendererObj, category.subtitle, left + SIDEBAR_WIDTH + 30, top + 37, 0xFF8C98AA);

        drawCategoryTabs();

        drawString(fontRendererObj, "K", left + 18, top + PANEL_HEIGHT - 28, 0xFFD0D7E2);
        drawString(fontRendererObj, "Bot Toggle", left + 36, top + PANEL_HEIGHT - 28, 0xFF707C8E);
        drawString(fontRendererObj, "Right Shift", left + SIDEBAR_WIDTH + 30,
                top + PANEL_HEIGHT - 28, 0xFFD0D7E2);
        drawString(fontRendererObj, "ClickGUI", left + SIDEBAR_WIDTH + 92,
                top + PANEL_HEIGHT - 28, 0xFF707C8E);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawCategoryTabs() {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            int x = left + 14;
            int y = top + 82 + i * 48;
            boolean selected = category == categories[i];
            drawRect(x, y, x + SIDEBAR_WIDTH - 28, y + 34, selected ? 0xFF242C37 : 0x00000000);
            if (selected) drawRect(x, y, x + 3, y + 34, 0xFF7E9BFF);
            drawString(fontRendererObj, categories[i].name(), x + 14, y + 11,
                    selected ? 0xFFFFFFFF : 0xFF98A4B5);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        Category[] categories = Category.values();
        for (int i = 0; i < categories.length; i++) {
            int y = top + 82 + i * 48;
            if (mouseX >= left + 14 && mouseX <= left + SIDEBAR_WIDTH - 14
                    && mouseY >= y && mouseY <= y + 34) {
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

    private static final class ValueButton extends GuiButton {
        final Runnable action;

        ValueButton(int id, int x, int y, int width, int height, String text, Runnable action) {
            super(id, x, y, width, height, text);
            this.action = action;
            enabled = action != null;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hover = mouseX >= xPosition && mouseX < xPosition + width
                    && mouseY >= yPosition && mouseY < yPosition + height;
            GuiScreen screen = mc.currentScreen;
            if (screen == null) return;

            int background = action == null ? 0xFF171D25 : (hover ? 0xFF293340 : 0xFF1C232D);
            screen.drawRect(xPosition, yPosition, xPosition + width, yPosition + height, background);
            mc.fontRendererObj.drawString(displayString, xPosition + 12, yPosition + 10,
                    action == null ? 0xFF8996A8 : 0xFFE0E6EE);
            if (action != null) {
                screen.drawRect(xPosition + width - 10, yPosition + 6,
                        xPosition + width - 7, yPosition + height - 6,
                        hover ? 0xFF8AA9FF : 0xFF42506A);
            }
        }
    }
}
