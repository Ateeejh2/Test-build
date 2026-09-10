package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import com.atlasdead.wanderbot.pathfinding.CombatPathFinder;
import com.atlasdead.wanderbot.pathfinding.CombatSteering;
import com.atlasdead.wanderbot.pathfinding.CombatStuckDetector;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.rotation.AimController;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/** Executes client-side movement for the Pit combat layer. */
public final class CombatExecutionController {
    private static final int ROTATION_MIN_DELAY_TICKS = 2;
    private static final int JUMP_COOLDOWN_TICKS = 6;
    private static final int COMBAT_PATH_LIMIT = 10000;
    private static final long DEBUG_UPDATE_MS = 200L;

    public static final class State {
        public final String mode;
        public final double distance;
        public final float yawError;
        public final float pitchError;
        public final boolean visible;
        public final boolean attacking;
        public final boolean aligned;
        public final boolean inRange;
        public final boolean attackAllowed;
        public final String decisionReason;

        State(String mode, double distance, float yawError, float pitchError,
              boolean visible, boolean attacking) {
            this(mode, distance, yawError, pitchError, visible, attacking,
                    false, false, false, "");
        }

        State(String mode, double distance, float yawError, float pitchError,
              boolean visible, boolean attacking, boolean aligned, boolean inRange,
              boolean attackAllowed, String decisionReason) {
            this.mode = mode;
            this.distance = distance;
            this.yawError = yawError;
            this.pitchError = pitchError;
            this.visible = visible;
            this.attacking = attacking;
            this.aligned = aligned;
            this.inRange = inRange;
            this.attackAllowed = attackAllowed;
            this.decisionReason = decisionReason == null ? "" : decisionReason;
        }
    }

    private final Minecraft mc;
    private final MovementController movement;
    private final RotationController rotation;
    private final CombatTacticalModel tactical = new CombatTacticalModel();
    private final CombatNavigationCoordinator combatCoordinator;
    private final CombatNavigationController combatNavigation = new CombatNavigationController();
    private final AimController aimController;
    private final CombatPathFinder combatPathFinder = new CombatPathFinder();
    private final CombatSteering combatSteering = new CombatSteering();
    private final CombatStuckDetector combatStuck = new CombatStuckDetector();
    private final CombatFeedbackModel feedback = new CombatFeedbackModel();

    private CombatThreatField threatField;
    private MegastreakProfileEngine.Profile megastreakProfile;
    private CombatControlModel control;
    private CombatTelemetry telemetry = CombatTelemetry.idle();
    private State lastState = idleState();
    private CombatState combatState = CombatState.NO_TARGET;
    private CombatTarget currentTarget;
    private Path lastCombatPath;

    private int jumpTimer;
    private int rotationAlignedTick;
    private long lastNavigationSampleTick = Long.MIN_VALUE;
    private long lastDebugUpdateMs;
    private boolean killAuraActive;

    public static String combatDebug = "";

    public CombatExecutionController(Minecraft mc, MovementController movement, RotationController rotation) {
        this(mc, movement, rotation, null, null);
    }

    public CombatExecutionController(Minecraft mc, MovementController movement, RotationController rotation,
                                     PitZoneManager zones, TargetTracker targets) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
        this.aimController = new AimController(rotation);
        this.combatCoordinator = new CombatNavigationCoordinator(
                mc, new TerrainAnalyzer(), new LocalAvoidanceController());
        this.control = new CombatControlModel(mc, zones, targets);
        if (zones != null && targets != null) {
            this.threatField = new CombatThreatField(targets, zones);
        }
    }

    public void bindContext(PitZoneManager zones, TargetTracker targets) {
        control = new CombatControlModel(mc, zones, targets);
        threatField = new CombatThreatField(targets, zones);
    }

    public void bindMegastreakProfile(PitStreakCatalog.Definition definition) {
        megastreakProfile = MegastreakProfileEngine.profile(definition);
    }

    public void setKillAuraActive(boolean active) {
        if (killAuraActive == active) return;
        killAuraActive = active;
        // Clear any leftover WanderBot rotational inertia at ownership handoff.
        // While active, Myau is the sole writer of combat yaw/pitch.
        rotation.reset();
        aimController.reset();
        rotationAlignedTick = active ? ROTATION_MIN_DELAY_TICKS : 0;
    }

    public Path getLastCombatPath() {
        return lastCombatPath;
    }

    /**
     * Leaves combat without carrying a stale route/target/telemetry into the
     * next policy state. The fast no-op guard makes repeated idle ticks cheap.
     * Myau camera ownership is intentionally untouched here; KillAuraBridge is
     * the single authority for that state.
     */
    public void suspend() {
        if (currentTarget == null && lastCombatPath == null
                && combatState == CombatState.NO_TARGET
                && combatPathFinder.getCurrentPath() == null) {
            return;
        }

        movement.release();
        jumpTimer = 0;
        rotationAlignedTick = 0;
        lastNavigationSampleTick = Long.MIN_VALUE;
        lastDebugUpdateMs = 0L;

        tactical.reset();
        aimController.reset();
        control.reset();
        combatCoordinator.reset();
        combatStuck.reset();
        combatSteering.reset();
        combatPathFinder.reset();
        feedback.reset();

        telemetry = CombatTelemetry.idle();
        clearTargetState();
        lastState = idleState();
        combatDebug = "";
    }

    public void reset() {
        jumpTimer = 0;
        rotationAlignedTick = 0;
        lastNavigationSampleTick = Long.MIN_VALUE;
        lastDebugUpdateMs = 0L;

        tactical.reset();
        aimController.reset();
        control.reset();
        combatCoordinator.reset();
        combatStuck.reset();
        combatPathFinder.reset();
        feedback.reset();

        telemetry = CombatTelemetry.idle();
        megastreakProfile = null;
        clearTargetState();
        lastState = idleState();
        combatDebug = "";
    }

    public State tick(EntityPlayerSP self, EntityPlayer target,
                      CombatDecisionEngine.Action action, long now) {
        if (self == null || target == null || action == null) {
            movement.release();
            clearTargetState();
            combatDebug = "";
            return publishState(idleState());
        }

        tickTimers();

        if (requiresReset(action)) {
            movement.release();
            clearTargetState();
            combatPathFinder.reset();
            return publishState(state(self, target, "RESET", false));
        }

        refreshTarget(target, now);

        CombatFeedbackModel.Snapshot feedbackState = feedback.update(self, target, now);
        boolean visible = feedbackState.targetVisible;
        double distance = feedbackState.distance;
        CombatThreatField.Snapshot threats = sampleThreats(self, target);
        CombatTacticalModel.State tacticalState = tactical.update(self, target, now);
        CombatControlModel.Decision controlDecision = control.evaluate(self, target, tacticalState, now);
        int crowdPressure = threats.nearbyEligible;

        CombatNavigationCoordinator.Outcome navOutcome = WanderBotSettings.navigationEnabled
                ? sampleNavigationOutcome(self, target, controlDecision, tacticalState, crowdPressure, now / 50L)
                : CombatNavigationCoordinator.Outcome.NONE;

        telemetry = buildTelemetry(
                action, feedbackState, visible, distance, crowdPressure, navOutcome, controlDecision.reason);

        if (threats.surrounded || threats.crossfire) {
            movement.release();
            combatState = CombatState.NO_TARGET;
            String mode = threats.crossfire ? "CROSSFIRE_REASSESS" : "SURROUNDED_REASSESS";
            return publishState(state(self, target, mode, false));
        }

        boolean suppressAttack = controlDecision.requestRetarget
                || controlDecision.phase == CombatControlModel.Phase.REASSESS;

        if (!WanderBotSettings.navigationEnabled) {
            clearNavigationState();
            movement.release();
            RotationResult rotationResult = updateRotation(self, target, null, distance, visible);
            boolean inRange = distance <= configuredCombatRange();
            boolean canAttackNow = action == CombatDecisionEngine.Action.ATTACK
                    && controlDecision.allowAttack
                    && !suppressAttack;
            updateCombatState(inRange, canAttackNow);
            refreshTelemetry(action, feedbackState, visible, distance);
            updateSimpleDebug(target, distance, now);
            return publishState(new State(
                    "NAV_DISABLED", distance, rotationResult.yawError, rotationResult.pitchError,
                    visible, false, rotationAlignedTick >= ROTATION_MIN_DELAY_TICKS,
                    inRange, canAttackNow, controlDecision.reason));
        }

        if (controlDecision.phase == CombatControlModel.Phase.RETREAT) {
            CombatNavigationController.Result retreatRoute =
                    applyRetreatMovement(self, target, tacticalState, distance);
            combatState = CombatState.APPROACH;
            return publishState(state(self, target, retreatRoute.reason, false));
        }

        Path combatPath = prepareCombatPath(self, target);
        CombatStuckDetector.RecoveryAction stuckAction = combatStuck.update(self, now / 50L);
        if (stuckAction == CombatStuckDetector.RecoveryAction.FULL_REPLAN) {
            combatPathFinder.reset();
            lastCombatPath = combatPathFinder.getPath(mc.theWorld, self, target, COMBAT_PATH_LIMIT);
            combatPath = lastCombatPath;
        }

        CombatSteering.Result steer = combatSteering.compute(self, combatPath, distance);
        RotationResult rotationResult = updateRotation(self, target, combatPath, distance, visible);

        boolean inRange = distance <= configuredCombatRange();
        boolean canAttackNow = action == CombatDecisionEngine.Action.ATTACK
                && controlDecision.allowAttack
                && !suppressAttack;
        updateCombatState(inRange, canAttackNow);
        applySteering(self, steer);
        refreshTelemetry(action, feedbackState, visible, distance);
        updateDebug(target, distance, combatPath, stuckAction, steer, self, now);

        return publishState(new State(
                action.name(),
                distance,
                rotationResult.yawError,
                rotationResult.pitchError,
                visible,
                combatState == CombatState.ATTACK_READY,
                rotationAlignedTick >= ROTATION_MIN_DELAY_TICKS,
                inRange,
                canAttackNow,
                controlDecision.reason));
    }

    private void tickTimers() {
        if (jumpTimer > 0) jumpTimer--;
    }

    private boolean requiresReset(CombatDecisionEngine.Action action) {
        return action == CombatDecisionEngine.Action.DISENGAGE
                || action == CombatDecisionEngine.Action.NONE
                || action == CombatDecisionEngine.Action.RETARGET;
    }

    private void refreshTarget(EntityPlayer target, long now) {
        if (currentTarget == null || !currentTarget.isAlive() || currentTarget.getEntity() != target) {
            currentTarget = new CombatTarget(target, now / 50L);
            combatState = CombatState.ACQUIRE_TARGET;
            combatPathFinder.reset();
        } else {
            currentTarget = currentTarget.refresh(now / 50L);
        }
    }

    private CombatThreatField.Snapshot sampleThreats(EntityPlayerSP self, EntityPlayer target) {
        if (threatField == null) return CombatThreatField.Snapshot.empty();
        return threatField.sample(mc.theWorld, self, target, 7.5D);
    }

    private CombatNavigationCoordinator.Outcome sampleNavigationOutcome(
            EntityPlayerSP self, EntityPlayer target, CombatControlModel.Decision decision,
            CombatTacticalModel.State tacticalState, int crowdPressure, long tick) {
        int sampleInterval = Math.max(4, Math.min(10, Math.max(2, WanderBotSettings.navRepathTicks) / 2));
        if (lastNavigationSampleTick != Long.MIN_VALUE
                && tick - lastNavigationSampleTick < sampleInterval) {
            return combatCoordinator.getLastOutcome();
        }
        lastNavigationSampleTick = tick;
        return combatCoordinator.update(
                self, target, decision.desiredDistance, crowdPressure,
                decision.phase == CombatControlModel.Phase.RETREAT,
                tacticalState != null
                        && tactical.getTrackingState() != null
                        && tactical.getTrackingState().reversal,
                tick);
    }

    private CombatTelemetry buildTelemetry(CombatDecisionEngine.Action action,
                                            CombatFeedbackModel.Snapshot feedbackState,
                                            boolean visible,
                                            double distance,
                                            int crowdPressure,
                                            CombatNavigationCoordinator.Outcome navOutcome,
                                            String reason) {
        return new CombatTelemetry(
                CombatStateMachine.Phase.MAINTAIN,
                action,
                0.0D,
                0.0D,
                crowdPressure,
                visible,
                distance,
                navOutcome,
                megastreakProfile == null ? "none" : megastreakProfile.id,
                0,
                reason)
                .withFeedback(feedbackState.event.name(), feedbackState.selfHealth, feedbackState.targetHealth);
    }

    private CombatNavigationController.Result applyRetreatMovement(
            EntityPlayerSP self, EntityPlayer target,
            CombatTacticalModel.State tacticalState, double distance) {
        CombatNavigationController.Result route = combatNavigation.compute(
                mc.theWorld, self, target, tacticalState, distance);
        float strafeSign = tacticalState == null ? 0.0F : tacticalState.strafeSign;
        float retreatStrafe = (float) (-route.z * 0.65D + strafeSign * 0.35D);
        movement.locomotion(route.x < -0.15D, route.x > 0.20D, retreatStrafe);
        movement.sprint(!self.isSneaking());
        return route;
    }

    private Path prepareCombatPath(EntityPlayerSP self, EntityPlayer target) {
        lastCombatPath = combatPathFinder.getPath(mc.theWorld, self, target, COMBAT_PATH_LIMIT);
        return lastCombatPath;
    }

    private void clearNavigationState() {
        if (lastCombatPath == null && combatPathFinder.getCurrentPath() == null) return;
        lastCombatPath = null;
        combatPathFinder.reset();
        combatSteering.reset();
        combatStuck.reset();
        combatCoordinator.reset();
    }

    private static double configuredCombatRange() {
        return Math.max(2.5D, Math.min(4.0D, WanderBotSettings.combatRange));
    }

    private RotationResult updateRotation(EntityPlayerSP self, EntityPlayer target,
                                          Path combatPath, double distance, boolean visible) {
        if (killAuraActive) {
            rotationAlignedTick = ROTATION_MIN_DELAY_TICKS;
            return new RotationResult(computeYawError(self, target), 0.0F);
        }

        if (combatPath != null && !combatPath.isFinished() && combatPath.current() != null) {
            PathNode waypoint = combatPath.current();
            float yawError = rotation.tickPath(
                    self,
                    waypoint.x + 0.5D,
                    waypoint.y + 1.0D,
                    waypoint.z + 0.5D);
            updateAlignment(yawError <= 10.0F);
            return new RotationResult(yawError, 0.0F);
        }

        AimController.Result aim = aimController.update(
                self, target, tactical.getTrackingState(), distance, visible, true);
        updateAlignment(aim.aligned);
        return new RotationResult(aim.yawError, aim.pitchError);
    }

    private void updateAlignment(boolean aligned) {
        if (aligned) rotationAlignedTick = Math.min(rotationAlignedTick + 1, 20);
        else rotationAlignedTick = 0;
    }

    private void updateCombatState(boolean inRange, boolean canAttackNow) {
        if (inRange && rotationAlignedTick >= ROTATION_MIN_DELAY_TICKS) {
            combatState = canAttackNow ? CombatState.ATTACK_READY : CombatState.AIM;
        } else {
            combatState = CombatState.APPROACH;
        }
    }

    private void applySteering(EntityPlayerSP self, CombatSteering.Result steer) {
        movement.locomotion(steer.forward, steer.backward, steer.strafe);
        movement.sprint(steer.sprint);
        if (steer.jump && self.onGround && jumpTimer <= 0) {
            movement.jump();
            jumpTimer = JUMP_COOLDOWN_TICKS;
        }
    }

    private void refreshTelemetry(CombatDecisionEngine.Action action,
                                  CombatFeedbackModel.Snapshot feedbackState,
                                  boolean visible,
                                  double distance) {
        CombatTelemetry previous = telemetry;
        telemetry = new CombatTelemetry(
                action == CombatDecisionEngine.Action.ATTACK
                        ? CombatStateMachine.Phase.ENGAGE
                        : CombatStateMachine.Phase.APPROACH,
                action,
                previous.threatScore,
                previous.combatScore,
                previous.crowdPressure,
                visible,
                distance,
                previous.navigationOutcome,
                previous.megastreakId,
                previous.streak,
                previous.reason)
                .withFeedback(feedbackState.event.name(), feedbackState.selfHealth, feedbackState.targetHealth);
    }

    private void updateSimpleDebug(EntityPlayer target, double distance, long now) {
        if (!shouldRefreshDebug(now)) return;
        combatDebug = "State=" + combatState.name()
                + " T=" + target.getName()
                + " D=" + oneDecimal(distance)
                + " Nav=OFF"
                + " Rot=" + (killAuraActive ? "KILLAURA" : "AIM");
    }

    private void updateDebug(EntityPlayer target, double distance, Path combatPath,
                             CombatStuckDetector.RecoveryAction stuckAction,
                             CombatSteering.Result steer, EntityPlayerSP self, long now) {
        if (!shouldRefreshDebug(now)) return;
        int pathIndex = combatPath == null ? 0 : combatPath.getIndex();
        int pathSize = combatPath == null ? 0 : combatPath.size();
        combatDebug = "State=" + combatState.name()
                + " T=" + (target == null ? "none" : target.getName())
                + " D=" + oneDecimal(distance)
                + " Path=" + pathIndex + "/" + pathSize
                + " Stuck=" + stuckAction.name()
                + " Sprint=" + (self.onGround ? "ON" : "AIR")
                + " Jump=" + (steer.jump ? "YES" : "no")
                + " Rot=" + (killAuraActive ? "KILLAURA" : "PATH");
    }

    private boolean shouldRefreshDebug(long now) {
        if (!WanderBotSettings.debugDashboard) return false;
        if (now - lastDebugUpdateMs < DEBUG_UPDATE_MS) return false;
        lastDebugUpdateMs = now;
        return true;
    }

    private static String oneDecimal(double value) {
        long tenths = Math.round(Math.max(0.0D, value) * 10.0D);
        return (tenths / 10L) + "." + (tenths % 10L);
    }

    private void clearTargetState() {
        combatState = CombatState.NO_TARGET;
        currentTarget = null;
        lastCombatPath = null;
    }

    private State publishState(State state) {
        lastState = state;
        return state;
    }

    public State getLastState() {
        return lastState;
    }

    public CombatState getCombatState() {
        return combatState;
    }

    public CombatTarget getCurrentTarget() {
        return currentTarget;
    }

    public CombatTelemetry getTelemetry() {
        return telemetry;
    }

    public CombatNavigationCoordinator.Outcome getNavigationOutcome() {
        return combatCoordinator.getLastOutcome();
    }

    private static State idleState() {
        return new State("IDLE", 0.0D, 180.0F, 90.0F, false, false);
    }

    private static float computeYawError(EntityPlayerSP self, EntityPlayer target) {
        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        if (dx * dx + dz * dz < 1.0E-8D) return 0.0F;
        float desired = (float) (Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(desired - self.rotationYaw));
    }

    private State state(EntityPlayerSP self, EntityPlayer target, String mode, boolean attacking) {
        double dx = target.posX - self.posX;
        double dy = target.posY + target.getEyeHeight() * 0.82D - (self.posY + self.getEyeHeight());
        double dz = target.posZ - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = rotation.getLastYawError();
        float pitch = horizontal < 0.001D
                ? 0.0F
                : Math.abs(MathHelper.wrapAngleTo180_float(
                        (float) (-(Math.atan2(dy, horizontal) * 180.0D / Math.PI)) - self.rotationPitch));
        return new State(mode, self.getDistanceToEntity(target), yaw, pitch,
                self.canEntityBeSeen(target), attacking);
    }

    private static final class RotationResult {
        final float yawError;
        final float pitchError;

        RotationResult(float yawError, float pitchError) {
            this.yawError = yawError;
            this.pitchError = pitchError;
        }
    }
}
