package com.atlasdead.wanderbot;

import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.gui.ClickGuiScreen;
import com.atlasdead.wanderbot.render.PathRenderer;
import com.atlasdead.wanderbot.render.PitDebugDashboard;
import com.atlasdead.wanderbot.render.PitHudRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.util.regex.Pattern;

@Mod(modid = WanderBotMod.MODID, name = WanderBotMod.NAME, version = WanderBotMod.VERSION, clientSideOnly = true)
public final class WanderBotMod {
    public static final String MODID = "KeyBindMod";
    public static final String NAME = "WanderBot";
    public static final String VERSION = "1.11.8";

    private static final String KILLAURA_ON = "[Myau] KillAura: ON";
    private static final String KILLAURA_OFF = "[Myau] KillAura: OFF";
    private static final Pattern DEATH_MESSAGE = Pattern.compile(
            ".*DEATH!\\s+by\\s+\\[[^\\]]+\\]\\s+\\S+\\s+VIEW\\s+RECAP.*",
            Pattern.CASE_INSENSITIVE);

    public static final KeyBinding TOGGLE_KEY = new KeyBinding(
            "WanderBot Toggle", Keyboard.KEY_K, "WanderBot");
    public static final KeyBinding CLICKGUI_KEY = new KeyBinding(
            "WanderBot ClickGUI", Keyboard.KEY_RSHIFT, "WanderBot");

    public static BotController BOT;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(CLICKGUI_KEY);

        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PathRenderer());
        MinecraftForge.EVENT_BUS.register(new PitHudRenderer());
        MinecraftForge.EVENT_BUS.register(new PitDebugDashboard());

        Minecraft mc = Minecraft.getMinecraft();
        WanderBotSettings.load(new File(mc.mcDataDir, "config"));
        BOT = new BotController(mc);
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (TOGGLE_KEY.isPressed() && BOT != null) {
            BOT.toggle();
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (CLICKGUI_KEY.isPressed() && mc.currentScreen == null) {
            mc.displayGuiScreen(new ClickGuiScreen());
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null || BOT == null) return;

        BOT.getPit().getProgress().observeChat(event.message);
        String plain = normalizeChat(event.message.getFormattedText());

        if (KILLAURA_ON.equals(plain)) {
            BOT.applyKillAuraStateFromMessage(true);
        } else if (KILLAURA_OFF.equals(plain)) {
            BOT.applyKillAuraStateFromMessage(false);
        }

        if (DEATH_MESSAGE.matcher(plain).matches()) {
            BOT.handleDeathMessage();
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (BOT != null) BOT.onDisconnect();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (BOT != null) BOT.tick();
    }

    private static String normalizeChat(String formatted) {
        String stripped = StringUtils.stripControlCodes(formatted == null ? "" : formatted);
        return stripped.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
    }
}
