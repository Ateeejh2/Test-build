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
 * Combat-specific terrain-aware A*.
 *
 * The important rule here is: a moving target does NOT cause the active path
 * to be replaced every tick.  A path is retained until it is actually stale,
 * blocked, finished, or the target's chase goal moved materially.
 */
public class CombatPathFinder {
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;

    // Stay outside the actual attack envelope so the controller does not
    // constantly oscillate between "approach" and "attack".
    private static final double CHASE_RADIUS = 2.75D;
    private static final double GOAL_SEARCH_RADIUS = 3.25D;

    // Replanning is deliberately conservative for a moving target.
    private static final int MIN_REPLAN_TICKS = 8;
    private static final int MAX_PATH_AGE_TICKS = 30;
    private static final double GOAL_REPLAN_DISTANCE = 1.75D;
    private static final double TARGET_MOVE_REPLAN_DISTANCE = 1.25D;

    private Path currentPath;
    private BlockPos currentGoal;
    private int ticksSinceReplan = 999;
    private double lastPlanTargetX;
    private double lastPlanTargetY;
    private double lastPlanTargetZ;
    private double predictedTargetX;
    private double predictedTargetZ;
    private int lastExpandedNodes;

    public Path getPath(World world, EntityPlayerSP self, EntityPlayer target, int maxNodes) {
        if (world == null || self == null || target == null || target.isDead || target.getHealth() <= 0.0F) {
            reset();
            return null;
        }

        predictTarget(target);
        BlockPos desiredGoal = chooseGoal(world, self, target);

        if (desiredGoal == null) {
            // Keep a still-valid active path rather than destroying it just
            // because the target's current block is temporarily awkward.
            ticksSinceReplan++;
            return isUsable(world, self, currentPath) ? currentPath : null;
        }

        boolean replan = shouldReplan(world, self, target, desiredGoal);
        if (replan) {
            Path newPath = findPath(world, self, desiredGoal, Math.max(300, maxNodes));
            if (newPath != null && !newPath.getNodes().isEmpty()) {
                currentPath = newPath;
                currentGoal = desiredGoal;
                ticksSinceReplan = 0;
                lastPlanTargetX = target.posX;
                lastPlanTargetY = target.posY;
                lastPlanTargetZ = target.posZ;
            } else {
                // Do not throw away a usable route just because one replan
                // failed. This is a major source of stop/replan oscillation.
                ticksSinceReplan++;
            }
        } else {
            ticksSinceReplan++;
        }

        return currentPath;
    }

    private void predictTarget(EntityPlayer target) {
        double vx = target.motionX;
        double vz = target.motionZ;
        double speed = Math.sqrt(vx * vx + vz * vz);

        // 2-4 ticks is enough to lead a chase without producing a goal that
        // jumps around corners before the target actually reaches them.
        double lead = speed > 0.18D ? 4.0D : (speed > 0.05D ? 3.0D : 1.5D);
        predictedTargetX = target.posX + vx * lead;
        predictedTargetZ = target.posZ + vz * lead;
    }

    /**
     * Pick a reachable point on a ring around the predicted target. Going to
     * "target minus range along the current vector" is unreliable around
     * walls/corners because that point may be behind an obstacle.
     */
    private BlockPos chooseGoal(World world, EntityPlayerSP self, EntityPlayer target) {
        double tx = predictedTargetX;
        double tz = predictedTargetZ;
        double dx = tx - self.posX;
        double dz = tz - self.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (dist <= CHASE_RADIUS) {
            return nearestStandable(world, new BlockPos(target.posX, target.posY, target.posZ), 2);
        }

        double baseAngle = Math.atan2(dz, dx);
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        // Candidate points around the target. Prefer the point that is both
        // near the desired ring and roughly in the current chase direction.
        for (int i = 0; i < 12; i++) {
            double angle = baseAngle + i * (Math.PI * 2.0D / 12.0D);
            double radius = CHASE_RADIUS;
            int x = (int)Math.floor(tx + Math.cos(angle) * radius);
            int z = (int)Math.floor(tz + Math.sin(angle) * radius);
            BlockPos raw = new BlockPos(x, (int)Math.floor(target.posY), z);
            BlockPos candidate = nearestStandable(world, raw, 1);
            if (candidate == null) continue;

            double toCandidateX = candidate.getX() + 0.5D - self.posX;
            double toCandidateZ = candidate.getZ() + 0.5D - self.posZ;
            double candidateDist = Math.sqrt(toCandidateX * toCandidateX + toCandidateZ * toCandidateZ);
            double targetDist = horizontal(candidate, new BlockPos((int)Math.floor(tx), candidate.getY(), (int)Math.floor(tz)));
            double angleDelta = Math.abs(wrapAngle(Math.atan2(toCandidateZ, toCandidateX) - baseAngle));
            double score = targetDist * 2.0D + angleDelta * 0.35D + candidateDist * 0.03D;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        return best != null ? best : nearestStandable(world, new BlockPos(target.posX, target.posY, target.posZ), 3);
    }

    private boolean shouldReplan(World world, EntityPlayerSP self, EntityPlayer target, BlockPos newGoal) {
        if (currentPath == null || currentPath.isFinished() || currentGoal == null) return true;
        if (ticksSinceReplan < MIN_REPLAN_TICKS) {
            return !isUsable(world, self, currentPath);
        }

        double goalDelta = horizontal(currentGoal, newGoal);
        double targetDelta = distance(lastPlanTargetX, lastPlanTargetZ, target.posX, target.posZ);

        if (goalDelta >= GOAL_REPLAN_DISTANCE) return true;
        if (targetDelta >= TARGET_MOVE_REPLAN_DISTANCE && pathPointsTowardTarget(self, target)) return true;
        if (ticksSinceReplan >= MAX_PATH_AGE_TICKS) return true;
        return !isUsable(world, self, currentPath);
    }

    private boolean isUsable(World world, EntityPlayerSP self, Path path) {
        if (path == null || path.isFinished()) return false;
        int idx = path.getIndex();
        List<PathNode> nodes = path.getNodes();
        if (idx >= nodes.size()) return false;

        // Validate only the next few nodes. Validating the whole route every
        // tick makes combat navigation unnecessarily brittle and expensive.
        int end = Math.min(nodes.size(), idx + 4);
        for (int i = idx; i < end; i++) {
            BlockPos p = new BlockPos(nodes.get(i).x, nodes.get(i).y, nodes.get(i).z);
            if (!canOccupy(world, p)) return false;
            if (i > idx) {
                PathNode prev = nodes.get(i - 1);
                BlockPos from = new BlockPos(prev.x, prev.y, prev.z);
                if (!transitionClear(world, from, p)) return false;
            }
        }
        return true;
    }

    private boolean pathPointsTowardTarget(EntityPlayerSP self, EntityPlayer target) {
        PathNode n = currentPath == null ? null : currentPath.current();
        if (n == null) return false;
        double pathX = n.x + 0.5D - self.posX;
        double pathZ = n.z + 0.5D - self.posZ;
        double targetX = target.posX - self.posX;
        double targetZ = target.posZ - self.posZ;
        double a = Math.sqrt(pathX * pathX + pathZ * pathZ);
        double b = Math.sqrt(targetX * targetX + targetZ * targetZ);
        if (a < 0.001D || b < 0.001D) return false;
        return (pathX * targetX + pathZ * targetZ) / (a * b) > 0.15D;
    }

    private Path findPath(World world, EntityPlayerSP self, BlockPos goal, int maxNodes) {
        BlockPos start = nearestStandable(world, new BlockPos(self.posX, self.posY, self.posZ), 2);
        BlockPos end = nearestStandable(world, goal, 2);
        if (start == null || end == null) return null;

        final Map<String, PathNode> nodes = new HashMap<String, PathNode>();
        final Map<String, Double> best = new HashMap<String, Double>();
        final Set<String> closed = new HashSet<String>();
        final PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(256, new Comparator<PathNode>() {
            @Override public int compare(PathNode a, PathNode b) {
                int c = Double.compare(a.fCost(), b.fCost());
                if (c != 0) return c;
                c = Double.compare(a.hCost, b.hCost);
                return c != 0 ? c : a.key().compareTo(b.key());
            }
        });

        PathNode s = getNode(nodes, start);
        s.gCost = 0.0D;
        s.hCost = heuristic(start, end);
        best.put(s.key(), 0.0D);
        open.add(s);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            PathNode cur = open.poll();
            Double known = best.get(cur.key());
            if (known != null && cur.gCost > known + 1.0E-6D) continue;
            if (!closed.add(cur.key())) continue;
            expanded++;

            if (same(cur, end)) {
                lastExpandedNodes = expanded;
                return buildPath(cur);
            }

            BlockPos from = new BlockPos(cur.x, cur.y, cur.z);
            int prevDx = cur.parent == null ? 0 : Integer.signum(cur.x - cur.parent.x);
            int prevDz = cur.parent == null ? 0 : Integer.signum(cur.z - cur.parent.z);

            for (int[] dir : DIRS) {
                int dx = dir[0], dz = dir[1];
                BlockPos next = findTransition(world, from, dx, dz);
                if (next == null) continue;
                if (dx != 0 && dz != 0 && !diagonalClear(world, from, dx, dz)) continue;
                if (Math.abs(next.getX() - start.getX()) > 24) continue;
                if (Math.abs(next.getZ() - start.getZ()) > 24) continue;
                if (Math.abs(next.getY() - start.getY()) > 8) continue;

                String key = key(next);
                if (closed.contains(key)) continue;
                double g = cur.gCost + edgeCost(world, from, next, prevDx, prevDz, end);
                Double old = best.get(key);
                if (old == null || g < old - 1.0E-6D) {
                    PathNode n = getNode(nodes, next);
                    n.parent = cur;
                    n.gCost = g;
                    n.hCost = heuristic(next, end);
                    best.put(key, g);
                    open.add(n);
                }
            }
        }
        lastExpandedNodes = expanded;
        return null;
    }

    private static BlockPos findTransition(World world, BlockPos from, int dx, int dz) {
        BlockPos same = from.add(dx, 0, dz);
        if (canOccupy(world, same) && transitionClear(world, from, same)) return same;

        BlockPos up = same.up();
        if (canOccupy(world, up) && transitionClear(world, from, up)) return up;

        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = same.down(drop);
            if (canOccupy(world, down) && transitionClear(world, from, down)) return down;
        }
        return null;
    }

    private static boolean transitionClear(World world, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (dy > MAX_STEP_UP || dy < -MAX_DROP) return false;
        if (!canOccupy(world, to)) return false;
        if (dy < 0) {
            for (int y = from.getY() - 1; y >= to.getY(); y--) {
                if (!clearAt(world, new BlockPos(to.getX(), y, to.getZ()))) return false;
                if (isSolidFloor(world, new BlockPos(to.getX(), y, to.getZ()))) break;
            }
        }
        return true;
    }

    private static boolean diagonalClear(World world, BlockPos from, int dx, int dz) {
        return canOccupy(world, from.add(dx, 0, 0)) && canOccupy(world, from.add(0, 0, dz));
    }

    /** Player-sized two-block clearance plus solid support. */
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

    private static BlockPos nearestStandable(World world, BlockPos p, int radius) {
        if (canOccupy(world, p)) return p;
        BlockPos best = null;
        double score = Double.POSITIVE_INFINITY;
        for (int r = 1; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos c = p.add(dx, dy, dz);
                        if (!canOccupy(world, c)) continue;
                        double s = dx * dx + dz * dz + Math.abs(dy) * 2.0D;
                        if (s < score) { score = s; best = c; }
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT2;
        double straight = Math.max(dx, dz) - Math.min(dx, dz);
        return diagonal + straight + Math.abs(a.getY() - b.getY()) * 1.35D;
    }

    private static double edgeCost(World world, BlockPos from, BlockPos to, int prevDx, int prevDz, BlockPos goal) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT2 : 1.0D;
        if (dy > 0) cost += 1.0D;
        if (dy < 0) cost += 0.35D;

        if (prevDx != 0 || prevDz != 0) {
            int dot = prevDx * dx + prevDz * dz;
            if (dot < 0) cost += 4.0D;
            else if (dot == 0) cost += 0.20D;
            else if (prevDx != dx || prevDz != dz) cost += 0.05D;
        }

        double before = horizontal(from, goal);
        double after = horizontal(to, goal);
        if (after > before + 1.0D) cost += 0.75D;
        return cost;
    }

    private static double horizontal(BlockPos a, BlockPos b) {
        return distance(a.getX(), a.getZ(), b.getX(), b.getZ());
    }

    private static double distance(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double wrapAngle(double a) {
        while (a > Math.PI) a -= Math.PI * 2.0D;
        while (a < -Math.PI) a += Math.PI * 2.0D;
        return a;
    }

    private static boolean same(PathNode n, BlockPos p) {
        return n.x == p.getX() && n.y == p.getY() && n.z == p.getZ();
    }

    private static String key(BlockPos p) { return p.getX() + ":" + p.getY() + ":" + p.getZ(); }

    private static PathNode getNode(Map<String, PathNode> nodes, BlockPos p) {
        String k = key(p);
        PathNode n = nodes.get(k);
        if (n == null) { n = new PathNode(p.getX(), p.getY(), p.getZ()); nodes.put(k, n); }
        return n;
    }

    private static Path buildPath(PathNode end) {
        List<PathNode> out = new ArrayList<PathNode>();
        PathNode cur = end;
        while (cur != null) { out.add(cur); cur = cur.parent; }
        Collections.reverse(out);
        return new Path(out);
    }

    public Path getCurrentPath() { return currentPath; }
    public BlockPos getCurrentGoal() { return currentGoal; }
    public double getPredictedTargetX() { return predictedTargetX; }
    public double getPredictedTargetZ() { return predictedTargetZ; }
    public int getLastExpandedNodes() { return lastExpandedNodes; }

    public void reset() {
        currentPath = null;
        currentGoal = null;
        ticksSinceReplan = 999;
        lastPlanTargetX = lastPlanTargetY = lastPlanTargetZ = 0.0D;
        predictedTargetX = predictedTargetZ = 0.0D;
        lastExpandedNodes = 0;
    }
}
