package com.atlasdead.wanderbot;

import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.render.PathRenderer;
import com.atlasdead.wanderbot.render.PitHudRenderer;
import com.atlasdead.wanderbot.render.PitDebugDashboard;
import net.minecraft.client.Minecraft;
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

@Mod(modid = WanderBotMod.MODID, name = WanderBotMod.NAME, version = WanderBotMod.VERSION, clientSideOnly = true)
public class WanderBotMod {
    public static final String MODID = "KeyBindMod";
    public static final String NAME = "WanderBot";
    public static final String VERSION = "1.11.3";

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
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event == null || event.message == null || BOT == null) return;
        BOT.getPit().getProgress().observeChat(event.message);
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        if (BOT != null) BOT.onDisconnect();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && BOT != null) BOT.tick();
    }
}
