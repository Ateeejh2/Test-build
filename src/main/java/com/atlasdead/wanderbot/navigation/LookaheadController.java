package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.List;

public class LookaheadController {
    public Steering compute(EntityPlayerSP player, Path path, boolean narrow) {
        List<PathNode> nodes = path.getNodes();
        int index = path.getIndex();
        if (index >= nodes.size()) return new Steering(player.posX, player.posY + player.getEyeHeight(), player.posZ, 0.0D, 0.0D, 0.0D);

        double speed = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        double look = narrow ? 1.55D : Math.min(5.2D, 2.2D + speed * 15.0D);
        if (!player.onGround) look *= 0.72D;

        PathNode anchor = nodes.get(index);
        double x = anchor.x + 0.5D;
        double z = anchor.z + 0.5D;
        double y = anchor.y + 1.0D;
        double travelled = 0.0D;
        double lastDx = 0.0D;
        double lastDz = 0.0D;

        for (int i = index; i < Math.min(nodes.size() - 1, index + 10); i++) {
            PathNode a = nodes.get(i);
            PathNode b = nodes.get(i + 1);
            double dx = b.x - a.x;
            double dz = b.z - a.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001D) continue;
            double nx = dx / len;
            double nz = dz / len;

            if (lastDx != 0.0D || lastDz != 0.0D) {
                double dot = lastDx * nx + lastDz * nz;
                if (dot < 0.25D) {
                    look = Math.min(look, Math.max(1.05D, travelled + 0.45D));
                    break;
                }
            }

            if (travelled + len >= look) {
                double t = (look - travelled) / len;
                x = a.x + 0.5D + dx * t;
                z = a.z + 0.5D + dz * t;
                y = a.y + 1.0D;
                lastDx = nx;
                lastDz = nz;
                break;
            }
            travelled += len;
            x = b.x + 0.5D;
            z = b.z + 0.5D;
            y = b.y + 1.0D;
            lastDx = nx;
            lastDz = nz;
        }

        // Velocity prediction prevents late turning while sprinting.
        double velocityBlend = Math.min(0.75D, speed * 8.0D);
        if (speed > 0.03D) {
            x += (player.motionX / speed) * velocityBlend;
            z += (player.motionZ / speed) * velocityBlend;
        }

        return new Steering(x, y, z, lastDx, lastDz, look);
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
