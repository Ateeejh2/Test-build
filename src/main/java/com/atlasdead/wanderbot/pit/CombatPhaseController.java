package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Coordinates the SEARCH/ENGAGE/RETREAT/REAR_CHECK/BOW_DECISION combat phases. */
public final class CombatPhaseController {
    private static final int REAR_CHECK_TRIGGER_COOLDOWN = 10;
    private static final int REAR_CHECK_RETURN_COOLDOWN = 5;
    private static final int BOW_DECISION_COOLDOWN = 5;
    private static final int ESCAPE_EVAL_COOLDOWN = 4;
    private static final int ESCAPE_FAILURE_COOLDOWN = 10;
    private static final int ESCAPE_REEVALUATE_TICKS = 20;
    private static final double RETREAT_NODE_REACHED_DISTANCE = 0.78D;
    private static final double REAR_CHECK_MOVE_DISTANCE = 0.5D;
    private static final double RETREAT_LOOK_DISTANCE = 3.0D;

    private final Minecraft mc;
    private final CombatContext context = new CombatContext();
    private final ChaserDetector chaserDetector = new ChaserDetector();
    private final EscapeRouteEvaluator escapeEvaluator;
    private final BowDecisionModel bowModel = new BowDecisionModel();
    private final CombatDecisionEngine decisionEngine;
    private final CombatExecutionController executor;
    private final RotationController rotation;
    private final MovementController movement;
    private final PathFinder pathFinder = new PathFinder();

    private Path currentRetreatPath;
    private int retreatPathIndex;
    private int rearCheckCooldown;
    private int bowDecisionCooldown;
    private int retreatStabilityTicks;
    private int lastDamageTick;
    private int escapeEvalCooldown;
    private int absoluteTicks;
    private boolean killAuraActive;

    public CombatPhaseController(Minecraft mc, MovementController movement, RotationController rotation,
                                 PitZoneManager zones, TargetTracker targets, StreakManager streak,
                                 CombatExecutionController executor) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
        this.escapeEvaluator = new EscapeRouteEvaluator(mc);
        this.decisionEngine = new CombatDecisionEngine(mc, zones, targets, streak);
        this.executor = executor;
    }

    public void reset() {
        context.reset();
        chaserDetector.reset();
        currentRetreatPath = null;
        retreatPathIndex = 0;
        rearCheckCooldown = 0;
        bowDecisionCooldown = 0;
        retreatStabilityTicks = 0;
        lastDamageTick = 0;
        escapeEvalCooldown = 0;
        absoluteTicks = 0;
    }

    public void setKillAuraActive(boolean active) {
        killAuraActive = active;
    }

    public boolean isKillAuraActive() {
        return killAuraActive;
    }

    public CombatContext getContext() {
        return context;
    }

    public CombatTelemetry getTelemetry() {
        return executor.getTelemetry();
    }

    /**
     * Main phase tick. The recoveryActive argument is retained for API compatibility;
     * recovery is represented by the current CombatContext phase.
     */
    public TickResult tick(EntityPlayerSP self, EntityPlayer target, boolean recoveryActive) {
        if (self == null || mc.theWorld == null) return TickResult.skip();

        updateContext(self, target);
        chaserDetector.detect(self);
        tickCooldowns();
        updateDamageClock(self);

        CombatContext.Phase phase = context.currentPhase;
        TickResult result = dispatchPhase(phase, self, target);
        context.previousPhase = phase;
        context.phaseTicks++;
        return result;
    }

    private void tickCooldowns() {
        if (rearCheckCooldown > 0) rearCheckCooldown--;
        if (bowDecisionCooldown > 0) bowDecisionCooldown--;
        if (escapeEvalCooldown > 0) escapeEvalCooldown--;
    }

    private void updateDamageClock(EntityPlayerSP self) {
        absoluteTicks++;
        if (self.hurtTime > 0) lastDamageTick = absoluteTicks;
        context.timeSinceLastDamage = absoluteTicks - lastDamageTick;
    }

    private TickResult dispatchPhase(CombatContext.Phase phase, EntityPlayerSP self, EntityPlayer target) {
        switch (phase) {
            case RETREAT:
                return tickRetreat(self, target);
            case REAR_CHECK:
                return tickRearCheck(self, target);
            case BOW_DECISION:
                return tickBowDecision();
            case ENGAGE:
                return tickEngage(self, target);
            case SEARCH:
            default:
                return tickSearch(self, target);
        }
    }

    private TickResult tickSearch(EntityPlayerSP self, EntityPlayer target) {
        if (isTargetValid(self, target)) {
            double distance = self.getDistanceToEntity(target);
            if (distance <= WanderBotSettings.targetScanRange) {
                context.combatTarget = target;
                context.targetDistance = distance;
                return transition(CombatContext.Phase.ENGAGE, "ENGAGE",
                        "target-acquired@" + oneDecimal(distance), false);
            }
        }

        setDecision("SEARCH", "no-target");
        return TickResult.skip();
    }

    private TickResult tickEngage(EntityPlayerSP self, EntityPlayer target) {
        if (!isTargetValid(self, target)) {
            context.combatTarget = null;
            return transition(CombatContext.Phase.SEARCH, "SEARCH", "target-lost", true);
        }

        double distance = self.getDistanceToEntity(target);
        context.targetDistance = distance;

        if (context.healthRatio() <= WanderBotSettings.retreatHealth) {
            retreatStabilityTicks = 0;
            return transition(CombatContext.Phase.RETREAT, "RETREAT",
                    "low-health@" + oneDecimal(context.healthRatio()), true);
        }

        if (context.threatScore > WanderBotSettings.threatThreshold) {
            retreatStabilityTicks = 0;
            return transition(CombatContext.Phase.RETREAT, "RETREAT",
                    "high-threat@" + oneDecimal(context.threatScore), true);
        }

        CombatDecisionEngine.Result combatResult = decisionEngine.evaluate(self, target);
        CombatDecisionEngine.Action action = combatResult.action;

        if (action == CombatDecisionEngine.Action.DISENGAGE) {
            retreatStabilityTicks = 0;
            return transition(CombatContext.Phase.RETREAT, "RETREAT",
                    "disengage@" + combatResult.reason, true);
        }

        if (action == CombatDecisionEngine.Action.RETARGET) {
            EntityPlayer betterTarget = findBetterTarget(self, target);
            if (betterTarget != null) {
                context.combatTarget = betterTarget;
                context.targetDistance = self.getDistanceToEntity(betterTarget);
                setDecision("RETARGET", "better-target-found");
            } else {
                setDecision("ENGAGE", "retarget-suppressed");
            }
            return TickResult.handled();
        }

        executor.tick(self, target, action, System.currentTimeMillis());
        setDecision(action.name(), combatResult.reason);
        return TickResult.handled();
    }

    private TickResult tickRetreat(EntityPlayerSP self, EntityPlayer target) {
        context.ticksSinceRetreat++;

        List<EntityPlayer> threats = collectThreats(self, target);
        updateThreatContext(self, threats);

        TickResult chaserTransition = evaluateChaserTransition(self);
        if (chaserTransition != null) return chaserTransition;

        refreshRetreatPath(self, threats);
        followRetreatPath(self, RETREAT_NODE_REACHED_DISTANCE, false);

        if (isRecovered()) {
            retreatStabilityTicks++;
            if (retreatStabilityTicks >= WanderBotSettings.recoverDelay) {
                return finishRecovery(self, target);
            }
        } else {
            retreatStabilityTicks = 0;
        }

        String score = context.bestEscapeCandidate == null
                ? "no-candidate"
                : String.format("%.0f", context.bestEscapeCandidate.escapeScore);
        setDecision("RETREAT", "escaping@" + score);
        return TickResult.handled();
    }

    private TickResult evaluateChaserTransition(EntityPlayerSP self) {
        ChaserDetector.ChaserResult chaser = chaserDetector.detect(self);
        if (chaser == null) {
            clearChaserContext();
            return null;
        }

        context.chaserTarget = chaser.player;
        context.chaserState = chaser.state;
        boolean likely = chaser.state == ChaserDetector.ChaseState.CHASE_LIKELY;
        boolean persistentUncertain = chaser.state == ChaserDetector.ChaseState.CHASE_UNCERTAIN
                && chaser.approachingTicks >= 3;

        if ((likely || persistentUncertain) && rearCheckCooldown <= 0) {
            rearCheckCooldown = REAR_CHECK_TRIGGER_COOLDOWN;
            return transition(CombatContext.Phase.REAR_CHECK, "REAR_CHECK",
                    "chaser-" + chaser.state, true);
        }
        return null;
    }

    private void refreshRetreatPath(EntityPlayerSP self, List<EntityPlayer> threats) {
        boolean pathMissing = currentRetreatPath == null || currentRetreatPath.isFinished();
        boolean firstNode = retreatPathIndex <= 0;
        boolean periodicRefresh = context.phaseTicks % ESCAPE_REEVALUATE_TICKS == 0;
        if (escapeEvalCooldown > 0 || (!pathMissing && !firstNode && !periodicRefresh)) return;

        escapeEvalCooldown = ESCAPE_EVAL_COOLDOWN;
        List<EscapeCandidate> candidates = escapeEvaluator.evaluate(self, threats);
        if (candidates.isEmpty()) return;

        EscapeCandidate best = candidates.get(0);
        context.bestEscapeCandidate = best;
        context.escapeScore = best.escapeScore;

        BlockPos selfPos = new BlockPos(self.posX, self.posY, self.posZ);
        Path retreatPath = pathFinder.findPath(mc.theWorld, selfPos, best.position, 30, 5000);
        if (retreatPath != null && !retreatPath.isFinished()) {
            currentRetreatPath = retreatPath;
            retreatPathIndex = 0;
        } else {
            escapeEvalCooldown = ESCAPE_FAILURE_COOLDOWN;
        }
    }

    private void followRetreatPath(EntityPlayerSP self, double reachedDistance, boolean forceForward) {
        if (currentRetreatPath == null || currentRetreatPath.isFinished()) {
            movement.forward(false);
            movement.sprint(false);
            return;
        }

        PathNode next = currentRetreatPath.current();
        if (next == null) return;

        double dx = next.x + 0.5D - self.posX;
        double dz = next.z + 0.5D - self.posZ;
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < reachedDistance) {
            currentRetreatPath.advance();
            retreatPathIndex++;
            return;
        }

        movement.forward(forceForward || length > 1.5D);
        movement.sprint(false);
        if (!killAuraActive && length > 1.0E-6D) {
            rotation.tick(
                    self,
                    self.posX + dx / length * RETREAT_LOOK_DISTANCE,
                    self.posY,
                    self.posZ + dz / length * RETREAT_LOOK_DISTANCE,
                    0F);
        }
    }

    private boolean isRecovered() {
        return context.healthRatio() > WanderBotSettings.retreatHealth + 0.15D
                && context.timeSinceLastDamage > WanderBotSettings.recoverDelay
                && context.nearbyThreatCount == 0;
    }

    private TickResult finishRecovery(EntityPlayerSP self, EntityPlayer target) {
        currentRetreatPath = null;
        retreatPathIndex = 0;
        context.ticksSinceRetreat = 0;

        if (isTargetValid(self, target)) {
            context.combatTarget = target;
            return transition(CombatContext.Phase.ENGAGE, "ENGAGE", "recovered-to-engage", true);
        }

        context.combatTarget = null;
        return transition(CombatContext.Phase.SEARCH, "SEARCH", "recovered-no-target", true);
    }

    private TickResult tickRearCheck(EntityPlayerSP self, EntityPlayer target) {
        ChaserDetector.ChaserResult chaser = chaserDetector.detect(self);
        if (chaser == null || chaser.state == ChaserDetector.ChaseState.NOT_CHASING) {
            clearChaserContext();
            return transition(CombatContext.Phase.RETREAT, "RETREAT", "chaser-gone", true);
        }

        context.chaserTarget = chaser.player;
        context.chaserState = chaser.state;

        BowDecisionModel.Result bowResult = bowModel.evaluate(self, chaser.player, context);
        if (bowResult.decision == BowDecisionModel.Decision.BOW && bowDecisionCooldown <= 0) {
            bowDecisionCooldown = BOW_DECISION_COOLDOWN;
            return transition(CombatContext.Phase.BOW_DECISION, "BOW_DECISION", bowResult.reason, true);
        }

        followRetreatPath(self, REAR_CHECK_MOVE_DISTANCE, true);

        if (rearCheckCooldown <= 0) {
            rearCheckCooldown = REAR_CHECK_RETURN_COOLDOWN;
            return transition(CombatContext.Phase.RETREAT, "RETREAT", "rear-check-complete", true);
        }

        double chaserDistance = self.getDistanceToEntity(chaser.player);
        setDecision("REAR_CHECK", "chaser-" + chaser.state + " dist=" + oneDecimal(chaserDistance));
        return TickResult.handled();
    }

    private TickResult tickBowDecision() {
        return transition(CombatContext.Phase.RETREAT, "RETREAT", "bow-decision-complete", true);
    }

    private void updateContext(EntityPlayerSP self, EntityPlayer target) {
        context.selfHealth = self.getHealth();
        context.maxHealth = self.getMaxHealth();
        context.selfX = self.posX;
        context.selfY = self.posY;
        context.selfZ = self.posZ;

        if (isTargetValid(self, target)) {
            context.combatTarget = target;
            context.targetDistance = self.getDistanceToEntity(target);
            context.lineOfSight = self.canEntityBeSeen(target);
        } else {
            context.combatTarget = null;
            context.targetDistance = Double.POSITIVE_INFINITY;
            context.lineOfSight = false;
        }

        List<EntityPlayer> threats = collectThreats(self, target);
        updateThreatContext(self, threats);
        context.threatScore = computeThreatScore(self, threats);
    }

    private void updateThreatContext(EntityPlayerSP self, List<EntityPlayer> threats) {
        context.nearbyThreatCount = threats.size();
        context.nearestThreat = nearestThreat(self, threats);
    }

    private EntityPlayer nearestThreat(EntityPlayerSP self, List<EntityPlayer> threats) {
        EntityPlayer nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (EntityPlayer threat : threats) {
            double distance = self.getDistanceToEntity(threat);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = threat;
            }
        }
        return nearest;
    }

    private void clearChaserContext() {
        context.chaserTarget = null;
        context.chaserState = ChaserDetector.ChaseState.NOT_CHASING;
    }

    private boolean isTargetValid(EntityPlayerSP self, EntityPlayer target) {
        return target != null
                && !target.isDead
                && target.getHealth() > 0F
                && self.getDistanceToEntity(target) <= WanderBotSettings.targetScanRange + 5D;
    }

    private List<EntityPlayer> collectThreats(EntityPlayerSP self, EntityPlayer exclude) {
        List<EntityPlayer> threats = new ArrayList<EntityPlayer>();
        double radius = WanderBotSettings.chaserMaxDistance;
        AxisAlignedBB box = self.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> players = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);

        for (EntityPlayer player : players) {
            if (player == null || player == self || player == exclude) continue;
            if (player.isDead || player.getHealth() <= 0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            threats.add(player);
        }
        return threats;
    }

    private double computeThreatScore(EntityPlayerSP self, List<EntityPlayer> threats) {
        double score = 0D;
        for (EntityPlayer threat : threats) {
            double distance = self.getDistanceToEntity(threat);
            double healthRatio = Math.min(1D, threat.getHealth() / Math.max(1F, threat.getMaxHealth()));
            score += Math.max(0D, 15D - distance) * 2D;
            score += healthRatio * 10D;
            if (self.canEntityBeSeen(threat)) score += 8D;
        }
        return score;
    }

    private EntityPlayer findBetterTarget(EntityPlayerSP self, EntityPlayer current) {
        double bestScore = targetScore(self, current);
        EntityPlayer best = null;

        double radius = WanderBotSettings.targetScanRange;
        AxisAlignedBB box = self.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> players = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        for (EntityPlayer player : players) {
            if (player == null || player == self || player == current) continue;
            if (player.isDead || player.getHealth() <= 0F) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;

            double score = targetScore(self, player);
            if (score > bestScore + 10D) {
                bestScore = score;
                best = player;
            }
        }
        return best;
    }

    private double targetScore(EntityPlayerSP self, EntityPlayer target) {
        if (target == null) return -1D;
        double distance = self.getDistanceToEntity(target);
        double health = Math.min(1D, target.getHealth() / Math.max(1F, target.getMaxHealth()));
        boolean lineOfSight = self.canEntityBeSeen(target);
        return (1D - distance / WanderBotSettings.targetScanRange) * 40D
                + (1D - health) * 25D
                + (lineOfSight ? 20D : 0D);
    }

    private TickResult transition(CombatContext.Phase phase, String decision, String reason, boolean clearedPath) {
        context.transitionTo(phase);
        setDecision(decision, reason);
        return clearedPath ? TickResult.cleared() : TickResult.handled();
    }

    private void setDecision(String decision, String reason) {
        context.lastDecision = decision;
        context.lastDecisionReason = reason;
    }

    private static String oneDecimal(double value) {
        return String.format("%.1f", value);
    }

    /** Result of one phase tick. */
    public static final class TickResult {
        public final boolean handled;
        public final boolean clearedPath;

        private TickResult(boolean handled, boolean clearedPath) {
            this.handled = handled;
            this.clearedPath = clearedPath;
        }

        static TickResult handled() {
            return new TickResult(true, false);
        }

        static TickResult cleared() {
            return new TickResult(true, true);
        }

        static TickResult skip() {
            return new TickResult(false, false);
        }
    }
}
