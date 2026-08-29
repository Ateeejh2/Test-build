package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Converts a CombatPathFinder path into desired movement inputs.
 *
 * Separates pathfinding logic from MovementController:
 *   CombatPathFinder → CombatSteering → MovementController
 *
 * Computes desiredForward, desiredStrafe, desiredSprint, desiredJump
 * based on the current path waypoint and player state.
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

    /**
     * Compute movement inputs from the current path and player state.
     */
    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (path == null || path.isFinished()) return Result.idle();

        PathNode waypoint = path.current();
        if (waypoint == null) return Result.idle();

        // Compute direction to waypoint in world space
        double dx = waypoint.x + 0.5 - self.posX;
        double dy = waypoint.y - self.posY;
        double dz = waypoint.z + 0.5 - self.posZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.1) {
            // Very close to waypoint, advance
            return new Result(false, false, 0.0F, false, false, "at-waypoint");
        }

        // Convert to local space using player yaw
        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        // Determine forward/backward
        boolean wantForward = localForward > 0.2;
        boolean wantBackward = localForward < -0.5;

        // Strafe: normalize to -1..1
        float strafeAmount = (float) MathHelper.clamp_double(localStrafe * 1.5, -1.0, 1.0);
        // Only strafe if forward component is small or we need lateral adjustment
        if (Math.abs(localForward) > 0.5) {
            strafeAmount *= 0.3F; // Reduce strafe when moving forward/backward
        }

        // Sprint: forward movement with good alignment
        boolean canSprint = self.onGround
                && localForward > 0.5
                && Math.abs(localStrafe) < 0.4
                && targetDistance > 2.0;

        // Jump: if next waypoint is higher or we need to jump over obstacle
        boolean needJump = false;
        if (dy > 0.3) {
            // Waypoint is above us
            needJump = true;
        } else if (isBlockedAhead(self, dx, dz)) {
            // Obstacle ahead
            needJump = true;
        }

        String reason = buildReason(wantForward, wantBackward, strafeAmount, canSprint, needJump, horizontalDist);

        return new Result(wantForward, wantBackward, strafeAmount, canSprint, needJump, reason);
    }

    /**
     * Check if there's an obstacle directly ahead that requires jumping.
     */
    private boolean isBlockedAhead(EntityPlayerSP self, double dx, double dz) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return false;

        // Normalize direction
        double ndx = dx / dist;
        double ndz = dz / dist;

        // Check 1-2 blocks ahead at foot and head level
        for (double probe = 0.5; probe <= 1.5; probe += 0.5) {
            int bx = (int) Math.round(self.posX + ndx * probe);
            int bz = (int) Math.round(self.posZ + ndz * probe);
            int by = (int) Math.round(self.posY);

            // Check if feet level is blocked
            if (!PathFinder.canOccupy(self.worldObj, new net.minecraft.util.BlockPos(bx, by, bz))) {
                // Check if jumping over would work (1 block up is clear)
                if (PathFinder.canOccupy(self.worldObj, new net.minecraft.util.BlockPos(bx, by + 1, bz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildReason(boolean forward, boolean backward, float strafe, boolean sprint, boolean jump, double dist) {
        StringBuilder sb = new StringBuilder();
        if (forward) sb.append("FWD");
        if (backward) sb.append("BWD");
        if (Math.abs(strafe) > 0.2) sb.append(strafe > 0 ? "STR-R" : "STR-L");
        if (sprint) sb.append("+SPRINT");
        if (jump) sb.append("+JUMP");
        sb.append(" d=").append(String.format("%.1f", dist));
        return sb.toString();
    }
}
