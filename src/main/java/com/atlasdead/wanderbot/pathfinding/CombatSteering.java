package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Converts the authoritative CombatPath into movement inputs.
 *
 * Steering is deterministic: there is no random rotation or random movement.
 * The controller anticipates upcoming turns, avoids unnecessary left/right
 * oscillation and eases through corners instead of snapping from checkpoint to
 * checkpoint.
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

    // Deterministic input hysteresis. This prevents a target direction that is
    // nearly centered from producing L/R/L/R key chatter every tick.
    private int lastStrafeDirection;
    private int strafeHoldTicks;

    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (self == null || path == null || path.isFinished()) {
            resetInputState();
            return Result.idle();
        }

        // Consume checkpoints as they are reached. We may skip one checkpoint
        // only when the following segment continues in essentially the same
        // direction and the player is already close to it.
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

        if (path.isFinished()) {
            resetInputState();
            return new Result(false, false, 0.0F, false, false, "path-finished");
        }

        PathNode waypoint = path.current();
        if (waypoint == null) {
            resetInputState();
            return Result.idle();
        }

        /*
         * Look ahead into the route. Close to a corner, steer toward a point
         * between the current and next checkpoint so the player begins turning
         * before reaching the corner instead of making a robotic 90-degree snap.
         */
        PathNode next = getNext(path, 1);
        PathNode nextNext = getNext(path, 2);

        double currentX = waypoint.x + 0.5D;
        double currentZ = waypoint.z + 0.5D;
        double steerX = currentX;
        double steerZ = currentZ;
        boolean anticipatingTurn = false;

        double currentDx = currentX - self.posX;
        double currentDz = currentZ - self.posZ;
        double currentDist = Math.sqrt(currentDx * currentDx + currentDz * currentDz);

        if (next != null && currentDist < 3.2D && sameHeight(waypoint, next)) {
            int inX = Integer.signum(next.x - waypoint.x);
            int inZ = Integer.signum(next.z - waypoint.z);
            int fromX = previousDirectionX(path, waypoint);
            int fromZ = previousDirectionZ(path, waypoint);
            int dot = fromX * inX + fromZ * inZ;

            if (fromX != 0 || fromZ != 0) {
                anticipatingTurn = dot < 2;
            }

            // Blend farther into the route only when doing so remains a natural
            // continuation. Sharp turns still retain the corner as the steering
            // anchor, but the blend starts before the player reaches it.
            double lookaheadWeight = anticipatingTurn ? 0.38D : 0.62D;
            if (currentDist < 1.7D) lookaheadWeight *= 0.72D;
            steerX = currentX * (1.0D - lookaheadWeight) + (next.x + 0.5D) * lookaheadWeight;
            steerZ = currentZ * (1.0D - lookaheadWeight) + (next.z + 0.5D) * lookaheadWeight;

            // On long straight runs, use one extra checkpoint to avoid tiny
            // heading changes caused by individual grid nodes.
            if (!anticipatingTurn && nextNext != null
                    && sameHeight(waypoint, next) && sameHeight(next, nextNext)) {
                steerX = steerX * 0.45D + (nextNext.x + 0.5D) * 0.55D;
                steerZ = steerZ * 0.45D + (nextNext.z + 0.5D) * 0.55D;
            }
        }

        double dx = steerX - self.posX;
        double dz = steerZ - self.posZ;
        double dy = waypoint.y - self.posY;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDist < 0.08D && Math.abs(dy) < 0.9D) {
            return new Result(false, false, 0.0F, false, false, "at-waypoint");
        }

        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        boolean wantForward = localForward > 0.04D;
        boolean wantBackward = localForward < -0.70D;

        float desiredStrafe = (float) MathHelper.clamp_double(localStrafe * 1.35D, -1.0D, 1.0D);
        if (Math.abs(localForward) > 0.78D) desiredStrafe *= 0.35F;

        float strafeAmount = applyStrafeHysteresis(desiredStrafe);

        // Human-like corner behavior without randomness: ease off sprint briefly
        // for a real turn, then resume immediately on the straight.
        boolean sharpTurn = isSharpTurn(path);
        boolean sprint = self.onGround && wantForward && !(sharpTurn && horizontalDist < 1.35D);

        boolean jump = dy > 0.30D || needsJumpForObstacle(self, dx, dz);

        String reason = buildReason(wantForward, wantBackward, strafeAmount, sprint,
                jump, horizontalDist, anticipatingTurn, sharpTurn);
        return new Result(wantForward, wantBackward, strafeAmount, sprint, jump, reason);
    }

    private PathNode getNext(Path path, int offset) {
        int index = path.getIndex() + offset;
        if (index < 0 || index >= path.getNodes().size()) return null;
        return path.getNodes().get(index);
    }

    private boolean sameHeight(PathNode a, PathNode b) {
        return a != null && b != null && a.y == b.y;
    }

    private int previousDirectionX(Path path, PathNode current) {
        int index = path.getIndex();
        if (index <= 0) return 0;
        PathNode previous = path.getNodes().get(index - 1);
        return Integer.signum(current.x - previous.x);
    }

    private int previousDirectionZ(Path path, PathNode current) {
        int index = path.getIndex();
        if (index <= 0) return 0;
        PathNode previous = path.getNodes().get(index - 1);
        return Integer.signum(current.z - previous.z);
    }

    private boolean isSharpTurn(Path path) {
        PathNode current = path.current();
        PathNode next = getNext(path, 1);
        PathNode nextNext = getNext(path, 2);
        if (current == null || next == null || nextNext == null) return false;
        if (current.y != next.y || next.y != nextNext.y) return true;

        int ax = Integer.signum(next.x - current.x);
        int az = Integer.signum(next.z - current.z);
        int bx = Integer.signum(nextNext.x - next.x);
        int bz = Integer.signum(nextNext.z - next.z);
        return ax != bx || az != bz;
    }

    private float applyStrafeHysteresis(float desired) {
        int desiredDirection = desired > 0.20F ? 1 : (desired < -0.20F ? -1 : 0);

        if (strafeHoldTicks > 0) strafeHoldTicks--;

        if (desiredDirection == 0) {
            if (strafeHoldTicks == 0) lastStrafeDirection = 0;
        } else if (lastStrafeDirection == 0) {
            lastStrafeDirection = desiredDirection;
            strafeHoldTicks = 2;
        } else if (desiredDirection != lastStrafeDirection) {
            // Require a meaningful opposite request before swapping sides.
            if (Math.abs(desired) >= 0.48F || strafeHoldTicks == 0) {
                lastStrafeDirection = desiredDirection;
                strafeHoldTicks = 2;
            }
        }

        if (lastStrafeDirection == 0) return 0.0F;
        return lastStrafeDirection * Math.min(1.0F, Math.max(0.22F, Math.abs(desired)));
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

    private String buildReason(boolean forward, boolean backward, float strafe,
                               boolean sprint, boolean jump, double dist,
                               boolean anticipatingTurn, boolean sharpTurn) {
        StringBuilder sb = new StringBuilder();
        if (forward) sb.append("FWD");
        if (backward) sb.append("BWD");
        if (Math.abs(strafe) > 0.2F) sb.append(strafe > 0 ? "STR-R" : "STR-L");
        if (sprint) sb.append("+SPRINT");
        if (jump) sb.append("+JUMP");
        if (anticipatingTurn) sb.append("+LOOKAHEAD");
        if (sharpTurn) sb.append("+CORNER");
        sb.append(" d=").append(String.format("%.1f", dist));
        return sb.toString();
    }

    private void resetInputState() {
        lastStrafeDirection = 0;
        strafeHoldTicks = 0;
    }
}
