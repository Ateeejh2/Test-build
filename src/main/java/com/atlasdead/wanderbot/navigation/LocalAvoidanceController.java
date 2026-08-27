package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

public class LocalAvoidanceController {
    private final TerrainAnalyzer terrain = new TerrainAnalyzer();

    public Result choose(World world, BlockPos base, double desiredX, double desiredZ) {
        double desiredLen = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (desiredLen < 0.001D) return new Result(0.0D, 0.0D, false, 0.0D);
        desiredX /= desiredLen;
        desiredZ /= desiredLen;

        Candidate best = null;
        double[] angles = {-55, -35, -18, 0, 18, 35, 55, 80, -80, 105, -105};
        for (double angle : angles) {
            double rad = Math.toRadians(angle);
            double x = desiredX * Math.cos(rad) - desiredZ * Math.sin(rad);
            double z = desiredX * Math.sin(rad) + desiredZ * Math.cos(rad);
            double score = score(world, base, x, z, desiredX, desiredZ);
            if (best == null || score < best.score) best = new Candidate(x, z, score);
        }
        boolean avoiding = best != null && best.deviation > 0.20D;
        return best == null ? new Result(desiredX, desiredZ, false, 0.0D) : new Result(best.x, best.z, avoiding, best.deviation);
    }

    private double score(World world, BlockPos base, double x, double z, double desiredX, double desiredZ) {
        CandidateProbe p = probe(world, base, x, z);
        if (!p.safe) return 1000.0D + p.blocked * 10.0D;
        double dot = x * desiredX + z * desiredZ;
        double deviation = 1.0D - Math.max(-1.0D, Math.min(1.0D, dot));
        double cliff = terrain.predictedCliff(world, base, x, z, 3.0D) ? 20.0D : 0.0D;
        double openness = 0.0D;
        BlockPos forward = base.add((int)Math.round(x), 0, (int)Math.round(z));
        openness += (6 - PathFinder.localOpenSpace(world, forward)) * 0.7D;
        return deviation * 4.0D + cliff + openness - dot * 1.5D;
    }

    private CandidateProbe probe(World world, BlockPos base, double x, double z) {
        int blocked = 0;
        for (int i = 1; i <= 3; i++) {
            BlockPos p = base.add((int)Math.round(x * i), 0, (int)Math.round(z * i));
            if (!PathFinder.canOccupy(world, p) || !PathFinder.isSafeDrop(world, p, 2)) blocked++;
        }
        return new CandidateProbe(blocked < 2, blocked);
    }

    private static class Candidate {
        final double x, z, score, deviation;
        Candidate(double x, double z, double score) {
            this.x = x; this.z = z; this.score = score;
            this.deviation = Math.abs(Math.atan2(z, x));
        }
    }

    private static class CandidateProbe {
        final boolean safe; final int blocked;
        CandidateProbe(boolean safe, int blocked) { this.safe = safe; this.blocked = blocked; }
    }

    public static class Result {
        public final double x, z;
        public final boolean avoiding;
        public final double deviation;
        public Result(double x, double z, boolean avoiding, double deviation) {
            this.x = x; this.z = z; this.avoiding = avoiding; this.deviation = deviation;
        }
    }
}
