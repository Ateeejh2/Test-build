package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

public class TerrainAnalyzer {
    public boolean safe(World world, BlockPos pos) {
        return PathFinder.canOccupy(world, pos) && edgeRisk(world, pos) < 0.85D;
    }

    public double edgeRisk(World world, BlockPos pos) {
        int safe = 0;
        int total = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                total++;
                if (PathFinder.canOccupy(world, pos.add(dx, 0, dz))) safe++;
            }
        }
        return 1.0D - (safe / (double)total);
    }

    public boolean narrow(World world, BlockPos pos) {
        return PathFinder.localOpenSpace(world, pos) <= 2;
    }

    public boolean safeForward(World world, BlockPos pos, int dx, int dz) {
        BlockPos p = pos.add(dx, 0, dz);
        if (!PathFinder.canOccupy(world, p)) return false;
        return PathFinder.isSafeDrop(world, p, 3);
    }

    public int safeForwardLength(World world, BlockPos pos, int dx, int dz, int max) {
        int result = 0;
        BlockPos p = pos;
        for (int i = 0; i < max; i++) {
            p = p.add(dx, 0, dz);
            if (!safeForward(world, p, 0, 0)) break;
            result++;
        }
        return result;
    }

    public boolean predictedCliff(World world, BlockPos pos, double dirX, double dirZ, double distance) {
        int steps = Math.max(1, (int)Math.ceil(distance));
        for (int i = 1; i <= steps; i++) {
            int x = pos.getX() + (int)Math.round(dirX * i);
            int z = pos.getZ() + (int)Math.round(dirZ * i);
            BlockPos p = new BlockPos(x, pos.getY(), z);
            if (!PathFinder.canOccupy(world, p)) return true;
            if (!PathFinder.isSafeDrop(world, p, 2)) return true;
        }
        return false;
    }
}
