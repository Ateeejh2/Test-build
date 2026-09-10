package com.atlasdead.wanderbot.bot;

import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Detects respawn-like vertical jumps and de-duplicates restart signals.
 *
 * <p>Chat-based death detection and Y-jump detection share the same cooldown so
 * they cannot restart the bot twice for one death.</p>
 */
final class DeathRestartDetector {
    private static final long RESTART_COOLDOWN_MS = 1000L;
    private static final double Y_RISE_THRESHOLD = 8.0D;
    private static final int Y_WINDOW_TICKS = 8;
    private static final double POSITIVE_STEP_THRESHOLD = 0.25D;
    private static final double MIN_VALID_Y = 1.0D;

    private long lastRestartMs;
    private double previousY;
    private double windowBaseY;
    private int windowTicks;
    private boolean initialized;

    void reset(double y) {
        previousY = y;
        windowBaseY = y;
        windowTicks = 0;
        initialized = true;
    }

    void clear() {
        initialized = false;
        windowTicks = 0;
        lastRestartMs = 0L;
    }

    boolean claimExternalSignal(long nowMs) {
        return claimRestartWindow(nowMs);
    }

    boolean detect(EntityPlayerSP player, long nowMs) {
        if (player == null) return false;

        double currentY = player.posY;
        if (!initialized) {
            reset(currentY);
            return false;
        }

        windowTicks++;
        double deltaY = currentY - previousY;
        previousY = currentY;

        if (windowTicks > Y_WINDOW_TICKS) {
            if (deltaY > POSITIVE_STEP_THRESHOLD) {
                windowBaseY = currentY - deltaY;
            } else {
                windowBaseY = currentY;
            }
            windowTicks = 1;
        }

        if (currentY < MIN_VALID_Y) return false;
        if (currentY - windowBaseY < Y_RISE_THRESHOLD) return false;
        return claimRestartWindow(nowMs);
    }

    private boolean claimRestartWindow(long nowMs) {
        if (nowMs - lastRestartMs < RESTART_COOLDOWN_MS) return false;
        lastRestartMs = nowMs;
        return true;
    }
}
