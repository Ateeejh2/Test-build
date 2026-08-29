package com.atlasdead.wanderbot.navigation;

import net.minecraft.util.BlockPos;
import net.minecraft.client.entity.EntityPlayerSP;
import com.atlasdead.wanderbot.pathfinding.PathNode;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Search/patrol state for target-less navigation.
 * Keeps exploration moving through fresh areas and can force a new search goal
 * when progress stalls, while leaving target acquisition to the Pit target layer.
 */
public final class SearchPatrolController {
    private final ArrayDeque<String> recentGoals = new ArrayDeque<String>();
    private final Set<String> recentSet = new HashSet<String>();
    private BlockPos activeGoal;
    private double lastX;
    private double lastZ;
    private int noProgressTicks;
    private double lastProgressMetric = Double.NaN;
    private String lastWaypointKey;

    public void reset() {
        recentGoals.clear();
        recentSet.clear();
        activeGoal = null;
        noProgressTicks = 0;
        lastProgressMetric = Double.NaN;
        lastWaypointKey = null;
    }

    public void beginGoal(BlockPos goal, EntityPlayerSP player) {
        activeGoal = goal;
        noProgressTicks = 0;
        lastProgressMetric = Double.NaN;
        lastWaypointKey = null;
        if (goal != null) remember(goal);
        if (player != null) {
            lastX = player.posX;
            lastZ = player.posZ;
        }
    }

    public void tick(EntityPlayerSP player, boolean searching, PathNode waypoint, BlockPos goal) {
        if (!searching || player == null) return;

        double metric;
        String waypointKey = null;
        if (waypoint != null) {
            double dx = waypoint.x + 0.5D - player.posX;
            double dz = waypoint.z + 0.5D - player.posZ;
            metric = dx * dx + dz * dz;
            waypointKey = waypoint.x + ":" + waypoint.y + ":" + waypoint.z;
        } else if (goal != null) {
            double dx = goal.getX() + 0.5D - player.posX;
            double dz = goal.getZ() + 0.5D - player.posZ;
            metric = dx * dx + dz * dz;
        } else {
            metric = 0.0D;
        }

        // A new waypoint changes the reference frame; do not punish the bot
        // for legitimately following a path segment that was just advanced.
        if (waypointKey != null && !waypointKey.equals(lastWaypointKey)) {
            lastWaypointKey = waypointKey;
            lastProgressMetric = metric;
            noProgressTicks = 0;
        } else if (!Double.isNaN(lastProgressMetric)) {
            double improvement = lastProgressMetric - metric;
            if (improvement > 0.004D) {
                noProgressTicks = Math.max(0, noProgressTicks - 3);
            } else {
                noProgressTicks++;
            }
            lastProgressMetric = metric;
        } else {
            lastProgressMetric = metric;
            noProgressTicks = 0;
        }

        lastX = player.posX;
        lastZ = player.posZ;
    }

    public boolean shouldReplan(boolean searching, BlockPos currentGoal) {
        if (!searching) return false;
        if (currentGoal == null || activeGoal == null) return true;
        return !same(currentGoal, activeGoal) || noProgressTicks >= 50;
    }

    public boolean allowsGoal(BlockPos goal) {
        if (goal == null) return false;
        return !recentSet.contains(key(goal));
    }

    public void clearActiveGoal() {
        activeGoal = null;
        noProgressTicks = 0;
        lastProgressMetric = Double.NaN;
        lastWaypointKey = null;
    }

    private void remember(BlockPos goal) {
        String k = key(goal);
        if (!recentSet.add(k)) return;
        recentGoals.addLast(k);
        while (recentGoals.size() > 10) recentSet.remove(recentGoals.removeFirst());
    }

    private static boolean same(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    private static String key(BlockPos p) {
        return p.getX() + ":" + p.getY() + ":" + p.getZ();
    }
}
