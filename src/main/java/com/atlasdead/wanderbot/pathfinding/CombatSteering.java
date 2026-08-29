package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;

/**
 * Converts a CombatPathFinder path into desired movement inputs.
 *
 * Important: the path contains the starting node. Steering therefore owns
 * waypoint progression; otherwise the bot can keep steering toward node 0
 * forever and appear to be completely stuck.
 */
public class CombatSteering {
    private static final double WAYPOINT_REACHED_RADIUS = 0.85D;
    private static final int MAX_ADVANCES_PER_TICK = 6;

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

        // Consume the start/current waypoint and any waypoints already reached.
        // This is intentionally done here rather than in CombatPathFinder so
        // planning remains pure and steering remains responsible for progress.
        for (int i = 0; i < MAX_ADVANCES_PER_TICK && !path.isFinished(); i++) {
            PathNode n = path.current();
            if (n == null) break;
            double dx = n.x + 0.5D - self.posX;
            double dz = n.z + 0.5D - self.posZ;
            double dy = n.y - self.posY;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal <= WAYPOINT_REACHED_RADIUS && Math.abs(dy) <= 1.35D) {
                path.advance();
            } else {
                break;
            }
        }

        if (path.isFinished()) return Result.idle();
        PathNode waypoint = path.current();
        if (waypoint == null) return Result.idle();

        double dx = waypoint.x + 0.5D - self.posX;
        double dy = waypoint.y - self.posY;
        double dz = waypoint.z + 0.5D - self.posZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.05D) return Result.idle();

        // Convert world-space waypoint direction to player-local movement.
        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        boolean wantForward = localForward > 0.10D;
        boolean wantBackward = localForward < -0.55D;

        float strafeAmount = (float)MathHelper.clamp_double(localStrafe * 1.35D, -1.0D, 1.0D);
        if (Math.abs(localForward) > 0.65D) strafeAmount *= 0.35F;

        // Sprint is based on movement direction, not target yaw. This keeps
        // combat-path steering usable when another module owns rotation.
        boolean canSprint = self.onGround
                && localForward > 0.55D
                && Math.abs(localStrafe) < 0.55D
                && targetDistance > 2.0D;

        boolean needJump = false;
        if (dy > 0.25D && horizontalDist < 2.0D) {
            needJump = true;
        } else if (isBlockedAhead(self, dx, dz)) {
            needJump = true;
        }

        String reason = buildReason(wantForward, wantBackward, strafeAmount, canSprint, needJump, horizontalDist);
        return new Result(wantForward, wantBackward, strafeAmount, canSprint, needJump, reason);
    }

    private boolean isBlockedAhead(EntityPlayerSP self, double dx, double dz) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.35D) return false;
        double ndx = dx / dist;
        double ndz = dz / dist;

        // Check the actual next two positions and only request a jump if the
        // current level is blocked while one block higher is occupiable.
        for (double probe = 0.55D; probe <= 1.35D; probe += 0.4D) {
            int bx = (int)Math.floor(self.posX + ndx * probe);
            int bz = (int)Math.floor(self.posZ + ndz * probe);
            int by = (int)Math.floor(self.posY);
            BlockPos feet = new BlockPos(bx, by, bz);
            if (!CombatPathFinder.canOccupy(self.worldObj, feet)
                    && CombatPathFinder.canOccupy(self.worldObj, feet.up())) {
                return true;
            }
        }
        return false;
    }

    private String buildReason(boolean forward, boolean backward, float strafe,
                               boolean sprint, boolean jump, double dist) {
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
