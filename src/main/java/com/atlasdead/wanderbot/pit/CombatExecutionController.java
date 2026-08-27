package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.rotation.RotationController;
import com.atlasdead.wanderbot.rotation.AimController;
import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * Executes ordinary client-side movement/attack input for the Pit combat layer.
 * The controller intentionally uses the same keybind path a normal player uses.
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
              boolean aligned, boolean inRange, boolean attackAllowed, int attackTimer, String decisionReason) {
            this.mode = mode;
            this.distance = distance;
            this.yawError = yawError;
            this.pitchError = pitchError;
            this.visible = visible;
            this.attacking = attacking;
            this.aligned = aligned;
            this.inRange = inRange;
            this.attackAllowed = attackAllowed;
            this.attackTimer = attackTimer;
            this.decisionReason = decisionReason == null ? "" : decisionReason;
        }
    }

    private final Minecraft mc;
    private final MovementController movement;
    private final RotationController rotation;
    private final AimController aimController;
    private int attackTimer;
    private int jumpTimer;
    private long targetLockUntil;
    private int strafeSign = 1;
    private final CombatTacticalModel tactical = new CombatTacticalModel();
    private final CombatNavigationController combatNavigation = new CombatNavigationController();
    private final CombatNavigationCoordinator combatCoordinator;
    private final CombatStateMachine stateMachine = new CombatStateMachine();
    private CombatTelemetry telemetry = CombatTelemetry.idle();
    private State lastState = new State("IDLE", 0.0D, 180.0F, 90.0F, false, false);
    private final CombatFeedbackModel feedback = new CombatFeedbackModel();
    private CombatThreatField threatField;
    private MegastreakProfileEngine.Profile megastreakProfile;
    private CombatControlModel control;

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
        attackTimer = 0;
        jumpTimer = 0;
        targetLockUntil = 0L;
        strafeSign = 1;
        tactical.reset();
        control.reset();
        stateMachine.reset();
        combatCoordinator.reset();
        telemetry = CombatTelemetry.idle();
        feedback.reset();
        aimController.reset();
        megastreakProfile = null;
    }

    public State tick(EntityPlayerSP self, EntityPlayer target, CombatDecisionEngine.Action action, long now) {
        if (self == null || target == null || action == null) {
            movement.release();
            return publishState(new State("IDLE", 0.0D, 180.0F, 90.0F, false, false));
        }

        if (attackTimer > 0) attackTimer--;
        if (jumpTimer > 0) jumpTimer--;

        if (action == CombatDecisionEngine.Action.DISENGAGE || action == CombatDecisionEngine.Action.NONE
                || action == CombatDecisionEngine.Action.RETARGET) {
            movement.release();
            return publishState(state(self, target, "RESET", false));
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
            return publishState(state(self, target, threats.crossfire ? "CROSSFIRE_REASSESS" : "SURROUNDED_REASSESS", false));
        }
        if (controlDecision.requestRetarget || controlDecision.phase == CombatControlModel.Phase.REASSESS) {
            movement.release();
            return publishState(state(self, target, "REASSESS", false));
        }
        if (controlDecision.phase == CombatControlModel.Phase.RETREAT) {
            CombatNavigationController.Result retreatRoute = combatNavigation.compute(
                    mc.theWorld, self, target, tacticalState, distance);
            float retreatStrafe = (float)(-retreatRoute.z * 0.65D + tacticalState.strafeSign * 0.35D);
            movement.forward(retreatRoute.x < -0.15D);
            movement.backward(retreatRoute.x > 0.20D);
            movement.strafe(retreatStrafe);
            movement.sprint(false);
            movement.attack(false);
            return publishState(state(self, target, retreatRoute.reason, false));
        }
        CombatTrackingModel.Snapshot tracking = tactical.getTrackingState();
        AimController.Result aim = aimController.update(self, target, tracking, distance, visible,
                action == CombatDecisionEngine.Action.ATTACK || action == CombatDecisionEngine.Action.APPROACH);
        float yawError = aim.yawError;
        float pitchError = aim.pitchError;

        CombatNavigationController.Result combatRoute = combatNavigation.compute(
                mc.theWorld, self, target, tacticalState, distance);
        if (combatRoute.blocked) {
            movement.release();
            return publishState(state(self, target, "COMBAT_ROUTE_BLOCKED", false));
        }

        if (action == CombatDecisionEngine.Action.ATTACK && controlDecision.allowAttack) {
            executeAttack(self, target, distance, visible, yawError, pitchError, tacticalState, combatRoute);
        } else {
            executeApproach(self, target, distance, visible, yawError, tacticalState, combatRoute);
        }

        CombatTelemetry previous = telemetry;
        telemetry = new CombatTelemetry(
                action == CombatDecisionEngine.Action.ATTACK ? CombatStateMachine.Phase.ENGAGE : CombatStateMachine.Phase.APPROACH,
                action, previous.threatScore, previous.combatScore, previous.crowdPressure,
                visible, distance, previous.navigationOutcome, previous.megastreakId, previous.streak, previous.reason)
                .withFeedback(feedbackState.event.name(), feedbackState.selfHealth, feedbackState.targetHealth);
        return publishState(new State(action.name(), distance, yawError, pitchError, visible,
                action == CombatDecisionEngine.Action.ATTACK && attackTimer > 0,
                aim.aligned, distance <= 3.20D,
                action == CombatDecisionEngine.Action.ATTACK && controlDecision.allowAttack,
                attackTimer, controlDecision.reason));
    }

    private State publishState(State state) {
        lastState = state;
        return state;
    }

    public State getLastState() {
        return lastState;
    }

    private void executeAttack(EntityPlayerSP self, EntityPlayer target, double distance,
                               boolean visible, float yawError, float pitchError,
                               CombatTacticalModel.State tacticalState,
                               CombatNavigationController.Result route) {
        float strafe = tacticalState.strafeSign;
        CombatTrackingModel.Snapshot tracked = tactical.getTrackingState();
        if (tracked != null && tracked.reversal) strafe = -strafe;
        if (tracked != null && tracked.suddenStop) strafe *= 0.75F;
        boolean aligned = visible && yawError <= 16.0F && Math.abs(pitchError) <= 18.0F;
        boolean inRange = distance <= 3.20D;
        boolean tooClose = distance < tacticalState.preferredDistance - 0.45D;
        boolean targetRetreating = tacticalState.targetRetreating;

        // Preserve a controllable melee gap instead of constantly colliding with
        // the target. Strafing becomes stronger while the target retreats.
        boolean moveForward = distance > tacticalState.preferredDistance + 0.15D;
        if (tooClose) moveForward = false;
        moveForward = moveForward && route.distanceBias > -0.95D;
        movement.forward(moveForward);
        double aggression = megastreakProfile == null ? 0.55D : megastreakProfile.targetAggression;
        float strafeAmount = tooClose ? 0.65F : (targetRetreating ? 0.58F : (float)(0.35D + aggression * 0.20D));
        float routeStrafe = (float)(route.z * 0.45D + strafe * strafeAmount);
        movement.strafe(routeStrafe);
        movement.sprint(false);

        if (jumpTimer == 0 && self.onGround && shouldCombatJump(self, target, distance, tacticalState)) {
            movement.jump();
            jumpTimer = 10;
        }

        if (inRange && visible && aligned && attackTimer == 0) {
            // Explicitly trigger the real 1.8.9 left-click pipeline.
            movement.attack(false);
            movement.clickAttack();
            attackTimer = targetRetreating ? 5 : 6;
        } else {
            movement.attack(false);
        }
    }

    private void executeApproach(EntityPlayerSP self, EntityPlayer target, double distance,
                                 boolean visible, float yawError,
                                 CombatTacticalModel.State tacticalState,
                                 CombatNavigationController.Result route) {
        if (visible && distance < 5.0D) {
            float strafe = tacticalState.strafeSign;
            boolean tooClose = distance < tacticalState.preferredDistance - 0.55D;
            movement.forward(route.x > 0.05D && !tooClose);
            movement.backward(route.x < -0.25D);
            movement.strafe((float)(route.z * 0.60D + strafe * (tooClose ? 0.45F : 0.30F)));
            movement.sprint(self.onGround && yawError < 24.0F && distance > tacticalState.preferredDistance);
        } else {
            movement.forward(route.x > -0.10D);
            movement.backward(route.x < -0.35D);
            movement.strafe((float)(route.z * 0.55D));
            movement.sprint(self.onGround && yawError < 20.0F);
        }
        movement.attack(false);
    }

    private boolean shouldCombatJump(EntityPlayerSP self, EntityPlayer target, double distance,
                                     CombatTacticalModel.State tacticalState) {
        return distance > 2.55D
                && distance < 4.0D
                && tacticalState.verticalDistance < 1.15D
                && Math.abs(tacticalState.closingRate) < 2.8D;
    }

    private float chooseStrafe(EntityPlayerSP self, EntityPlayer target) {
        long now = System.currentTimeMillis();
        if (now >= targetLockUntil) {
            double dx = target.posX - self.posX;
            double dz = target.posZ - self.posZ;
            double cross = self.motionX * dz - self.motionZ * dx;
            if (Math.abs(cross) > 0.01D) strafeSign = cross > 0.0D ? -1 : 1;
            else if (((now / 900L) & 1L) == 0L) strafeSign = 1;
            else strafeSign = -1;
            targetLockUntil = now + 650L;
        }
        return strafeSign;
    }

    private double predictedTargetX(EntityPlayer target, double distance, CombatTacticalModel.State tacticalState) {
        CombatTrackingModel.Snapshot tracked = tactical.getTrackingState();
        if (tracked != null) return tracked.predictedX;
        double lead = MathHelper.clamp_double(distance / 10.0D, 0.05D, 0.25D);
        return target.posX + target.motionX * lead;
    }

    private double predictedTargetY(EntityPlayer target, double distance, CombatTacticalModel.State tacticalState) {
        CombatTrackingModel.Snapshot tracked = tactical.getTrackingState();
        if (tracked != null) {
            double extra = tracked.jumping ? 0.06D : (tracked.descending ? -0.04D : 0.0D);
            return tracked.predictedY + target.getEyeHeight() * 0.82D + extra;
        }
        double lead = MathHelper.clamp_double(distance / 12.0D, 0.04D, 0.16D);
        return target.posY + target.motionY * lead + target.getEyeHeight() * 0.82D;
    }

    private double predictedTargetZ(EntityPlayer target, double distance, CombatTacticalModel.State tacticalState) {
        CombatTrackingModel.Snapshot tracked = tactical.getTrackingState();
        if (tracked != null) return tracked.predictedZ;
        double lead = MathHelper.clamp_double(distance / 10.0D, 0.05D, 0.25D);
        return target.posZ + target.motionZ * lead;
    }

    private float estimatePitchError(EntityPlayerSP self, double x, double y, double z) {
        double dx = x - self.posX;
        double dy = y - (self.posY + self.getEyeHeight());
        double dz = z - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) return 0.0F;
        float desired = (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));
        return Math.abs(MathHelper.wrapAngleTo180_float(desired - self.rotationPitch));
    }

    public CombatTelemetry getTelemetry() { return telemetry; }
    public CombatNavigationCoordinator.Outcome getNavigationOutcome() { return combatCoordinator.getLastOutcome(); }

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
