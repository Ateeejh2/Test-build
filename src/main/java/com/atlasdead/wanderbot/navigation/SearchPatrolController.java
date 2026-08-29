package com.atlasdead.wanderbot.navigation;

import net.minecraft.util.BlockPos;
import net.minecraft.client.entity.EntityPlayerSP;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Search/patrol state for target-less navigation.
 * A selected goal is kept until the normal path reaches it or another layer
 * explicitly invalidates the path.  Stuck recovery is responsible for handling
 * genuine movement failures; lack of progress alone must not destroy a valid
 * route and cause the bot to stop/replan repeatedly.
 */
public final class SearchPatrolController {
    private final ArrayDeque<String> recentGoals = new ArrayDeque<String>();
    private final Set<String> recentSet = new HashSet<String>();
    private BlockPos activeGoal;
    private double lastX;
    private double lastZ;
    private int noProgressTicks;

    public void reset() {
        recentGoals.clear();
        recentSet.clear();
        activeGoal = null;
        noProgressTicks = 0;
    }

    public void beginGoal(BlockPos goal, EntityPlayerSP player) {
        activeGoal = goal;
        noProgressTicks = 0;
        if (goal != null) remember(goal);
        if (player != null) {
            lastX = player.posX;
            lastZ = player.posZ;
        }
    }

    public void tick(EntityPlayerSP player, boolean searching) {
        if (!searching || player == null) return;
        double dx = player.posX - lastX;
        double dz = player.posZ - lastZ;
        if (dx * dx + dz * dz < 0.0025D) {
            noProgressTicks++;
        } else {
            noProgressTicks = Math.max(0, noProgressTicks - 2);
        }
        lastX = player.posX;
        lastZ = player.posZ;
    }

    public boolean shouldReplan(boolean searching, BlockPos currentGoal) {
        if (!searching) return false;
        if (currentGoal == null || activeGoal == null) return true;
        // Do not rebuild a route merely because progress is temporarily slow.
        // StuckDetector/RecoveryController handles actual movement failure.
        return !same(currentGoal, activeGoal);
    }

    public boolean allowsGoal(BlockPos goal) {
        if (goal == null) return false;
        return !recentSet.contains(key(goal));
    }

    public void clearActiveGoal() {
        activeGoal = null;
        noProgressTicks = 0;
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
