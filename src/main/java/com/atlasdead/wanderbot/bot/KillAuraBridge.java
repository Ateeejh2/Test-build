package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import com.atlasdead.wanderbot.pit.CombatPhaseController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Owns synchronization with the external Myau KillAura commands.
 *
 * <p>This keeps chat-command state, target registration and the two combat
 * controllers in one place instead of duplicating that state in BotController.</p>
 */
final class KillAuraBridge {
    private static final int CHAT_COOLDOWN_TICKS = 10;

    private final Minecraft mc;
    private final CombatExecutionController executor;
    private final CombatPhaseController phaseController;

    private boolean active;
    private int chatCooldown;
    private String registeredTargetName;

    KillAuraBridge(Minecraft mc, CombatExecutionController executor, CombatPhaseController phaseController) {
        this.mc = mc;
        this.executor = executor;
        this.phaseController = phaseController;
    }

    void reset() {
        active = false;
        chatCooldown = 0;
        registeredTargetName = null;
        propagate(false);
    }

    void tick(EntityPlayerSP player, EntityPlayer target) {
        if (chatCooldown > 0) {
            chatCooldown--;
            return;
        }

        if (!isLive(target)) {
            clearTarget();
            disable();
            return;
        }

        double range = Math.max(WanderBotSettings.killAuraSwingRange, WanderBotSettings.combatRange);
        if (player.getDistanceToEntity(target) <= range) {
            syncTarget(target.getName());
            enable();
        } else {
            clearTarget();
            disable();
        }
    }

    void disable() {
        if (!active) return;
        sendKillAuraCommand(false);
        applyState(false);
        chatCooldown = CHAT_COOLDOWN_TICKS;
    }

    void forceDisableCommand() {
        sendKillAuraCommand(false);
        applyState(false);
        chatCooldown = CHAT_COOLDOWN_TICKS;
    }

    void clearTarget() {
        if (registeredTargetName == null) return;
        sendEnemyCommand(false, registeredTargetName);
        registeredTargetName = null;
    }

    void applyStateFromMessage(boolean enabled) {
        applyState(enabled);
    }

    boolean isActive() {
        return active;
    }

    private void enable() {
        if (active) return;
        sendKillAuraCommand(true);
        applyState(true);
        chatCooldown = CHAT_COOLDOWN_TICKS;
    }

    private void syncTarget(String targetName) {
        String normalized = normalizeName(targetName);
        if (normalized == null || normalized.equals(registeredTargetName)) return;

        if (registeredTargetName != null) {
            sendEnemyCommand(false, registeredTargetName);
        }
        sendEnemyCommand(true, normalized);
        registeredTargetName = normalized;
    }

    private void applyState(boolean enabled) {
        active = enabled;
        propagate(enabled);
    }

    private void propagate(boolean enabled) {
        executor.setKillAuraActive(enabled);
        phaseController.setKillAuraActive(enabled);
    }

    private boolean isLive(EntityPlayer target) {
        return target != null && !target.isDead && target.getHealth() > 0.0F;
    }

    private String normalizeName(String targetName) {
        if (targetName == null) return null;
        String normalized = targetName.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void sendEnemyCommand(boolean add, String targetName) {
        if (mc.thePlayer == null || targetName == null) return;
        mc.thePlayer.sendChatMessage((add ? ".enemy add " : ".enemy remove ") + targetName);
    }

    private void sendKillAuraCommand(boolean enabled) {
        if (mc.thePlayer == null) return;
        mc.thePlayer.sendChatMessage(enabled ? ".t killaura on" : ".t killaura off");
    }
}
