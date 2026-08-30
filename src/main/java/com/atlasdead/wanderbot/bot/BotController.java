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
 */
public class BotController {
    private static final double KILLAURA_RANGE = 3.5D;
    private static final double DEATH_Y_RISE_THRESHOLD = 8.0D;
    private static final int DEATH_Y_WINDOW_TICKS = 8;

    private final Minecraft mc;
    private final RotationController rotation = new RotationController();
    private final MovementController movement;
    private final PitDecisionEngine pit;
    private final CombatExecutionController combatExecutor;
    private final CombatPhaseController combatPhaseController;
    private final PitRuntimeGuard runtimeGuard = new PitRuntimeGuard();

    /* Kept only for the startup route. Active post-startup locomotion uses CombatPathFinder. */
    private final PathFinder startupPathFinder = new PathFinder();
    private final PitStartupSequence startupSequence;

    private BotState state = BotState.OFF;
    private boolean killAuraActive;
    private int killAuraChatCooldown;
    private long lastDeathRestartMs;
    private double previousPlayerY;
    private double deathYWindowBase;
    private int deathYWindowTicks;
    private boolean previousPlayerYInitialized;
    private String enemyRegisteredTargetName;

    public BotController(Minecraft mc) {
        this.mc = mc;
        this.movement = new MovementController(mc);
        this.pit = new PitDecisionEngine(mc);
        this.combatExecutor = new CombatExecutionController(mc, movement, rotation);
        this.pit.attachCombatExecutor(combatExecutor);
        this.combatExecutor.bindMegastreakProfile(this.pit.getStreakControl().getActiveMegastreak());
        this.combatPhaseController = new CombatPhaseController(mc, movement, rotation,
                pit.getZones(), pit.getTargets(), pit.getStreak(), combatExecutor);
        this.startupSequence = new PitStartupSequence(mc, movement, startupPathFinder, rotation);
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
        lastDeathRestartMs = 0L;
        enemyRegisteredTargetName = null;
        resetDeathYTracking(mc.thePlayer.posY);
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
        previousPlayerYInitialized = false;
        deathYWindowTicks = 0;
        clearEnemyTarget();
        ensureKillAuraOff();
    }

    public void handleDeathMessage() {
        if (state == BotState.OFF || mc.theWorld == null || mc.thePlayer == null) return;

        long now = System.currentTimeMillis();
        if (now - lastDeathRestartMs < 1000L) return;

        lastDeathRestartMs = now;
        restartAfterDeath();
    }

    private boolean detectDeathByYJump(EntityPlayerSP player) {
        double currentY = player.posY;
        if (!previousPlayerYInitialized) {
            resetDeathYTracking(currentY);
            return false;
        }

        deathYWindowTicks++;
        double deltaY = currentY - previousPlayerY;
        previousPlayerY = currentY;

        if (deltaY > 0.25D) {
            if (deathYWindowTicks > DEATH_Y_WINDOW_TICKS) {
                deathYWindowBase = currentY - deltaY;
                deathYWindowTicks = 1;
            }
        } else if (deathYWindowTicks > DEATH_Y_WINDOW_TICKS) {
            deathYWindowBase = currentY;
            deathYWindowTicks = 1;
        }

        double windowRise = currentY - deathYWindowBase;
        if (windowRise < DEATH_Y_RISE_THRESHOLD) return false;
        if (currentY < 1.0D) return false;

        long now = System.currentTimeMillis();
        if (now - lastDeathRestartMs < 1000L) return false;
        lastDeathRestartMs = now;
        return true;
    }

    private void resetDeathYTracking(double y) {
        previousPlayerY = y;
        deathYWindowBase = y;
        deathYWindowTicks = 0;
        previousPlayerYInitialized = true;
    }

    private void restartAfterDeath() {
        clearEnemyTarget();
        sendKillAuraCommand(false);
        killAuraActive = false;
        killAuraChatCooldown = 10;

        movement.release();
        movement.releaseJump();
        movement.attack(false);

        pit.getTargets().clear();
        runtimeGuard.reset();
        combatExecutor.reset();
        combatPhaseController.reset();
        startupSequence.reset();
        pit.start();
        state = BotState.PLANNING;

        resetDeathYTracking(mc.thePlayer != null ? mc.thePlayer.posY : 0.0D);
    }

    public void tick() {
        EntityPlayerSP player = mc.thePlayer;
        if (state == BotState.OFF) return;
        if (player == null || mc.theWorld == null) {
            stop();
            return;
        }

        if (startupSequence.isComplete() && detectDeathByYJump(player)) {
            restartAfterDeath();
            return;
        }

        if (!startupSequence.isComplete()) {
            startupSequence.tick(player);
            state = BotState.WALKING;
            return;
        }

        PitRuntimeGuard.Status guardStatus = runtimeGuard.inspect(mc, pit.getRuntimeTick());
        if (guardStatus == PitRuntimeGuard.Status.PAUSED_GUI) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            clearEnemyTarget();
            return;
        }
        if (guardStatus == PitRuntimeGuard.Status.PLAYER_DEAD) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            clearEnemyTarget();
            ensureKillAuraOff();
            pit.handleRuntimeDeath();
            state = BotState.RECOVERING;
            return;
        }
        if (guardStatus == PitRuntimeGuard.Status.WORLD_CHANGED) {
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            clearEnemyTarget();
            ensureKillAuraOff();
            pit.resetForWorldChange();
            startupSequence.reset();
            state = BotState.PLANNING;
            resetDeathYTracking(player.posY);
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
            clearEnemyTarget();
            ensureKillAuraOff();
            movement.release();
            state = pitMode == PitMode.PAUSED ? BotState.WALKING : BotState.PLANNING;
            return;
        }

        if (pit.getZones().isSelfProtected(player)) {
            clearEnemyTarget();
            ensureKillAuraOff();
            movement.release();
            state = BotState.WALKING;
            return;
        }

        EntityPlayer target = pit.getTargets().findBest(
                mc.theWorld,
                player,
                WanderBotSettings.targetScanRange,
                pit.getZones(),
                null);

        if (target == null || !pit.getTargets().isViable(player, WanderBotSettings.targetScanRange)) {
            clearEnemyTarget();
            pit.getTargets().clear();
            ensureKillAuraOff();
            movement.release();
            state = BotState.WALKING;
            return;
        }

        if (pit.getZones().isPlayerProtected(target)) {
            clearEnemyTarget();
            pit.getTargets().clear();
            ensureKillAuraOff();
            movement.release();
            return;
        }

        combatExecutor.bindMegastreakProfile(pit.getStreakControl().getActiveMegastreak());
        updateKillAura(player, target);

        combatExecutor.tick(
                player,
                target,
                CombatDecisionEngine.Action.ATTACK,
                System.currentTimeMillis());

        state = BotState.WALKING;
    }

    public void onDisconnect() {
        if (state == BotState.OFF) return;
        clearEnemyTarget();
        pit.stop();
        combatExecutor.reset();
        movement.release();
        movement.releaseJump();
        movement.attack(false);
        runtimeGuard.reset();
        state = BotState.OFF;
        previousPlayerYInitialized = false;
        deathYWindowTicks = 0;
        ensureKillAuraOff();
    }

    private void updateKillAura(EntityPlayerSP player, EntityPlayer target) {
        if (killAuraChatCooldown > 0) {
            killAuraChatCooldown--;
            return;
        }

        if (target == null || target.isDead || target.getHealth() <= 0.0F) {
            clearEnemyTarget();
            ensureKillAuraOff();
            return;
        }

        double distance = player.getDistanceToEntity(target);
        if (distance <= KILLAURA_RANGE) {
            syncEnemyTarget(target.getName());
            if (!killAuraActive) {
                sendKillAuraCommand(true);
                killAuraActive = true;
                combatExecutor.setKillAuraActive(true);
                combatPhaseController.setKillAuraActive(true);
                killAuraChatCooldown = 10;
            }
        } else {
            clearEnemyTarget();
            if (killAuraActive) {
                sendKillAuraCommand(false);
                killAuraActive = false;
                combatExecutor.setKillAuraActive(false);
                combatPhaseController.setKillAuraActive(false);
                killAuraChatCooldown = 10;
            }
        }
    }

    private void syncEnemyTarget(String targetName) {
        if (targetName == null || targetName.trim().isEmpty()) return;
        if (targetName.equals(enemyRegisteredTargetName)) return;

        if (enemyRegisteredTargetName != null) {
            sendEnemyCommand(false, enemyRegisteredTargetName);
        }
        sendEnemyCommand(true, targetName);
        enemyRegisteredTargetName = targetName;
    }

    private void clearEnemyTarget() {
        if (enemyRegisteredTargetName == null) return;
        sendEnemyCommand(false, enemyRegisteredTargetName);
        enemyRegisteredTargetName = null;
    }

    private void sendEnemyCommand(boolean add, String targetName) {
        if (mc.thePlayer == null || targetName == null || targetName.trim().isEmpty()) return;
        mc.thePlayer.sendChatMessage((add ? ".enemy add " : ".enemy remove ") + targetName);
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

    public void applyKillAuraStateFromMessage(boolean active) {
        killAuraActive = active;
        combatExecutor.setKillAuraActive(active);
        combatPhaseController.setKillAuraActive(active);
    }

    public CombatExecutionController getCombatExecutor() { return combatExecutor; }
    public boolean isKillAuraActive() { return killAuraActive; }

    public Path getPath() {
        if (!startupSequence.isComplete()) {
            Path startupPath = startupSequence.getPath();
            if (startupPath != null) return startupPath;
        }
        return combatExecutor.getLastCombatPath();
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
