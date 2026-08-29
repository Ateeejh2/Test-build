package com.atlasdead.wanderbot.navigation;

import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Path-aware local steering.
 *
 * The A* path is authoritative about obstacle avoidance.  This controller no
 * longer rotates the desired vector into an unrelated candidate direction,
 * because doing so made the actual movement diverge from the rendered path.
 * It only reports the requested path direction as the movement direction.
 */
public class LocalAvoidanceController {
    private Result cachedResult;

    public void invalidate() {
        cachedResult = null;
    }

    public void tick() {
        // No time-based steering cache is required anymore.
    }

    public Result choose(World world, BlockPos base, double desiredX, double desiredZ) {
        double len = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (len < 0.001D) return new Result(0.0D, 0.0D, false, 0.0D);

        double x = desiredX / len;
        double z = desiredZ / len;
        cachedResult = new Result(x, z, false, 0.0D);
        return cachedResult;
    }

    public static class Result {
        public final double x, z;
        public final boolean avoiding;
        public final double deviation;

        public Result(double x, double z, boolean avoiding, double deviation) {
            this.x = x;
            this.z = z;
            this.avoiding = avoiding;
            this.deviation = deviation;
        }
    }
}
