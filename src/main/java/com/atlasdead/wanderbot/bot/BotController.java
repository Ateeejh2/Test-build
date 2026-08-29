package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.navigation.LookaheadController;
import com.atlasdead.wanderbot.navigation.NavigationMemory;
import com.atlasdead.wanderbot.navigation.PathValidator;
import com.atlasdead.wanderbot.navigation.RecoveryController;
import com.atlasdead.wanderbot.navigation.SearchPatrolController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import com.atlasdead.wanderbot.pit.CombatContext;
import com.atlasdead.wanderbot.pit.PitStartupSequence;
import com.atlasdead.wanderbot.pit.CombatDecisionEngine;
import com.atlasdead.wanderbot.pit.CombatPhaseController;
import com.atlasdead.wanderbot.pit.PitDecisionEngine;
import com.atlasdead.wanderbot.pit.PitMode;
import com.atlasdead.wanderbot.pit.PitRuntimeGuard;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.rotation.RotationController;
import com.atlasdead.wanderbot.stuck.StuckDetector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Full navigation state machine: planning, smoothing/validation, steering, avoidance and recovery. */
public class BotController {
    private final Minecraft mc;
    private final PathFinder pathFinder = new PathFinder();
    private final RotationController rotation = new RotationController();
    private final StuckDetector stuck = new StuckDetector();
    private final MovementController movement;
    private final TerrainAnalyzer terrain = new TerrainAnalyzer();
    private final PathValidator validator = new PathValidator();
    private final LookaheadController lookahead = new LookaheadController();
    private final LocalAvoidanceController avoidance = new LocalAvoidanceController();
    private final NavigationMemory memory = new NavigationMemory();
    private final RecoveryController recovery = new RecoveryController();
    private final SearchPatrolController searchPatrol = new SearchPatrolController();
    private final PitDecisionEngine pit;
    private final Random random = new Random();
    private final com.atlasdead.wanderbot.pit.CombatExecutionController combatExecutor;
    private final com.atlasdead.wanderbot.pit.PitExecutionOrchestrator executorRouter = new com.atlasdead.wanderbot.pit.PitExecutionOrchestrator();
    private final PitRuntimeGuard runtimeGuard = new PitRuntimeGuard();
    private CombatPhaseController combatPhaseController;
    private final PitStartupSequence startupSequence;

    private BotState state = BotState.OFF;
    private Path path;
    private BlockPos goal;
    private int planCooldown;
    private int validationCooldown;
    private int replanCooldown;
    private int jumpCooldown;
    private int attackCooldown;

    // Myau KillAura chat control
    private static final double KILLAURA_RANGE = 3.5D;
    private boolean killAuraActive = false;
    private int killAuraChatCooldown = 0;

    /* Validation cache: share validate() result between tick() and localHazard(). */
    private Path lastValidatedPath;
    private int lastValidatedIndex = -1;
    private boolean lastValidationResult;

    /* Perf counters – logged every 200 ticks. */
    private int perfTicks;
    private int validateCacheHits;
    private int validateCacheMisses;

    /* Avoidance invalidation tracking. */
    private Path lastAvoidancePath;
    private EntityPlayer lastAvoidanceTarget;

    public BotController(Minecraft mc) {
        this.mc = mc;
        this.movement = new MovementController(mc);
        this.pit = new PitDecisionEngine(mc);
        this.combatExecutor = new com.atlasdead.wanderbot.pit.CombatExecutionController(mc, movement, rotation);
        this.pit.attachCombatExecutor(combatExecutor);
        this.combatExecutor.bindMegastreakProfile(this.pit.getStreakControl().getActiveMegastreak());
        this.combatPhaseController = new CombatPhaseController(mc, movement, rotation,
                pit.getZones(), pit.getTargets(), pit.getStreak(), combatExecutor);
        this.startupSequence = new PitStartupSequence(mc, movement, pathFinder);
    }

    public void toggle() { if (state == BotState.OFF) start(); else stop(); }

    public void start() {
        if (mc.thePlayer == null || mc.theWorld == null) return;
        state = BotState.PLANNING;
        path = null;
        goal = null;
        planCooldown = 0;
        validationCooldown = 0;
        replanCooldown = 0;
        jumpCooldown = 0;
        attackCooldown = 0;
        pit.start();
        runtimeGuard.reset();
        combatExecutor.reset();
        combatPhaseController.reset();
        stuck.reset(mc.thePlayer);
        recovery.begin();
        searchPatrol.reset();
        startupSequence.reset();
    }

    public void stop() {
        state = BotState.OFF;
        path = null;
        goal = null;
        pit.stop();
        runtimeGuard.reset();
        combatExecutor.reset();
        movement.release();
        ensureKillAuraOff();
    }

    public void tick() {
        EntityPlayerSP player = mc.thePlayer;
        if (state == BotState.OFF) return;
        if (player == null || mc.theWorld == null) { stop(); return; }

        // Startup phase: verify Pit, enter it if needed, then execute route #1 to mid/slime.
        if (!startupSequence.isComplete()) {
            startupSequence.tick(player);
            path = startupSequence.getPath();
            goal = startupSequence.getGoal();
            state = BotState.WALKING;
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
            path = null;
            goal = null;
            targetsClear();
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            ensureKillAuraOff();
            state = BotState.RECOVERING;
            pit.handleRuntimeDeath();
            return;
        }
        if (guardStatus == PitRuntimeGuard.Status.WORLD_CHANGED) {
            path = null;
            goal = null;
            movement.release();
            movement.releaseJump();
            movement.attack(false);
            pit.resetForWorldChange();
            state = BotState.PLANNING;
            return;
        }

        if (planCooldown > 0) planCooldown--;
        if (validationCooldown > 0) validationCooldown--;
        if (replanCooldown > 0) replanCooldown--;
        if (jumpCooldown > 0) jumpCooldown--;
        if (attackCooldown > 0) attackCooldown--;
        movement.release();
        movement.releaseJump();
        movement.attack(false);

        /* Tick avoidance cache age. */
        avoidance.tick();

        /* Invalidate avoidance when path reference changes (new path from findBestPath, etc). */
        if (path != lastAvoidancePath) {
            avoidance.invalidate();
            lastAvoidancePath = path;
        }
        /* Invalidate avoidance when target changes. */
        EntityPlayer tgtNow = pit.getTargets().getTarget();
        if (tgtNow != lastAvoidanceTarget) {
            avoidance.invalidate();
            lastAvoidanceTarget = tgtNow;
        }

        /* Perf logging every 200 ticks. */
        perfTicks++;
        if (perfTicks >= 200) {
            System.out.println("[WB-Perf] validate cache hits=" + validateCacheHits
                    + " misses=" + validateCacheMisses + " / " + perfTicks + " ticks");
            perfTicks = 0;
            validateCacheHits = 0;
            validateCacheMisses = 0;
        }

        pit.tick();
        EntityPlayer targetForSearch = pit.getTargets().getTarget();
        /* Only tick searchPatrol when CombatPhaseController is not managing combat
           (i.e. in SEARCH phase or combat disabled). */
        CombatContext.Phase combatPhase = combatPhaseController.getContext().currentPhase;
        boolean combatActive = WanderBotSettings.combatEnabled
                && (combatPhase == CombatContext.Phase.ENGAGE
                || combatPhase == CombatContext.Phase.RETREAT
                || combatPhase == CombatContext.Phase.REAR_CHECK
                || combatPhase == CombatContext.Phase.BOW_DECISION);
        if (!combatActive) {
            searchPatrol.tick(player, targetForSearch == null);
        }
        PitMode pitMode = pit.getMode();
        com.atlasdead.wanderbot.pit.PitRulesEngine.State pitRules = pit.getRulesState();
        if (!WanderBotSettings.forcePitMode
                && (pitMode == PitMode.WAITING || pitMode == PitMode.WARMUP || pitMode == PitMode.UNSAFE || pitMode == PitMode.PAUSED
                || pitRules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.EVENT
                || pitRules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.WAITING
                || pitRules.phase == com.atlasdead.wanderbot.pit.PitRulesEngine.MatchPhase.WARMUP)) {
            path = null;
            goal = null;
            movement.release();
            return;
        }

        combatExecutor.bindMegastreakProfile(pit.getStreakControl().getActiveMegastreak());
        EntityPlayer target = pit.getTargets().getTarget();
        com.atlasdead.wanderbot.pit.PitMasterDecisionEngine.Decision masterDecision = pit.getMasterDecision().evaluate(player, target);
        if (pit.getZones().isSelfProtected(player)) {
            path = null;
            goal = null;
            movement.release();
            return;
        }
        if (target != null && pit.getZones().isPlayerProtected(target)) {
            pit.getTargets().clear();
            target = null;
        }

        // Myau KillAura chat control: toggle on/off based on target distance
        updateKillAura(player, target);

        com.atlasdead.wanderbot.pit.PitExecutionOrchestrator.Directive directive =
                executorRouter.resolve(player, masterDecision, target, state == BotState.RECOVERING);

        if (directive.clearPath) {
            path = null;
            goal = null;
        }
        if (directive.releaseMovement) movement.release();

        switch (directive.owner) {
            case WAIT:
                state = BotState.WALKING;
                return;
            case RECOVERY:
                state = BotState.RECOVERING;
                break;
            case COMBAT:
                if (directive.target != null && pit.getTargets().isViable(player, WanderBotSettings.targetScanRange)) {
                    CombatPhaseController.TickResult combatResult =
                            combatPhaseController.tick(player, directive.target, state == BotState.RECOVERING);
                    if (combatResult.handled) {
                        if (combatResult.clearedPath) {
                            path = null;
                            goal = null;
                        }
                        state = BotState.WALKING;
                        return;
                    }
                    // SEARCH phase: fall through to normal navigation/patrol
                }
                break;
            case NAVIGATION:
            case NONE:
            default:
                break;
        }

        if (directive.owner == com.atlasdead.wanderbot.pit.PitExecutionOrchestrator.Owner.RECOVERY) {
            // handled by the normal recovery branch below
        }

        if (!WanderBotSettings.navigationEnabled) {
            path = null;
            goal = null;
            movement.release();
            state = BotState.WALKING;
            return;
        }

        if (state == BotState.RECOVERING) {
            if (!recovery.tick(mc.theWorld, player, movement, player.motionX, player.motionZ)) {
                memory.recordFailure(new BlockPos(player.posX, player.posY, player.posZ));
                state = BotState.REPLANNING;
                path = null;
                planCooldown = 0;
            }
            return;
        }

        boolean needsReplan = path == null || path.isFinished() || searchPatrol.shouldReplan(target == null, goal);
        if (needsReplan) {
            if (replanCooldown > 0) {
                // Cooldown active – defer the replan but still clear stale path state
                // so the bot does not steer along an invalid route.
                movement.release();
                path = null;
                goal = null;
                state = BotState.REPLANNING;
            } else {
                movement.release();
                path = null;
                goal = null;
                searchPatrol.clearActiveGoal();
                state = BotState.PLANNING;
                findBestPath(player);
            }
            return;
        }

        advanceReached(player);
        if (path == null || path.isFinished()) return;

        // Skip path validation/hazard checks during combat — the combat layer
        // manages its own movement and does not use the navigation path.
        if (!combatActive && validationCooldown == 0) {
            validationCooldown = 4;
            if (!validator.validate(mc.theWorld, path, 9)) {
                lastValidatedPath = path;
                lastValidatedIndex = path.getIndex();
                lastValidationResult = false;
                validateCacheMisses++;
                requestReplan();
                return;
            }
            lastValidatedPath = path;
            lastValidatedIndex = path.getIndex();
            lastValidationResult = true;
            validateCacheMisses++;
        }

        if (!combatActive && replanCooldown == 0 && localHazard(player)) {
            requestReplan();
            return;
        }

        PathNode current = path.current();
        boolean narrow = current != null && terrain.narrow(mc.theWorld, new BlockPos(current.x, current.y, current.z));
        LookaheadController.Steering steering = lookahead.compute(player, path, narrow);

        double desiredX = steering.dirX;
        double desiredZ = steering.dirZ;
        if (desiredX == 0.0D && desiredZ == 0.0D) {
            desiredX = steering.x - player.posX;
            desiredZ = steering.z - player.posZ;
        }

        LocalAvoidanceController.Result avoid = avoidance.choose(
                mc.theWorld,
                new BlockPos(player.posX, player.posY, player.posZ),
                desiredX,
                desiredZ
        );

        float yawError = rotation.tick(player, player.posX + avoid.x * 3.0D, steering.y, player.posZ + avoid.z * 3.0D, 0.0F);

        boolean tightTurn = yawError > 78.0F;
        boolean moderateTurn = yawError > 34.0F;
        // Sprint decision based on forward-component alignment instead of raw yawError.
        // This avoids sprint being blocked by Humanizer overshoot/jitter/distraction
        // that temporarily inflates yawError even though the movement direction is correct.
        double fwdLen = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        double forwardComponent = 0.0D;
        if (fwdLen > 0.001D) {
            double ndx = desiredX / fwdLen;
            double ndz = desiredZ / fwdLen;
            double yawRad = Math.toRadians(player.rotationYaw);
            double playerFwdX = -Math.sin(yawRad);
            double playerFwdZ = Math.cos(yawRad);
            forwardComponent = ndx * playerFwdX + ndz * playerFwdZ;
        }
        boolean sprintAllowed = player.onGround && forwardComponent > 0.6D && !avoid.avoiding && steering.lookahead > 1.2D;

        if (tightTurn) {
            movement.forward(false);
            movement.sprint(false);
            movement.strafe(avoid.deviation > 0.2D ? (avoid.x < desiredX ? -0.45F : 0.45F) : 0.0F);
        } else {
            movement.forward(true);
            movement.strafe(strafeAmount(player, avoid.x, avoid.z));
            movement.sprint(sprintAllowed);
        }

        if (shouldJump(player, current, steering, yawError)) {
            state = BotState.JUMPING;
            movement.jump();
            jumpCooldown = 8;
        } else if (state == BotState.JUMPING && !player.onGround) {
            // Stay in jumping state until a landing occurs.
        } else {
            state = avoid.avoiding ? BotState.AVOIDING : (narrow ? BotState.NARROW : BotState.WALKING);
        }

        if (stuck.tick(player)) {
            state = BotState.STUCK;
            recovery.begin();
            memory.recordFailure(new BlockPos(player.posX, player.posY, player.posZ));
            path = null;
            state = BotState.RECOVERING;
        }
    }

    private void targetsClear() {
        pit.getTargets().clear();
    }

    public void onDisconnect() {
        if (state == BotState.OFF) return;
        path = null;
        goal = null;
        movement.release();
        movement.releaseJump();
        movement.attack(false);
        runtimeGuard.reset();
        pit.stop();
        state = BotState.OFF;
        ensureKillAuraOff();
    }

    public String getRuntimeGuardStatus() {
        return runtimeGuard.getStatus().name();
    }

    // ===================================================================
    // Myau KillAura chat control
    // ===================================================================

    /**
     * Toggle Myau KillAura on/off via chat based on target distance.
     * Sends `.t killaura on` when target <= 3.5 blocks,
     * `.t killaura off` when target > 3.5 blocks or target is null.
     * Uses a cooldown to avoid spamming the same command every tick.
     */
    private void updateKillAura(EntityPlayerSP player, EntityPlayer target) {
        if (killAuraChatCooldown > 0) {
            killAuraChatCooldown--;
            return;
        }

        if (target == null || target.isDead || target.getHealth() <= 0F) {
            // No target: ensure KillAura is off
            if (killAuraActive) {
                sendKillAuraCommand(false);
                killAuraActive = false;
                combatExecutor.setKillAuraActive(false);
                combatPhaseController.setKillAuraActive(false);
                killAuraChatCooldown = 10; // 0.5s cooldown
            }
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

    /** Send `.t killaura on` or `.t killaura off` via chat. */
    private void sendKillAuraCommand(boolean on) {
        if (mc.thePlayer == null) return;
        String cmd = on ? ".t killaura on" : ".t killaura off";
        mc.thePlayer.sendChatMessage(cmd);
    }

    /** Ensure KillAura is turned off when bot stops. */
    private void ensureKillAuraOff() {
        if (killAuraActive) {
            sendKillAuraCommand(false);
            killAuraActive = false;
            combatExecutor.setKillAuraActive(false);
            combatPhaseController.setKillAuraActive(false);
        }
    }

    /**
     * Synchronize the Combat rotation owner with an externally observed Myau
     * KillAura state. This is used when the client-side status message is
     * received, so the combat path rotation follows the actual KillAura state.
     */
    public void applyKillAuraStateFromMessage(boolean active) {
        killAuraActive = active;
        combatExecutor.setKillAuraActive(active);
        combatPhaseController.setKillAuraActive(active);
    }

    public com.atlasdead.wanderbot.pit.CombatExecutionController getCombatExecutor() { return combatExecutor; }
    public boolean isKillAuraActive() { return killAuraActive; }

    private boolean handleApproachTarget(EntityPlayerSP player, EntityPlayer target) {
        if (target == null || target.isDead || target.getHealth() <= 0.0F) return false;
        BlockPos targetPos = new BlockPos(target.posX, target.posY, target.posZ);
        boolean targetChanged = goal == null || Math.abs(goal.getX() - targetPos.getX()) > 2 || Math.abs(goal.getZ() - targetPos.getZ()) > 2;
        if (targetChanged || path == null || path.isFinished()) {
            Path targetPath = pathFinder.findHierarchicalPath(mc.theWorld, new BlockPos(player.posX, player.posY, player.posZ), targetPos, 45, 12000);
            if (targetPath != null && !targetPath.isFinished()) {
                path = targetPath;
                goal = targetPos;
                state = BotState.WALKING;
                validationCooldown = 0;
                replanCooldown = 5;
                stuck.reset(player);
            }
        }
        return path != null && !path.isFinished();
    }

    private float strafeAmount(EntityPlayerSP player, double desiredX, double desiredZ) {
        double len = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (len < 0.001D) return 0.0F;
        desiredX /= len;
        desiredZ /= len;
        double yaw = Math.toRadians(player.rotationYaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        double side = desiredX * rightX + desiredZ * rightZ;
        double forward = desiredX * forwardX + desiredZ * forwardZ;
        if (forward < -0.15D) return 0.0F;
        return (float)Math.max(-0.7D, Math.min(0.7D, side * 0.8D));
    }

    private boolean shouldJump(EntityPlayerSP player, PathNode current, LookaheadController.Steering steering, float yawError) {
        if (!player.onGround || jumpCooldown > 0 || yawError > 28.0F) return false;
        if (current != null && current.y > player.posY + 0.45D) return true;
        if (steering.dirX == 0.0D && steering.dirZ == 0.0D) return false;
        return terrain.predictedCliff(mc.theWorld, new BlockPos(player.posX, player.posY, player.posZ), steering.dirX, steering.dirZ, 1.8D)
                && current != null && current.y >= player.posY + 1.0D;
    }

    private boolean localHazard(EntityPlayerSP player) {
        BlockPos base = new BlockPos(player.posX, player.posY, player.posZ);
        if (terrain.predictedCliff(mc.theWorld, base, -Math.sin(Math.toRadians(player.rotationYaw)), Math.cos(Math.toRadians(player.rotationYaw)), 2.5D)) {
            return true;
        }
        com.atlasdead.wanderbot.pit.PitMapIntelligence map = pit.getMap();
        double lookX = -Math.sin(Math.toRadians(player.rotationYaw));
        double lookZ = Math.cos(Math.toRadians(player.rotationYaw));
        if (map != null && map.shouldAvoid(player, mc.theWorld, lookX, lookZ)) return true;
        if (path == null) return false;
        /* Reuse cached validation when path and index haven't changed. */
        if (path == lastValidatedPath && path.getIndex() == lastValidatedIndex) {
            validateCacheHits++;
            return !lastValidationResult;
        }
        boolean valid = validator.validate(mc.theWorld, path, 4);
        lastValidatedPath = path;
        lastValidatedIndex = path.getIndex();
        lastValidationResult = valid;
        validateCacheMisses++;
        return !valid;
    }

    private void advanceReached(EntityPlayerSP player) {
        while (path != null && !path.isFinished()) {
            PathNode n = path.current();
            double dx = n.x + 0.5D - player.posX;
            double dz = n.z + 0.5D - player.posZ;
            double radius = Math.abs(player.posY - n.y) < 0.5D ? 0.78D : 0.55D;
            if (dx * dx + dz * dz <= radius * radius && Math.abs(player.posY - n.y) < 1.35D) path.advance();
            else break;
        }
    }

    private void requestReplan() {
        if (replanCooldown > 0) return;
        movement.release();
        path = null;
        state = BotState.REPLANNING;
        replanCooldown = 6;
        planCooldown = 0;
    }

    private void findBestPath(EntityPlayerSP player) {
        if (planCooldown > 0) return;
        BlockPos start = new BlockPos(player.posX, player.posY, player.posZ);
        List<BlockPos> candidates = chooseGoalCandidates(start);
        if (candidates.isEmpty()) {
            // Fallback: try short-range random forward positions
            for (int i = 0; i < 12; i++) {
                int dx = random.nextInt(11) - 5;
                int dz = random.nextInt(11) - 5;
                if (dx == 0 && dz == 0) continue;
                BlockPos p = start.add(dx, 0, dz);
                if (PathFinder.canOccupy(mc.theWorld, p)) {
                    candidates.add(p);
                }
            }
            if (candidates.isEmpty()) {
                planCooldown = 4;
                return;
            }
        }

        List<PathChoice> choices = new ArrayList<PathChoice>();
        int tested = Math.min(6, candidates.size());
        for (int i = 0; i < tested; i++) {
            BlockPos candidate = candidates.get(i);
            Path candidatePath = pathFinder.findHierarchicalPath(mc.theWorld, start, candidate, 60, 15000);
            if (candidatePath == null || candidatePath.isFinished()) continue;
            double score = scorePath(candidatePath, candidate, start);
            choices.add(new PathChoice(candidatePath, candidate, score));
        }
        if (choices.isEmpty()) {
            planCooldown = 4;
            return;
        }

        choices.sort(new Comparator<PathChoice>() {
            @Override public int compare(PathChoice a, PathChoice b) {
                return Double.compare(a.score, b.score);
            }
        });
        PathChoice best = choices.get(0);
        path = best.path;
        goal = best.goal;
        if (pit.getTargets().getTarget() == null) searchPatrol.beginGoal(goal, player);
        state = BotState.WALKING;
        avoidance.invalidate();
        validationCooldown = 0;
        replanCooldown = 8;
        stuck.reset(player);
    }

    private double scorePath(Path candidate, BlockPos candidateGoal, BlockPos start) {
        double length = 0.0D;
        double turns = 0.0D;
        int jumps = 0;
        double danger = 0.0D;
        List<PathNode> nodes = candidate.getNodes();
        int lastDx = 0, lastDz = 0;
        for (int i = 1; i < nodes.size(); i++) {
            PathNode a = nodes.get(i - 1);
            PathNode b = nodes.get(i);
            int dx = Integer.signum(b.x - a.x);
            int dz = Integer.signum(b.z - a.z);
            double seg = Math.sqrt((b.x - a.x) * (double)(b.x - a.x) + (b.z - a.z) * (double)(b.z - a.z));
            length += seg;
            if (b.y != a.y) jumps++;
            if ((lastDx != 0 || lastDz != 0) && (dx != lastDx || dz != lastDz)) turns += 1.0D;
            lastDx = dx;
            lastDz = dz;
            danger += Math.max(0.0D, 0.75D - terrain.edgeRisk(mc.theWorld, new BlockPos(b.x, b.y, b.z)));
        }
        double memoryPenalty = memory.penalty(candidateGoal);
        double distanceToGoal = Math.sqrt((candidateGoal.getX() - start.getX()) * (double)(candidateGoal.getX() - start.getX()) +
                (candidateGoal.getZ() - start.getZ()) * (double)(candidateGoal.getZ() - start.getZ()));
        return length + turns * 1.45D + jumps * 1.25D + memoryPenalty - danger * 0.25D - distanceToGoal * 0.12D;
    }

    private List<BlockPos> chooseGoalCandidates(BlockPos start) {
        List<BlockPos> candidates = new ArrayList<BlockPos>();
        for (int i = 0; i < 80; i++) {
            int dx = random.nextInt(81) - 40;
            int dz = random.nextInt(81) - 40;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 12.0D || dist > 58.0D) continue;
            for (int dy = 4; dy >= -4; dy--) {
                BlockPos p = start.add(dx, dy, dz);
                if (PathFinder.canOccupy(mc.theWorld, p) && terrain.edgeRisk(mc.theWorld, p) < 0.8D
                        && searchPatrol.allowsGoal(p)) {
                    candidates.add(p);
                    break;
                }
            }
        }
        candidates.sort(new Comparator<BlockPos>() {
            @Override public int compare(BlockPos a, BlockPos b) {
                double sa = goalScore(start, a);
                double sb = goalScore(start, b);
                return Double.compare(sb, sa);
            }
        });
        if (candidates.size() > 12) return new ArrayList<BlockPos>(candidates.subList(0, 12));
        return candidates;
    }

    private double goalScore(BlockPos start, BlockPos goal) {
        double dx = goal.getX() - start.getX();
        double dz = goal.getZ() - start.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        return distance - Math.abs(goal.getY() - start.getY()) * 0.8D - memory.penalty(goal);
    }

    public Path getPath() { return path; }
    public BotState getState() { return state; }
    public boolean isEnabled() { return state != BotState.OFF; }
    public PitMode getPitMode() { return pit.getMode(); }
    public PitDecisionEngine getPit() { return pit; }
    public double getTargetScore() {
        return pit.getTargets().getTargetScore();
    }

    public com.atlasdead.wanderbot.pit.TargetTracker.ArmorProfile getTargetArmor() {
        return pit.getTargets().getTargetArmor();
    }

    public String getTargetName() {
        combatExecutor.bindMegastreakProfile(pit.getStreakControl().getActiveMegastreak());
        EntityPlayer target = pit.getTargets().getTarget();
        return target == null ? null : target.getName();
    }

    public int getLocalStreak() { return pit.getStreak().getEffectiveStreak(); }
    public com.atlasdead.wanderbot.pit.StreakManager.Tier getStreakTier() { return pit.getStreak().getTier(); }
    public int getPeakStreak() { return pit.getStreak().getPeakStreak(); }
    public CombatContext getCombatContext() { return combatPhaseController.getContext(); }
    private static final class PathChoice {
        final Path path;
        final BlockPos goal;
        final double score;
        PathChoice(Path path, BlockPos goal, double score) {
            this.path = path;
            this.goal = goal;
            this.score = score;
        }
    }

}
