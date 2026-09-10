package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.pit.PitZoneManager;
import com.atlasdead.wanderbot.pit.TargetTracker;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;

import java.util.List;

/**
 * Low-frequency patrol/navigation used only while no local combat target exists.
 *
 * <p>The previous SEARCH state did not actually move: it released combat input and
 * returned. This navigator keeps the player moving through walkable Pit terrain,
 * preferring the direction of the nearest loaded opponent outside the local combat
 * tracking envelope. It uses the ordinary PathFinder, normal WASD, sprint and jump.
 * No attack input is owned here.</p>
 */
public final class TargetSearchNavigator {
    private static final int PLAN_MAX_DISTANCE = 30;
    private static final int PLAN_NODE_BUDGET = 6500;
    private static final double DIRECT_STEP = 17.0D;
    private static final double PATROL_STEP = 13.0D;
    private static final long PLAN_RETRY_MS = 350L;
    private static final int STALL_REPLAN_TICKS = 28;
    private static final double NODE_REACHED = 0.62D;

    private static final int[] ANGLE_OFFSETS = {0, 45, -45, 90, -90, 135, -135, 180};

    private final Minecraft mc;
    private final MovementController movement;
    private final RotationController rotation;
    private final PathFinder pathFinder = new PathFinder();

    private Path path;
    private BlockPos goal;
    private long lastPlanAt;
    private int patrolIndex;
    private int stallTicks;
    private double lastX;
    private double lastZ;
    private String status = "IDLE";

    public TargetSearchNavigator(Minecraft mc, MovementController movement, RotationController rotation) {
        this.mc = mc;
        this.movement = movement;
        this.rotation = rotation;
    }

    public void reset() {
        path = null;
        goal = null;
        lastPlanAt = 0L;
        stallTicks = 0;
        status = "IDLE";
    }

    public void tick(EntityPlayerSP self, PitZoneManager zones) {
        if (self == null || mc == null || mc.theWorld == null) {
            reset();
            movement.release();
            return;
        }

        updateProgress(self);
        long now = System.currentTimeMillis();

        if (needsPlan(self) && now - lastPlanAt >= PLAN_RETRY_MS) {
            lastPlanAt = now;
            plan(self, zones);
        }

        if (path == null || path.isFinished()) {
            movement.release();
            status = "SEARCHING";
            return;
        }

        follow(self);
    }

    private boolean needsPlan(EntityPlayerSP self) {
        if (path == null || path.isFinished() || goal == null) return true;
        if (stallTicks >= STALL_REPLAN_TICKS) return true;
        double dx = goal.getX() + 0.5D - self.posX;
        double dz = goal.getZ() + 0.5D - self.posZ;
        return dx * dx + dz * dz < 1.0D;
    }

    private void plan(EntityPlayerSP self, PitZoneManager zones) {
        path = null;
        goal = null;
        stallTicks = 0;

        EntityPlayer hint = nearestLoadedOpponent(self, zones);
        double baseAngle;
        double step;
        if (hint != null) {
            double dx = hint.posX - self.posX;
            double dz = hint.posZ - self.posZ;
            baseAngle = Math.atan2(dz, dx);
            double distance = Math.sqrt(dx * dx + dz * dz);
            step = Math.min(DIRECT_STEP, Math.max(4.0D, distance - 4.0D));
            status = "HUNTING " + hint.getName();
        } else {
            baseAngle = Math.toRadians((patrolIndex++ * 67) % 360);
            step = PATROL_STEP;
            status = "PATROL";
        }

        BlockPos start = new BlockPos(self.posX, self.posY, self.posZ);
        for (int offset : ANGLE_OFFSETS) {
            double angle = baseAngle + Math.toRadians(offset);
            double x = self.posX + Math.cos(angle) * step;
            double z = self.posZ + Math.sin(angle) * step;
            BlockPos candidateGoal = findStandableNear(self, x, z, 4);
            if (candidateGoal == null) continue;

            Path candidate = pathFinder.findHierarchicalPath(
                    mc.theWorld, start, candidateGoal, PLAN_MAX_DISTANCE, PLAN_NODE_BUDGET);
            if (candidate == null || candidate.isFinished()) continue;

            path = candidate;
            goal = candidateGoal;
            lastX = self.posX;
            lastZ = self.posZ;
            return;
        }

        status = hint == null ? "NO PATROL ROUTE" : "NO HUNT ROUTE";
    }

    private EntityPlayer nearestLoadedOpponent(EntityPlayerSP self, PitZoneManager zones) {
        List<EntityPlayer> players = mc.theWorld.playerEntities;
        if (players == null || players.isEmpty()) return null;

        EntityPlayer best = null;
        double bestSq = Double.POSITIVE_INFINITY;
        for (EntityPlayer player : players) {
            if (!TargetTracker.isBasicCandidate(player, self)) continue;
            if (TargetTracker.isInSpawn(player)) continue;
            if (TargetTracker.hasDiamondArmor(player)) continue;
            if (zones != null && zones.isPlayerProtected(player)) continue;

            double dx = player.posX - self.posX;
            double dz = player.posZ - self.posZ;
            double sq = dx * dx + dz * dz;

            // SEARCH should never mean stand still. Even if a local player was
            // rejected by a higher policy layer, keeping the patrol biased toward
            // nearby loaded opponents is more useful than idling in place.
            if (sq < bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    private BlockPos findStandableNear(EntityPlayerSP self, double targetX, double targetZ, int radius) {
        int baseX = (int) Math.floor(targetX);
        int baseZ = (int) Math.floor(targetZ);
        int baseY = (int) Math.floor(self.posY);

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 3; dy++) {
                    BlockPos candidate = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                    if (!PathFinder.isStandable(mc.theWorld, candidate)) continue;
                    double score = dx * dx + dz * dz + Math.abs(dy) * 1.5D;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private void updateProgress(EntityPlayerSP self) {
        if (path == null || path.isFinished()) {
            lastX = self.posX;
            lastZ = self.posZ;
            stallTicks = 0;
            return;
        }
        double dx = self.posX - lastX;
        double dz = self.posZ - lastZ;
        if (dx * dx + dz * dz < 0.0016D) stallTicks++;
        else stallTicks = Math.max(0, stallTicks - 2);
        lastX = self.posX;
        lastZ = self.posZ;
    }

    private void follow(EntityPlayerSP self) {
        advanceReached(self);
        if (path == null || path.isFinished()) {
            movement.release();
            return;
        }

        PathNode current = path.current();
        if (current == null) {
            movement.release();
            return;
        }

        double targetX = current.x + 0.5D;
        double targetY = current.y + 1.0D;
        double targetZ = current.z + 0.5D;
        double dx = targetX - self.posX;
        double dz = targetZ - self.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) return;

        float yawError = rotation.tickPath(self, targetX, targetY, targetZ);
        double yaw = Math.toRadians(self.rotationYaw);
        double localForward = dx * (-Math.sin(yaw)) + dz * Math.cos(yaw);
        double localStrafe = dx * Math.cos(yaw) + dz * Math.sin(yaw);

        movement.locomotion(
                localForward > -0.18D && yawError < 80.0F,
                localForward < -0.45D,
                (float) clamp(localStrafe * 0.90D, -1.0D, 1.0D));
        movement.sprint(self.onGround && yawError < 35.0F && localForward > 0.45D);

        if (current.y > self.posY + 0.38D && self.onGround) movement.jump();
    }

    private void advanceReached(EntityPlayerSP self) {
        while (path != null && !path.isFinished()) {
            PathNode node = path.current();
            if (node == null) return;
            double dx = node.x + 0.5D - self.posX;
            double dz = node.z + 0.5D - self.posZ;
            double horizontalSq = dx * dx + dz * dz;
            if (horizontalSq <= NODE_REACHED * NODE_REACHED && Math.abs(self.posY - node.y) <= 1.25D) {
                path.advance();
            } else {
                return;
            }
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public Path getPath() { return path; }
    public BlockPos getGoal() { return goal; }
    public String getStatus() { return status; }
}
