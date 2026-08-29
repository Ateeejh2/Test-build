package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.List;

/**
 * Produces the exact world-space steering point used by normal navigation.
 * The renderer already displays PathNode positions, so navigation follows the
 * active waypoint directly instead of steering toward a separate lookahead or
 * velocity-predicted point.
 */
public class LookaheadController {
    public Steering compute(EntityPlayerSP player, Path path, boolean narrow) {
        List<PathNode> nodes = path.getNodes();
        int index = path.getIndex();
        if (index >= nodes.size()) {
            return new Steering(player.posX, player.posY + player.getEyeHeight(), player.posZ,
                    0.0D, 0.0D, 0.0D);
        }

        PathNode current = nodes.get(index);
        double x = current.x + 0.5D;
        double z = current.z + 0.5D;
        double y = current.y + 1.0D;

        double dirX = 0.0D;
        double dirZ = 0.0D;
        if (index + 1 < nodes.size()) {
            PathNode next = nodes.get(index + 1);
            double dx = next.x - current.x;
            double dz = next.z - current.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001D) {
                dirX = dx / len;
                dirZ = dz / len;
            }
        } else {
            double dx = x - player.posX;
            double dz = z - player.posZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len > 0.001D) {
                dirX = dx / len;
                dirZ = dz / len;
            }
        }

        // Keep the value meaningful for existing sprint logic without creating
        // another spatial target: report distance to the actual active node.
        double dx = x - player.posX;
        double dz = z - player.posZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        return new Steering(x, y, z, dirX, dirZ, distance);
    }

    public static class Steering {
        public final double x;
        public final double y;
        public final double z;
        public final double dirX;
        public final double dirZ;
        public final double lookahead;

        public Steering(double x, double y, double z, double dirX, double dirZ, double lookahead) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.lookahead = lookahead;
        }
    }
}
