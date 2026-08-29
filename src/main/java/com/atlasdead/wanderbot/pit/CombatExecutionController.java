package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.rotation.RotationController;
import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import com.atlasdead.wanderbot.pathfinding.CombatPathFinder;
import com.atlasdead.wanderbot.pathfinding.CombatSteering;
import com.atlasdead.wanderbot.pathfinding.CombatStuckDetector;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.rotation.AimController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * Executes client-side movement for the Pit combat layer.
 *
 * Combat Path is authoritative for movement. When KillAura is OFF, rotation
 * follows the same rendered combat waypoint. When KillAura is ON, rotation is
 * left entirely to KillAura and only path movement inputs are generated here.
 */
public class CombatExecutionController {
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
        public final int attackTimer;
        public final String decisionReason;

        State(String mode, double distance, float yawError, float pitchError, boolean visible, boolean attacking) {
            this(mode, distance, yawError, pitchError, visible, attacking, false, false, false, 0, "");
        }

        State(String mode, double distance, float yawError, float pitchError, boolean visible, boolean attacking,
              boolean aligned, boolean inRange, boolean allowAttack, int attackTimer, String decisionReason) {
            this.mode = mode;
            this.distance = distance;
            this.yawError = yawError;
            this.pitchError = pitchError;
            this.visible = visible;
            this.attacking = attacking;
            this.aligned = aligned;
            this.inRange = inRange;
            this.attackAllowed = allowAttack;
            this.attackTimer = attackTimer;
            this.decisionReason = decisionReason == null ? "" : decisionReason;
        }
    }

    private final Minecraft mc;
    private final MovementController movement;
    private final RotationController rotation;
    private long attackDelayMS = 0L;
    private int attackTimer;
    private int jumpTimer;
    private long targetLockUntil;
    private int strafeSign = 1;
    private static final int ROTATION_MIN_DELAY = 2;
    private int rotationAlignedTick;
    private final CombatTacticalModel tactical = new CombatTacticalModel();
    private final CombatNavigationCoordinator combatCoordinator;
    private final CombatNavigationController combatNavigation = new CombatNavigationController();
    private final AimController aimController;
    private final CombatPathFinder combatPathFinder = new CombatPathFinder();
    private final CombatSteering combatSteering = new CombatSteering();
    private final CombatStuckDetector combatStuck = new CombatStuckDetector();
    private final CombatStateMachine stateMachine = new CombatStateMachine();
    private CombatTelemetry telemetry = CombatTelemetry.idle();
    private State lastState = new State("IDLE", 0.0D, 180.0F, 90.0F, false, false);
    private final CombatFeedbackModel feedback = new CombatFeedbackModel();
    private CombatThreatField threatField;
    private MegastreakProfileEngine.Profile megastreakProfile;
    private CombatControlModel control;

    private CombatState combatState = CombatState.NO_TARGET;
    private CombatTarget currentTarget;
    private Path lastCombatPath;
    private boolean killAuraActive;

    public static String combatDebug = "";

    public void setKillAuraActive(boolean active) { this.killAuraActive = active; }
    public Path getLastCombatPath() { return lastCombatPath; }

    public CombatExecutionController(Minecraft mc, MovementController movement, RotationController rotation) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
        this.aimController = new AimController(rotation);
        this.combatCoordinator = new CombatNavigationCoordinator(mc, new TerrainAnalyzer(), new LocalAvoidanceController());
        this.control = new CombatControlModel(mc, null, null);
    }

    public CombatExecutionController(Minecraft mc, MovementController movement, RotationController rotation,
                                     PitZoneManager zones, TargetTracker targets) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
        this.aimController = new AimController(rotation);
        this.combatCoordinator = new CombatNavigationCoordinator(mc, new TerrainAnalyzer(), new LocalAvoidanceController());
        this.control = new CombatControlModel(mc, zones, targets);
    }

    public void bindContext(PitZoneManager zones, TargetTracker targets) {
        this.control = new CombatControlModel(mc, zones, targets);
        this.threatField = new CombatThreatField(targets, zones);
    }

    public void bindMegastreakProfile(PitStreakCatalog.Definition definition) {
        this.megastreakProfile = MegastreakProfileEngine.profile(definition);
    }

    public void reset() {
        attackDelayMS = 0L;
        attackTimer = 0;
        jumpTimer = 0;
        targetLockUntil = 0L;
        rotationAlignedTick = 0;
        strafeSign = 1;
        tactical.reset();
        aimController.reset();
        control.reset();
        stateMachine.reset();
        combatCoordinator.reset();
        telemetry = CombatTelemetry.idle();
        feedback.reset();
        megastreakProfile = null;
        combatState = CombatState.NO_TARGET;
        currentTarget = null;
        lastCombatPath = null;
    }

    public State tick(EntityPlayerSP self, EntityPlayer target, CombatDecisionEngine.Action action, long now) {
        if (self == null || target == null || action == null) {
            movement.release();
            combatDebug = "";
            combatState = CombatState.NO_TARGET;
            currentTarget = null;
            lastCombatPath = null;
            return publishState(new State("IDLE", 0.0D, 180.0F, 90.0F, false, false));
        }

        if (attackDelayMS > 0L) attackDelayMS -= 50L;
        if (attackTimer > 0) attackTimer--;
        if (jumpTimer > 0) jumpTimer--;

        if (action == CombatDecisionEngine.Action.DISENGAGE || action == CombatDecisionEngine.Action.NONE
                || action == CombatDecisionEngine.Action.RETARGET) {
            movement.release();
            combatState = CombatState.NO_TARGET;
            currentTarget = null;
            lastCombatPath = null;
            return publishState(state(self, target, "RESET", false));
        }

        if (currentTarget == null || !currentTarget.isAlive() || currentTarget.getEntity() != target) {
            currentTarget = new CombatTarget(target, now / 50L);
            combatState = CombatState.ACQUIRE_TARGET;
        } else {
            currentTarget = currentTarget.refresh(now / 50L);
        }

        CombatFeedbackModel.Snapshot feedbackState = feedback.update(self, target, now);
        boolean visible = feedbackState.targetVisible;
        double distance = feedbackState.distance;
        CombatThreatField.Snapshot threats = threatField == null ? CombatThreatField.Snapshot.empty()
                : threatField.sample(mc.theWorld, self, target, 7.5D);
        CombatTacticalModel.State tacticalState = tactical.update(self, target, now);
        CombatControlModel.Decision controlDecision = control.evaluate(self, target, tacticalState, now);
        int crowdPressure = threats.nearbyEligible;
        CombatNavigationCoordinator.Outcome navOutcome = combatCoordinator.update(
                self, target, controlDecision.desiredDistance, crowdPressure,
                controlDecision.phase == CombatControlModel.Phase.RETREAT,
                tacticalState != null && tactical.getTrackingState() != null && tactical.getTrackingState().reversal,
                now / 50L);
        telemetry = new CombatTelemetry(CombatStateMachine.Phase.MAINTAIN, action, 0.0D, 0.0D,
                crowdPressure, visible, distance, navOutcome,
                megastreakProfile == null ? "none" : megastreakProfile.id, 0, controlDecision.reason)
                .withFeedback(feedbackState.event.name(), feedbackState.selfHealth, feedbackState.targetHealth);

        if (threats.surrounded || threats.crossfire) {
            movement.release();
            combatState = CombatState.NO_TARGET;
            return publishState(state(self, target, threats.crossfire ? "CROSSFIRE_REASSESS" : "SURROUNDED_REASSESS", false));
        }

        boolean suppressAttack = controlDecision.requestRetarget
                || controlDecision.phase == CombatControlModel.Phase.REASSESS;

        if (controlDecision.phase == CombatControlModel.Phase.RETREAT) {
            CombatNavigationController.Result retreatRoute = combatNavigation.compute(
                    mc.theWorld, self, target, tacticalState, distance);
            float retreatStrafe = (float)(-retreatRoute.z * 0.65D + tacticalState.strafeSign * 0.35D);
            movement.forward(retreatRoute.x < -0.15D);
            movement.backward(retreatRoute.x > 0.20D);
            movement.strafe(retreatStrafe);
            movement.sprint(false);
            movement.attack(false);
            combatState = CombatState.APPROACH;
            return publishState(state(self, target, retreatRoute.reason, false));
        }

        // Compute the same combat path that PathRenderer displays before any
        // rotation or movement decision is made.
        lastCombatPath = combatPathFinder.getPath(mc.theWorld, self, target, 200);
        Path combatPath = lastCombatPath;

        CombatStuckDetector.RecoveryAction stuckAction = combatStuck.update(self, now / 50L);
        if (stuckAction == CombatStuckDetector.RecoveryAction.FULL_REPLAN) {
            combatPathFinder.reset();
            lastCombatPath = combatPathFinder.getPath(mc.theWorld, self, target, 200);
            combatPath = lastCombatPath;
        }

        // When KillAura is OFF, rotate toward the exact active rendered
        // waypoint. When ON, never touch the player's rotation here.
        float yawError;
        float pitchError;
        if (killAuraActive) {
            yawError = computeYawError(self, target);
            pitchError = 0.0F;
            rotationAlignedTick = ROTATION_MIN_DELAY;
        } else if (combatPath != null && !combatPath.isFinished() && combatPath.current() != null) {
            PathNode waypoint = combatPath.current();
            double waypointX = waypoint.x + 0.5D;
            double waypointY = waypoint.y + 1.0D;
            double waypointZ = waypoint.z + 0.5D;
            yawError = rotation.tick(self, waypointX, waypointY, waypointZ, 0.0F);
            pitchError = 0.0F;
            if (yawError <= 10.0F) rotationAlignedTick = Math.min(rotationAlignedTick + 1, 20);
            else rotationAlignedTick = 0;
        } else {
            AimController.Result aim = aimController.update(self, target, tactical.getTrackingState(), distance, visible, true);
            yawError = aim.yawError;
            pitchError = aim.pitchError;
            if (aim.aligned) rotationAlignedTick = Math.min(rotationAlignedTick + 1, 20);
            else rotationAlignedTick = 0;
        }

        // CombatSteering advances the same Path instance that is rendered,
        // then computes movement from that active waypoint.
        CombatSteering.Result steer = combatSteering.compute(self, combatPath, distance);

        boolean inRange = distance <= 3.20D;
        boolean canAttackNow = action == CombatDecisionEngine.Action.ATTACK
                && controlDecision.allowAttack && !suppressAttack;

        if (inRange && rotationAlignedTick >= ROTATION_MIN_DELAY) {
            if (attackTimer == 0 && canAttackNow) combatState = CombatState.ATTACK_READY;
            else if (attackTimer > 0) combatState = CombatState.COOLDOWN;
            else combatState = CombatState.AIM;
        } else {
            combatState = CombatState.APPROACH;
        }

        movement.forward(steer.forward);
        movement.backward(steer.backward);
        movement.strafe(steer.strafe);
        movement.sprint(steer.sprint && self.onGround);
        if (steer.jump && self.onGround && jumpTimer <= 0) {
            movement.jump();
            jumpTimer = 6;
        }
        movement.attack(false);

        CombatTelemetry previous = telemetry;
        telemetry = new CombatTelemetry(
                action == CombatDecisionEngine.Action.ATTACK ? CombatStateMachine.Phase.ENGAGE : CombatStateMachine.Phase.APPROACH,
                action, previous.threatScore, previous.combatScore, previous.crowdPressure,
                visible, distance, previous.navigationOutcome, previous.megastreakId, previous.streak, previous.reason)
                .withFeedback(feedbackState.event.name(), feedbackState.selfHealth, feedbackState.targetHealth);

        int pathIdx = combatPath != null ? combatPath.getIndex() : 0;
        int pathSize = combatPath != null ? combatPath.getNodes().size() : 0;
        combatDebug = String.format("State=%s T=%s D=%.1f Path=%d/%d Stuck=%s Sprint=%s Jump=%s Atk=%d Rot=%s",
                combatState.name(),
                target != null ? target.getName() : "none",
                distance, pathIdx, pathSize,
                stuckAction.name(),
                steer.sprint ? "ON" : "OFF",
                steer.jump ? "YES" : "no",
                attackTimer,
                killAuraActive ? "KILLAURA" : "PATH");

        return publishState(new State(action.name(), distance, yawError, pitchError, visible,
                combatState == CombatState.COOLDOWN,
                rotationAlignedTick >= ROTATION_MIN_DELAY, inRange,
                canAttackNow,
                attackTimer, controlDecision.reason));
    }

    private State publishState(State state) {
        lastState = state;
        return state;
    }

    public State getLastState() { return lastState; }
    public CombatState getCombatState() { return combatState; }
    public CombatTarget getCurrentTarget() { return currentTarget; }
    public CombatTelemetry getTelemetry() { return telemetry; }
    public CombatNavigationCoordinator.Outcome getNavigationOutcome() { return combatCoordinator.getLastOutcome(); }

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
        float pitch = horizontal < 0.001D ? 0.0F : Math.abs(MathHelper.wrapAngleTo180_float(
                (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI)) - self.rotationPitch));
        return new State(mode, self.getDistanceToEntity(target), yaw, pitch,
                self.canEntityBeSeen(target), attacking);
    }
}
