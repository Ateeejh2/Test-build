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
        // Lock all other keyboard input when bot is running
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
        // MouseInputEvent is NOT @Cancelable in Forge 1.8.9.
        // Attempting setCanceled(true) throws IllegalArgumentException and crashes the game.
        // Mouse clicks are harmless during bot operation since movement/rotation/attack
        // are controlled via KeyBinding.setKeyBindState() which overrides player input.
        // If accidental inventory opening is a concern, the bot can detect and close it
        // in the tick handler instead.
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

    /**
     * KillAura rotation: PRE phase, BEFORE vanilla tick sends position packets.
     * This mirrors Myau's @EventTarget(priority=3) onUpdate(PRE) handler.
     * By setting rotationYaw/pitch here, vanilla's C03/C06 packets will include
     * the correct rotation values, preventing Vulcan Killaura A / BadPacket X.
     */
    /**
     * PlayerTickEvent fires for each entity tick. PRE fires before the entity
     * processes its tick (and sends position packets). POST fires after.
     * This mirrors Myau's @EventTarget(priority=3) onUpdate(PRE) handler.
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (BOT == null) return;

        if (event.phase == TickEvent.Phase.START) {
            // KillAura rotation: BEFORE vanilla tick sends position packets.
            // By setting rotationYaw/pitch here, vanilla's C03/C06 packets will include
            // the correct rotation values, preventing Vulcan Killaura A / BadPacket X.
            com.atlasdead.wanderbot.pit.KillAura killAura = BOT.getCombatExecutor().getKillAura();
            if (killAura != null && killAura.enabled && event.player != null && mc.theWorld != null) {
                net.minecraft.entity.player.EntityPlayer target = BOT.getPit().getTargets().getTarget();
                if (target != null) {
                    killAura.tickPre((net.minecraft.client.entity.EntityPlayerSP) event.player, target);
                }
            }
        }

        if (event.phase == TickEvent.Phase.END) {
            // KillAura attack: AFTER vanilla tick has sent packets
            com.atlasdead.wanderbot.pit.KillAura killAura = BOT.getCombatExecutor().getKillAura();
            if (killAura != null && killAura.enabled && event.player != null) {
                net.minecraft.entity.player.EntityPlayer target = BOT.getPit().getTargets().getTarget();
                if (target != null) {
                    killAura.tickPost((net.minecraft.client.entity.EntityPlayerSP) event.player, target);
                }
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (BOT != null) BOT.tick();
    }
}
