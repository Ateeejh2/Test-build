package com.atlasdead.wanderbot.pathfinding;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded A* pathfinder specialized for chasing a moving combat target. */
public class CombatPathFinder {
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT_2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final double DESIRED_STOP_MARGIN = 0.25D;
    private static final double REPLAN_THRESHOLD = 5.5D;
    private static final int SEARCH_RADIUS = 64;
    private static final int SEARCH_HEIGHT = 12;
    private static final int GOAL_STAND_RADIUS = 5;
    private static final double COST_EPSILON = 0.000001D;
    private static final int FALLBACK_MAX_DISTANCE = 64;
    private static final int FALLBACK_NODE_BUDGET = 10000;
    private static final int FALLBACK_RETRY_TICKS = 12;

    private Path currentPath;
    private BlockPos currentGoal;
    private int ticksSinceReplan;
    private double predictedTargetX;
    private double predictedTargetZ;
    private final PathFinder fallbackPathFinder = new PathFinder();
    private int fallbackCooldown;

    public Path getPath(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null) return currentPath;

        predictTarget(target);
        BlockPos goal = computeGoal(self, target);
        if (!shouldReplan(goal)) {
            ticksSinceReplan++;
            return currentPath;
        }

        boolean initial = currentPath == null || currentPath.isFinished();
        int nodeBudget = adaptiveNodeBudget(self, goal, maxNodes, initial);
        Path candidate = findPath(world, self, goal, nodeBudget);
        if (fallbackCooldown > 0) fallbackCooldown--;

        // The fast combat A* intentionally has a tight budget. Complex Pit terrain
        // can exceed it even though the route is perfectly walkable. When that
        // happens, fall back to the same terrain-aware hierarchical planner used by
        // startup instead of leaving steering with a null path forever.
        if ((candidate == null || candidate.isFinished()) && fallbackCooldown <= 0) {
            candidate = fallbackPathFinder.findHierarchicalPath(
                    world,
                    new BlockPos(self.posX, self.posY, self.posZ),
                    goal,
                    FALLBACK_MAX_DISTANCE,
                    FALLBACK_NODE_BUDGET);
            fallbackCooldown = FALLBACK_RETRY_TICKS;
        }

        if (candidate != null && !candidate.isFinished()) {
            currentPath = candidate;
            currentGoal = goal;
            ticksSinceReplan = 0;
        } else {
            // A bounded replan may fail on a busy tick; keep the last usable route.
            ticksSinceReplan++;
        }
        return currentPath;
    }

    /** Lightweight reachability probe used by target validation. */
    public boolean canReachTarget(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null) return false;
        predictTarget(target);
        BlockPos goal = computeGoal(self, target);
        int budget = Math.min(3500, Math.max(1200, maxNodes));
        Path path = findPath(world, self, goal, budget);
        return path != null && !path.isFinished();
    }

    private int adaptiveNodeBudget(EntityPlayerSP self, BlockPos goal, int requested, boolean initial) {
        double dx = goal.getX() - self.posX;
        double dz = goal.getZ() - self.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int requestedSafe = Math.max(1200, requested);

        int budget;
        if (distance <= 10.0D) budget = initial ? 4200 : 2600;
        else if (distance <= 24.0D) budget = initial ? 5200 : 3400;
        else budget = initial ? 6500 : 4200;
        return Math.min(requestedSafe, budget);
    }

    private void predictTarget(EntityPlayer target) {
        double speed = Math.sqrt(target.motionX * target.motionX + target.motionZ * target.motionZ);
        double predictionTime = speed > 0.10D ? 5.0D : 2.5D;
        predictedTargetX = target.posX + target.motionX * predictionTime;
        predictedTargetZ = target.posZ + target.motionZ * predictionTime;
    }

    private BlockPos computeGoal(EntityPlayerSP self, EntityPlayer target) {
        double dx = predictedTargetX - self.posX;
        double dz = predictedTargetZ - self.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);

        double attackRange = configuredCombatRange();
        if (distance <= attackRange) {
            return new BlockPos(target.posX, target.posY, target.posZ);
        }

        double desiredStop = Math.max(1.5D, attackRange - DESIRED_STOP_MARGIN);
        double ratio = Math.max(0.0D, (distance - desiredStop) / distance);
        return new BlockPos(
                Math.floor(self.posX + dx * ratio),
                Math.floor(target.posY),
                Math.floor(self.posZ + dz * ratio));
    }

    private boolean shouldReplan(BlockPos newGoal) {
        if (currentPath == null || currentPath.isFinished() || currentGoal == null) return true;
        return horizontalDistance(currentGoal, newGoal) > REPLAN_THRESHOLD
                || ticksSinceReplan >= configuredReplanTicks();
    }

    private static int configuredReplanTicks() {
        return Math.max(2, Math.min(40, WanderBotSettings.navRepathTicks));
    }

    private static double configuredCombatRange() {
        return Math.max(2.5D, Math.min(4.0D, WanderBotSettings.combatRange));
    }

    private Path findPath(World world, EntityPlayerSP self, BlockPos goal, int maxNodes) {
        SearchContext context = new SearchContext();
        BlockPos start = findStandNear(world, new BlockPos(self.posX, self.posY, self.posZ), 2, context);
        BlockPos resolvedGoal = findStandNear(world, goal, GOAL_STAND_RADIUS, context);
        if (start == null || resolvedGoal == null) return null;

        PathNode startNode = context.node(start);
        startNode.gCost = 0.0D;
        startNode.hCost = heuristic(start, resolvedGoal);
        context.bestCost.put(startNode.key(), 0.0D);
        context.open.add(startNode);

        int expanded = 0;
        while (!context.open.isEmpty() && expanded < maxNodes) {
            expanded++;
            PathNode current = context.open.poll();
            Double known = context.bestCost.get(current.key());
            if (known != null && current.gCost > known + COST_EPSILON) continue;
            if (!context.closed.add(current.key())) continue;
            if (same(current, resolvedGoal)) return simplifyPath(buildPath(current));

            expandNeighbors(world, start, resolvedGoal, current, context);
        }
        return null;
    }

    private static void expandNeighbors(World world,
                                        BlockPos searchStart,
                                        BlockPos goal,
                                        PathNode current,
                                        SearchContext context) {
        BlockPos currentPos = new BlockPos(current.x, current.y, current.z);
        int previousDx = current.parent == null ? 0 : Integer.signum(current.x - current.parent.x);
        int previousDz = current.parent == null ? 0 : Integer.signum(current.z - current.parent.z);

        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            if (dx != 0 && dz != 0 && !cornerClear(world, currentPos, dx, dz)) continue;

            BlockPos next = findBestDestination(world, currentPos, dx, dz);
            if (next == null || outsideSearchBounds(searchStart, next)) continue;
            String key = key(next);
            if (context.closed.contains(key)) continue;

            int openDirections = context.localOpenSpace(world, next);
            double candidate = current.gCost
                    + edgeCost(currentPos, next, previousDx, previousDz, goal, openDirections);
            Double previousBest = context.bestCost.get(key);
            if (previousBest != null && candidate >= previousBest - COST_EPSILON) continue;

            PathNode node = context.node(next);
            node.parent = current;
            node.gCost = candidate;
            node.hCost = heuristic(next, goal);
            context.bestCost.put(key, candidate);
            context.open.add(node);
        }
    }

    private static boolean outsideSearchBounds(BlockPos start, BlockPos next) {
        return Math.abs(next.getX() - start.getX()) > SEARCH_RADIUS
                || Math.abs(next.getZ() - start.getZ()) > SEARCH_RADIUS
                || Math.abs(next.getY() - start.getY()) > SEARCH_HEIGHT;
    }

    private static BlockPos findBestDestination(World world, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        BlockPos sameLevel = new BlockPos(x, from.getY(), z);
        if (canOccupy(world, sameLevel)) return sameLevel;

        BlockPos stepUp = sameLevel.up();
        if (stepUp.getY() - from.getY() <= MAX_STEP_UP
                && canOccupy(world, stepUp)
                && clearAt(world, sameLevel)
                && clearAt(world, sameLevel.up())) {
            return stepUp;
        }

        for (int y = from.getY() - 1; y > 1; y--) {
            BlockPos candidate = new BlockPos(x, y, z);
            if (!clearAt(world, candidate) || !clearAt(world, candidate.up())) break;
            if (isSolidFloor(world, candidate.down())) return candidate;
        }
        return null;
    }

    public static boolean canOccupy(World world, BlockPos feet) {
        if (world == null || feet == null || feet.getY() <= 1 || feet.getY() >= world.getHeight() - 3) return false;
        return clearAt(world, feet) && clearAt(world, feet.up()) && isSolidFloor(world, feet.down());
    }

    private static boolean cornerClear(World world, BlockPos from, int dx, int dz) {
        return canOccupy(world, from.add(dx, 0, 0))
                && canOccupy(world, from.add(0, 0, dz));
    }

    private static BlockPos findStandNear(World world, BlockPos origin, int radius, SearchContext context) {
        if (canOccupy(world, origin)) return origin;

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!canOccupy(world, candidate)) continue;
                    int openDirections = context.localOpenSpace(world, candidate);
                    double score = dx * dx + dz * dz + Math.abs(dy) * 2.0D;
                    if (openDirections <= 1) score += 4.0D;
                    else if (openDirections == 2) score += 1.2D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static double edgeCost(BlockPos from, BlockPos to,
                                   int previousDx, int previousDz,
                                   BlockPos goal, int openDirections) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT_2 : 1.0D;

        if (dy > 0) cost += 0.80D;
        if (dy < 0) cost += 0.05D;

        if (previousDx != 0 || previousDz != 0) {
            int dot = previousDx * dx + previousDz * dz;
            if (dot < 0) cost += 7.0D;
            else if (dot == 0) cost += 0.35D;
            else if (previousDx != dx || previousDz != dz) cost += 0.07D;
        }

        if (openDirections <= 1) cost += 2.5D;
        else if (openDirections == 2) cost += 0.8D;
        else if (openDirections == 3) cost += 0.18D;
        else if (openDirections >= 7) cost -= 0.03D;

        if (horizontalDistance(to, goal) > horizontalDistance(from, goal) + 1.5D) cost += 0.35D;
        return Math.max(0.05D, cost);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT_2;
        double straight = Math.max(dx, dz) - Math.min(dx, dz);
        double dy = Math.abs(a.getY() - b.getY());
        return diagonal + straight + dy * 1.25D;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean clearAt(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || block.isAir(world, pos)) return true;
        if (material.isLiquid()) return false;
        return !material.blocksMovement();
    }

    private static boolean isSolidFloor(World world, BlockPos pos) {
        if (pos.getY() <= 0 || pos.getY() >= world.getHeight()) return false;
        Block block = world.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isAir(world, pos)) return false;
        return block.isOpaqueCube() || block.isFullBlock() || material.blocksMovement();
    }

    private static boolean same(PathNode node, BlockPos position) {
        return node.x == position.getX() && node.y == position.getY() && node.z == position.getZ();
    }

    private static String key(BlockPos position) {
        return position.getX() + ":" + position.getY() + ":" + position.getZ();
    }

    private static Path buildPath(PathNode end) {
        List<PathNode> result = new ArrayList<PathNode>();
        for (PathNode cursor = end; cursor != null; cursor = cursor.parent) result.add(cursor);
        Collections.reverse(result);
        return new Path(result);
    }

    /** Remove redundant collinear checkpoints while preserving turns and height changes. */
    private static Path simplifyPath(Path original) {
        List<PathNode> source = original.getNodes();
        if (source.size() < 3) return original;

        List<PathNode> result = new ArrayList<PathNode>();
        result.add(source.get(0));
        for (int i = 1; i < source.size() - 1; i++) {
            PathNode previous = result.get(result.size() - 1);
            PathNode current = source.get(i);
            PathNode next = source.get(i + 1);

            int dx1 = Integer.signum(current.x - previous.x);
            int dz1 = Integer.signum(current.z - previous.z);
            int dy1 = Integer.signum(current.y - previous.y);
            int dx2 = Integer.signum(next.x - current.x);
            int dz2 = Integer.signum(next.z - current.z);
            int dy2 = Integer.signum(next.y - current.y);
            if (dx1 != dx2 || dz1 != dz2 || dy1 != dy2) result.add(current);
        }
        result.add(source.get(source.size() - 1));
        return result.size() == source.size() ? original : new Path(result);
    }

    public Path getCurrentPath() { return currentPath; }
    public BlockPos getCurrentGoal() { return currentGoal; }
    public double getPredictedTargetX() { return predictedTargetX; }
    public double getPredictedTargetZ() { return predictedTargetZ; }

    public void reset() {
        currentPath = null;
        currentGoal = null;
        ticksSinceReplan = 0;
        fallbackCooldown = 0;
    }

    private static final Comparator<PathNode> PATH_NODE_ORDER = new Comparator<PathNode>() {
        @Override
        public int compare(PathNode a, PathNode b) {
            int total = Double.compare(a.fCost(), b.fCost());
            return total != 0 ? total : Double.compare(a.hCost, b.hCost);
        }
    };

    private static final class SearchContext {
        final Map<String, PathNode> nodes = new HashMap<String, PathNode>();
        final Map<String, Double> bestCost = new HashMap<String, Double>();
        final Set<String> closed = new HashSet<String>();
        final Map<String, Integer> openSpace = new HashMap<String, Integer>();
        final PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(256, PATH_NODE_ORDER);

        PathNode node(BlockPos position) {
            String key = key(position);
            PathNode node = nodes.get(key);
            if (node == null) {
                node = new PathNode(position.getX(), position.getY(), position.getZ());
                nodes.put(key, node);
            }
            return node;
        }

        int localOpenSpace(World world, BlockPos position) {
            String key = key(position);
            Integer cached = openSpace.get(key);
            if (cached != null) return cached;
            int count = 0;
            for (int[] direction : DIRECTIONS) {
                if (canOccupy(world, position.add(direction[0], 0, direction[1]))) count++;
            }
            openSpace.put(key, count);
            return count;
        }
    }
}
