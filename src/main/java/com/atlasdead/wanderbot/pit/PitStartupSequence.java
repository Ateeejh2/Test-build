package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.StringUtils;

/** Startup sequence used before the normal bot loop. Currently only route #1 is implemented. */
public final class PitStartupSequence {
    private enum State { CHECK_PIT, WAITING_FOR_PIT, MOVE_TO_MID, WAIT_FOR_DROP, COMPLETE }

    private static final int MID_X = -13;
    private static final int MID_Z = -14;
    private static final int GOAL_Y_SCAN = 12;

    private final Minecraft mc;
    private final MovementController movement;
    private final PathFinder pathFinder;

    private State state = State.CHECK_PIT;
    private Path path;
    private BlockPos goal;
    private long commandSentAt;
    private boolean commandSent;
    private double dropBaselineY;
    private double dropPrevY;
    private int dropTicks;

    public PitStartupSequence(Minecraft mc, MovementController movement, PathFinder pathFinder) {
        this.mc = mc;
        this.movement = movement;
        this.pathFinder = pathFinder;
    }

    public void reset() {
        state = State.CHECK_PIT;
        path = null;
        goal = null;
        commandSentAt = 0L;
        commandSent = false;
        dropBaselineY = 0.0D;
        dropPrevY = 0.0D;
        dropTicks = 0;
    }

    public boolean isComplete() { return state == State.COMPLETE; }
    public Path getPath() { return path; }
    public BlockPos getGoal() { return goal; }
    public String getStatus() { return state.name(); }

    public void tick(EntityPlayerSP player) {
        if (player == null || mc.theWorld == null) return;
        movement.release();

        switch (state) {
            case CHECK_PIT:
                if (isPit()) {
                    state = State.MOVE_TO_MID;
                    commandSent = false;
                    buildMidPath(player);
                } else {
                    sendPlayPitOnce();
                    state = State.WAITING_FOR_PIT;
                }
                return;

            case WAITING_FOR_PIT:
                if (isPit()) {
                    state = State.MOVE_TO_MID;
                    commandSent = false;
                    buildMidPath(player);
                } else {
                    if (commandSent && System.currentTimeMillis() - commandSentAt > 8000L) {
                        commandSent = false;
                    }
                    sendPlayPitOnce();
                }
                return;

            case MOVE_TO_MID:
                if (goal == null) {
                    buildMidPath(player);
                }
                followPath(player);
                if (isExactlyAtGoal(player)) {
                    path = null;
                    dropBaselineY = player.posY;
                    dropPrevY = player.posY;
                    dropTicks = 0;
                    state = State.WAIT_FOR_DROP;
                }
                return;

            case WAIT_FOR_DROP:
                movement.forward(false);
                movement.backward(false);
                movement.strafe(0.0F);
                movement.sprint(false);

                double deltaY = dropPrevY - player.posY;
                if (deltaY >= 0.035D && player.posY < dropBaselineY - 0.10D) {
                    dropTicks++;
                } else if (player.posY >= dropPrevY - 0.01D) {
                    dropTicks = 0;
                }
                dropPrevY = player.posY;

                if (dropTicks >= 2) {
                    movement.release();
                    state = State.COMPLETE;
                    return;
                }
                return;

            case COMPLETE:
            default:
                movement.release();
        }
    }

    private void buildMidPath(EntityPlayerSP player) {
        goal = findExactMidStand(player);
        path = null;
        if (goal == null) return;
        path = pathFinder.findHierarchicalPath(
                mc.theWorld,
                new BlockPos(player.posX, player.posY, player.posZ),
                goal,
                45,
                12000);
    }

    /** Resolve the target column exactly at (-13,-14); only Y is searched. */
    private BlockPos findExactMidStand(EntityPlayerSP player) {
        int baseY = (int) Math.floor(player.posY);
        BlockPos best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (int dy = -GOAL_Y_SCAN; dy <= GOAL_Y_SCAN; dy++) {
            int y = baseY + dy;
            BlockPos candidate = new BlockPos(MID_X, y, MID_Z);
            if (!PathFinder.isStandable(mc.theWorld, candidate)) continue;
            int delta = Math.abs(dy);
            if (best == null || delta < bestDelta) {
                best = candidate;
                bestDelta = delta;
            }
        }
        return best;
    }

    private void followPath(EntityPlayerSP player) {
        if (goal == null) return;
        if (path == null || path.isFinished()) {
            path = pathFinder.findHierarchicalPath(
                    mc.theWorld,
                    new BlockPos(player.posX, player.posY, player.posZ),
                    goal,
                    45,
                    12000);
        }
        if (path == null || path.isFinished()) return;

        while (!path.isFinished()) {
            BlockPos p = new BlockPos(path.current().x, path.current().y, path.current().z);
            if (nearNode(player, p, 0.55D)) path.advance(); else break;
        }
        if (path.isFinished()) return;

        BlockPos p = new BlockPos(path.current().x, path.current().y, path.current().z);
        double dx = p.getX() + 0.5D - player.posX;
        double dz = p.getZ() + 0.5D - player.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) return;

        double yaw = Math.atan2(-dx, dz) * 180.0D / Math.PI;
        double yawDiff = wrapDegrees(yaw - player.rotationYaw);
        double rad = Math.toRadians(yaw);
        double forward = (-Math.sin(rad)) * (dx / len) + Math.cos(rad) * (dz / len);
        double strafe = Math.cos(rad) * (dx / len) + Math.sin(rad) * (dz / len);

        // Startup route uses deterministic waypoint-facing rotation.
        player.rotationYaw = (float) yaw;
        player.rotationYawHead = (float) yaw;

        movement.forward(forward > -0.2D && Math.abs(yawDiff) <= 65.0D);
        movement.backward(forward < -0.35D);
        movement.strafe((float) Math.max(-1.0D, Math.min(1.0D, strafe)));
        movement.sprint(Math.abs(yawDiff) < 25.0D && forward > 0.55D && player.onGround);

        if (path.current().y > player.posY + 0.45D && player.onGround) {
            movement.jump();
        }
    }

    private boolean isExactlyAtGoal(EntityPlayerSP player) {
        if (goal == null) return false;
        double dx = player.posX - (MID_X + 0.5D);
        double dz = player.posZ - (MID_Z + 0.5D);
        double dy = player.posY - goal.getY();
        return dx * dx + dz * dz <= 0.36D
                && Math.abs(dy) <= 0.75D
                && player.onGround
                && PathFinder.isStandable(mc.theWorld, new BlockPos(MID_X, goal.getY(), MID_Z));
    }

    private boolean nearNode(EntityPlayerSP player, BlockPos pos, double radius) {
        double dx = pos.getX() + 0.5D - player.posX;
        double dz = pos.getZ() + 0.5D - player.posZ;
        return dx * dx + dz * dz <= radius * radius
                && Math.abs(player.posY - pos.getY()) <= 1.0D;
    }

    private boolean isPit() {
        Scoreboard board = mc.theWorld == null ? null : mc.theWorld.getScoreboard();
        if (board == null) return false;
        ScoreObjective objective = board.getObjectiveInDisplaySlot(1);
        if (objective == null || objective.getDisplayName() == null) return false;
        String title = StringUtils.stripControlCodes(objective.getDisplayName());
        title = title.replace("\u00a7l", "").trim();
        return "THE HYPIXEL PIT".equalsIgnoreCase(title);
    }

    private void sendPlayPitOnce() {
        if (mc.thePlayer == null || commandSent) return;
        mc.thePlayer.sendChatMessage("/play pit");
        commandSent = true;
        commandSentAt = System.currentTimeMillis();
    }

    private static double wrapDegrees(double angle) {
        while (angle <= -180.0D) angle += 360.0D;
        while (angle > 180.0D) angle -= 360.0D;
        return angle;
    }
}
