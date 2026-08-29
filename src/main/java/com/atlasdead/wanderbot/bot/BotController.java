package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pit.CombatContext;
import com.atlasdead.wanderbot.pit.CombatDecisionEngine;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import com.atlasdead.wanderbot.pit.CombatPhaseController;
import com.atlasdead.wanderbot.pit.PitDecisionEngine;
import com.atlasdead.wanderbot.pit.PitMode;
import com.atlasdead.wanderbot.pit.PitRuntimeGuard;
import com.atlasdead.wanderbot.pit.PitStartupSequence;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Main bot controller.
 *
 * After startup, CombatPathFinder is the single locomotion/pathfinding system:
 *   target acquisition -> nearest eligible target -> CombatPathFinder ->
 *   CombatSteering -> MovementController -> attack when in range.
 *
 * The old general navigation path is no longer used for active movement.
 */
public class BotController {
    private static final double KILLAURA_RANGE = 3.5D;

    private final Minecraft mc;
    private final RotationController rotation = new RotationController();
    private final MovementController movement;
    private final PitDecisionEngine pit;
    private final CombatExecutionController combatExecutor;
    private final CombatPhaseController combatPhaseController;
    private final PitRuntimeGuard runtimeGuard = new PitRuntimeGuard();

    /* Kept for the existing startup route only. Active locomotion uses CombatPathFinder. */
    private final PathFinder startupPathFinder = new PathFinder();
    private final PitStartupSequence startupSequence;

    private BotState state = BotState.OFF;
    private boolean killAuraActive;
    private int killAuraChatCooldown;

    public BotController(Minecraft mc) {
        this.mc = mc;
        this.movement = new MovementController(mc);
        this.pit = new PitDecisionEngine(mc);
        this.combatExecutor = new CombatExecutionController(mc, movement, rotation);
        this.pit.attachCombatExecutor(combatExecutor);
        this.combatExecutor.bindMegastreakProfile(this.pit.getStreakControl().getActiveMegastreak());
        this.combatPhaseController = new CombatPhaseController(mc, movement, rotation,
                pit.getZones(), pit.getTargets(), pit.getStreak(), combatExecutor);
        this.startupSequence = new PitStartupSequence(mc, movement, startupPathFinder);
    }

    public void toggle() {
        if (state == BotState.OFF) start();
        else stop();
    }

    public void start() {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        state = BotState.PLANNING;
        pit.start();
        runtimeGuard.reset();
        combatExecutor.reset();
        combatPhaseController.reset();
        startupSequence.reset();
        killAuraActive = false;
        killAuraChatCooldown = 0;
        movement.release();
        movement.releaseJump();
        movement.attack(false);
    }

    public void stop() {
        state = BotState.OFF;
        runtimeGuard.reset();
        pit.stop();
        combatExecutor.reset();
        movement.release();
        movement.releaseJump();
        movement.attack(false);
        ensureKillAuraOff();
    }

    public void tick() {
        EntityPlayerSP player = mc.thePlayer;
        if (state == BotState.OFF) return;
        if (player == null || mc.theWorld == null) {
            stop();
            return;
        }

        /* Startup sequence is the only place where the legacy static PathFinder is used. */
        if (!startupSequence.isComplete()) {
            startupSequence.tick(player);
            state = BotState.WALKING;
            movement.release();
            return;
        }

        PitRuntimeGuard.Status guardStatus = runtimeGuard.inspect(mc, pit.getRuntimeTick());
        if (guardStatus == PitRuntimeGuard.Status.PAUSED_GUI) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            return;
        }
        if (guardStatus == PitRuntimeGuard.Status.PLAYER_DEAD) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            ensureKillAuraOff();
            pit.handleRuntimeDeath();
            state = BotState.RECOVERING;
            return;
        }
        if (guardStatus == PitRuntimeGuard.Status.WORLD_CHANGED) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            ensureKillAuraOff();
            pit.resetForWorldChange();
            startupSequence.reset();
            state = BotState.PLANNING;
            return;
        }

        movement.release();
        movement.releaseJump();
        movement.attack(false);

        pit.tick();

        PitMode pitMode = pit.getMode();
        PitDecisionEngine pitEngine = pit;
        com.atlasdead.wanderbot.pit.PitRulesEngine.State rules = pitEngine.getRulesState();

        if (!WanderBotSettings.forcePitMode
                && (pitMode == PitMode.WAITING || pitMode == PitMode.WARMUP || pitMode == PitMode.UNSAFE
                || pitMode == PitMode.PAUSED
                || rules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.EVENT
                || rules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.WAITING
                || rules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.WARMUP)) {
            ensureKillAuraOff();
            movement.release();
            state = pitMode == PitMode.PAUSED ? BotState.WALKING : BotState.PLANNING;
            return;
        }

        if (pit.getZones().isSelfProtected(player)) {
            ensureKillAuraOff();
            movement.release();
            state = BotState.WALKING;
            return;
        }

        /*
         * Do not inherit any strategic target chosen by the old scoring pipeline.
         * Re-scan explicitly and select the nearest target that satisfies the
         * TargetTracker eligibility policy. CombatPathFinder then follows that
         * exact entity.
         */
        EntityPlayer target = pit.getTargets().findBest(
                mc.theWorld,
                player,
                WanderBotSettings.targetScanRange,
                pit.getZones(),
                null);

        if (target == null || !pit.getTargets().isViable(player, WanderBotSettings.targetScanRange)) {
            pit.getTargets().clear();
            ensureKillAuraOff();
            movement.release();
            state = BotState.WALKING;
            return;
        }

        /* Never pursue a protected target. */
        if (pit.getZones().isPlayerProtected(target)) {
            pit.getTargets().clear();
            ensureKillAuraOff();
            movement.release();
            return;
        }

        combatExecutor.bindMegastreakProfile(pit.getStreakControl().getActiveMegastreak());
        updateKillAura(player, target);

        /*
         * Single combat movement pipeline. ATTACK is requested continuously;
         * CombatExecutionController only considers the attack ready when the
         * target is actually inside its combat range and its control policy
         * allows the attack.
         */
        CombatExecutionController.State combatState = combatExecutor.tick(
                player,
                target,
                CombatDecisionEngine.Action.ATTACK,
                System.currentTimeMillis());

        state = combatState == null
                ? BotState.WALKING
                : (combatState.inRange ? BotState.WALKING : BotState.WALKING);
    }

    public void onDisconnect() {
        if (state == BotState.OFF) return;
        pit.stop();
        combatExecutor.reset();
        movement.release();
        movement.releaseJump();
        movement.attack(false);
        runtimeGuard.reset();
        state = BotState.OFF;
        ensureKillAuraOff();
    }

    private void updateKillAura(EntityPlayerSP player, EntityPlayer target) {
        if (killAuraChatCooldown > 0) {
            killAuraChatCooldown--;
            return;
        }

        if (target == null || target.isDead || target.getHealth() <= 0.0F) {
            ensureKillAuraOff();
            return;
        }

        double distance = player.getDistanceToEntity(target);
        if (distance <= KILLAURA_RANGE && !killAuraActive) {
            sendKillAuraCommand(true);
            killAuraActive = true;
            combatExecutor.setKillAuraActive(true);
            combatPhaseController.setKillAuraActive(true);
            killAuraChatCooldown = 10;
        } else if (distance > KILLAURA_RANGE && killAuraActive) {
            sendKillAuraCommand(false);
            killAuraActive = false;
            combatExecutor.setKillAuraActive(false);
            combatPhaseController.setKillAuraActive(false);
            killAuraChatCooldown = 10;
        }
    }

    private void sendKillAuraCommand(boolean on) {
        if (mc.thePlayer == null) return;
        mc.thePlayer.sendChatMessage(on ? ".t killaura on" : ".t killaura off");
    }

    private void ensureKillAuraOff() {
        if (!killAuraActive) return;
        sendKillAuraCommand(false);
        killAuraActive = false;
        combatExecutor.setKillAuraActive(false);
        combatPhaseController.setKillAuraActive(false);
        killAuraChatCooldown = 10;
    }

    /** Synchronize rotation ownership with an externally observed KillAura state. */
    public void applyKillAuraStateFromMessage(boolean active) {
        killAuraActive = active;
        combatExecutor.setKillAuraActive(active);
        combatPhaseController.setKillAuraActive(active);
    }

    public CombatExecutionController getCombatExecutor() { return combatExecutor; }
    public boolean isKillAuraActive() { return killAuraActive; }

    /** Active rendered path is now the CombatPathFinder path. */
    public Path getPath() {
        Path combatPath = combatExecutor.getLastCombatPath();
        return combatPath != null ? combatPath : null;
    }

    public BotState getState() { return state; }
    public boolean isEnabled() { return state != BotState.OFF; }
    public PitMode getPitMode() { return pit.getMode(); }
    public PitDecisionEngine getPit() { return pit; }
    public String getRuntimeGuardStatus() { return runtimeGuard.getStatus().name(); }

    public double getTargetScore() { return pit.getTargets().getTargetScore(); }
    public com.atlasdead.wanderbot.pit.TargetTracker.ArmorProfile getTargetArmor() { return pit.getTargets().getTargetArmor(); }

    public String getTargetName() {
        EntityPlayer target = pit.getTargets().getTarget();
        return target == null ? null : target.getName();
    }

    public int getLocalStreak() { return pit.getStreak().getEffectiveStreak(); }
    public com.atlasdead.wanderbot.pit.StreakManager.Tier getStreakTier() { return pit.getStreak().getTier(); }
    public int getPeakStreak() { return pit.getStreak().getPeakStreak(); }
    public CombatContext getCombatContext() { return combatPhaseController.getContext(); }
}
