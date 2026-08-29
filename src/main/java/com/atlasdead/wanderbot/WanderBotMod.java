package com.atlasdead.wanderbot;

import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.render.PathRenderer;
import com.atlasdead.wanderbot.render.PitHudRenderer;
import com.atlasdead.wanderbot.render.PitDebugDashboard;
import net.minecraft.client.Minecraft;
import net.minecraft.util.StringUtils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import com.atlasdead.wanderbot.gui.ClickGuiScreen;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

@Mod(modid = WanderBotMod.MODID, name = WanderBotMod.NAME, version = WanderBotMod.VERSION, clientSideOnly = true)
public class WanderBotMod {
    public static final String MODID = "KeyBindMod";
    public static final String NAME = "WanderBot";
    public static final String VERSION = "1.11.8";

    public static final KeyBinding TOGGLE_KEY = new KeyBinding("WanderBot Toggle", Keyboard.KEY_K, "WanderBot");
    public static final KeyBinding CLICKGUI_KEY = new KeyBinding("WanderBot ClickGUI", Keyboard.KEY_RSHIFT, "WanderBot");
    public static BotController BOT;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        ClientRegistry.registerKeyBinding(TOGGLE_KEY);
        ClientRegistry.registerKeyBinding(CLICKGUI_KEY);
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new PathRenderer());
        MinecraftForge.EVENT_BUS.register(new PitHudRenderer());
        MinecraftForge.EVENT_BUS.register(new PitDebugDashboard());
        WanderBotSettings.load(new java.io.File(Minecraft.getMinecraft().mcDataDir, "config"));
        BOT = new BotController(Minecraft.getMinecraft());
    }

    @SubscribeEvent
    public void onKey(InputEvent.KeyInputEvent event) {
        if (TOGGLE_KEY.isPressed() && BOT != null) BOT.toggle();
        if (CLICKGUI_KEY.isPressed() && Minecraft.getMinecraft().currentScreen == null) {
            Minecraft.getMinecraft().displayGuiScreen(new ClickGuiScreen());
        }
        if (BOT != null && BOT.isEnabled() && Minecraft.getMinecraft().currentScreen == null) {
            int key = Keyboard.getEventKey();
            boolean isToggleKey = key == TOGGLE_KEY.getKeyCode();
            boolean isEscape = key == Keyboard.KEY_ESCAPE;
            boolean isGuiKey = key == CLICKGUI_KEY.getKeyCode();
            if (!isToggleKey && !isEscape && !isGuiKey) {
                Keyboard.next();
            }
        }
    }

    @SubscribeEvent
    public void onMouse(InputEvent.MouseInputEvent event) {
        // MouseInputEvent is not cancelable in Forge 1.8.9.
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null || BOT == null) return;

        BOT.getPit().getProgress().observeChat(event.message);

        String formatted = event.message.getFormattedText();
        String plain = StringUtils.stripControlCodes(formatted == null ? "" : formatted);
        plain = plain.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();

        if ("[Myau] KillAura: ON".equals(plain)) {
            BOT.applyKillAuraStateFromMessage(true);
        } else if ("[Myau] KillAura: OFF".equals(plain)) {
            BOT.applyKillAuraStateFromMessage(false);
        }

        // The server varies both level and IGN. Match the invariant message
        // structure after stripping all Minecraft formatting codes.
        if (plain.matches("(?i).*DEATH!\\s+by\\s+\\[[^\\]]+\\]\\s+\\S+\\s+VIEW\\s+RECAP.*")) {
            BOT.handleDeathMessage();
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (BOT != null) BOT.onDisconnect();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (BOT != null) BOT.tick();
    }
}
