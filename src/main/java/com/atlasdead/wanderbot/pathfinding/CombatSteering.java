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
 * Instead of chasing the exact center of every checkpoint, the controller
 * follows a small corridor around the upcoming path segment and blends through
 * corners. This keeps movement smooth while preserving the authoritative path.
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

    // Deterministic input hysteresis. This prevents a nearly centered corridor
    // target from producing L/R/L/R key chatter every tick.
    private int lastStrafeDirection;
    private int strafeHoldTicks;

    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (self == null || path == null || path.isFinished()) {
            resetInputState();
            return Result.idle();
        }

        advanceReached(path, self);
        if (path.isFinished()) {
            resetInputState();
            return new Result(false, false, 0.0F, false, false, "path-finished");
        }

        PathNode waypoint = path.current();
        if (waypoint == null) {
            resetInputState();
            return Result.idle();
        }

        // Follow the path as a corridor rather than aiming at a single block.
        // The lookahead distance grows on straight segments and shrinks near
        // corners / vertical transitions.
        SteeringPoint point = computeCorridorPoint(self, path);

        double dx = point.x - self.posX;
        double dz = point.z - self.posZ;
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

        float desiredStrafe = (float) MathHelper.clamp_double(localStrafe * 1.20D, -1.0D, 1.0D);
        if (Math.abs(localForward) > 0.78D) desiredStrafe *= 0.28F;
        float strafeAmount = applyStrafeHysteresis(desiredStrafe);

        boolean sharpTurn = isSharpTurn(path);
        boolean sprint = self.onGround && wantForward && !(sharpTurn && horizontalDist < 1.35D);
        boolean jump = dy > 0.30D || needsJumpForObstacle(self, dx, dz);

        String reason = buildReason(wantForward, wantBackward, strafeAmount, sprint,
                jump, horizontalDist, point.corridorMode, sharpTurn);
        return new Result(wantForward, wantBackward, strafeAmount, sprint, jump, reason);
    }

    /**
     * Advance monotonically. A checkpoint is considered passed when the player
     * has either reached its vicinity or crossed the checkpoint plane in the
     * direction of travel. This prevents corridor steering from making the bot
     * turn back toward an already-passed checkpoint.
     */
    private void advanceReached(Path path, EntityPlayerSP self) {
        while (!path.isFinished()) {
            PathNode current = path.current();
            if (current == null) break;

            double cdx = current.x + 0.5D - self.posX;
            double cdz = current.z + 0.5D - self.posZ;
            double horizontal = Math.sqrt(cdx * cdx + cdz * cdz);
            double vertical = Math.abs(self.posY - current.y);

            if (horizontal <= 0.72D && vertical <= 1.30D) {
                path.advance();
                continue;
            }

            if (horizontal <= 1.00D && vertical <= 1.30D && hasStraightLookahead(path)) {
                path.advance();
                continue;
            }

            if (hasPassedCheckpoint(path, self, current)) {
                path.advance();
                continue;
            }

            break;
        }
    }

    private boolean hasPassedCheckpoint(Path path, EntityPlayerSP self, PathNode current) {
        int index = path.getIndex();
        if (index <= 0) return false;

        PathNode previous = path.getNodes().get(index - 1);
        if (previous.y != current.y) return false;

        double cx = current.x + 0.5D;
        double cz = current.z + 0.5D;
        double px = previous.x + 0.5D;
        double pz = previous.z + 0.5D;

        double dirX = cx - px;
        double dirZ = cz - pz;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001D) return false;

        dirX /= len;
        dirZ /= len;

        // Signed distance beyond the checkpoint along the incoming segment.
        double fromCheckpointX = self.posX - cx;
        double fromCheckpointZ = self.posZ - cz;
        double along = fromCheckpointX * dirX + fromCheckpointZ * dirZ;
        double lateral = Math.abs(fromCheckpointX * (-dirZ) + fromCheckpointZ * dirX);

        // The player must have crossed the checkpoint plane and remain reasonably
        // close to the incoming corridor. This is intentionally independent of
        // the outgoing turn, so a corner is never skipped just because it is
        // visible around the bend.
        return along >= 0.12D && lateral <= 0.90D;
    }

    /**
     * Build a deterministic point inside the path corridor.
     *
     * On straight segments we project the player onto the segment and steer to
     * a point a little farther along it. Near a turn we blend the outgoing
     * segment so the movement arcs through the corner instead of rotating around
     * the checkpoint center.
     */
    private SteeringPoint computeCorridorPoint(EntityPlayerSP self, Path path) {
        PathNode a = path.current();
        PathNode b = getNext(path, 1);
        PathNode c = getNext(path, 2);

        double ax = a.x + 0.5D;
        double az = a.z + 0.5D;
        if (b == null || a.y != b.y) {
            return new SteeringPoint(ax, az, false);
        }

        double bx = b.x + 0.5D;
        double bz = b.z + 0.5D;
        double segX = bx - ax;
        double segZ = bz - az;
        double segLenSq = segX * segX + segZ * segZ;

        if (segLenSq < 0.001D) return new SteeringPoint(ax, az, false);

        double px = self.posX - ax;
        double pz = self.posZ - az;
        double t = (px * segX + pz * segZ) / segLenSq;
        t = Math.max(0.0D, Math.min(1.0D, t));

        // A compact corridor keeps the player near the route, but not pinned to
        // the exact centerline. This is deterministic and collision-safe because
        // the authoritative path remains unchanged.
        double corridor = 0.55D;
        double nearestX = ax + segX * t;
        double nearestZ = az + segZ * t;

        double lookAhead = 0.85D;
        double remaining = Math.sqrt(segLenSq) * (1.0D - t);
        if (remaining < 0.85D) lookAhead = Math.max(0.20D, remaining * 0.55D);

        double dirLen = Math.sqrt(segLenSq);
        double dirX = segX / dirLen;
        double dirZ = segZ / dirLen;

        double steerX = nearestX + dirX * lookAhead;
        double steerZ = nearestZ + dirZ * lookAhead;

        boolean corridorMode = true;

        // Blend into the outgoing segment before the actual corner.
        if (c != null && b.y == c.y && isTurnBetween(a, b, c)) {
            double bcx = c.x + 0.5D - bx;
            double bcz = c.z + 0.5D - bz;
            double nextLen = Math.sqrt(bcx * bcx + bcz * bcz);
            if (nextLen > 0.001D) {
                double turnDistance = 1.15D;
                double blend = 1.0D - Math.min(1.0D, remaining / turnDistance);
                blend = blend * blend * (3.0D - 2.0D * blend); // smoothstep

                double nextPointX = bx + (bcx / nextLen) * turnDistance;
                double nextPointZ = bz + (bcz / nextLen) * turnDistance;
                steerX = steerX * (1.0D - blend * 0.65D) + nextPointX * (blend * 0.65D);
                steerZ = steerZ * (1.0D - blend * 0.65D) + nextPointZ * (blend * 0.65D);
            }
        }

        // Keep the steering point inside a narrow lateral corridor around the
        // current segment so the bot does not cut corners through obstacles.
        double sideX = -dirZ;
        double sideZ = dirX;
        double lateral = (self.posX - nearestX) * sideX + (self.posZ - nearestZ) * sideZ;
        double correctedLateral = MathHelper.clamp_double(lateral, -corridor, corridor);
        steerX += (correctedLateral - lateral) * sideX * 0.70D;
        steerZ += (correctedLateral - lateral) * sideZ * 0.70D;

        return new SteeringPoint(steerX, steerZ, corridorMode);
    }

    private boolean isTurnBetween(PathNode a, PathNode b, PathNode c) {
        int abx = Integer.signum(b.x - a.x);
        int abz = Integer.signum(b.z - a.z);
        int bcx = Integer.signum(c.x - b.x);
        int bcz = Integer.signum(c.z - b.z);
        return abx != bcx || abz != bcz;
    }

    private PathNode getNext(Path path, int offset) {
        int index = path.getIndex() + offset;
        if (index < 0 || index >= path.getNodes().size()) return null;
        return path.getNodes().get(index);
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
            if (Math.abs(desired) >= 0.48F || strafeHoldTicks == 0) {
                lastStrafeDirection = desiredDirection;
                strafeHoldTicks = 2;
            }
        }

        if (lastStrafeDirection == 0) return 0.0F;
        return lastStrafeDirection * Math.min(1.0F, Math.max(0.22F, Math.abs(desired)));
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
                               boolean corridorMode, boolean sharpTurn) {
        StringBuilder sb = new StringBuilder();
        if (forward) sb.append("FWD");
        if (backward) sb.append("BWD");
        if (Math.abs(strafe) > 0.2F) sb.append(strafe > 0 ? "STR-R" : "STR-L");
        if (sprint) sb.append("+SPRINT");
        if (jump) sb.append("+JUMP");
        if (corridorMode) sb.append("+CORRIDOR");
        if (sharpTurn) sb.append("+CORNER");
        sb.append(" d=").append(String.format("%.1f", dist));
        return sb.toString();
    }

    private void resetInputState() {
        lastStrafeDirection = 0;
        strafeHoldTicks = 0;
    }

    private static final class SteeringPoint {
        final double x;
        final double z;
        final boolean corridorMode;

        SteeringPoint(double x, double z, boolean corridorMode) {
            this.x = x;
            this.z = z;
            this.corridorMode = corridorMode;
        }
    }
}
