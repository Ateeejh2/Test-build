package com.atlasdead.wanderbot.pathfinding;

import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Combat-specific stuck detector.
 *
 * Tracks position history and velocity to detect when the bot is stuck.
 * Provides progressive recovery actions instead of immediately replanning.
 *
 * Recovery sequence:
 * 1. Re-evaluate current node
 * 2. Try alternative adjacent node
 * 3. Jump if possible
 * 4. Local replan (re-route around obstacle)
 * 5. Full replan (last resort)
 */
public class CombatStuckDetector {
    private static final int HISTORY_SIZE = 10;
    private static final double STUCK_THRESHOLD = 0.15;  // Min distance moved per check
    private static final int STUCK_TICKS_BEFORE_ACTION = 15;  // Ticks stuck before acting

    // Position history
    private final double[] historyX = new double[HISTORY_SIZE];
    private final double[] historyZ = new double[HISTORY_SIZE];
    private int historyIndex = 0;
    private int historyCount = 0;

    // Stuck state
    private int stuckTicks = 0;
    private int recoveryStage = 0;  // 0=none, 1=retry node, 2=try alt, 3=jump, 4=local replan, 5=full replan
    private long lastRecoveryTick;

    /**
     * Update stuck detection. Returns a recovery action.
     */
    public RecoveryAction update(EntityPlayerSP self, long currentTick) {
        // Record position
        historyX[historyIndex] = self.posX;
        historyZ[historyIndex] = self.posZ;
        historyIndex = (historyIndex + 1) % HISTORY_SIZE;
        if (historyCount < HISTORY_SIZE) historyCount++;

        // Check if we've moved
        double totalDistance = computeTotalDistance();
        if (totalDistance < STUCK_THRESHOLD * HISTORY_SIZE) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            recoveryStage = 0;
        }

        // Determine recovery action
        if (stuckTicks < STUCK_TICKS_BEFORE_ACTION) {
            return RecoveryAction.NONE;
        }

        // Progressive recovery
        if (stuckTicks >= STUCK_TICKS_BEFORE_ACTION && stuckTicks < STUCK_TICKS_BEFORE_ACTION + 20) {
            if (recoveryStage == 0) {
                recoveryStage = 1;
                lastRecoveryTick = currentTick;
                return RecoveryAction.RETRY_NODE;
            }
        }

        if (stuckTicks >= STUCK_TICKS_BEFORE_ACTION + 20 && stuckTicks < STUCK_TICKS_BEFORE_ACTION + 40) {
            if (recoveryStage <= 1) {
                recoveryStage = 2;
                lastRecoveryTick = currentTick;
                return RecoveryAction.TRY_ALTERNATIVE;
            }
        }

        if (stuckTicks >= STUCK_TICKS_BEFORE_ACTION + 40 && stuckTicks < STUCK_TICKS_BEFORE_ACTION + 60) {
            if (recoveryStage <= 2 && self.onGround) {
                recoveryStage = 3;
                lastRecoveryTick = currentTick;
                return RecoveryAction.JUMP;
            }
        }

        if (stuckTicks >= STUCK_TICKS_BEFORE_ACTION + 60 && stuckTicks < STUCK_TICKS_BEFORE_ACTION + 80) {
            if (recoveryStage <= 3) {
                recoveryStage = 4;
                lastRecoveryTick = currentTick;
                return RecoveryAction.LOCAL_REPLAN;
            }
        }

        if (stuckTicks >= STUCK_TICKS_BEFORE_ACTION + 80) {
            recoveryStage = 5;
            lastRecoveryTick = currentTick;
            stuckTicks = 0;  // Reset to avoid immediate re-trigger
            return RecoveryAction.FULL_REPLAN;
        }

        return RecoveryAction.NONE;
    }

    /**
     * Compute total distance moved in position history.
     */
    private double computeTotalDistance() {
        if (historyCount < 2) return Double.MAX_VALUE;

        double total = 0;
        int newest = (historyIndex - 1 + HISTORY_SIZE) % HISTORY_SIZE;
        int oldest = historyIndex;

        for (int i = 0; i < historyCount - 1; i++) {
            int idx1 = (oldest + i) % HISTORY_SIZE;
            int idx2 = (oldest + i + 1) % HISTORY_SIZE;
            double dx = historyX[idx2] - historyX[idx1];
            double dz = historyZ[idx2] - historyZ[idx1];
            total += Math.sqrt(dx * dx + dz * dz);
        }
        return total;
    }

    public boolean isStuck() {
        return stuckTicks >= STUCK_TICKS_BEFORE_ACTION;
    }

    public int getRecoveryStage() {
        return recoveryStage;
    }

    public void reset() {
        historyIndex = 0;
        historyCount = 0;
        stuckTicks = 0;
        recoveryStage = 0;
    }

    public enum RecoveryAction {
        NONE,
        RETRY_NODE,       // Re-evaluate current waypoint
        TRY_ALTERNATIVE,  // Try a different adjacent node
        JUMP,             // Jump over obstacle
        LOCAL_REPLAN,     // Re-route around nearby obstacle
        FULL_REPLAN       // Complete path recalculation
    }
}
