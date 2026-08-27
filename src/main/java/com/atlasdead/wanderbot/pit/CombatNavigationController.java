package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Combat-specific navigation layer.
 *
 * It converts target motion + desired melee spacing into a movement vector,
 * then filters that vector through the existing terrain/obstacle safety model.
 * It does not inject packets or alter server validation.
 */
public final class CombatNavigationController {
    public static final class Result {
        public final double x;
        public final double z;
        public final boolean blocked;
        public final boolean cliffRisk;
        public final boolean detoured;
        public final double targetMotionBias;
        public final double distanceBias;
        public final String reason;

        Result(double x, double z, boolean blocked, boolean cliffRisk, boolean detoured,
               double targetMotionBias, double distanceBias, String reason) {
            this.x = x;
            this.z = z;
            this.blocked = blocked;
            this.cliffRisk = cliffRisk;
            this.detoured = detoured;
            this.targetMotionBias = targetMotionBias;
            this.distanceBias = distanceBias;
            this.reason = reason;
        }
    }

    private final LocalAvoidanceController avoidance = new LocalAvoidanceController();
    private final TerrainAnalyzer terrain = new TerrainAnalyzer();

    public Result compute(World world, EntityPlayerSP self, EntityPlayer target,
                          CombatTacticalModel.State tactical, double distance) {
        if (world == null || self == null || target == null) {
            return new Result(0.0D, 0.0D, true, false, false, 0.0D, 0.0D, "missing-context");
        }

        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) {
            return new Result(0.0D, 0.0D, false, false, false, 0.0D, 0.0D, "overlap");
        }
        dx /= horizontal;
        dz /= horizontal;

        double desired = tactical == null ? 2.45D : tactical.preferredDistance;
        double distanceError = distance - desired;
        double distanceGain = clamp(distanceError / 2.0D, -1.0D, 1.0D);

        // Lead the chase slightly in the target's current motion direction so
        // the navigation target moves with the opponent instead of trailing it.
        double targetMotionX = target.motionX;
        double targetMotionZ = target.motionZ;
        double targetSpeed = Math.sqrt(targetMotionX * targetMotionX + targetMotionZ * targetMotionZ);
        if (targetSpeed > 0.001D) {
            targetMotionX /= targetSpeed;
            targetMotionZ /= targetSpeed;
        }
        double motionWeight = clamp(targetSpeed * 0.55D, 0.0D, 0.45D);

        // Tangential/orbit component. This keeps the movement from becoming a
        // straight-line collision when we are inside the preferred combat range.
        double tangentX = -dz;
        double tangentZ = dx;
        int side = tactical == null ? 1 : tactical.strafeSign;
        double orbitWeight = Math.abs(distanceError) < 0.75D ? 0.48D : 0.25D;
        if (tactical != null && tactical.targetRetreating) orbitWeight += 0.08D;

        double moveX = dx * (0.70D * distanceGain + 0.20D)
                + targetMotionX * motionWeight
                + tangentX * orbitWeight * side;
        double moveZ = dz * (0.70D * distanceGain + 0.20D)
                + targetMotionZ * motionWeight
                + tangentZ * orbitWeight * side;

        double len = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (len < 0.001D) {
            moveX = targetMotionX * 0.35D + tangentX * 0.20D * side;
            moveZ = targetMotionZ * 0.35D + tangentZ * 0.20D * side;
            len = Math.sqrt(moveX * moveX + moveZ * moveZ);
        }
        if (len > 0.001D) {
            moveX /= len;
            moveZ /= len;
        }

        BlockPos base = new BlockPos(self.posX, self.posY, self.posZ);
        boolean cliffRisk = terrain.predictedCliff(world, base, moveX, moveZ, 2.6D);
        LocalAvoidanceController.Result filtered = avoidance.choose(world, base, moveX, moveZ);
        boolean filteredSafe = !cliffRisk && PathFinder.canOccupy(world, base.add((int)Math.round(filtered.x), 0, (int)Math.round(filtered.z)));

        if (!filteredSafe) {
            // Fall back to a direct safe cardinal/diagonal vector instead of
            // forcing the combat controller to push into an unsafe block.
            Candidate safe = findFallback(world, base, dx, dz, side);
            if (safe != null) {
                return new Result(safe.x, safe.z, false, cliffRisk, true,
                        motionWeight, distanceGain, "safe-detour");
            }
            return new Result(0.0D, 0.0D, true, cliffRisk, true,
                    motionWeight, distanceGain, "no-safe-combat-route");
        }

        return new Result(filtered.x, filtered.z, false, cliffRisk,
                filtered.avoiding, motionWeight, distanceGain,
                filtered.avoiding ? "local-avoidance" : "direct-combat-route");
    }

    private Candidate findFallback(World world, BlockPos base, double towardX, double towardZ, int side) {
        double[] angles = {0.0D, 25.0D, -25.0D, 45.0D, -45.0D, 70.0D, -70.0D, 90.0D * side};
        for (double angle : angles) {
            double rad = Math.toRadians(angle);
            double x = towardX * Math.cos(rad) - towardZ * Math.sin(rad);
            double z = towardX * Math.sin(rad) + towardZ * Math.cos(rad);
            if (terrain.predictedCliff(world, base, x, z, 2.0D)) continue;
            BlockPos probe = base.add((int)Math.round(x), 0, (int)Math.round(z));
            if (!PathFinder.canOccupy(world, probe) || !PathFinder.isSafeDrop(world, probe, 2)) continue;
            return new Candidate(x, z);
        }
        return null;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class Candidate {
        final double x;
        final double z;
        Candidate(double x, double z) {
            this.x = x;
            this.z = z;
        }
    }
}
