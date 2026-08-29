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
    private final AimController aimController;
    private int attackTimer;
    private int jumpTimer;
    private long targetLockUntil;
    private int strafeSign = 1;
    /**
     * Minimum ticks that rotation must be aligned before an attack is sent.
     * This ensures the rotation packet reaches the server before the attack
     * packet, preventing Vulcan Bad Packets Type 7 / Type H detections.
     */
    private static final int ROTATION_MIN_DELAY = 2;
    private int rotationAlignedTick;
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

    // Combat state machine
    private CombatState combatState = CombatState.NO_TARGET;
    private CombatTarget currentTarget;

    /** Debug output for HUD: last computed local-space movement values. */
    public static String combatDebug = "";

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
        rotationAlignedTick = 0;
        strafeSign = 1;
        tactical.reset();
        control.reset();
        stateMachine.reset();
        combatCoordinator.reset();
        telemetry = CombatTelemetry.idle();
        feedback.reset();
        aimController.reset();
        megastreakProfile = null;
        combatState = CombatState.NO_TARGET;
        currentTarget = null;
    }

    /**
     * Convert a world-space movement vector (routeX, routeZ) into the player's
     * local forward/strafe components using the player's current rotationYaw.
     *
     * Minecraft 1.8.9 conventions:
     *   yaw=0 → facing South (+Z)
     *   yaw=90 → facing West (-X)
     *   forward world vector = (-sin(yaw), cos(yaw))
     *   right world vector   = ( cos(yaw), sin(yaw))
     */
    private static double toLocalForward(EntityPlayerSP self, double routeX, double routeZ) {
        double yaw = Math.toRadians(self.rotationYaw);
        return routeX * (-Math.sin(yaw)) + routeZ * Math.cos(yaw);
    }

    private static double toLocalStrafe(EntityPlayerSP self, double routeX, double routeZ) {
        double yaw = Math.toRadians(self.rotationYaw);
        return routeX * Math.cos(yaw) + routeZ * Math.sin(yaw);
    }

    /**
     * Send an explicit rotation packet to the server before attack packets.
     */
    private void sendRotationPacket(EntityPlayerSP self) {
        try {
            java.lang.reflect.Field connField = self.getClass().getField("connection");
            Object conn = connField.get(self);
            if (conn == null) return;

            java.lang.reflect.Field nmField = conn.getClass().getField("netManager");
            Object nm = nmField.get(conn);
            if (nm == null) return;

            Class<?> c06Class = Class.forName(
                    "net.minecraft.network.play.client.C03PacketPlayer$C06PacketPlayerPosLook");
            java.lang.reflect.Constructor<?> ctor = c06Class.getConstructor(
                    double.class, double.class, double.class,
                    float.class, float.class, boolean.class);
            Object packet = ctor.newInstance(
                    self.posX, self.posY, self.posZ,
                    self.rotationYaw, self.rotationPitch, self.onGround);

            java.lang.reflect.Method sendMethod = nm.getClass().getMethod("sendPacket",
                    Class.forName("net.minecraft.network.Packet"));
            sendMethod.invoke(nm, packet);
        } catch (Exception ignored) {
            // Fallback: rotation will be sent naturally via onUpdateWalkingPlayer.
        }
    }

    public State tick(EntityPlayerSP self, EntityPlayer target, CombatDecisionEngine.Action action, long now) {
        if (self == null || target == null || action == null) {
            movement.release();
            combatDebug = "";
            combatState = CombatState.NO_TARGET;
            currentTarget = null;
            return publishState(new State("IDLE", 0.0D, 180.0F, 90.0F, false, false));
        }

        if (attackTimer > 0) attackTimer--;
        if (jumpTimer > 0) jumpTimer--;

        if (action == CombatDecisionEngine.Action.DISENGAGE || action == CombatDecisionEngine.Action.NONE
                || action == CombatDecisionEngine.Action.RETARGET) {
            movement.release();
            combatState = CombatState.NO_TARGET;
            currentTarget = null;
            return publishState(state(self, target, "RESET", false));
        }

        // Maintain or create CombatTarget snapshot
        if (currentTarget == null || !currentTarget.isAlive() || currentTarget.getEntity() != target) {
            currentTarget = new CombatTarget(target, now / 50L);
            combatState = CombatState.ACQUIRE_TARGET;
        } else {
            // Refresh snapshot each tick while target is held
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

        // REASSESS: suppress attack only, NOT movement — keep approaching/tracking
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

        CombatTrackingModel.Snapshot tracking = tactical.getTrackingState();
        AimController.Result aim = aimController.update(self, target, tracking, distance, visible,
                action == CombatDecisionEngine.Action.ATTACK || action == CombatDecisionEngine.Action.APPROACH);
        float yawError = aim.yawError;
        float pitchError = aim.pitchError;

        // Track rotation alignment timing for anti-cheat compliance.
        if (aim.aligned) {
            rotationAlignedTick++;
        } else {
            rotationAlignedTick = 0;
        }

        CombatNavigationController.Result combatRoute = combatNavigation.compute(
                mc.theWorld, self, target, tacticalState, distance);
        if (combatRoute.blocked) {
            movement.release();
            combatState = CombatState.APPROACH;
            return publishState(state(self, target, "COMBAT_ROUTE_BLOCKED", false));
        }

        // State machine transitions
        boolean aligned = visible && yawError <= 16.0F && Math.abs(pitchError) <= 18.0F;
        boolean inRange = distance <= 3.20D;
        boolean canAttackNow = action == CombatDecisionEngine.Action.ATTACK
                && controlDecision.allowAttack && !suppressAttack;

        if (inRange && aligned && rotationAlignedTick >= ROTATION_MIN_DELAY) {
            if (attackTimer == 0 && canAttackNow) {
                combatState = CombatState.ATTACK_READY;
            } else if (attackTimer > 0) {
                combatState = CombatState.COOLDOWN;
            } else {
                combatState = CombatState.AIM;
            }
        } else if (aligned) {
            combatState = CombatState.AIM;
        } else {
            combatState = CombatState.APPROACH;
        }

        // Execute movement + attack based on state
        if (combatState == CombatState.ATTACK_READY) {
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

        // Build debug string
        double localFwd = toLocalForward(self, combatRoute.x, combatRoute.z);
        double localStr = toLocalStrafe(self, combatRoute.x, combatRoute.z);
        combatDebug = String.format("State=%s T=%s D=%.1f Yaw=%.0f LF=%.2f LS=%.2f Sp=%s Jp=%s Atk=%d MR=%s",
                combatState.name(),
                target != null ? target.getName() : "none",
                distance, yawError, localFwd, localStr,
                self.onGround ? "G" : "A",
                jumpTimer > 0 ? "CD" + jumpTimer : "-",
                attackTimer,
                combatRoute.reason);

        return publishState(new State(action.name(), distance, yawError, pitchError, visible,
                combatState == CombatState.COOLDOWN,
                aim.aligned, inRange,
                canAttackNow,
                attackTimer, controlDecision.reason));
    }

    private State publishState(State state) {
        lastState = state;
        return state;
    }

    public State getLastState() {
        return lastState;
    }

    public CombatState getCombatState() { return combatState; }
    public CombatTarget getCurrentTarget() { return currentTarget; }

    private void executeAttack(EntityPlayerSP self, EntityPlayer target, double distance,
                               boolean visible, float yawError, float pitchError,
                               CombatTacticalModel.State tacticalState,
                               CombatNavigationController.Result route) {
        float strafe = tacticalState.strafeSign;
        CombatTrackingModel.Snapshot tracked = tactical.getTrackingState();
        if (tracked != null && tracked.reversal) strafe = -strafe;
        if (tracked != null && tracked.suddenStop) strafe *= 0.75F;
        boolean aligned = visible && yawError <= 16.0F && Math.abs(pitchError) <= 18.0F;
        boolean tooClose = distance < tacticalState.preferredDistance - 0.45D;
        boolean targetRetreating = tacticalState.targetRetreating;

        // Convert world-space route to local-space for forward/backward
        double localFwd = toLocalForward(self, route.x, route.z);
        double localStr = toLocalStrafe(self, route.x, route.z);

        // Distance-based movement: approach when far, hold when close
        boolean moveForward = distance > tacticalState.preferredDistance + 0.15D;
        if (tooClose) moveForward = false;
        moveForward = moveForward && localFwd > -0.50D;
        movement.forward(moveForward);

        boolean moveBackward = tooClose && localFwd < -0.15D;
        movement.backward(moveBackward);

        double aggression = megastreakProfile == null ? 0.55D : megastreakProfile.targetAggression;
        float strafeAmount = tooClose ? 0.65F : (targetRetreating ? 0.58F : (float)(0.35D + aggression * 0.20D));
        float routeStrafe = (float)(localStr * 0.45D + strafe * strafeAmount);
        movement.strafe(routeStrafe);

        // Sprint when approaching from distance
        boolean wantSprint = distance > tacticalState.preferredDistance
                && self.onGround && localFwd > 0.3D;
        movement.sprint(wantSprint);

        // Combat jump: always check for obstacles
        if (jumpTimer == 0 && self.onGround && shouldCombatJump(self, target, distance, tacticalState)) {
            movement.jump();
            jumpTimer = 8;
        }

        if (attackTimer == 0 && rotationAlignedTick >= ROTATION_MIN_DELAY) {
            // Lock rotation
            float savedYaw = self.rotationYaw;
            float savedPitch = self.rotationPitch;
            self.rotationYaw = savedYaw;
            self.rotationPitch = savedPitch;

            // Send C06 (position+rotation)
            sendRotationPacket(self);

            // Swing BEFORE attack (Vulcan Type 7)
            mc.thePlayer.swingItem();
            // Attack packet
            mc.playerController.attackEntity(mc.thePlayer, target);

            attackTimer = targetRetreating ? 5 : 6;
            rotationAlignedTick = 0;
            combatState = CombatState.COOLDOWN;
        } else {
            movement.attack(false);
        }
    }

    private void executeApproach(EntityPlayerSP self, EntityPlayer target, double distance,
                                 boolean visible, float yawError,
                                 CombatTacticalModel.State tacticalState,
                                 CombatNavigationController.Result route) {
        double localFwd = toLocalForward(self, route.x, route.z);
        double localStr = toLocalStrafe(self, route.x, route.z);

        float preferred = tacticalState.preferredDistance;
        boolean tooClose = distance < preferred - 0.55D;
        boolean tooFar = distance > preferred + 0.55D;

        if (visible && distance < 5.0D) {
            float strafe = tacticalState.strafeSign;
            // FORWARD: target ahead, need to close distance
            boolean wantForward = tooFar && localFwd > -0.50D;
            // BACKWARD: target behind (overshot) and too close
            boolean wantBackward = tooClose && localFwd < -0.15D;
            movement.forward(wantForward);
            movement.backward(wantBackward);
            movement.strafe((float)(localStr * 0.60D + strafe * (tooClose ? 0.45F : 0.30F)));
            // Sprint: approaching from distance with good alignment
            movement.sprint(self.onGround && tooFar && localFwd > 0.3D);
        } else {
            // Not visible or far away: move toward target direction
            boolean wantForward = localFwd > -0.50D;
            boolean wantBackward = localFwd < -0.50D && distance < preferred;
            movement.forward(wantForward);
            movement.backward(wantBackward);
            movement.strafe((float)(localStr * 0.55D));
            movement.sprint(self.onGround && localFwd > 0.3D);
        }
        movement.attack(false);
    }

    private boolean shouldCombatJump(EntityPlayerSP self, EntityPlayer target, double distance,
                                     CombatTacticalModel.State tacticalState) {
        // Standard combat jump for maintaining melee spacing
        boolean standardJump = distance > 2.0D && distance < 4.0D
                && tacticalState.verticalDistance < 1.15D
                && Math.abs(tacticalState.closingRate) < 2.8D;
        if (standardJump) return true;

        // Obstacle jump: block in the way toward target
        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        double hLen = Math.sqrt(dx * dx + dz * dz);
        if (hLen > 0.001D) {
            // Check 1-2 blocks ahead in the target direction
            for (double probe = 0.8D; probe <= 2.0D; probe += 0.6D) {
                int bx = (int) Math.round(self.posX + (dx / hLen) * probe);
                int bz = (int) Math.round(self.posZ + (dz / hLen) * probe);
                net.minecraft.util.BlockPos probeFeet = new net.minecraft.util.BlockPos(bx, (int) self.posY, bz);
                net.minecraft.util.BlockPos probeHead = probeFeet.up();
                boolean blocked = !com.atlasdead.wanderbot.pathfinding.PathFinder.canOccupy(self.worldObj, probeFeet)
                        || !com.atlasdead.wanderbot.pathfinding.PathFinder.canOccupy(self.worldObj, probeHead);
                if (blocked) return true;
            }
        }

        // Height difference jump: target is 1 block up
        if (distance < 3.5D && self.onGround) {
            double targetY = target.posY;
            if (targetY > self.posY + 0.5D && targetY < self.posY + 1.5D) {
                return true;
            }
        }

        return false;
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
