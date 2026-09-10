package com.atlasdead.wanderbot.pathfinding;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

/**
 * Converts the authoritative combat path into deterministic movement inputs.
 * Path progression, corridor selection and input hysteresis are kept separate
 * so each part can be reasoned about independently.
 */
public class CombatSteering {
    private static final double REACHED_DISTANCE = 0.72D;
    private static final double STRAIGHT_LOOKAHEAD_REACHED_DISTANCE = 1.00D;
    private static final double PASSED_PLANE_EPSILON = 0.12D;
    private static final double PASSED_CORRIDOR_WIDTH = 0.90D;
    private static final double CORRIDOR_HALF_WIDTH = 0.55D;
    private static final double CORNER_BLEND_DISTANCE = 1.15D;
    private static final float STRAFE_DEAD_ZONE = 0.20F;
    private static final float STRAFE_FLIP_THRESHOLD = 0.48F;
    private static final int STRAFE_HOLD_TICKS = 2;

    public static final class Result {
        public final boolean forward;
        public final boolean backward;
        public final float strafe;
        public final boolean sprint;
        public final boolean jump;
        Result(boolean forward, boolean backward, float strafe, boolean sprint, boolean jump) {
            this.forward = forward;
            this.backward = backward;
            this.strafe = strafe;
            this.sprint = sprint;
            this.jump = jump;
        }

        public static Result idle() {
            return new Result(false, false, 0.0F, false, false);
        }
    }

    private int lastStrafeDirection;
    private int strafeHoldTicks;

    /** targetDistance is retained for API compatibility; steering is path-driven. */
    public Result compute(EntityPlayerSP self, Path path, double targetDistance) {
        if (self == null || path == null || path.isFinished()) return idleAndReset();

        advanceReached(path, self);
        if (path.isFinished()) {
            resetInputState();
            return new Result(false, false, 0.0F, false, false);
        }

        PathNode waypoint = path.current();
        if (waypoint == null) return idleAndReset();

        SteeringPoint point = computeCorridorPoint(self, path);
        LocalMovement movement = toLocalMovement(self, point);
        double verticalDistance = waypoint.y - self.posY;
        if (movement.horizontalDistance < 0.08D && Math.abs(verticalDistance) < 0.9D) {
            return new Result(false, false, 0.0F, false, false);
        }

        boolean forward = movement.forward > 0.04D;
        boolean backward = movement.forward < -0.70D;
        float desiredStrafe = desiredStrafe(movement);
        float strafe = applyStrafeHysteresis(desiredStrafe);
        boolean sharpTurn = isSharpTurn(path);
        boolean sprint = self.onGround && forward && !(sharpTurn && movement.horizontalDistance < 1.35D);
        boolean jump = verticalDistance > 0.30D || needsJumpForObstacle(self, movement.worldDx, movement.worldDz);

        return new Result(
                forward,
                backward,
                strafe,
                sprint,
                jump);
    }

    public void reset() {
        resetInputState();
    }

    private Result idleAndReset() {
        resetInputState();
        return Result.idle();
    }

    private static LocalMovement toLocalMovement(EntityPlayerSP self, SteeringPoint point) {
        double dx = point.x - self.posX;
        double dz = point.z - self.posZ;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        double yaw = Math.toRadians(self.rotationYaw);
        double forward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double strafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);
        return new LocalMovement(dx, dz, horizontalDistance, forward, strafe);
    }

    private static float desiredStrafe(LocalMovement movement) {
        float desired = (float) MathHelper.clamp_double(movement.strafe * 1.20D, -1.0D, 1.0D);
        if (Math.abs(movement.forward) > 0.78D) desired *= 0.28F;
        return desired;
    }

    /** Advance monotonically without turning back toward passed checkpoints. */
    private static void advanceReached(Path path, EntityPlayerSP self) {
        while (!path.isFinished()) {
            PathNode current = path.current();
            if (current == null) return;

            double dx = current.x + 0.5D - self.posX;
            double dz = current.z + 0.5D - self.posZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double vertical = Math.abs(self.posY - current.y);

            if ((horizontal <= REACHED_DISTANCE && vertical <= 1.30D)
                    || (horizontal <= STRAIGHT_LOOKAHEAD_REACHED_DISTANCE
                        && vertical <= 1.30D
                        && hasStraightLookahead(path))
                    || hasPassedCheckpoint(path, self, current)) {
                path.advance();
                continue;
            }
            return;
        }
    }

    private static boolean hasPassedCheckpoint(Path path, EntityPlayerSP self, PathNode current) {
        int index = path.getIndex();
        if (index <= 0) return false;

        PathNode previous = path.getNodes().get(index - 1);
        if (previous.y != current.y) return false;

        double currentX = current.x + 0.5D;
        double currentZ = current.z + 0.5D;
        double directionX = currentX - (previous.x + 0.5D);
        double directionZ = currentZ - (previous.z + 0.5D);
        double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (length < 0.001D) return false;

        directionX /= length;
        directionZ /= length;
        double fromCheckpointX = self.posX - currentX;
        double fromCheckpointZ = self.posZ - currentZ;
        double along = fromCheckpointX * directionX + fromCheckpointZ * directionZ;
        double lateral = Math.abs(fromCheckpointX * (-directionZ) + fromCheckpointZ * directionX);
        return along >= PASSED_PLANE_EPSILON && lateral <= PASSED_CORRIDOR_WIDTH;
    }

    private static SteeringPoint computeCorridorPoint(EntityPlayerSP self, Path path) {
        PathNode current = path.current();
        PathNode next = getNext(path, 1);
        PathNode afterNext = getNext(path, 2);

        double currentX = current.x + 0.5D;
        double currentZ = current.z + 0.5D;
        if (next == null || current.y != next.y) {
            return new SteeringPoint(currentX, currentZ, false);
        }

        double nextX = next.x + 0.5D;
        double nextZ = next.z + 0.5D;
        double segmentX = nextX - currentX;
        double segmentZ = nextZ - currentZ;
        double segmentLengthSquared = segmentX * segmentX + segmentZ * segmentZ;
        if (segmentLengthSquared < 0.001D) return new SteeringPoint(currentX, currentZ, false);

        double projection = ((self.posX - currentX) * segmentX + (self.posZ - currentZ) * segmentZ)
                / segmentLengthSquared;
        projection = Math.max(0.0D, Math.min(1.0D, projection));

        double nearestX = currentX + segmentX * projection;
        double nearestZ = currentZ + segmentZ * projection;
        double segmentLength = Math.sqrt(segmentLengthSquared);
        double remaining = segmentLength * (1.0D - projection);
        double directionX = segmentX / segmentLength;
        double directionZ = segmentZ / segmentLength;

        double preview = configuredLookahead();
        double steerX;
        double steerZ;
        if (preview <= remaining) {
            steerX = nearestX + directionX * preview;
            steerZ = nearestZ + directionZ * preview;
        } else {
            steerX = nextX;
            steerZ = nextZ;
            double previewLeft = preview - remaining;
            PathNode previous = current;
            PathNode segmentStart = next;
            int offset = 2;

            while (previewLeft > 0.001D) {
                PathNode segmentEnd = getNext(path, offset);
                if (segmentEnd == null || segmentStart.y != segmentEnd.y) break;
                // Do not aim through a corner/wall. Long lookahead is only consumed
                // across collinear corridor segments; corners keep the existing blend.
                if (isTurnBetween(previous, segmentStart, segmentEnd)) break;

                double startX = segmentStart.x + 0.5D;
                double startZ = segmentStart.z + 0.5D;
                double endX = segmentEnd.x + 0.5D;
                double endZ = segmentEnd.z + 0.5D;
                double dx = endX - startX;
                double dz = endZ - startZ;
                double length = Math.sqrt(dx * dx + dz * dz);
                if (length < 0.001D) break;

                double step = Math.min(previewLeft, length);
                steerX = startX + dx / length * step;
                steerZ = startZ + dz / length * step;
                if (previewLeft <= length) break;

                previewLeft -= length;
                previous = segmentStart;
                segmentStart = segmentEnd;
                offset++;
            }
        }

        if (afterNext != null && next.y == afterNext.y && isTurnBetween(current, next, afterNext)) {
            double[] blended = blendCorner(steerX, steerZ, nextX, nextZ, afterNext, remaining);
            steerX = blended[0];
            steerZ = blended[1];
        }

        double sideX = -directionZ;
        double sideZ = directionX;
        double lateral = (self.posX - nearestX) * sideX + (self.posZ - nearestZ) * sideZ;
        double corrected = MathHelper.clamp_double(lateral, -CORRIDOR_HALF_WIDTH, CORRIDOR_HALF_WIDTH);
        steerX += (corrected - lateral) * sideX * 0.70D;
        steerZ += (corrected - lateral) * sideZ * 0.70D;
        return new SteeringPoint(steerX, steerZ, true);
    }

    private static double configuredLookahead() {
        return Math.max(1.0D, Math.min(8.0D, WanderBotSettings.lookaheadDistance));
    }

    private static double[] blendCorner(double steerX, double steerZ,
                                        double nextX, double nextZ,
                                        PathNode afterNext, double remaining) {
        double outgoingX = afterNext.x + 0.5D - nextX;
        double outgoingZ = afterNext.z + 0.5D - nextZ;
        double outgoingLength = Math.sqrt(outgoingX * outgoingX + outgoingZ * outgoingZ);
        if (outgoingLength <= 0.001D) return new double[] {steerX, steerZ};

        double blend = 1.0D - Math.min(1.0D, remaining / CORNER_BLEND_DISTANCE);
        blend = blend * blend * (3.0D - 2.0D * blend);
        double targetX = nextX + outgoingX / outgoingLength * CORNER_BLEND_DISTANCE;
        double targetZ = nextZ + outgoingZ / outgoingLength * CORNER_BLEND_DISTANCE;
        double weight = blend * 0.65D;
        return new double[] {
                steerX * (1.0D - weight) + targetX * weight,
                steerZ * (1.0D - weight) + targetZ * weight
        };
    }

    private static boolean isTurnBetween(PathNode a, PathNode b, PathNode c) {
        return Integer.signum(b.x - a.x) != Integer.signum(c.x - b.x)
                || Integer.signum(b.z - a.z) != Integer.signum(c.z - b.z);
    }

    private static PathNode getNext(Path path, int offset) {
        int index = path.getIndex() + offset;
        return index < 0 || index >= path.getNodes().size() ? null : path.getNodes().get(index);
    }

    private static boolean hasStraightLookahead(Path path) {
        int index = path.getIndex();
        if (index + 2 >= path.getNodes().size()) return false;

        PathNode a = path.getNodes().get(index);
        PathNode b = path.getNodes().get(index + 1);
        PathNode c = path.getNodes().get(index + 2);
        int abX = Integer.signum(b.x - a.x);
        int abZ = Integer.signum(b.z - a.z);
        int bcX = Integer.signum(c.x - b.x);
        int bcZ = Integer.signum(c.z - b.z);
        int abY = Integer.signum(b.y - a.y);
        int bcY = Integer.signum(c.y - b.y);
        if (abY != bcY) return false;
        return (abX == bcX && abZ == bcZ) || abX * bcX + abZ * bcZ >= 1;
    }

    private static boolean isSharpTurn(Path path) {
        PathNode current = path.current();
        PathNode next = getNext(path, 1);
        PathNode afterNext = getNext(path, 2);
        if (current == null || next == null || afterNext == null) return false;
        if (current.y != next.y || next.y != afterNext.y) return true;
        return isTurnBetween(current, next, afterNext);
    }

    private float applyStrafeHysteresis(float desired) {
        int desiredDirection = desired > STRAFE_DEAD_ZONE ? 1 : (desired < -STRAFE_DEAD_ZONE ? -1 : 0);
        if (strafeHoldTicks > 0) strafeHoldTicks--;

        if (desiredDirection == 0) {
            if (strafeHoldTicks == 0) lastStrafeDirection = 0;
        } else if (lastStrafeDirection == 0) {
            holdStrafe(desiredDirection);
        } else if (desiredDirection != lastStrafeDirection
                && (Math.abs(desired) >= STRAFE_FLIP_THRESHOLD || strafeHoldTicks == 0)) {
            holdStrafe(desiredDirection);
        }

        return lastStrafeDirection == 0
                ? 0.0F
                : lastStrafeDirection * Math.min(1.0F, Math.max(0.22F, Math.abs(desired)));
    }

    private void holdStrafe(int direction) {
        lastStrafeDirection = direction;
        strafeHoldTicks = STRAFE_HOLD_TICKS;
    }

    private static boolean needsJumpForObstacle(EntityPlayerSP self, double dx, double dz) {
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.35D) return false;

        double directionX = dx / distance;
        double directionZ = dz / distance;
        int baseY = MathHelper.floor_double(self.posY + 0.05D);
        for (double probe = 0.45D; probe <= 1.35D; probe += 0.30D) {
            int x = MathHelper.floor_double(self.posX + directionX * probe);
            int z = MathHelper.floor_double(self.posZ + directionZ * probe);
            BlockPos feet = new BlockPos(x, baseY, z);
            BlockPos head = feet.up();
            BlockPos landingFeet = feet.up();
            BlockPos landingHead = feet.up(2);

            if ((!isClear(self.worldObj, feet)
                    && isClear(self.worldObj, landingFeet)
                    && isClear(self.worldObj, landingHead)
                    && isSolidFloor(self.worldObj, feet))
                    || (!isClear(self.worldObj, head) && isClear(self.worldObj, landingHead))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClear(World world, BlockPos position) {
        if (world == null) return false;
        Block block = world.getBlockState(position).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || block.isAir(world, position)) return true;
        if (material.isLiquid()) return false;
        return !material.blocksMovement();
    }

    private static boolean isSolidFloor(World world, BlockPos position) {
        if (world == null || position.getY() <= 0 || position.getY() >= world.getHeight()) return false;
        Block block = world.getBlockState(position).getBlock();
        Material material = block.getMaterial();
        if (material == Material.air || material.isLiquid() || block.isAir(world, position)) return false;
        return block.isOpaqueCube() || block.isFullBlock() || material.blocksMovement();
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

    private static final class LocalMovement {
        final double worldDx;
        final double worldDz;
        final double horizontalDistance;
        final double forward;
        final double strafe;

        LocalMovement(double worldDx, double worldDz, double horizontalDistance, double forward, double strafe) {
            this.worldDx = worldDx;
            this.worldDz = worldDz;
            this.horizontalDistance = horizontalDistance;
            this.forward = forward;
            this.strafe = strafe;
        }
    }
}
