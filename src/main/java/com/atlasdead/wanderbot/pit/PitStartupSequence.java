package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.rotation.RotationController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.StringUtils;

import java.util.Random;

/** Startup sequence before the normal bot loop. One of five mid routes is chosen per startup. */
public final class PitStartupSequence {
    private enum State { CHECK_PIT, WAITING_FOR_PIT, MOVE_TO_MID, WAIT_FOR_DROP, COMPLETE }

    private static final int[][] MID_ROUTES = {
            {-13, -14},
            {-14, 15},
            {16, 19},
            {17, -17},
            {0, 0}
    };

    private static final int GOAL_Y_SCAN = 12;
    private static final double DROP_PER_TICK = 0.035D;
    private static final double DROP_MIN_TOTAL = 0.10D;
    private static final int DROP_CONFIRM_TICKS = 2;

    private final Minecraft mc;
    private final MovementController movement;
    private final PathFinder pathFinder;
    private final RotationController rotation;
    private final Random random = new Random();

    private State state = State.CHECK_PIT;
    private Path path;
    private BlockPos goal;
    private int selectedRoute = -1;
    private long commandSentAt;
    private boolean commandSent;
    private double dropBaselineY;
    private double dropPrevY;
    private int dropTicks;

    public PitStartupSequence(Minecraft mc, MovementController movement, PathFinder pathFinder,
                              RotationController rotation) {
        this.mc = mc;
        this.movement = movement;
        this.pathFinder = pathFinder;
        this.rotation = rotation;
    }

    public void reset() {
        state = State.CHECK_PIT;
        path = null;
        goal = null;
        selectedRoute = -1;
        commandSentAt = 0L;
        commandSent = false;
        dropBaselineY = 0.0D;
        dropPrevY = 0.0D;
        dropTicks = 0;
        rotation.reset();
    }

    public boolean isComplete() { return state == State.COMPLETE; }
    public Path getPath() { return path; }
    public BlockPos getGoal() { return goal; }
    public String getStatus() {
        if (selectedRoute < 0) return state.name();
        int[] route = MID_ROUTES[selectedRoute];
        return state.name() + " R" + (selectedRoute + 1) + " (" + route[0] + "," + route[1] + ")";
    }

    public void tick(EntityPlayerSP player) {
        if (player == null || mc.theWorld == null) return;
        movement.release();

        switch (state) {
            case CHECK_PIT:
                if (isPit()) {
                    selectRoute();
                    state = State.MOVE_TO_MID;
                    commandSent = false;
                    beginDropWatch(player);
                    buildMidPath(player);
                } else {
                    sendPlayPitOnce();
                    state = State.WAITING_FOR_PIT;
                }
                return;

            case WAITING_FOR_PIT:
                if (isPit()) {
                    selectRoute();
                    state = State.MOVE_TO_MID;
                    commandSent = false;
                    beginDropWatch(player);
                    buildMidPath(player);
                } else {
                    if (commandSent && System.currentTimeMillis() - commandSentAt > 8000L) {
                        commandSent = false;
                    }
                    sendPlayPitOnce();
                }
                return;

            case MOVE_TO_MID:
                if (detectDrop(player)) {
                    completeStartup();
                    return;
                }
                if (goal == null) buildMidPath(player);
                followPath(player);
                if (isExactlyAtGoal(player)) {
                    path = null;
                    beginDropWatch(player);
                    state = State.WAIT_FOR_DROP;
                }
                return;

            case WAIT_FOR_DROP:
                movement.forward(false);
                movement.backward(false);
                movement.strafe(0.0F);
                movement.sprint(false);
                if (detectDrop(player)) completeStartup();
                return;

            case COMPLETE:
            default:
                movement.release();
        }
    }

    private void selectRoute() {
        if (selectedRoute >= 0) return;
        selectedRoute = random.nextInt(MID_ROUTES.length);
    }

    private void buildMidPath(EntityPlayerSP player) {
        if (selectedRoute < 0) selectRoute();
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

    private BlockPos findExactMidStand(EntityPlayerSP player) {
        int[] route = MID_ROUTES[selectedRoute];
        int baseY = (int) Math.floor(player.posY);
        BlockPos best = null;
        int bestDelta = Integer.MAX_VALUE;
        for (int dy = -GOAL_Y_SCAN; dy <= GOAL_Y_SCAN; dy++) {
            int y = baseY + dy;
            BlockPos candidate = new BlockPos(route[0], y, route[1]);
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
            PathNode current = path.current();
            if (current == null) break;
            if (nearNode(player, new BlockPos(current.x, current.y, current.z), 0.55D)) path.advance();
            else break;
        }
        if (path.isFinished()) return;

        PathNode current = path.current();
        if (current == null) return;

        double targetX = current.x + 0.5D;
        double targetY = current.y + 1.0D;
        double targetZ = current.z + 0.5D;

        double dx = targetX - player.posX;
        double dz = targetZ - player.posZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001D) return;

        // Deterministic humanized/eased rotation toward the exact active waypoint.
        float yawError = rotation.tickPath(player, targetX, targetY, targetZ);
        double yawRad = Math.toRadians(player.rotationYaw);
        double localForward = dx * (-Math.sin(yawRad)) + dz * Math.cos(yawRad);
        double localStrafe = dx * Math.cos(yawRad) + dz * Math.sin(yawRad);

        movement.forward(localForward > -0.2D && yawError < 75.0F);
        movement.backward(localForward < -0.35D);
        movement.strafe((float) Math.max(-1.0D, Math.min(1.0D, localStrafe)));
        movement.sprint(yawError < 35.0F && localForward > 0.45D && player.onGround);

        if (current.y > player.posY + 0.45D && player.onGround) movement.jump();
    }

    private void beginDropWatch(EntityPlayerSP player) {
        dropBaselineY = player.posY;
        dropPrevY = player.posY;
        dropTicks = 0;
    }

    private boolean detectDrop(EntityPlayerSP player) {
        double deltaY = dropPrevY - player.posY;
        if (deltaY >= DROP_PER_TICK && player.posY < dropBaselineY - DROP_MIN_TOTAL) {
            dropTicks++;
        } else if (player.posY >= dropPrevY - 0.01D) {
            dropTicks = 0;
        }
        dropPrevY = player.posY;
        return dropTicks >= DROP_CONFIRM_TICKS;
    }

    private void completeStartup() {
        movement.release();
        state = State.COMPLETE;
        path = null;
        goal = null;
    }

    private boolean isExactlyAtGoal(EntityPlayerSP player) {
        if (goal == null || selectedRoute < 0) return false;
        int[] route = MID_ROUTES[selectedRoute];
        double dx = player.posX - (route[0] + 0.5D);
        double dz = player.posZ - (route[1] + 0.5D);
        double dy = player.posY - goal.getY();
        return dx * dx + dz * dz <= 0.36D
                && Math.abs(dy) <= 0.75D
                && player.onGround
                && PathFinder.isStandable(mc.theWorld, new BlockPos(route[0], goal.getY(), route[1]));
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
}
