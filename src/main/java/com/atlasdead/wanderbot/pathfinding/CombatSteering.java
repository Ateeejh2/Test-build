package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Converts the authoritative CombatPath into movement inputs.
 * Movement, jump decisions, and waypoint progression are based on the same
 * active path so the rendered route and actual steering stay synchronized.
 */
public class CombatSteering {
    public static final class Result {
        public final boolean forward;
        public final boolean backward;
        public final float strafe;
        public final boolean sprint;
        public final boolean jump;
        public final String reason;

        Result(boolean forward, boolean backward, float strafe, boolean sprint, boolean jump, String reason) {
            this.forward = forward;
            this.backward = backward;
            this.strafe = strafe;
            this.sprint = sprint;
            this.jump = jump;
            this.reason = reason;
        }

        public static Result idle() {
            return new Result(false, false, 0.0F, false, false, "no-path");
        }
    }

    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (self == null || path == null || path.isFinished()) return Result.idle();

        // Consume checkpoints as they are reached. A small amount of lookahead
        // is allowed only when the next checkpoint continues in nearly the same
        // direction; turn checkpoints and height transitions remain intact.
        while (!path.isFinished()) {
            PathNode current = path.current();
            if (current == null) break;

            double cdx = current.x + 0.5D - self.posX;
            double cdz = current.z + 0.5D - self.posZ;
            double horizontal = Math.sqrt(cdx * cdx + cdz * cdz);
            double vertical = Math.abs(self.posY - current.y);

            if (horizontal <= 0.72D && vertical <= 1.30D) {
                path.advance();
            } else if (horizontal <= 1.05D && vertical <= 1.30D && hasStraightLookahead(path)) {
                path.advance();
            } else {
                break;
            }
        }

        if (path.isFinished()) return new Result(false, false, 0.0F, false, false, "path-finished");

        PathNode waypoint = path.current();
        if (waypoint == null) return Result.idle();

        double dx = waypoint.x + 0.5D - self.posX;
        double dz = waypoint.z + 0.5D - self.posZ;
        double dy = waypoint.y - self.posY;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.08D && Math.abs(dy) < 0.9D) {
            return new Result(false, false, 0.0F, false, false, "at-waypoint");
        }

        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        boolean wantForward = localForward > 0.05D;
        boolean wantBackward = localForward < -0.55D;
        float strafeAmount = (float) MathHelper.clamp_double(localStrafe * 1.55D, -1.0D, 1.0D);
        if (Math.abs(localForward) > 0.70D) strafeAmount *= 0.20F;

        boolean sprint = self.onGround && wantForward;
        boolean jump = dy > 0.30D || needsJumpForObstacle(self, dx, dz);

        String reason = buildReason(wantForward, wantBackward, strafeAmount, sprint, jump, horizontalDist);
        return new Result(wantForward, wantBackward, strafeAmount, sprint, jump, reason);
    }

    private boolean hasStraightLookahead(Path path) {
        int index = path.getIndex();
        if (index + 2 >= path.getNodes().size()) return false;

        PathNode a = path.getNodes().get(index);
        PathNode b = path.getNodes().get(index + 1);
        PathNode c = path.getNodes().get(index + 2);

        int abx = Integer.signum(b.x - a.x);
        int abz = Integer.signum(b.z - a.z);
        int bcx = Integer.signum(c.x - b.x);
        int bcz = Integer.signum(c.z - b.z);
        int aby = Integer.signum(b.y - a.y);
        int bcy = Integer.signum(c.y - b.y);

        if (aby != bcy) return false;
        if (abx == bcx && abz == bcz) return true;
        return abx * bcx + abz * bcz >= 1;
    }

    private boolean needsJumpForObstacle(EntityPlayerSP self, double dx, double dz) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.35D) return false;

        double ndx = dx / dist;
        double ndz = dz / dist;
        int baseY = MathHelper.floor_double(self.posY + 0.05D);

        for (double probe = 0.45D; probe <= 1.35D; probe += 0.30D) {
            int bx = MathHelper.floor_double(self.posX + ndx * probe);
            int bz = MathHelper.floor_double(self.posZ + ndz * probe);
            BlockPos feet = new BlockPos(bx, baseY, bz);
            BlockPos head = feet.up();
            BlockPos landingFeet = feet.up();
            BlockPos landingHead = feet.up(2);

            if (!isClear(self.worldObj, feet)
                    && isClear(self.worldObj, landingFeet)
                    && isClear(self.worldObj, landingHead)
                    && isSolidFloor(self.worldObj, feet)) {
                return true;
            }

            if (!isClear(self.worldObj, head)
                    && isClear(self.worldObj, landingHead)) {
                return true;
            }
        }
        return false;
    }

    private boolean isClear(World world, BlockPos pos) {
        if (world == null) return false;
        Block block = world.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || block.isAir(world, pos)) return true;
        if (material.isLiquid()) return false;
        return !material.blocksMovement();
    }

    private boolean isSolidFloor(World world, BlockPos pos) {
        if (world == null || pos.getY() <= 0 || pos.getY() >= world.getHeight()) return false;
        Block block = world.getBlockState(pos).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isAir(world, pos)) return false;
        return block.isOpaqueCube() || block.isFullBlock() || material.blocksMovement();
    }

    private String buildReason(boolean forward, boolean backward, float strafe, boolean sprint, boolean jump, double dist) {
        StringBuilder sb = new StringBuilder();
        if (forward) sb.append("FWD");
        if (backward) sb.append("BWD");
        if (Math.abs(strafe) > 0.2F) sb.append(strafe > 0 ? "STR-R" : "STR-L");
        if (sprint) sb.append("+SPRINT");
        if (jump) sb.append("+JUMP");
        sb.append(" d=").append(String.format("%.1f", dist));
        return sb.toString();
    }
}
