package com.atlasdead.wanderbot.pathfinding;

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

/**
 * Combat-specific A* pathfinder for chasing moving targets.
 *
 * The active route is retained across replans. Search work is deliberately
 * bounded and terrain queries are cached per search so pathfinding does not
 * monopolize the Minecraft client thread during frequent combat updates.
 */
public class CombatPathFinder {
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final double ATTACK_RANGE = 3.2D;
    private static final double REPLAN_THRESHOLD = 5.5D;
    private static final int REPLAN_TICKS = 24;
    private static final int SEARCH_RADIUS = 64;
    private static final int GOAL_STAND_RADIUS = 5;

    private Path currentPath;
    private BlockPos currentGoal;
    private int tickSinceReplan;

    private double predictedTargetX;
    private double predictedTargetZ;
    private int predictionTicks;

    // Per-search cache. Combat A* calls localOpenSpace frequently, so avoid
    // resolving the same neighboring positions over and over.
    private final Map<String, Integer> openSpaceCache = new HashMap<String, Integer>();

    public Path getPath(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null) return currentPath;

        predictTarget(target);
        BlockPos goalPos = computeGoal(self, target);

        if (shouldReplan(self, goalPos)) {
            boolean initial = currentPath == null || currentPath.isFinished();
            int effectiveNodes = adaptiveNodeBudget(self, goalPos, maxNodes, initial);
            Path candidate = findPath(world, self, goalPos, effectiveNodes);
            if (candidate != null && !candidate.isFinished()) {
                currentPath = candidate;
                currentGoal = goalPos;
                tickSinceReplan = 0;
            } else {
                // Preserve the last usable path when a bounded search fails.
                tickSinceReplan++;
            }
        } else {
            tickSinceReplan++;
        }

        return currentPath;
    }

    /** Lightweight reachability probe for callers that need target validation. */
    public boolean canReachTarget(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null) return false;
        predictTarget(target);
        BlockPos goalPos = computeGoal(self, target);
        int budget = Math.min(3500, Math.max(1200, maxNodes));
        Path path = findPath(world, self, goalPos, budget);
        return path != null && !path.isFinished();
    }

    private int adaptiveNodeBudget(EntityPlayerSP self, BlockPos goal, int requested, boolean initial) {
        double dx = goal.getX() - self.posX;
        double dz = goal.getZ() - self.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        int requestedSafe = Math.max(1200, requested);

        // Combat paths are normally local. Keep the first search a little more
        // generous, but prevent a routine replan from doing a 10k-node search.
        int budget;
        if (distance <= 10.0D) budget = initial ? 4200 : 2600;
        else if (distance <= 24.0D) budget = initial ? 5200 : 3400;
        else budget = initial ? 6500 : 4200;
        return Math.min(requestedSafe, budget);
    }

    private void predictTarget(EntityPlayer target) {
        double targetX = target.posX;
        double targetZ = target.posZ;
        double vx = target.motionX;
        double vz = target.motionZ;
        double speed = Math.sqrt(vx * vx + vz * vz);
        double predictTime = speed > 0.10D ? 5.0D : 2.5D;
        predictedTargetX = targetX + vx * predictTime;
        predictedTargetZ = targetZ + vz * predictTime;
        predictionTicks++;
    }

    private BlockPos computeGoal(EntityPlayerSP self, EntityPlayer target) {
        double dx = predictedTargetX - self.posX;
        double dz = predictedTargetZ - self.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= ATTACK_RANGE) {
            return new BlockPos(target.posX, target.posY, target.posZ);
        }

        double desiredStop = ATTACK_RANGE - 0.25D;
        double ratio = Math.max(0.0D, (dist - desiredStop) / dist);
        int goalX = (int) Math.floor(self.posX + dx * ratio);
        int goalZ = (int) Math.floor(self.posZ + dz * ratio);
        int goalY = (int) Math.floor(target.posY);
        return new BlockPos(goalX, goalY, goalZ);
    }

    private boolean shouldReplan(EntityPlayerSP self, BlockPos newGoal) {
        if (currentPath == null || currentPath.isFinished()) return true;
        if (currentGoal == null) return true;

        double goalDist = horizontalDistance(currentGoal, newGoal);
        if (goalDist > REPLAN_THRESHOLD) return true;
        return tickSinceReplan >= REPLAN_TICKS;
    }

    private Path findPath(World world, EntityPlayerSP self, BlockPos goal, int maxNodes) {
        openSpaceCache.clear();

        BlockPos start = new BlockPos(self.posX, self.posY, self.posZ);
        BlockPos s = findStandNear(world, start, 2);
        BlockPos g = findStandNear(world, goal, GOAL_STAND_RADIUS);
        if (s == null || g == null) return null;

        final Map<String, PathNode> nodes = new HashMap<String, PathNode>();
        final Map<String, Double> best = new HashMap<String, Double>();
        final Set<String> closed = new HashSet<String>();
        final PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(256,
                new Comparator<PathNode>() {
                    @Override
                    public int compare(PathNode a, PathNode b) {
                        int c = Double.compare(a.fCost(), b.fCost());
                        if (c != 0) return c;
                        return Double.compare(a.hCost, b.hCost);
                    }
                });

        PathNode startNode = node(nodes, s);
        startNode.gCost = 0.0D;
        startNode.hCost = heuristic(s, g);
        best.put(startNode.key(), 0.0D);
        open.add(startNode);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            expanded++;
            PathNode current = open.poll();
            Double known = best.get(current.key());
            if (known != null && current.gCost > known + 0.000001D) continue;
            if (!closed.add(current.key())) continue;
            if (same(current, g)) return simplifyPath(buildPath(current));

            BlockPos cp = new BlockPos(current.x, current.y, current.z);
            int prevDx = current.parent == null ? 0 : Integer.signum(current.x - current.parent.x);
            int prevDz = current.parent == null ? 0 : Integer.signum(current.z - current.parent.z);

            for (int[] dir : DIRS) {
                int dx = dir[0];
                int dz = dir[1];
                if (dx != 0 && dz != 0 && !cornerClear(world, cp, dx, dz)) continue;

                BlockPos next = findBestDestination(world, cp, dx, dz);
                if (next == null) continue;

                if (Math.abs(next.getX() - s.getX()) > SEARCH_RADIUS
                        || Math.abs(next.getZ() - s.getZ()) > SEARCH_RADIUS
                        || Math.abs(next.getY() - s.getY()) > 12) {
                    continue;
                }

                String k = key(next);
                if (closed.contains(k)) continue;

                int openDirs = cachedLocalOpenSpace(world, next);
                double edge = edgeCost(world, cp, next, prevDx, prevDz, g, openDirs);
                double candidate = current.gCost + edge;
                Double old = best.get(k);
                if (old == null || candidate < old - 0.000001D) {
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

    private static BlockPos findBestDestination(World world, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        BlockPos same = new BlockPos(x, from.getY(), z);
        if (canOccupy(world, same)) return same;

        BlockPos up = same.up();
        if (up.getY() - from.getY() <= MAX_STEP_UP
                && canOccupy(world, up)
                && clearAt(world, same)
                && clearAt(world, same.up())) {
            return up;
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
        if (!clearAt(world, feet) || !clearAt(world, feet.up())) return false;
        return isSolidFloor(world, feet.down());
    }

    private static boolean cornerClear(World world, BlockPos from, int dx, int dz) {
        return canOccupy(world, from.add(dx, 0, 0))
                && canOccupy(world, from.add(0, 0, dz));
    }

    private BlockPos findStandNear(World world, BlockPos p, int radius) {
        if (canOccupy(world, p)) return p;
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos c = p.add(dx, dy, dz);
                    if (!canOccupy(world, c)) continue;
                    int open = cachedLocalOpenSpace(world, c);
                    double score = dx * dx + dz * dz + Math.abs(dy) * 2.0D;
                    if (open <= 1) score += 4.0D;
                    else if (open == 2) score += 1.2D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    private int cachedLocalOpenSpace(World world, BlockPos p) {
        String k = key(p);
        Integer cached = openSpaceCache.get(k);
        if (cached != null) return cached;
        int count = 0;
        for (int[] d : DIRS) {
            if (canOccupy(world, p.add(d[0], 0, d[1]))) count++;
        }
        openSpaceCache.put(k, count);
        return count;
    }

    private static double edgeCost(World world, BlockPos from, BlockPos to,
                                   int prevDx, int prevDz, BlockPos goal, int openDirs) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT2 : 1.0D;

        if (dy > 0) cost += 0.80D;
        if (dy < 0) cost += 0.05D;

        if (prevDx != 0 || prevDz != 0) {
            int dot = prevDx * dx + prevDz * dz;
            if (dot < 0) cost += 7.0D;
            else if (dot == 0) cost += 0.35D;
            else if (prevDx != dx || prevDz != dz) cost += 0.07D;
        }

        if (openDirs <= 1) cost += 2.5D;
        else if (openDirs == 2) cost += 0.8D;
        else if (openDirs == 3) cost += 0.18D;
        else if (openDirs >= 7) cost -= 0.03D;

        double before = horizontalDistance(from, goal);
        double after = horizontalDistance(to, goal);
        if (after > before + 1.5D) cost += 0.35D;

        return Math.max(0.05D, cost);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT2;
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
        List<PathNode> result = new ArrayList<PathNode>();
        PathNode cur = end;
        while (cur != null) {
            result.add(cur);
            cur = cur.parent;
        }
        Collections.reverse(result);
        return new Path(result);
    }

    /** Remove redundant collinear checkpoints while preserving every turn and height change. */
    private static Path simplifyPath(Path original) {
        List<PathNode> source = original.getNodes();
        if (source.size() < 3) return original;

        List<PathNode> result = new ArrayList<PathNode>();
        result.add(source.get(0));

        for (int i = 1; i < source.size() - 1; i++) {
            PathNode prev = result.get(result.size() - 1);
            PathNode cur = source.get(i);
            PathNode next = source.get(i + 1);

            int dx1 = Integer.signum(cur.x - prev.x);
            int dz1 = Integer.signum(cur.z - prev.z);
            int dy1 = Integer.signum(cur.y - prev.y);
            int dx2 = Integer.signum(next.x - cur.x);
            int dz2 = Integer.signum(next.z - cur.z);
            int dy2 = Integer.signum(next.y - cur.y);

            boolean sameDirection = dx1 == dx2 && dz1 == dz2 && dy1 == dy2;
            if (!sameDirection) result.add(cur);
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
        tickSinceReplan = 0;
        predictionTicks = 0;
        openSpaceCache.clear();
    }
}
