package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
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

/**
 * Terrain-aware A* for Minecraft 1.8.9.
 *
 * Search state is intentionally scoped to a single invocation. The old
 * implementation stored terrain caches in static mutable fields, which made
 * nested or concurrent path searches interfere with one another.
 */
public class PathFinder {
    private static final int[][] DIRECTIONS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT_2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;
    private static final double COST_EPSILON = 0.000001D;

    public Path findPath(World world, BlockPos start, BlockPos goal, int maxDistance, int maxNodes) {
        if (world == null || start == null || goal == null || maxDistance < 0 || maxNodes <= 0) return null;

        SearchContext context = new SearchContext();
        BlockPos resolvedStart = findStandNear(world, start, 2, context);
        BlockPos resolvedGoal = findStandNear(world, goal, 3, context);
        if (resolvedStart == null || resolvedGoal == null) return null;
        if (horizontalDistance(resolvedStart, resolvedGoal) > maxDistance) return null;

        PathNode startNode = context.node(resolvedStart);
        startNode.gCost = 0.0D;
        startNode.hCost = heuristic(resolvedStart, resolvedGoal);
        context.bestCost.put(startNode.key(), 0.0D);
        context.open.add(startNode);

        int expanded = 0;
        while (!context.open.isEmpty() && expanded < maxNodes) {
            expanded++;
            PathNode current = context.open.poll();
            if (isOutdated(context, current)) continue;
            if (!context.closed.add(current.key())) continue;

            if (same(current, resolvedGoal)) {
                return buildPath(current);
            }

            expandNeighbors(world, resolvedStart, resolvedGoal, current, maxDistance, context);
        }
        return null;
    }

    /** Two-stage planner used when a direct search is too hard on complex terrain. */
    public Path findHierarchicalPath(World world, BlockPos start, BlockPos goal, int maxDistance, int maxNodes) {
        Path direct = findPath(world, start, goal, maxDistance, maxNodes);
        if (direct != null) return direct;
        if (world == null || start == null || goal == null || maxNodes < 2) return null;

        BlockPos midpointGuess = new BlockPos(
                (start.getX() + goal.getX()) / 2,
                start.getY(),
                (start.getZ() + goal.getZ()) / 2);
        BlockPos middle = findStandNear(world, midpointGuess, 6, new SearchContext());
        if (middle == null) return null;

        int halfBudget = Math.max(1, maxNodes / 2);
        Path first = findPath(world, start, middle, maxDistance, halfBudget);
        if (first == null) return null;
        Path second = findPath(world, middle, goal, maxDistance, halfBudget);
        if (second == null) return null;

        return concatenate(first, second);
    }

    private static void expandNeighbors(World world,
                                        BlockPos searchStart,
                                        BlockPos goal,
                                        PathNode current,
                                        int maxDistance,
                                        SearchContext context) {
        BlockPos currentPos = new BlockPos(current.x, current.y, current.z);
        int previousDx = current.parent == null ? 0 : Integer.signum(current.x - current.parent.x);
        int previousDz = current.parent == null ? 0 : Integer.signum(current.z - current.parent.z);

        for (int[] direction : DIRECTIONS) {
            int dx = direction[0];
            int dz = direction[1];
            if (dx != 0 && dz != 0 && !cornerClear(world, currentPos, dx, dz)) continue;

            BlockPos next = findBestDestination(world, currentPos, dx, dz);
            if (next == null || outsideSearchBounds(searchStart, next, maxDistance)) continue;

            String key = key(next);
            if (context.closed.contains(key)) continue;

            int openDirections = context.localOpenSpace(world, next);
            double candidate = current.gCost
                    + edgeCost(world, currentPos, next, previousDx, previousDz, goal, openDirections, context);
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

    private static boolean isOutdated(SearchContext context, PathNode node) {
        Double best = context.bestCost.get(node.key());
        return best != null && node.gCost > best + COST_EPSILON;
    }

    private static boolean outsideSearchBounds(BlockPos start, BlockPos next, int maxDistance) {
        return Math.abs(next.getX() - start.getX()) > maxDistance
                || Math.abs(next.getZ() - start.getZ()) > maxDistance
                || Math.abs(next.getY() - start.getY()) > 8;
    }

    private static BlockPos findBestDestination(World world, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        BlockPos sameLevel = new BlockPos(x, from.getY(), z);
        if (canOccupy(world, sameLevel) && safeTransition(world, from, sameLevel)) return sameLevel;

        BlockPos stepUp = sameLevel.up();
        if (stepUp.getY() - from.getY() <= MAX_STEP_UP
                && canOccupy(world, stepUp)
                && safeTransition(world, from, stepUp)) {
            return stepUp;
        }

        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = sameLevel.down(drop);
            if (canOccupy(world, down) && safeTransition(world, from, down)) return down;
        }
        return null;
    }

    private static boolean safeTransition(World world, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (dy > MAX_STEP_UP || dy < -MAX_DROP) return false;
        if (dy >= 0) return true;

        int fallDistance = 0;
        for (int y = from.getY() - 1; y >= to.getY(); y--) {
            fallDistance++;
            BlockPos column = new BlockPos(to.getX(), y, to.getZ());
            if (isSolidFloor(world, column)) return fallDistance <= MAX_DROP + 1;
            if (!isClearColumn(world, column)) return false;
        }
        return false;
    }

    private static boolean cornerClear(World world, BlockPos from, int dx, int dz) {
        return canOccupy(world, from.add(dx, 0, 0))
                && canOccupy(world, from.add(0, 0, dz));
    }

    public static boolean canOccupy(World world, BlockPos feet) {
        if (world == null || feet == null) return false;
        if (feet.getY() <= 1 || feet.getY() >= world.getHeight() - 3) return false;
        return clearAt(world, feet)
                && clearAt(world, feet.up())
                && isSolidFloor(world, feet.down());
    }

    public static boolean isStandable(World world, BlockPos feet) {
        return canOccupy(world, feet);
    }

    public static boolean isSafeDrop(World world, BlockPos feet, int maxDrop) {
        if (!canOccupy(world, feet)) return false;
        int depth = 0;
        int bottomY = Math.max(1, feet.getY() - maxDrop - 1);
        for (int y = feet.getY() - 1; y >= bottomY; y--) {
            BlockPos below = new BlockPos(feet.getX(), y, feet.getZ());
            if (isSolidFloor(world, below)) return depth <= maxDrop;
            if (!clearAt(world, below)) return false;
            depth++;
        }
        return true;
    }

    private static boolean clearAt(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || block.isAir(world, pos)) return true;
        if (material.isLiquid()) return false;
        return !material.blocksMovement();
    }

    private static boolean isSolidFloor(World world, BlockPos pos) {
        if (world == null || pos == null || pos.getY() <= 0 || pos.getY() >= world.getHeight()) return false;
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isAir(world, pos)) return false;
        return block.isOpaqueCube() || block.isFullBlock() || material.blocksMovement();
    }

    private static boolean isClearColumn(World world, BlockPos pos) {
        return clearAt(world, pos) && clearAt(world, pos.up());
    }

    private static BlockPos findStandNear(World world, BlockPos origin, int radius, SearchContext context) {
        if (canOccupy(world, origin)) return origin;

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos candidate = origin.add(dx, dy, dz);
                    if (!canOccupy(world, candidate)) continue;

                    int openDirections = context.localOpenSpace(world, candidate);
                    double score = dx * dx + dz * dz + Math.abs(dy) * 2.5D;
                    score += dangerPenalty(world, candidate, openDirections, context) * 0.7D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static double edgeCost(World world,
                                   BlockPos from,
                                   BlockPos to,
                                   int previousDx,
                                   int previousDz,
                                   BlockPos goal,
                                   int openDirections,
                                   SearchContext context) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT_2 : 1.0D;

        if (dy > 0) cost += 0.95D;
        if (dy < 0) cost += 0.18D;

        if (previousDx != 0 || previousDz != 0) {
            int dot = previousDx * dx + previousDz * dz;
            if (dot < 0) cost += 7.0D;
            else if (dot == 0) cost += 0.55D;
            else if (previousDx != dx || previousDz != dz) cost += 0.12D;
        }

        cost += dangerPenalty(world, to, openDirections, context);

        if (openDirections <= 1) cost += 4.5D;
        else if (openDirections == 2) cost += 2.0D;
        else if (openDirections == 3) cost += 0.45D;
        else if (openDirections >= 7) cost -= 0.08D;

        double before = horizontalDistance(from, goal);
        double after = horizontalDistance(to, goal);
        if (after > before + 1.25D) cost += 0.35D;

        return Math.max(0.05D, cost);
    }

    private static double dangerPenalty(World world,
                                        BlockPos position,
                                        int openDirections,
                                        SearchContext context) {
        double penalty = 0.0D;
        int support = context.supportCount(world, position, 1);
        if (support <= 2) penalty += 5.0D;
        else if (support <= 4) penalty += 1.0D;

        // The original implementation contained an isSafeDrop() branch that
        // added 0.0, so it had no effect. Keep path costs unchanged here.
        if (openDirections <= 1) penalty += 3.0D;
        else if (openDirections == 2) penalty += 0.8D;
        return penalty;
    }

    /** Uncached version intentionally kept for external callers such as TerrainAnalyzer. */
    public static int localOpenSpace(World world, BlockPos position) {
        int count = 0;
        for (int[] direction : DIRECTIONS) {
            if (canOccupy(world, position.add(direction[0], 0, direction[1]))) count++;
        }
        return count;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT_2;
        double straight = Math.max(dx, dz) - Math.min(dx, dz);
        double dy = Math.abs(a.getY() - b.getY());
        return diagonal + straight + dy * 1.20D;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean same(PathNode node, BlockPos position) {
        return node.x == position.getX()
                && node.y == position.getY()
                && node.z == position.getZ();
    }

    private static String key(BlockPos position) {
        return position.getX() + ":" + position.getY() + ":" + position.getZ();
    }

    private static Path buildPath(PathNode end) {
        List<PathNode> nodes = new ArrayList<PathNode>();
        for (PathNode cursor = end; cursor != null; cursor = cursor.parent) {
            nodes.add(cursor);
        }
        Collections.reverse(nodes);
        return new Path(removeRedundantCollinear(nodes));
    }

    private static List<PathNode> removeRedundantCollinear(List<PathNode> raw) {
        if (raw.size() <= 2) return raw;

        List<PathNode> result = new ArrayList<PathNode>();
        result.add(raw.get(0));
        int lastDx = Integer.MIN_VALUE;
        int lastDz = Integer.MIN_VALUE;

        for (int i = 1; i < raw.size(); i++) {
            PathNode previous = raw.get(i - 1);
            PathNode current = raw.get(i);
            int dx = Integer.signum(current.x - previous.x);
            int dz = Integer.signum(current.z - previous.z);
            boolean directionChanged = lastDx != Integer.MIN_VALUE && (dx != lastDx || dz != lastDz);
            if (i == raw.size() - 1 || current.y != previous.y || directionChanged) {
                result.add(previous);
            }
            lastDx = dx;
            lastDz = dz;
        }

        PathNode end = raw.get(raw.size() - 1);
        if (result.get(result.size() - 1) != end) result.add(end);
        return result;
    }

    private static Path concatenate(Path first, Path second) {
        List<PathNode> combined = new ArrayList<PathNode>(first.getNodes());
        for (PathNode node : second.getNodes()) {
            if (!combined.isEmpty() && node.equals(combined.get(combined.size() - 1))) continue;
            combined.add(node);
        }
        return new Path(combined);
    }

    /** Mutable state owned by exactly one path search. */
    private static final class SearchContext {
        private final Map<String, PathNode> nodes = new HashMap<String, PathNode>();
        private final Map<String, Double> bestCost = new HashMap<String, Double>();
        private final Set<String> closed = new HashSet<String>();
        private final Map<String, Integer> localOpenSpace = new HashMap<String, Integer>();
        private final Map<String, Integer> supportCount = new HashMap<String, Integer>();
        private final PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(256, PATH_NODE_ORDER);

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
            Integer cached = localOpenSpace.get(key);
            if (cached != null) return cached;

            int count = PathFinder.localOpenSpace(world, position);
            localOpenSpace.put(key, count);
            return count;
        }

        int supportCount(World world, BlockPos position, int radius) {
            String key = key(position) + ":s" + radius;
            Integer cached = supportCount.get(key);
            if (cached != null) return cached;

            int count = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (isSolidFloor(world, position.add(dx, -1, dz))) count++;
                }
            }
            supportCount.put(key, count);
            return count;
        }
    }

    private static final Comparator<PathNode> PATH_NODE_ORDER = new Comparator<PathNode>() {
        @Override
        public int compare(PathNode a, PathNode b) {
            int byTotalCost = Double.compare(a.fCost(), b.fCost());
            if (byTotalCost != 0) return byTotalCost;
            int byHeuristic = Double.compare(a.hCost, b.hCost);
            if (byHeuristic != 0) return byHeuristic;
            return a.key().compareTo(b.key());
        }
    };
}
