package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Converts a CombatPathFinder path into desired movement inputs.
 *
 * Combat Render and movement both use Path.current() as the authoritative
 * waypoint. This class advances the shared Path index when the active
 * waypoint is reached, so the rendered path and actual movement stay in sync.
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
     * Compute movement inputs from the current combat path.
     * The path index is advanced here so rendering and movement consume the
     * exact same active waypoint.
     */
    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (path == null || path.isFinished()) return Result.idle();

        // Consume every waypoint we have actually reached before selecting the
        // next one. Use a slightly generous horizontal threshold because the
        // player occupies a volume rather than a point.
        while (!path.isFinished()) {
            PathNode current = path.current();
            if (current == null) break;

            double cdx = current.x + 0.5D - self.posX;
            double cdz = current.z + 0.5D - self.posZ;
            double horizontal = Math.sqrt(cdx * cdx + cdz * cdz);
            double vertical = Math.abs(self.posY - current.y);

            if (horizontal <= 0.60D && vertical <= 1.05D) {
                path.advance();
            } else {
                break;
            }
        }

        if (path.isFinished()) return new Result(false, false, 0.0F, false, false, "path-finished");

        PathNode waypoint = path.current();
        if (waypoint == null) return Result.idle();

        double dx = waypoint.x + 0.5D - self.posX;
        double dy = waypoint.y - self.posY;
        double dz = waypoint.z + 0.5D - self.posZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (horizontalDist < 0.08D && Math.abs(dy) < 0.9D) {
            return new Result(false, false, 0.0F, false, false, "at-waypoint");
        }

        // World-space waypoint -> player-local movement space.
        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        boolean wantForward = localForward > 0.15D;
        boolean wantBackward = localForward < -0.45D;

        float strafeAmount = (float) MathHelper.clamp_double(localStrafe * 1.4D, -1.0D, 1.0D);
        if (Math.abs(localForward) > 0.65D) {
            strafeAmount *= 0.25F;
        }

        boolean canSprint = self.onGround
                && localForward > 0.55D
                && Math.abs(localStrafe) < 0.45D
                && targetDistance > 2.0D;

        boolean needJump = false;
        if (dy > 0.30D) {
            needJump = true;
        } else if (isBlockedAhead(self, dx, dz)) {
            needJump = true;
        }

        String reason = buildReason(wantForward, wantBackward, strafeAmount, canSprint, needJump, horizontalDist);
        return new Result(wantForward, wantBackward, strafeAmount, canSprint, needJump, reason);
    }

    private boolean isBlockedAhead(EntityPlayerSP self, double dx, double dz) {
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5D) return false;

        double ndx = dx / dist;
        double ndz = dz / dist;

        for (double probe = 0.5D; probe <= 1.5D; probe += 0.5D) {
            int bx = (int) Math.round(self.posX + ndx * probe);
            int bz = (int) Math.round(self.posZ + ndz * probe);
            int by = (int) Math.round(self.posY);

            if (!PathFinder.canOccupy(self.worldObj, new net.minecraft.util.BlockPos(bx, by, bz))) {
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
        if (Math.abs(strafe) > 0.2F) sb.append(strafe > 0 ? "STR-R" : "STR-L");
        if (sprint) sb.append("+SPRINT");
        if (jump) sb.append("+JUMP");
        sb.append(" d=").append(String.format("%.1f", dist));
        return sb.toString();
    }
}
