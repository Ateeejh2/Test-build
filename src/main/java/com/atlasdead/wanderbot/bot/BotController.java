package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.navigation.TargetSearchNavigator;
import com.atlasdead.wanderbot.pit.CombatDecisionEngine;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import com.atlasdead.wanderbot.pit.PitDecisionEngine;
import com.atlasdead.wanderbot.pit.PitExecutionOrchestrator;
import com.atlasdead.wanderbot.pit.PitMasterDecisionEngine;
import com.atlasdead.wanderbot.pit.PitMode;
import com.atlasdead.wanderbot.pit.PitRulesEngine;
import com.atlasdead.wanderbot.pit.PitRuntimeGuard;
import com.atlasdead.wanderbot.pit.PitStartupSequence;
import com.atlasdead.wanderbot.pit.StreakManager;
import com.atlasdead.wanderbot.pit.TargetTracker;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Main WanderBot runtime coordinator.
 *
 * <p>Policy, execution and external-module ownership are intentionally kept in
 * separate layers. The Pit master policy selects an action, the execution
 * orchestrator selects the owner for that tick, and KillAuraBridge arbitrates
 * combat camera ownership with Myau.</p>
 */
public final class BotController {
    private final Minecraft mc;
    private final RotationController rotation = new RotationController();
    private final MovementController movement;
    private final PitDecisionEngine pit;
    private final CombatExecutionController combatExecutor;
    private final PitExecutionOrchestrator executionOrchestrator = new PitExecutionOrchestrator();
    private final PitRuntimeGuard runtimeGuard = new PitRuntimeGuard();
    private final PathFinder startupPathFinder = new PathFinder();
    private final PitStartupSequence startupSequence;
    private final TargetSearchNavigator targetSearchNavigator;
    private final DeathRestartDetector deathRestartDetector = new DeathRestartDetector();
    private final KillAuraBridge killAuraBridge;

    private BotState state = BotState.OFF;
    private long lastTickNanos;
    private double smoothedTickNanos;

    public BotController(Minecraft mc) {
        this.mc = mc;
        this.movement = new MovementController(mc);
        this.pit = new PitDecisionEngine(mc);
        this.combatExecutor = new CombatExecutionController(mc, movement, rotation);
        this.pit.attachCombatExecutor(combatExecutor);
        this.combatExecutor.bindMegastreakProfile(this.pit.getStreakControl().getActiveMegastreak());
        this.startupSequence = new PitStartupSequence(mc, movement, startupPathFinder, rotation);
        this.targetSearchNavigator = new TargetSearchNavigator(mc, movement, rotation);
        this.killAuraBridge = new KillAuraBridge(mc, combatExecutor);
    }

    public void toggle() {
        if (state == BotState.OFF) start();
        else stop();
    }

    public void start() {
        if (mc.thePlayer == null || mc.theWorld == null) return;

        lastTickNanos = 0L;
        smoothedTickNanos = 0.0D;
        state = BotState.PLANNING;
        pit.start();
        runtimeGuard.reset();
        combatExecutor.reset();
        startupSequence.reset();
        targetSearchNavigator.reset();
        killAuraBridge.reset();
        // Normalize Myau to OFF. Startup must not depend on a chat ACK because
        // Myau output differs between builds/configurations.
        killAuraBridge.beginNavigationSync();
        deathRestartDetector.clear();
        deathRestartDetector.reset(mc.thePlayer.posY);
        releaseAutomationInput();
    }

    public void stop() {
        if (state == BotState.OFF) return;

        killAuraBridge.shutdown();
        state = BotState.OFF;
        runtimeGuard.reset();
        pit.stop();
        combatExecutor.reset();
        startupSequence.reset();
        targetSearchNavigator.reset();
        deathRestartDetector.clear();
        releaseAutomationInput();
        lastTickNanos = 0L;
        smoothedTickNanos = 0.0D;
    }

    public void handleDeathMessage() {
        if (state == BotState.OFF || mc.theWorld == null || mc.thePlayer == null) return;
        if (!deathRestartDetector.claimExternalSignal(System.currentTimeMillis())) return;
        restartAfterDeath();
    }

    public void tick() {
        if (state == BotState.OFF) return;
        long started = System.nanoTime();
        try {
            tickInternal();
        } finally {
            long elapsed = Math.max(0L, System.nanoTime() - started);
            lastTickNanos = elapsed;
            smoothedTickNanos = smoothedTickNanos <= 0.0D
                    ? elapsed
                    : smoothedTickNanos * 0.90D + elapsed * 0.10D;
        }
    }

    private void tickInternal() {
        if (state == BotState.OFF) return;

        EntityPlayerSP player = mc.thePlayer;
        if (player == null || mc.theWorld == null) {
            stop();
            return;
        }

        movement.beginTick();

        // GUI/death/world guards run before startup movement too.
        if (handleRuntimeGuard(player)) return;

        if (startupSequence.isComplete()
                && deathRestartDetector.detect(player, System.currentTimeMillis())) {
            restartAfterDeath();
            return;
        }

        if (!startupSequence.isComplete()) {
            deactivateCombat(player, false);
            // Startup navigation owns rotation after the local OFF hand-off.
            // Never block /play pit or route startup on a Myau chat ACK.
            if (!WanderBotSettings.navigationEnabled) {
                movement.release();
                state = BotState.PLANNING;
                return;
            }
            startupSequence.tick(player);
            state = BotState.WALKING;
            return;
        }

        pit.tick();

        if (!WanderBotSettings.combatEnabled) {
            pit.getMasterDecision().evaluate(player, null);
            deactivateCombat(player, false);
            state = BotState.WALKING;
            return;
        }

        PitMode pitMode = pit.getMode();
        PitRulesEngine.State rules = pit.getRulesState();
        if (isCombatBlocked(pitMode, rules)) {
            pit.getMasterDecision().evaluate(player, null);
            deactivateCombat(player, false);
            state = pitMode == PitMode.PAUSED ? BotState.WALKING : BotState.PLANNING;
            return;
        }

        if (pit.getZones().isSelfProtected(player)) {
            pit.getMasterDecision().evaluate(player, null);
            deactivateCombat(player, false);
            state = BotState.WALKING;
            return;
        }

        EntityPlayer target = findTarget(player);
        if (target == null || !pit.getTargets().isViable(player, TargetTracker.LOADED_PLAYER_SCAN_RANGE)) {
            pit.getMasterDecision().evaluate(player, null);
            enterTargetSearch(player, true);
            state = WanderBotSettings.navigationEnabled ? BotState.WALKING : BotState.PLANNING;
            return;
        }

        // A combat target now owns navigation; discard any old patrol route before
        // the combat planner writes movement for this tick.
        targetSearchNavigator.reset();

        if (pit.getZones().isPlayerProtected(target)) {
            pit.getMasterDecision().evaluate(player, target);
            deactivateCombat(player, true);
            state = BotState.WALKING;
            return;
        }

        PitMasterDecisionEngine.Decision decision = pit.getMasterDecision().evaluate(player, target);
        PitExecutionOrchestrator.Directive directive = executionOrchestrator.resolve(player, decision, target);
        executeDirective(player, directive);
    }

    private void executeDirective(EntityPlayerSP player, PitExecutionOrchestrator.Directive directive) {
        if (directive == null) {
            deactivateCombat(player, false);
            state = BotState.PLANNING;
            return;
        }

        if (directive.releaseMovement) movement.release();

        switch (directive.owner) {
            case WAIT:
                deactivateCombat(player, false);
                state = BotState.PLANNING;
                return;

            case RECOVERY:
                deactivateCombat(player, false);
                state = BotState.RECOVERING;
                return;

            case NAVIGATION:
                deactivateCombat(player, directive.target == null);
                state = WanderBotSettings.navigationEnabled ? BotState.WALKING : BotState.PLANNING;
                return;

            case COMBAT:
                executeCombatDirective(player, directive);
                return;

            case NONE:
            default:
                deactivateCombat(player, false);
                state = BotState.PLANNING;
        }
    }

    private void executeCombatDirective(EntityPlayerSP player, PitExecutionOrchestrator.Directive directive) {
        EntityPlayer target = directive.target;
        CombatDecisionEngine.Action action = toCombatAction(directive.action);

        if (action == CombatDecisionEngine.Action.DISENGAGE) {
            killAuraBridge.tick(player, null);
            combatExecutor.tick(player, target, action, System.currentTimeMillis());
            pit.disengage(6);
            movement.release();
            state = BotState.WALKING;
            return;
        }

        if (action == CombatDecisionEngine.Action.RETARGET || action == CombatDecisionEngine.Action.NONE) {
            killAuraBridge.tick(player, null);
            combatExecutor.tick(player, target, action, System.currentTimeMillis());
            pit.getTargets().clearTarget();
            movement.release();
            state = BotState.PLANNING;
            return;
        }

        combatExecutor.bindMegastreakProfile(pit.getStreakControl().getActiveMegastreak());

        // Ownership is updated before execution, so Myau and WanderBot never
        // write combat yaw/pitch during the same transition tick.
        killAuraBridge.tick(player, target);
        // A missing Myau chat acknowledgement is diagnostic only. Movement and
        // pathing continue; camera ownership remains conservative for ON and
        // returns locally for OFF.
        combatExecutor.tick(player, target, action, System.currentTimeMillis());
        state = BotState.WALKING;
    }

    private CombatDecisionEngine.Action toCombatAction(PitMasterDecisionEngine.Action action) {
        if (action == null) return CombatDecisionEngine.Action.NONE;
        switch (action) {
            case ATTACK:
                return CombatDecisionEngine.Action.ATTACK;
            case APPROACH:
            case REPOSITION:
                return CombatDecisionEngine.Action.APPROACH;
            case RETARGET:
                return CombatDecisionEngine.Action.RETARGET;
            case DISENGAGE:
                return CombatDecisionEngine.Action.DISENGAGE;
            default:
                return CombatDecisionEngine.Action.NONE;
        }
    }

    public void onDisconnect() {
        if (state == BotState.OFF) return;

        killAuraBridge.shutdown();
        pit.stop();
        combatExecutor.reset();
        releaseAutomationInput();
        runtimeGuard.reset();
        deathRestartDetector.clear();
        state = BotState.OFF;
        lastTickNanos = 0L;
        smoothedTickNanos = 0.0D;
    }

    private boolean handleRuntimeGuard(EntityPlayerSP player) {
        PitRuntimeGuard.Status guardStatus = runtimeGuard.inspect(mc, pit.getRuntimeTick());
        switch (guardStatus) {
            case PAUSED_GUI:
                deactivateCombat(player, false);
                return true;

            case PLAYER_DEAD:
                deactivateCombat(player, false);
                pit.handleRuntimeDeath();
                state = BotState.RECOVERING;
                return true;

            case WORLD_CHANGED:
                deactivateCombat(player, false);
                pit.resetForWorldChange();
                startupSequence.reset();
                targetSearchNavigator.reset();
                state = BotState.PLANNING;
                deathRestartDetector.reset(player.posY);
                return true;

            default:
                return false;
        }
    }

    private boolean isCombatBlocked(PitMode pitMode, PitRulesEngine.State rules) {
        if (WanderBotSettings.forcePitMode) return false;
        if (rules == null) return true;

        return pitMode == PitMode.WAITING
                || pitMode == PitMode.WARMUP
                || pitMode == PitMode.UNSAFE
                || pitMode == PitMode.PAUSED
                || rules.phase == PitRulesEngine.MatchPhase.EVENT
                || rules.phase == PitRulesEngine.MatchPhase.WAITING
                || rules.phase == PitRulesEngine.MatchPhase.WARMUP;
    }

    private EntityPlayer findTarget(EntityPlayerSP player) {
        // In normal Pit mode the policy engine owns acquisition/disengage timing.
        // Forced mode bypasses that policy, so acquire exactly once here.
        if (!WanderBotSettings.forcePitMode) {
            return pit.getTargets().getTarget();
        }
        return pit.getTargets().findBest(mc.theWorld, player, pit.getZones());
    }

    private void enterTargetSearch(EntityPlayerSP player, boolean clearTrackedTarget) {
        // SEARCH is an actual navigation state, not an idle state. Myau/combat are
        // released first, then the low-frequency patrol planner owns movement.
        killAuraBridge.tick(player, null);
        killAuraBridge.clearTarget();
        combatExecutor.suspend();
        if (clearTrackedTarget) pit.getTargets().clearTarget();

        if (WanderBotSettings.navigationEnabled) {
            targetSearchNavigator.tick(player, pit.getZones());
        } else {
            targetSearchNavigator.reset();
            movement.release();
        }
    }

    private void deactivateCombat(EntityPlayerSP player, boolean clearTrackedTarget) {
        killAuraBridge.tick(player, null);
        killAuraBridge.clearTarget();
        combatExecutor.suspend();
        if (clearTrackedTarget) pit.getTargets().clearTarget();
        releaseAutomationInput();
    }

    private void releaseAutomationInput() {
        movement.release();
    }

    private void restartAfterDeath() {
        releaseAutomationInput();
        pit.getTargets().clear();
        runtimeGuard.reset();
        combatExecutor.reset();
        startupSequence.reset();
        targetSearchNavigator.reset();
        pit.start();

        // Re-establish external OFF ownership after local controllers are reset.
        // beginNavigationSync also removes any previously registered enemy.
        killAuraBridge.beginNavigationSync();
        state = BotState.PLANNING;
        deathRestartDetector.reset(mc.thePlayer != null ? mc.thePlayer.posY : 0.0D);
    }

    public void applyKillAuraStateFromMessage(boolean active) {
        killAuraBridge.applyStateFromMessage(active);
    }

    public CombatExecutionController getCombatExecutor() {
        return combatExecutor;
    }

    /** True only after Myau has confirmed KillAura ON. */
    public boolean isKillAuraActive() {
        return killAuraBridge.isActive();
    }

    /** True while Myau owns (or may still own) combat rotation. */
    public boolean isMyauAimOwner() {
        return killAuraBridge.ownsCombatAim();
    }

    public String getKillAuraStatus() {
        return killAuraBridge.getStatus();
    }

    /** Current active path for compatibility/debug views. */
    public Path getPath() {
        Path navigationPath = getNavigationPath();
        return navigationPath != null ? navigationPath : combatExecutor.getLastCombatPath();
    }

    /** Startup/navigation route only; combat routing is rendered separately. */
    public Path getNavigationPath() {
        if (!startupSequence.isComplete()) return startupSequence.getPath();
        return targetSearchNavigator.getPath();
    }

    public String getTargetSearchStatus() {
        return targetSearchNavigator.getStatus();
    }

    public BotState getState() {
        return state;
    }

    public boolean isEnabled() {
        return state != BotState.OFF;
    }

    public PitMode getPitMode() {
        return pit.getMode();
    }

    public PitDecisionEngine getPit() {
        return pit;
    }

    public String getRuntimeGuardStatus() {
        return runtimeGuard.getStatus().name();
    }

    public double getTargetScore() {
        return pit.getTargets().getTargetScore();
    }

    public TargetTracker.ArmorProfile getTargetArmor() {
        return pit.getTargets().getTargetArmor();
    }

    public String getTargetName() {
        EntityPlayer target = pit.getTargets().getTarget();
        return target == null ? null : target.getName();
    }

    public int getLocalStreak() {
        return pit.getStreak().getEffectiveStreak();
    }

    public StreakManager.Tier getStreakTier() {
        return pit.getStreak().getTier();
    }

    public int getPeakStreak() {
        return pit.getStreak().getPeakStreak();
    }

    public double getLastTickMillis() {
        return lastTickNanos / 1000000.0D;
    }

    public double getAverageTickMillis() {
        return smoothedTickNanos / 1000000.0D;
    }
}
