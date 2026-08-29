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
 * The search models the player feet position and movement transitions instead
 * of treating the world as a flat 2D grid. It accounts for clearance, support,
 * drops, turns, diagonals, local openness and edge danger.
 */
public class PathFinder {
    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final double SQRT2 = 1.4142135623730951D;
    private static final int MAX_STEP_UP = 1;
    private static final int MAX_DROP = 3;

    /* Per-search terrain query caches – cleared at the start of each findPath(). */
    private static Map<String, Integer> localOpenSpaceCache;
    private static Map<String, Integer> supportCountCache;

    private static int cachedLocalOpenSpace(World world, BlockPos p) {
        String k = key(p);
        Integer v = localOpenSpaceCache.get(k);
        if (v != null) return v;
        int count = 0;
        for (int[] d : DIRS) {
            if (canOccupy(world, p.add(d[0], 0, d[1]))) count++;
        }
        localOpenSpaceCache.put(k, count);
        return count;
    }

    private static int cachedSupportCount(World world, BlockPos p, int radius) {
        String k = key(p) + ":s" + radius;
        Integer v = supportCountCache.get(k);
        if (v != null) return v;
        int count = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (isSolidFloor(world, p.add(dx, -1, dz))) count++;
            }
        }
        supportCountCache.put(k, count);
        return count;
    }

    public Path findPath(World world, BlockPos start, BlockPos goal, int maxDistance, int maxNodes) {
        if (world == null || start == null || goal == null) return null;

        // findStandNear() uses the cached terrain queries, so initialize the
        // per-search caches BEFORE resolving the start/goal stand positions.
        localOpenSpaceCache = new HashMap<String, Integer>();
        supportCountCache = new HashMap<String, Integer>();

        BlockPos s = findStandNear(world, start, 2);
        BlockPos g = findStandNear(world, goal, 3);
        if (s == null || g == null) return null;
        if (horizontalDistance(s, g) > maxDistance) return null;

        final Map<String, PathNode> nodes = new HashMap<String, PathNode>();
        final Map<String, Double> best = new HashMap<String, Double>();

        final Set<String> closed = new HashSet<String>();
        final PriorityQueue<PathNode> open = new PriorityQueue<PathNode>(256, new Comparator<PathNode>() {
            @Override
            public int compare(PathNode a, PathNode b) {
                int c = Double.compare(a.fCost(), b.fCost());
                if (c != 0) return c;
                c = Double.compare(a.hCost, b.hCost);
                if (c != 0) return c;
                return a.key().compareTo(b.key());
            }
        });

        PathNode startNode = node(nodes, s);
        startNode.gCost = 0.0D;
        startNode.hCost = heuristic(s, g);
        best.put(startNode.key(), startNode.gCost);
        open.add(startNode);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            expanded++;
            PathNode current = open.poll();
            Double known = best.get(current.key());
            if (known != null && current.gCost > known + 0.000001D) continue;
            if (!closed.add(current.key())) continue;

            if (same(current, g)) {
                return buildPath(current);
            }

            BlockPos cp = new BlockPos(current.x, current.y, current.z);
            int prevDx = current.parent == null ? 0 : Integer.signum(current.x - current.parent.x);
            int prevDz = current.parent == null ? 0 : Integer.signum(current.z - current.parent.z);

            for (int[] dir : DIRS) {
                int dx = dir[0];
                int dz = dir[1];
                if (dx != 0 && dz != 0 && !cornerClear(world, cp, dx, dz)) continue;

                BlockPos next = findBestDestination(world, cp, dx, dz);
                if (next == null) continue;
                if (Math.abs(next.getX() - s.getX()) > maxDistance) continue;
                if (Math.abs(next.getZ() - s.getZ()) > maxDistance) continue;
                if (Math.abs(next.getY() - s.getY()) > 8) continue;

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

    /** Two-stage planner used when a direct search is too hard on complex terrain. */
    public Path findHierarchicalPath(World world, BlockPos start, BlockPos goal, int maxDistance, int maxNodes) {
        Path direct = findPath(world, start, goal, maxDistance, maxNodes);
        if (direct != null) return direct;

        BlockPos first = new BlockPos(
                (start.getX() + goal.getX()) / 2,
                start.getY(),
                (start.getZ() + goal.getZ()) / 2
        );
        BlockPos middle = findStandNear(world, first, 6);
        if (middle == null) return null;

        Path a = findPath(world, start, middle, maxDistance, maxNodes / 2);
        if (a == null) return null;
        Path b = findPath(world, middle, goal, maxDistance, maxNodes / 2);
        if (b == null) return null;

        List<PathNode> combined = new ArrayList<PathNode>();
        combined.addAll(a.getNodes());
        List<PathNode> bn = b.getNodes();
        for (int i = 0; i < bn.size(); i++) {
            PathNode n = bn.get(i);
            if (!combined.isEmpty() && n.equals(combined.get(combined.size() - 1))) continue;
            combined.add(n);
        }
        return new Path(combined);
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

    private static String key(BlockPos p) {
        return p.getX() + ":" + p.getY() + ":" + p.getZ();
    }

    private static boolean same(PathNode n, BlockPos p) {
        return n.x == p.getX() && n.y == p.getY() && n.z == p.getZ();
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double diagonal = Math.min(dx, dz) * SQRT2;
        double straight = Math.max(dx, dz) - Math.min(dx, dz);
        double dy = Math.abs(a.getY() - b.getY());
        return diagonal + straight + dy * 1.20D;
    }

    private static BlockPos findBestDestination(World world, BlockPos from, int dx, int dz) {
        int x = from.getX() + dx;
        int z = from.getZ() + dz;
        BlockPos same = new BlockPos(x, from.getY(), z);
        if (canOccupy(world, same) && safeTransition(world, from, same)) return same;

        BlockPos up = same.up();
        if (up.getY() - from.getY() <= MAX_STEP_UP && canOccupy(world, up) && safeTransition(world, from, up)) {
            return up;
        }

        for (int drop = 1; drop <= MAX_DROP; drop++) {
            BlockPos down = same.down(drop);
            if (canOccupy(world, down) && safeTransition(world, from, down)) return down;
        }
        return null;
    }

    private static boolean safeTransition(World world, BlockPos from, BlockPos to) {
        int dy = to.getY() - from.getY();
        if (dy > MAX_STEP_UP || dy < -MAX_DROP) return false;
        if (dy < 0) {
            int fall = 0;
            for (int y = from.getY() - 1; y >= to.getY(); y--) {
                fall++;
                if (isSolidFloor(world, new BlockPos(to.getX(), y, to.getZ()))) {
                    return fall <= MAX_DROP + 1;
                }
                if (!isClearColumn(world, new BlockPos(to.getX(), y, to.getZ()))) return false;
            }
            return false;
        }
        return true;
    }

    private static boolean cornerClear(World world, BlockPos from, int dx, int dz) {
        BlockPos a = from.add(dx, 0, 0);
        BlockPos b = from.add(0, 0, dz);
        return canOccupy(world, a) && canOccupy(world, b);
    }

    public static boolean canOccupy(World world, BlockPos feet) {
        if (world == null || feet.getY() <= 1 || feet.getY() >= world.getHeight() - 3) return false;
        if (!clearAt(world, feet) || !clearAt(world, feet.up())) return false;
        if (!isSolidFloor(world, feet.down())) return false;
        return true;
    }

    public static boolean isStandable(World world, BlockPos feet) {
        return canOccupy(world, feet);
    }

    public static boolean isSafeDrop(World world, BlockPos feet, int maxDrop) {
        if (!canOccupy(world, feet)) return false;
        int depth = 0;
        for (int y = feet.getY() - 1; y >= Math.max(1, feet.getY() - maxDrop - 1); y--) {
            if (isSolidFloor(world, new BlockPos(feet.getX(), y, feet.getZ()))) return depth <= maxDrop;
            if (!clearAt(world, new BlockPos(feet.getX(), y, feet.getZ()))) return false;
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
        if (pos.getY() <= 0 || pos.getY() >= world.getHeight()) return false;
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isAir(world, pos)) return false;
        return block.isOpaqueCube() || block.isFullBlock() || material.blocksMovement();
    }

    private static boolean isClearColumn(World world, BlockPos pos) {
        return clearAt(world, pos) && clearAt(world, pos.up());
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
                    double score = dx * dx + dz * dz + Math.abs(dy) * 2.5D;
                    score += dangerPenalty(world, c, cachedLocalOpenSpace(world, c)) * 0.7D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = c;
                    }
                }
            }
        }
        return best;
    }

    private static double edgeCost(World world, BlockPos from, BlockPos to, int prevDx, int prevDz, BlockPos goal, int openDirs) {
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        int dy = to.getY() - from.getY();
        double cost = (dx != 0 && dz != 0) ? SQRT2 : 1.0D;

        if (dy > 0) cost += 0.95D;
        if (dy < 0) cost += 0.18D;

        if (prevDx != 0 || prevDz != 0) {
            int dot = prevDx * dx + prevDz * dz;
            if (dot < 0) cost += 7.0D;
            else if (dot == 0) cost += 0.55D;
            else if (prevDx != dx || prevDz != dz) cost += 0.12D;
        }

        double danger = dangerPenalty(world, to, openDirs);
        cost += danger;

        if (openDirs <= 1) cost += 4.5D;
        else if (openDirs == 2) cost += 2.0D;
        else if (openDirs == 3) cost += 0.45D;
        else if (openDirs >= 7) cost -= 0.08D;

        double before = horizontalDistance(from, goal);
        double after = horizontalDistance(to, goal);
        if (after > before + 1.25D) cost += 0.35D;

        return Math.max(0.05D, cost);
    }

    private static double dangerPenalty(World world, BlockPos p, int openDirs) {
        double penalty = 0.0D;
        int support = cachedSupportCount(world, p, 1);
        if (support <= 2) penalty += 5.0D;
        else if (support <= 4) penalty += 1.0D;

        if (!isSafeDrop(world, p, MAX_DROP)) penalty += 0.0D;

        if (openDirs <= 1) penalty += 3.0D;
        else if (openDirs == 2) penalty += 0.8D;
        return penalty;
    }

    /* Original uncached version kept for external callers (TerrainAnalyzer etc). */

    public static int localOpenSpace(World world, BlockPos p) {
        int count = 0;
        for (int[] d : DIRS) {
            if (canOccupy(world, p.add(d[0], 0, d[1]))) count++;
        }
        return count;
    }

    private static Path buildPath(PathNode end) {
        List<PathNode> result = new ArrayList<PathNode>();
        PathNode cur = end;
        while (cur != null) {
            result.add(cur);
            cur = cur.parent;
        }
        Collections.reverse(result);
        return new Path(removeRedundantCollinear(result));
    }

    private static List<PathNode> removeRedundantCollinear(List<PathNode> raw) {
        if (raw.size() <= 2) return raw;
        List<PathNode> out = new ArrayList<PathNode>();
        out.add(raw.get(0));
        int lastDx = Integer.MIN_VALUE;
        int lastDz = Integer.MIN_VALUE;
        for (int i = 1; i < raw.size(); i++) {
            PathNode prev = raw.get(i - 1);
            PathNode cur = raw.get(i);
            int dx = Integer.signum(cur.x - prev.x);
            int dz = Integer.signum(cur.z - prev.z);
            if (i == raw.size() - 1 || cur.y != prev.y || (lastDx != Integer.MIN_VALUE && (dx != lastDx || dz != lastDz))) {
                out.add(prev);
            }
            lastDx = dx;
            lastDz = dz;
        }
        PathNode end = raw.get(raw.size() - 1);
        if (out.get(out.size() - 1) != end) out.add(end);
        return out;
    }
}
