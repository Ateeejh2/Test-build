package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
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
 * Combat-specific A* pathfinder for chasing moving targets.
 *
 * Key differences from the general PathFinder:
 * - Target is a moving entity, not a static BlockPos
 * - Goal is attack range, not exact position
 * - Predicts target position using velocity
 * - Only replans when target deviates significantly from path goal
 * - Includes jump/drop nodes explicitly
 * - Considers player bounding box for clearance
 */
public class CombatPathFinder {
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;
    private static final double ATTACK_RANGE = 3.2D;
    private static final double REPLAN_THRESHOLD = 3.0D;

    // Current path and goal tracking
    private Path currentPath;
    private BlockPos currentGoal;
    private int tickSinceReplan;

    // Target prediction
    private double lastTargetX;
    private double lastTargetZ;
    private double predictedTargetX;
    private double predictedTargetZ;
    private int predictionTicks;

    /**
     * Get the best path toward the target, replanning only when necessary.
     */
    public Path getPath(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null) return null;

        // Predict target position
        predictTarget(target);

        // Build goal position (attack range from predicted target)
        BlockPos goalPos = computeGoal(self, target);

        // Check if we need to replan
        if (shouldReplan(self, goalPos)) {
            currentPath = findPath(world, self, goalPos, maxNodes);
            currentGoal = goalPos;
            tickSinceReplan = 0;
        } else {
            tickSinceReplan++;
        }

        return currentPath;
    }

    /**
     * Predict target position using velocity extrapolation.
     */
    private void predictTarget(EntityPlayer target) {
        double targetX = target.posX;
        double targetZ = target.posZ;

        // Smooth velocity tracking
        double vx = target.motionX;
        double vz = target.motionZ;

        // Predict 5-10 ticks ahead based on speed
        double speed = Math.sqrt(vx * vx + vz * vz);
        double predictTime = speed > 0.1 ? 6.0 : 3.0;

        predictedTargetX = targetX + vx * predictTime;
        predictedTargetZ = targetZ + vz * predictTime;

        lastTargetX = targetX;
        lastTargetZ = targetZ;
        predictionTicks++;
    }

    /**
     * Compute goal position: a position within attack range of the predicted target.
     */
    private BlockPos computeGoal(EntityPlayerSP self, EntityPlayer target) {
        double dx = predictedTargetX - self.posX;
        double dz = predictedTargetZ - self.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist < ATTACK_RANGE) {
            // Already in range, use current target position as goal
            return new BlockPos(target.posX, target.posY, target.posZ);
        }

        // Move toward predicted position, stopping at attack range
        double ratio = (dist - ATTACK_RANGE + 0.5) / dist;
        int goalX = (int) Math.round(self.posX + dx * ratio);
        int goalZ = (int) Math.round(self.posZ + dz * ratio);
        int goalY = (int) Math.round(target.posY);

        return new BlockPos(goalX, goalY, goalZ);
    }

    /**
     * Check if we need to replan the path.
     */
    private boolean shouldReplan(EntityPlayerSP self, BlockPos newGoal) {
        if (currentPath == null || currentPath.isFinished()) return true;
        if (currentGoal == null) return true;

        // Target moved significantly from our goal
        double goalDist = horizontalDistance(
                new BlockPos(currentGoal.getX(), 0, currentGoal.getZ()),
                new BlockPos(newGoal.getX(), 0, newGoal.getZ()));
        if (goalDist > REPLAN_THRESHOLD) return true;

        // Haven't replanned in a while and target is moving
        if (tickSinceReplan > 40) return true;

        return false;
    }

    /**
     * A* pathfinding to the goal position.
     */
    private Path findPath(World world, EntityPlayerSP self, BlockPos goal, int maxNodes) {
        BlockPos start = new BlockPos(self.posX, self.posY, self.posZ);
        BlockPos s = findStandNear(world, start, 2);
        BlockPos g = findStandNear(world, goal, 3);
        if (s == null || g == null) return null;

        final Map<String, PathNode> nodes = new HashMap<>();
        final Map<String, Double> best = new HashMap<>();
        final Set<String> closed = new HashSet<>();
        final PriorityQueue<PathNode> open = new PriorityQueue<>(256,
                Comparator.comparingDouble(PathNode::fCost));

        PathNode startNode = node(nodes, s);
        startNode.gCost = 0.0;
        startNode.hCost = heuristic(s, g);
        best.put(startNode.key(), startNode.gCost);
        open.add(startNode);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            expanded++;
            PathNode current = open.poll();
            Double known = best.get(current.key());
            if (known != null && current.gCost > known + 0.000001) continue;
            if (!closed.add(current.key())) continue;

            if (same(current, g)) {
                return buildPath(current);
            }

            BlockPos cp = new BlockPos(current.x, current.y, current.z);

            for (int[] dir : DIRS) {
                int dx = dir[0];
                int dz = dir[1];
                if (dx != 0 && dz != 0 && !cornerClear(world, cp, dx, dz)) continue;

                BlockPos next = findBestDestination(world, cp, dx, dz);
                if (next == null) continue;

                // Limit search radius
                if (Math.abs(next.getX() - s.getX()) > 20) continue;
                if (Math.abs(next.getZ() - s.getZ()) > 20) continue;
                if (Math.abs(next.getY() - s.getY()) > 8) continue;

                String k = key(next);
                if (closed.contains(k)) continue;

                double edge = edgeCost(world, cp, next, g);
                double candidate = current.gCost + edge;
                Double old = best.get(k);
                if (old == null || candidate < old - 0.000001) {
                    PathNode n = node(nodes, next);
                    n.parent = current;
                    n.gCost = candidate;
                    n.hCost = heuristic(next, g);
                    best.put(k, candidate);
                    open.add(n);
                }
            }
        }
        return null;
    }

    // === Terrain helpers (adapted from PathFinder) ===

    private static BlockPos findBestDestination(World world, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;

        // Try same level
        BlockPos same = new BlockPos(x, from.getY(), z);
        if (canOccupy(world, same)) return same;

        // Try step up
        BlockPos up = same.up();
        if (up.getY() - from.getY() <= MAX_STEP_UP && canOccupy(world, up)) return up;

        // Try drops
        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = same.down(drop);
            if (canOccupy(world, down) && isSafeDrop(world, down, MAX_DROP)) return down;
        }
        return null;
    }

    public static boolean canOccupy(World world, BlockPos feet) {
        if (world == null || feet.getY() <= 1 || feet.getY() >= world.getHeight() - 3) return false;
        if (!clearAt(world, feet) || !clearAt(world, feet.up())) return false;
        if (!isSolidFloor(world, feet.down())) return false;
        return true;
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

    private static boolean isSafeDrop(World world, BlockPos feet, int maxDrop) {
        if (!canOccupy(world, feet)) return false;
        int depth = 0;
        for (int y = feet.getY() - 1; y >= Math.max(1, feet.getY() - maxDrop - 1); y--) {
            if (isSolidFloor(world, new BlockPos(feet.getX(), y, feet.getZ())))
                return depth <= maxDrop;
            if (!clearAt(world, new BlockPos(feet.getX(), y, feet.getZ()))) return false;
            depth++;
        }
        return false;
    }

    private static boolean cornerClear(World world, BlockPos from, int dx, int dz) {
        BlockPos a = from.add(dx, 0, 0);
        BlockPos b = from.add(0, 0, dz);
        return canOccupy(world, a) && canOccupy(world, b);
    }

    private static BlockPos findStandNear(World world, BlockPos p, int radius) {
        if (canOccupy(world, p)) return p;
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos c = p.add(dx, dy, dz);
                    if (!canOccupy(world, c)) continue;
                    double score = dx * dx + dz * dz + Math.abs(dy) * 2.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    // === Cost functions ===

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT2;
        double straight = Math.max(dx, dz) - Math.min(dx, dz);
        double dy = Math.abs(a.getY() - b.getY());
        return diagonal + straight + dy * 1.20;
    }

    private static double edgeCost(World world, BlockPos from, BlockPos to, BlockPos goal) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT2 : 1.0;

        if (dy > 0) cost += 0.95;  // Jump cost
        if (dy < 0) cost += 0.18;  // Drop cost (slightly preferred)

        // Penalize moving away from goal
        double before = horizontalDistance(from, goal);
        double after = horizontalDistance(to, goal);
        if (after > before + 1.25) cost += 0.35;

        return Math.max(0.05, cost);
    }

    // === Utility ===

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean same(PathNode n, BlockPos p) {
        return n.x == p.getX() && n.y == p.getY() && n.z == p.getZ();
    }

    private static String key(BlockPos p) {
        return p.getX() + ":" + p.getY() + ":" + p.getZ();
    }

    private static PathNode node(Map<String, PathNode> nodes, BlockPos pos) {
        String k = key(pos);
        PathNode n = nodes.get(k);
        if (n == null) {
            n = new PathNode(pos.getX(), pos.getY(), pos.getZ());
            nodes.put(k, n);
        }
        return n;
    }

    private static Path buildPath(PathNode end) {
        List<PathNode> result = new ArrayList<>();
        PathNode cur = end;
        while (cur != null) {
            result.add(cur);
            cur = cur.parent;
        }
        Collections.reverse(result);
        return new Path(result);
    }

    // === Public state access ===

    public Path getCurrentPath() { return currentPath; }
    public BlockPos getCurrentGoal() { return currentGoal; }
    public double getPredictedTargetX() { return predictedTargetX; }
    public double getPredictedTargetZ() { return predictedTargetZ; }
    public void reset() {
        currentPath = null;
        currentGoal = null;
        tickSinceReplan = 0;
        predictionTicks = 0;
    }
}
