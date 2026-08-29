package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import com.atlasdead.wanderbot.rotation.RotationController;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;

import com.atlasdead.wanderbot.humanization.Humanizer;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level combat phase controller. Manages the 5-phase combat state machine:
 *
 *   SEARCH → ENGAGE → RETREAT → REAR_CHECK → BOW_DECISION
 *                        ↑          │            │
 *                        └──────────┘            │
 *                        ↑                      │
 *                        └──────────────────────┘
 *
 * Priority: RETREAT > REAR_CHECK/BOW_DECISION > ENGAGE > SEARCH
 *
 * This controller:
 *  - Updates CombatContext each tick
 *  - Delegates to existing subsystems (CombatDecisionEngine, CombatExecutionController)
 *  - Manages chaser detection (ChaserDetector)
 *  - Manages escape route evaluation (EscapeRouteEvaluator)
 *  - Handles auto-attack via existing CombatExecutionController
 */
public final class CombatPhaseController {

    private final Minecraft mc;
    private final CombatContext context;
    private final ChaserDetector chaserDetector;
    private final EscapeRouteEvaluator escapeEvaluator;
    private final BowDecisionModel bowModel;
    private final CombatDecisionEngine decisionEngine;
    private final CombatExecutionController executor;
    private final RotationController rotation;
    private final MovementController movement;
    private final PathFinder pathFinder;
    private final TerrainAnalyzer terrain;

    private Path currentRetreatPath;
    private int retreatPathIndex;
    private int retreatCooldown;
    private int rearCheckCooldown;
    private int bowDecisionCooldown;
    private int retreatStabilityTicks;
    private int lastDamageTick;
    private int escapeEvalCooldown;
    private int absoluteTicks;

    public CombatPhaseController(Minecraft mc, MovementController movement, RotationController rotation,
                                  PitZoneManager zones, TargetTracker targets, StreakManager streak) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
        this.context = new CombatContext();
        this.chaserDetector = new ChaserDetector();
        this.escapeEvaluator = new EscapeRouteEvaluator(mc);
        this.bowModel = new BowDecisionModel();
        this.pathFinder = new PathFinder();
        this.terrain = new TerrainAnalyzer();
        this.decisionEngine = new CombatDecisionEngine(mc, zones, targets, streak);
        this.executor = new CombatExecutionController(mc, movement, rotation);
    }

    public void reset() {
        context.reset();
        chaserDetector.reset();
        currentRetreatPath = null;
        retreatPathIndex = 0;
        retreatCooldown = 0;
        rearCheckCooldown = 0;
        bowDecisionCooldown = 0;
        retreatStabilityTicks = 0;
        lastDamageTick = 0;
        escapeEvalCooldown = 0;
        absoluteTicks = 0;
    }

    public CombatContext getContext() { return context; }
    public CombatTelemetry getTelemetry() { return executor.getTelemetry(); }

    /**
     * Main tick entry point. Called instead of the old COMBAT branch in BotController.
     *
     * @return null if bot should continue with navigation, or a Directive indicating combat handled the tick.
     */
    public TickResult tick(EntityPlayerSP self, EntityPlayer target, boolean recoveryActive) {
        if (self == null || mc.theWorld == null) {
            return TickResult.skip();
        }

        updateContext(self, target);
        chaserDetector.detect(self);

        // Update retreat cooldown
        if (retreatCooldown > 0) retreatCooldown--;
        if (rearCheckCooldown > 0) rearCheckCooldown--;
        if (bowDecisionCooldown > 0) bowDecisionCooldown--;

        // Time since last damage (use absolute tick counter, not phase-relative)
        absoluteTicks++;
        if (self.hurtTime > 0) lastDamageTick = absoluteTicks;
        context.timeSinceLastDamage = absoluteTicks - lastDamageTick;
        if (escapeEvalCooldown > 0) escapeEvalCooldown--;

        // Phase dispatch with priority
        CombatContext.Phase phase = context.currentPhase;
        TickResult result;

        switch (phase) {
            case RETREAT:
                result = tickRetreat(self, target);
                break;
            case REAR_CHECK:
                result = tickRearCheck(self, target);
                break;
            case BOW_DECISION:
                result = tickBowDecision(self, target);
                break;
            case ENGAGE:
                result = tickEngage(self, target);
                break;
            case SEARCH:
            default:
                result = tickSearch(self, target);
                break;
        }

        context.previousPhase = phase;
        context.phaseTicks++;
        return result;
    }

    // =====================================================================
    // SEARCH Phase
    // =====================================================================

    private TickResult tickSearch(EntityPlayerSP self, EntityPlayer target) {
        // Target acquisition
        if (target != null && isTargetValid(self, target)) {
            double dist = self.getDistanceToEntity(target);
            if (dist <= WanderBotSettings.targetScanRange) {
                context.combatTarget = target;
                context.targetDistance = dist;
                context.transitionTo(CombatContext.Phase.ENGAGE);
                context.lastDecision = "ENGAGE";
                context.lastDecisionReason = "target-acquired@" + String.format("%.1f", dist);
                return TickResult.handled();
            }
        }

        // No target: return skip so BotController handles navigation/patrol
        context.lastDecision = "SEARCH";
        context.lastDecisionReason = "no-target";
        return TickResult.skip();
    }

    // =====================================================================
    // ENGAGE Phase
    // =====================================================================

    private TickResult tickEngage(EntityPlayerSP self, EntityPlayer target) {
        // Validate target
        if (target == null || !isTargetValid(self, target)) {
            context.combatTarget = null;
            context.transitionTo(CombatContext.Phase.SEARCH);
            context.lastDecision = "SEARCH";
            context.lastDecisionReason = "target-lost";
            return TickResult.cleared();
        }

        double dist = self.getDistanceToEntity(target);
        context.targetDistance = dist;

        // Check retreat condition: low health
        if (context.healthRatio() <= WanderBotSettings.retreatHealth) {
            context.transitionTo(CombatContext.Phase.RETREAT);
            retreatStabilityTicks = 0;
            context.lastDecision = "RETREAT";
            context.lastDecisionReason = "low-health@" + String.format("%.1f", context.healthRatio());
            return TickResult.cleared();
        }

        // Check retreat condition: high threat
        if (context.threatScore > WanderBotSettings.threatThreshold) {
            context.transitionTo(CombatContext.Phase.RETREAT);
            retreatStabilityTicks = 0;
            context.lastDecision = "RETREAT";
            context.lastDecisionReason = "high-threat@" + String.format("%.1f", context.threatScore);
            return TickResult.cleared();
        }

        // Delegate to existing combat execution
        CombatDecisionEngine.Result combatResult = decisionEngine.evaluate(self, target);
        CombatDecisionEngine.Action action = combatResult.action;

        if (action == CombatDecisionEngine.Action.DISENGAGE) {
            context.transitionTo(CombatContext.Phase.RETREAT);
            retreatStabilityTicks = 0;
            context.lastDecision = "RETREAT";
            context.lastDecisionReason = "disengage@" + combatResult.reason;
            return TickResult.cleared();
        }

        if (action == CombatDecisionEngine.Action.RETARGET) {
            // Try to find a better target, else stay
            EntityPlayer newTarget = findBetterTarget(self, target);
            if (newTarget != null) {
                context.combatTarget = newTarget;
                context.targetDistance = self.getDistanceToEntity(newTarget);
                context.lastDecision = "RETARGET";
                context.lastDecisionReason = "better-target-found";
            } else {
                context.lastDecision = "ENGAGE";
                context.lastDecisionReason = "retarget-suppressed";
            }
            return TickResult.handled();
        }

        // Execute via existing CombatExecutionController (single attack path)
        // NOTE: sprint is set inside executor.tick() -> executeApproach/executeAttack()
        // NOTE: do NOT also call checkAndExecuteAttack() here — it sends
        // a second clickAttack() per tick, causing CPS that triggers WatchDog.
        executor.tick(self, target, action, System.currentTimeMillis());

        context.lastDecision = action.name();
        context.lastDecisionReason = combatResult.reason;
        context.canAttack = canAttack(self, target, dist);
        return TickResult.handled();
    }

    // =====================================================================
    // RETREAT Phase
    // =====================================================================

    private TickResult tickRetreat(EntityPlayerSP self, EntityPlayer target) {
        context.ticksSinceRetreat++;

        // Collect threats
        List<EntityPlayer> threats = collectThreats(self, target);
        context.nearbyThreatCount = threats.size();
        if (!threats.isEmpty()) {
            double minDist = Double.MAX_VALUE;
            for (EntityPlayer t : threats) {
                double d = self.getDistanceToEntity(t);
                if (d < minDist) { minDist = d; context.nearestThreat = t; }
            }
        }

        // Detect chaser
        ChaserDetector.ChaserResult chaser = chaserDetector.detect(mc.thePlayer);
        if (chaser != null) {
            context.chaserTarget = chaser.player;
            context.chaserState = chaser.state;

            if (chaser.state == ChaserDetector.ChaseState.CHASE_LIKELY
                    || (chaser.state == ChaserDetector.ChaseState.CHASE_UNCERTAIN
                        && chaser.approachingTicks >= 3)) {
                if (rearCheckCooldown <= 0) {
                    context.transitionTo(CombatContext.Phase.REAR_CHECK);
                    rearCheckCooldown = 10;
                    context.lastDecision = "REAR_CHECK";
                    context.lastDecisionReason = "chaser-" + chaser.state;
                    return TickResult.cleared();
                }
            }
        } else {
            context.chaserTarget = null;
            context.chaserState = ChaserDetector.ChaseState.NOT_CHASING;
        }

        // Evaluate escape routes if we don't have a current path or need re-evaluation
        if (escapeEvalCooldown <= 0 && (currentRetreatPath == null || retreatPathIndex <= 0 || context.phaseTicks % 20 == 0)) {
            escapeEvalCooldown = 4; // Prevent eval loop when PathFinding fails
            List<EscapeCandidate> candidates = escapeEvaluator.evaluate(self, threats);
            if (!candidates.isEmpty()) {
                EscapeCandidate best = candidates.get(0);
                context.bestEscapeCandidate = best;
                context.escapeScore = best.escapeScore;

                // Pathfind to best escape position
                BlockPos selfPos = new BlockPos(self.posX, self.posY, self.posZ);
                Path retreatPath = pathFinder.findPath(mc.theWorld, selfPos, best.position, 30, 5000);
                if (retreatPath != null && !retreatPath.isFinished()) {
                    currentRetreatPath = retreatPath;
                    retreatPathIndex = 0;
                } else {
                    escapeEvalCooldown = 10; // Longer cooldown on failure
                }
            }
        }

        // Move along retreat path
        if (currentRetreatPath != null && !currentRetreatPath.isFinished()) {
            PathNode next = currentRetreatPath.current();
            if (next != null) {
                double dx = next.x + 0.5D - self.posX;
                double dz = next.z + 0.5D - self.posZ;
                double len = Math.sqrt(dx * dx + dz * dz);

                if (len < 0.78D) {
                    currentRetreatPath.advance();
                    retreatPathIndex++;
                } else {
                    double nx = dx / len;
                    double nz = dz / len;
                    movement.forward(len > 1.5D);
                    movement.sprint(false);
                    float yaw = (float) Math.toDegrees(Math.atan2(-nx, nz));
                    rotation.tick(self, self.posX + nx * 3D, self.posY, self.posZ + nz * 3D, 0F);
                }
            }
        } else {
            movement.forward(false);
            movement.sprint(false);
        }

        // Recovery check
        if (context.healthRatio() > WanderBotSettings.retreatHealth + 0.15D
                && context.timeSinceLastDamage > WanderBotSettings.recoverDelay
                && context.nearbyThreatCount == 0) {
            retreatStabilityTicks++;
            if (retreatStabilityTicks >= WanderBotSettings.recoverDelay) {
                currentRetreatPath = null;
                retreatPathIndex = 0;
                context.ticksSinceRetreat = 0;
                if (target != null && isTargetValid(self, target)) {
                    context.combatTarget = target;
                    context.transitionTo(CombatContext.Phase.ENGAGE);
                    context.lastDecision = "ENGAGE";
                    context.lastDecisionReason = "recovered-to-engage";
                } else {
                    context.combatTarget = null;
                    context.transitionTo(CombatContext.Phase.SEARCH);
                    context.lastDecision = "SEARCH";
                    context.lastDecisionReason = "recovered-no-target";
                }
                return TickResult.cleared();
            }
        } else {
            retreatStabilityTicks = 0;
        }

        context.lastDecision = "RETREAT";
        context.lastDecisionReason = "escaping@" + (context.bestEscapeCandidate != null
                ? String.format("%.0f", context.bestEscapeCandidate.escapeScore) : "no-candidate");
        return TickResult.handled();
    }

    // =====================================================================
    // REAR_CHECK Phase
    // =====================================================================

    private TickResult tickRearCheck(EntityPlayerSP self, EntityPlayer target) {
        ChaserDetector.ChaserResult chaser = chaserDetector.detect(mc.thePlayer);

        if (chaser == null || chaser.state == ChaserDetector.ChaseState.NOT_CHASING) {
            // Chaser gone or not chasing: return to RETREAT
            context.chaserTarget = null;
            context.chaserState = ChaserDetector.ChaseState.NOT_CHASING;
            context.transitionTo(CombatContext.Phase.RETREAT);
            context.lastDecision = "RETREAT";
            context.lastDecisionReason = "chaser-gone";
            return TickResult.cleared();
        }

        context.chaserTarget = chaser.player;
        context.chaserState = chaser.state;

        double chaserDist = self.getDistanceToEntity(chaser.player);
        boolean chaserLOS = self.canEntityBeSeen(chaser.player);
        float healthRatio = context.healthRatio();

        // Evaluate bow conditions
        BowDecisionModel.Result bowResult = bowModel.evaluate(self, chaser.player, context);

        if (bowResult.decision == BowDecisionModel.Decision.BOW) {
            if (bowDecisionCooldown <= 0) {
                context.transitionTo(CombatContext.Phase.BOW_DECISION);
                bowDecisionCooldown = 5;
                context.lastDecision = "BOW_DECISION";
                context.lastDecisionReason = bowResult.reason;
                return TickResult.cleared();
            }
        }

        // No bow opportunity: continue retreating but keep monitoring
        // Maintain movement during REAR_CHECK
        if (currentRetreatPath != null && !currentRetreatPath.isFinished()) {
            PathNode next = currentRetreatPath.current();
            if (next != null) {
                double dx = next.x + 0.5D - self.posX;
                double dz = next.z + 0.5D - self.posZ;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len > 0.5D) {
                    movement.forward(true);
                    float yaw = (float) Math.toDegrees(Math.atan2(-dx / len, dz / len));
                    rotation.tick(self, self.posX + dx / len * 3D, self.posY, self.posZ + dz / len * 3D, 0F);
                }
            }
        }

        // Return to RETREAT after brief check
        if (rearCheckCooldown <= 0) {
            context.transitionTo(CombatContext.Phase.RETREAT);
            rearCheckCooldown = 5;
            context.lastDecision = "RETREAT";
            context.lastDecisionReason = "rear-check-complete";
            return TickResult.cleared();
        }

        context.lastDecision = "REAR_CHECK";
        context.lastDecisionReason = "chaser-" + chaser.state + " dist=" + String.format("%.1f", chaserDist);
        return TickResult.handled();
    }

    // =====================================================================
    // BOW_DECISION Phase
    // =====================================================================

    private TickResult tickBowDecision(EntityPlayerSP self, EntityPlayer target) {
        // Decision-only layer, no actual bow input
        context.lastDecision = "BOW_DECISION";
        context.lastDecisionReason = "evaluating";

        // Immediately return to RETREAT
        context.transitionTo(CombatContext.Phase.RETREAT);
        context.lastDecision = "RETREAT";
        context.lastDecisionReason = "bow-decision-complete";
        return TickResult.cleared();
    }

    // =====================================================================
    // Auto-Attack (Legitimate 1.8.9 packets)
    // =====================================================================

    /**
     * Check attack conditions and execute via the standard client-side
     * click pipeline (same as a real player pressing left-click).
     * This sends the vanilla attack packet, not custom packets.
     */
    private void checkAndExecuteAttack(EntityPlayerSP self, EntityPlayer target, double distance) {
        if (context.attackCooldown > 0) {
            context.attackCooldown--;
            context.canAttack = false;
            return;
        }

        if (!canAttack(self, target, distance)) {
            context.canAttack = false;
            return;
        }

        // Humanization: occasional hesitation (skip attack)
        if (Humanizer.shouldHesitate()) {
            context.attackCooldown = Humanizer.range(1, 2);
            context.canAttack = false;
            return;
        }

        // Humanization: occasional miss (skip valid attack)
        if (Humanizer.shouldMiss()) {
            context.attackCooldown = Humanizer.attackDelayTicks();
            context.canAttack = false;
            return;
        }

        // All conditions met: execute attack via standard click
        // This triggers the same client-side pipeline as a real player
        float yawError = angleToTarget(self, target);
        boolean visible = self.canEntityBeSeen(target);

        if (distance <= WanderBotSettings.combatRange && visible && yawError <= 35F) {
            // Humanization: apply slight aim error before attacking
            float aimError = Humanizer.aimErrorDegrees();
            self.rotationYaw += aimError;
            self.rotationYawHead = self.rotationYaw;

            // Use existing movement controller for legitimate attack
            movement.attack(false);
            movement.clickAttack(target);

            // Humanization: variable attack cooldown (5-8 ticks)
            context.attackCooldown = Humanizer.attackDelayTicks();
            context.canAttack = false;
        }
    }

    private boolean canAttack(EntityPlayerSP self, EntityPlayer target, double distance) {
        if (target == null || target.isDead || target.getHealth() <= 0F) return false;
        if (distance > WanderBotSettings.combatRange) return false;
        float yawError = angleToTarget(self, target);
        // Humanization: variable yaw tolerance (25-40 degrees)
        float tolerance = Humanizer.attackYawTolerance();
        if (yawError > tolerance) return false;
        if (!self.canEntityBeSeen(target)) return false;
        return self.onGround || distance < 2.5D;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private void updateContext(EntityPlayerSP self, EntityPlayer target) {
        context.selfHealth = self.getHealth();
        context.maxHealth = self.getMaxHealth();
        context.selfX = self.posX;
        context.selfY = self.posY;
        context.selfZ = self.posZ;

        if (target != null && isTargetValid(self, target)) {
            context.combatTarget = target;
            context.targetDistance = self.getDistanceToEntity(target);
            context.lineOfSight = self.canEntityBeSeen(target);
        }

        // Compute threat score from nearby players
        List<EntityPlayer> threats = collectThreats(self, target);
        context.nearbyThreatCount = threats.size();
        context.threatScore = computeThreatScore(self, threats);
        if (!threats.isEmpty()) {
            double minD = Double.MAX_VALUE;
            for (EntityPlayer t : threats) {
                double d = self.getDistanceToEntity(t);
                if (d < minD) { minD = d; context.nearestThreat = t; }
            }
        } else {
            context.nearestThreat = null;
        }
    }

    private boolean isTargetValid(EntityPlayerSP self, EntityPlayer target) {
        if (target == null || target.isDead || target.getHealth() <= 0F) return false;
        double dist = self.getDistanceToEntity(target);
        return dist <= WanderBotSettings.targetScanRange + 5D;
    }

    private List<EntityPlayer> collectThreats(EntityPlayerSP self, EntityPlayer exclude) {
        List<EntityPlayer> threats = new ArrayList<EntityPlayer>();
        double radius = WanderBotSettings.chaserMaxDistance;
        AxisAlignedBB box = self.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> players = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        for (EntityPlayer p : players) {
            if (p == null || p == self || p == exclude) continue;
            if (p.isDead || p.getHealth() <= 0F || p.isInvisible()) continue;
            if (p.capabilities != null && p.capabilities.isCreativeMode) continue;
            threats.add(p);
        }
        return threats;
    }

    private double computeThreatScore(EntityPlayerSP self, List<EntityPlayer> threats) {
        if (threats.isEmpty()) return 0D;
        double score = 0D;
        for (EntityPlayer t : threats) {
            double dist = self.getDistanceToEntity(t);
            double healthRatio = Math.min(1D, t.getHealth() / Math.max(1F, t.getMaxHealth()));
            boolean los = self.canEntityBeSeen(t);
            score += Math.max(0, (15D - dist)) * 2D;
            score += healthRatio * 10D;
            if (los) score += 8D;
        }
        return score;
    }

    private EntityPlayer findBetterTarget(EntityPlayerSP self, EntityPlayer current) {
        double currentScore = targetScore(self, current);
        double bestScore = currentScore;
        EntityPlayer best = null;

        double radius = WanderBotSettings.targetScanRange;
        AxisAlignedBB box = self.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> players = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        for (EntityPlayer p : players) {
            if (p == null || p == self || p == current) continue;
            if (p.isDead || p.getHealth() <= 0F) continue;
            if (p.capabilities != null && p.capabilities.isCreativeMode) continue;
            double s = targetScore(self, p);
            if (s > bestScore + 10D) { bestScore = s; best = p; }
        }
        return best;
    }

    private double targetScore(EntityPlayerSP self, EntityPlayer target) {
        if (target == null) return -1D;
        double dist = self.getDistanceToEntity(target);
        double health = Math.min(1D, target.getHealth() / Math.max(1F, target.getMaxHealth()));
        boolean los = self.canEntityBeSeen(target);
        return (1D - dist / WanderBotSettings.targetScanRange) * 40D
                + (1D - health) * 25D
                + (los ? 20D : 0D);
    }

    private float angleToTarget(EntityPlayerSP self, EntityPlayer target) {
        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        float desired = (float)(Math.atan2(dz, dx) * 180D / Math.PI) - 90F;
        float delta = desired - self.rotationYaw;
        while (delta > 180F) delta -= 360F;
        while (delta < -180F) delta += 360F;
        return Math.abs(delta);
    }

    /**
     * Result of a single tick's phase processing.
     */
    public static final class TickResult {
        public final boolean handled;
        public final boolean clearedPath;

        private TickResult(boolean handled, boolean clearedPath) {
            this.handled = handled;
            this.clearedPath = clearedPath;
        }

        static TickResult handled() { return new TickResult(true, false); }
        static TickResult cleared() { return new TickResult(true, true); }
        static TickResult skip() { return new TickResult(false, false); }
    }
}
