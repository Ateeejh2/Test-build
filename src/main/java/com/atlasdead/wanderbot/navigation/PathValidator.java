package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.List;

public class PathValidator {
    private final TerrainAnalyzer terrain = new TerrainAnalyzer();

    public boolean validate(World world, Path path, int horizon) {
        List<PathNode> nodes = path.getNodes();
        int start = path.getIndex();
        int end = Math.min(nodes.size(), start + horizon);
        for (int i = start; i < end; i++) {
            PathNode n = nodes.get(i);
            if (!PathFinder.canOccupy(world, new BlockPos(n.x, n.y, n.z))) return false;
            if (i + 1 < end) {
                PathNode b = nodes.get(i + 1);
                int dx = b.x - n.x;
                int dz = b.z - n.z;
                if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
                    if (!PathFinder.canOccupy(world, new BlockPos(n.x + dx, n.y, n.z)) ||
                            !PathFinder.canOccupy(world, new BlockPos(n.x, n.y, n.z + dz))) return false;
                }
                if (b.y < n.y && !PathFinder.isSafeDrop(world, new BlockPos(b.x, b.y, b.z), 3)) return false;
            }
            if (terrain.edgeRisk(world, new BlockPos(n.x, n.y, n.z)) > 0.98D) return false;
        }
        return true;
    }

    public boolean segmentClear(World world, double x, double y, double z, double tx, double tz, double distance) {
        double dx = tx - x;
        double dz = tz - z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) return true;
        int samples = Math.max(2, (int)Math.ceil(Math.min(8.0D, distance) * 3.0D));
        for (int i = 1; i <= samples; i++) {
            double t = i / (double)samples;
            BlockPos p = new BlockPos(x + dx * t, y, z + dz * t);
            if (!PathFinder.canOccupy(world, p)) return false;
        }
        return true;
    }
}
