package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Synchronizes WanderBot with the external Myau KillAura module.
 *
 * <p>Requested state, acknowledged state and camera ownership are deliberately
 * separate. WanderBot yields combat rotation as soon as Myau may own it.
 * OFF requests are treated as a local ownership hand-off rather than a hard
 * acknowledgement barrier: some Myau builds do not emit a stable chat ACK for
 * toggle commands, and blocking navigation on that message can deadlock the
 * whole startup sequence.</p>
 */
final class KillAuraBridge {
    enum State {
        DISABLED,
        REQUESTING_ON,
        ACTIVE,
        REQUESTING_OFF,
        FAULT
    }

    private static final int CHAT_COOLDOWN_TICKS = 10;
    private static final int ACK_TIMEOUT_TICKS = 24;
    private static final int MAX_COMMAND_RETRIES = 2;
    private static final int FAULT_RETRY_TICKS = 20;
    private static final double DISABLE_HYSTERESIS = 0.60D;

    private final Minecraft mc;
    private final CombatExecutionController executor;

    private State state = State.DISABLED;
    private boolean desiredActive;
    private int chatCooldown;
    private int pendingTicks;
    private int retries;
    private int faultRetryTicks;
    private String registeredTargetName;

    KillAuraBridge(Minecraft mc, CombatExecutionController executor) {
        this.mc = mc;
        this.executor = executor;
    }

    void reset() {
        state = State.DISABLED;
        desiredActive = false;
        chatCooldown = 0;
        pendingTicks = 0;
        retries = 0;
        faultRetryTicks = 0;
        registeredTargetName = null;
        propagateAimOwnership(false);
    }

    /**
     * Normalizes Myau to OFF at startup without making navigation depend on a
     * chat acknowledgement. The command is local to the client, while chat
     * output varies between Myau versions/configurations.
     */
    void beginNavigationSync() {
        desiredActive = false;
        clearTarget();
        sendKillAuraCommand(false);
        state = State.DISABLED;
        pendingTicks = 0;
        retries = 0;
        faultRetryTicks = 0;
        chatCooldown = CHAT_COOLDOWN_TICKS;
        propagateAimOwnership(false);
    }

    /** Bot shutdown no longer needs to wait for acknowledgement. */
    void shutdown() {
        desiredActive = false;
        clearTarget();
        sendKillAuraCommand(false);
        state = State.DISABLED;
        pendingTicks = 0;
        retries = 0;
        faultRetryTicks = 0;
        chatCooldown = CHAT_COOLDOWN_TICKS;
        propagateAimOwnership(false);
    }

    void tick(EntityPlayerSP player, EntityPlayer target) {
        tickTimers();
        updateDesiredState(player, target);
        reconcileDesiredState();
        advancePendingState();
    }

    void disable() {
        desiredActive = false;
        reconcileDesiredState();
    }

    void clearTarget() {
        if (registeredTargetName == null) return;
        sendEnemyCommand(false, registeredTargetName);
        registeredTargetName = null;
    }

    void applyStateFromMessage(boolean enabled) {
        pendingTicks = 0;
        retries = 0;
        faultRetryTicks = 0;

        if (enabled) {
            state = State.ACTIVE;
            propagateAimOwnership(true);
        } else {
            state = State.DISABLED;
            propagateAimOwnership(false);
        }
        reconcileDesiredState();
    }

    boolean isActive() {
        return state == State.ACTIVE;
    }

    boolean ownsCombatAim() {
        return state == State.REQUESTING_ON
                || state == State.ACTIVE
                || (state == State.FAULT && desiredActive);
    }

    /**
     * OFF hand-off is optimistic by design. REQUESTING_OFF/FAULT with an OFF
     * desire must never freeze startup or ordinary navigation.
     */
    boolean isNavigationRotationSafe() {
        return !desiredActive
                && state != State.REQUESTING_ON
                && state != State.ACTIVE;
    }

    String getStatus() {
        switch (state) {
            case REQUESTING_ON: return "ENABLING";
            case ACTIVE: return "ACTIVE";
            case REQUESTING_OFF: return "DISABLING";
            case FAULT: return "SYNC WARN";
            case DISABLED:
            default: return "OFF";
        }
    }

    private void updateDesiredState(EntityPlayerSP player, EntityPlayer target) {
        if (player == null || !isLive(target)) {
            desiredActive = false;
            clearTarget();
            return;
        }

        double enableRange = configuredTriggerRange();
        double disableRange = enableRange + DISABLE_HYSTERESIS;
        double distance = player.getDistanceToEntity(target);

        if (distance <= enableRange) {
            syncTarget(target.getName());
            desiredActive = true;
        } else if (distance > disableRange) {
            desiredActive = false;
            clearTarget();
        }
        // Inside the hysteresis band, preserve the previous desired state.
    }

    private void reconcileDesiredState() {
        switch (state) {
            case DISABLED:
                if (desiredActive && chatCooldown <= 0) beginRequest(true);
                return;

            case ACTIVE:
                // OFF is safety-critical: do not keep Myau active merely because
                // the previous ON command is still inside the anti-spam cooldown.
                if (!desiredActive) beginRequest(false);
                return;

            case REQUESTING_ON:
                // If the target left while ON was pending, cancel the stale ON
                // immediately and never retry it.
                if (!desiredActive) beginRequest(false);
                return;

            case REQUESTING_OFF:
                // Always finish the OFF handshake first. If the target returns,
                // applyStateFromMessage(false) will immediately request ON again.
                return;

            case FAULT:
                if (faultRetryTicks <= 0 && chatCooldown <= 0) beginRequest(desiredActive);
                return;

            default:
                return;
        }
    }

    private void beginRequest(boolean enable) {
        sendKillAuraCommand(enable);
        state = enable ? State.REQUESTING_ON : State.REQUESTING_OFF;
        pendingTicks = 0;
        retries = 0;
        chatCooldown = CHAT_COOLDOWN_TICKS;
        // Yield immediately when requesting ON. When requesting OFF, return
        // rotation ownership immediately so a missing chat ACK cannot deadlock
        // path following.
        propagateAimOwnership(enable);
    }

    private void advancePendingState() {
        if (state != State.REQUESTING_ON && state != State.REQUESTING_OFF) return;

        // Do not retry a stale ON request after the desired state changed.
        if (state == State.REQUESTING_ON && !desiredActive) {
            reconcileDesiredState();
            return;
        }

        pendingTicks++;
        if (pendingTicks < ACK_TIMEOUT_TICKS) return;
        if (chatCooldown > 0) return;

        boolean commandOn = state == State.REQUESTING_ON;
        if (retries < MAX_COMMAND_RETRIES) {
            sendKillAuraCommand(commandOn);
            retries++;
            pendingTicks = 0;
            chatCooldown = CHAT_COOLDOWN_TICKS;
            return;
        }

        state = State.FAULT;
        pendingTicks = 0;
        faultRetryTicks = FAULT_RETRY_TICKS;
        // Unknown ON state remains conservative; unknown OFF state must not
        // freeze navigation.
        propagateAimOwnership(desiredActive);
    }

    private void tickTimers() {
        if (chatCooldown > 0) chatCooldown--;
        if (faultRetryTicks > 0) faultRetryTicks--;
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

    private void propagateAimOwnership(boolean myauOwnsAim) {
        executor.setKillAuraActive(myauOwnsAim);
    }

    private boolean isLive(EntityPlayer target) {
        return target != null && !target.isDead && target.getHealth() > 0.0F;
    }

    private static double configuredTriggerRange() {
        return Math.max(2.0D, Math.min(6.0D,
                Math.max(WanderBotSettings.killAuraSwingRange, WanderBotSettings.combatRange)));
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
