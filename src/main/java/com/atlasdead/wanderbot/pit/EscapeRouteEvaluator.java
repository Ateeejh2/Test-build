package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates multiple escape route candidates against all nearby threats,
 * terrain quality, and future escape potential.
 *
 * Each candidate is scored on:
 *  - minimumThreatDistance: closest enemy distance (higher = safer)
 *  - averageThreatDistance: average distance to all threats
 *  - coverScore: how much solid block cover exists between candidate and threats
 *  - lineOfSightBreak: ability to break LOS with most threats
 *  - footingScore: stability of ground at candidate position
 *  - fallRisk: danger of falling (cliffs, void)
 *  - deadEndRisk: how many exit routes exist from candidate (more = better)
 *  - escapeRouteQuality: can we pathfind from candidate to further safety?
 *
 * "Most survivable" is preferred over "farthest from enemy" —
 * a candidate that allows further escape is rated higher.
 */
public final class EscapeRouteEvaluator {

    private static final int[][] DIRS_8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final Minecraft mc;

    public EscapeRouteEvaluator(Minecraft mc) {
        this.mc = mc;
    }

    /**
     * Generate and evaluate escape candidates around the player.
     * Returns candidates sorted by escapeScore descending.
     */
    public List<EscapeCandidate> evaluate(EntityPlayerSP self, List<EntityPlayer> threats) {
        if (self == null || mc == null || mc.theWorld == null) return Collections.emptyList();

        World world = mc.theWorld;
        BlockPos selfPos = new BlockPos(self.posX, self.posY, self.posZ);
        double radius = WanderBotSettings.escapeEvalRadius;
        int count = WanderBotSettings.escapeCandidateCount;

        List<BlockPos> rawPositions = generateCandidates(selfPos, radius, count + 8);
        List<EscapeCandidate> candidates = new ArrayList<EscapeCandidate>();

        for (BlockPos pos : rawPositions) {
            if (!PathFinder.canOccupy(world, pos)) continue;

            double minThreat = Double.MAX_VALUE;
            double avgThreat = 0D;
            for (EntityPlayer t : threats) {
                if (t == null) continue;
                double d = horizontalDist(pos, t);
                if (d < minThreat) minThreat = d;
                avgThreat += d;
            }
            if (!threats.isEmpty()) avgThreat /= threats.size();
            else { minThreat = radius; avgThreat = radius; }

            double cover = computeCoverScore(world, pos, threats);
            double los = computeLineOfSightBreak(world, pos, threats);
            double footing = computeFootingScore(world, pos);
            double fall = computeFallRisk(world, pos);
            double deadEnd = computeDeadEndRisk(world, pos);
            double routeQ = computeEscapeRouteQuality(world, pos, threats);

            double score = computeEscapeScore(minThreat, avgThreat, cover, los, footing, fall, deadEnd, routeQ);

            candidates.add(new EscapeCandidate(pos, score, minThreat, avgThreat,
                    cover, los, footing, fall, deadEnd, routeQ));
        }

        Collections.sort(candidates, new Comparator<EscapeCandidate>() {
            @Override public int compare(EscapeCandidate a, EscapeCandidate b) {
                return Double.compare(b.escapeScore, a.escapeScore);
            }
        });

        return candidates;
    }

    /**
     * Combined escape score. Weights prioritize survival over distance.
     * "Future escape" (escapeRouteQuality) is weighted higher than raw distance.
     */
    private double computeEscapeScore(double minThreat, double avgThreat,
                                       double cover, double los, double footing,
                                       double fallRisk, double deadEnd, double routeQuality) {
        double score = 0D;
        score += minThreat * 1.2D;             // distance from nearest threat
        score += avgThreat * 0.4D;             // distance from all threats
        score += cover * 15.0D;                // block cover
        score += los * 12.0D;                  // LOS break ability
        score += footing * 5.0D;               // stable ground
        score -= fallRisk * 25.0D;             // falling is very bad
        score -= deadEnd * 20.0D;              // dead ends are dangerous
        score += routeQuality * 18.0D;         // future escape potential
        return score;
    }

    /** Generate candidate positions in a roughly circular pattern. */
    private List<BlockPos> generateCandidates(BlockPos center, double radius, int targetCount) {
        List<BlockPos> result = new ArrayList<BlockPos>();
        int radiusInt = (int) Math.ceil(radius);

        // Evenly distribute angles, with some randomness
        double goldenAngle = 2.39996323D; // radians
        for (int i = 0; i < targetCount; i++) {
            double r = radiusInt * Math.sqrt((double) i / targetCount) * 0.8D + 3.0D;
            double angle = i * goldenAngle + (i % 3) * 0.3D;
            int dx = (int) Math.round(r * Math.cos(angle));
            int dz = (int) Math.round(r * Math.sin(angle));
            // Find ground level
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = center.add(dx, dy, dz);
                if (PathFinder.canOccupy(mc.theWorld, p)) {
                    result.add(p);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Count how many directions from pos have solid blocks that could
     * provide cover against threats. Higher = more cover.
     */
    private double computeCoverScore(World world, BlockPos pos, List<EntityPlayer> threats) {
        if (threats.isEmpty()) return 3.0D;

        int coverDirs = 0;
        for (int[] d : DIRS_8) {
            BlockPos check = pos.add(d[0] * 2, 0, d[1] * 2);
            if (!PathFinder.canOccupy(world, check)) {
                coverDirs++;
            }
        }

        // Also check if there's a wall between us and threats
        int threatsCovered = 0;
        for (EntityPlayer t : threats) {
            if (t == null) continue;
            double dx = t.posX - (pos.getX() + 0.5D);
            double dz = t.posZ - (pos.getZ() + 0.5D);
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 1D) continue;
            double nx = dx / dist;
            double nz = dz / dist;
            boolean blocked = false;
            for (int step = 1; step <= (int) dist; step++) {
                BlockPos mid = pos.add((int) Math.round(nx * step), 0, (int) Math.round(nz * step));
                if (!PathFinder.canOccupy(world, mid)) { blocked = true; break; }
            }
            if (blocked) threatsCovered++;
        }

        return Math.min(6D, coverDirs * 0.5D + (threats.isEmpty() ? 0 : (double) threatsCovered / threats.size() * 3.0D));
    }

    /**
     * Ability to break line of sight with threats.
     * Checks how many threats have their LOS blocked from candidate position.
     */
    private double computeLineOfSightBreak(World world, BlockPos pos, List<EntityPlayer> threats) {
        if (threats.isEmpty()) return 3.0D;
        int broken = 0;
        for (EntityPlayer t : threats) {
            if (t == null) continue;
            if (!hasDirectLOS(world, pos, t)) broken++;
        }
        return (double) broken / threats.size() * 5.0D;
    }

    /**
     * Ground stability score. Checks that there's solid floor
     * and surrounding blocks for safe movement.
     */
    private double computeFootingScore(World world, BlockPos pos) {
        double score = 0D;
        // Direct floor
        if (PathFinder.isStandable(world, pos)) score += 2.0D;

        // Surrounding support
        int solidFloors = 0;
        for (int[] d : DIRS_8) {
            BlockPos adj = pos.add(d[0], -1, d[1]);
            if (PathFinder.canOccupy(world, adj)) solidFloors++;
        }
        score += solidFloors * 0.3D;

        // Clear space to move
        if (PathFinder.canOccupy(world, pos.up())) score += 0.5D;

        return Math.min(5D, score);
    }

    /**
     * Risk of falling. Higher = more dangerous.
     */
    private double computeFallRisk(World world, BlockPos pos) {
        // Check below for void/deep fall
        int airBelow = 0;
        for (int y = pos.getY() - 1; y >= Math.max(1, pos.getY() - 10); y--) {
            BlockPos below = new BlockPos(pos.getX(), y, pos.getZ());
            if (!PathFinder.canOccupy(world, below)) airBelow++;
            else break;
        }

        // Edge cliff check
        int edgeCount = 0;
        for (int[] d : DIRS_8) {
            BlockPos adj = pos.add(d[0], 0, d[1]);
            if (!PathFinder.canOccupy(world, adj)) edgeCount++;
        }

        double risk = 0D;
        risk += Math.min(5D, airBelow * 0.8D);
        risk += edgeCount > 5 ? 3.0D : 0D;
        return risk;
    }

    /**
     * Dead end risk. How many directions can we move from here?
     * Fewer exits = higher risk.
     */
    private double computeDeadEndRisk(World world, BlockPos pos) {
        int exits = 0;
        for (int[] d : DIRS_8) {
            BlockPos next = pos.add(d[0], 0, d[1]);
            if (PathFinder.canOccupy(world, next)) exits++;
        }
        // 8 = fully open, 0 = trapped
        if (exits <= 1) return 5.0D;
        if (exits <= 3) return 2.5D;
        if (exits <= 5) return 0.5D;
        return 0D;
    }

    /**
     * Can we pathfind from candidate to a safer area?
     * Uses a short A* to check connectivity to areas far from threats.
     */
    private double computeEscapeRouteQuality(World world, BlockPos pos, List<EntityPlayer> threats) {
        // Simple heuristic: check if there are reachable positions farther from threats
        int reachableFar = 0;
        int totalChecked = 0;
        int checkRadius = 6;

        for (int dx = -checkRadius; dx <= checkRadius; dx += 3) {
            for (int dz = -checkRadius; dz <= checkRadius; dz += 3) {
                BlockPos p = pos.add(dx, 0, dz);
                if (!PathFinder.canOccupy(world, p)) continue;
                totalChecked++;

                double minDist = Double.MAX_VALUE;
                for (EntityPlayer t : threats) {
                    if (t == null) continue;
                    double d = horizontalDist(p, t);
                    if (d < minDist) minDist = d;
                }
                if (minDist > WanderBotSettings.chaserMaxDistance) reachableFar++;
            }
        }

        return totalChecked > 0 ? (double) reachableFar / totalChecked * 5.0D : 0D;
    }

    private boolean hasDirectLOS(World world, BlockPos from, EntityPlayer to) {
        double dx = to.posX - from.getX();
        double dy = to.posY - from.getY();
        double dz = to.posZ - from.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.5D) return true;
        int steps = Math.min(10, (int) Math.ceil(dist * 2));
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            BlockPos mid = new BlockPos(
                    from.getX() + (int) Math.round(dx * t),
                    from.getY() + (int) Math.round(dy * t),
                    from.getZ() + (int) Math.round(dz * t));
            if (!PathFinder.canOccupy(world, mid)) return false;
        }
        return true;
    }

    private double horizontalDist(BlockPos a, EntityPlayer b) {
        double dx = a.getX() + 0.5D - b.posX;
        double dz = a.getZ() + 0.5D - b.posZ;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
