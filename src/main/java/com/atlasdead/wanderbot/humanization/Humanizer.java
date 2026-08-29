package com.atlasdead.wanderbot.humanization;

import java.util.Random;

/**
 * Central humanization utility.
 *
 * Provides human-like randomization for combat timing, rotation, and movement.
 * All values are tuned for Hypixel WatchDog tolerance:
 *  - CPS: 6-14 (most humans average 8-12)
 *  - Attack delay: 280-420ms (vanilla cooldown + human jitter)
 *  - Aim error: ±2-6 degrees typical, ±10 max
 *  - Rotation overshoot: occasional, ±3-8 degrees
 *  - Sprint toggle: every 3-8 attacks
 *  - Micro-pauses: 1-4 ticks every 20-50 ticks
 */
public final class Humanizer {
    private static final Random RNG = new Random();

    private Humanizer() {}

    // =====================================================================
    // Attack Timing
    // =====================================================================

    /**
     * Returns a human-like attack delay in ticks (20 ticks = 1 second).
     * Vanilla 1.8.9 cooldown is ~6 ticks (300ms).
     * Humans vary between ~280ms (5.6 ticks) and ~420ms (8.4 ticks).
     *
     * WatchDog looks for:
     *  - Exactly N ticks between hits (bot signature)
     *  - CPS above 20 (impossible for humans)
     *  - Perfectly regular hit intervals
     */
    public static int attackDelayTicks() {
        // 60% chance: 6 ticks (standard), 20%: 5 ticks (fast), 20%: 7 ticks (slow)
        int r = RNG.nextInt(100);
        if (r < 20) return 5;
        if (r < 80) return 6;
        if (r < 95) return 7;
        return 8; // 5% chance of slow hit (8 ticks = 400ms)
    }

    /**
     * Occasionally skip an attack tick to simulate human hesitation.
     * Returns true ~8% of the time during combat.
     */
    public static boolean shouldHesitate() {
        return RNG.nextInt(100) < 8;
    }

    /**
     * Returns a human-like "miss" probability.
     * Real players don't hit every single attack opportunity.
     * ~5% chance to skip an otherwise valid attack.
     */
    public static boolean shouldMiss() {
        return RNG.nextInt(100) < 5;
    }

    // =====================================================================
    // Rotation / Aim
    // =====================================================================

    /**
     * Returns aim error in degrees.
     * Even good players have ±2-6° aim error.
     * Occasionally ±10° (when distracted, turning, etc.)
     */
    public static float aimErrorDegrees() {
        float base = (float)(RNG.nextGaussian() * 2.5);
        // Clamp to [-8, 8]
        if (base > 8.0F) base = 8.0F;
        if (base < -8.0F) base = -8.0F;
        // 5% chance of larger miss
        if (RNG.nextInt(100) < 5) {
            base *= 2.0F;
            if (base > 12.0F) base = 12.0F;
            if (base < -12.0F) base = -12.0F;
        }
        return base;
    }

    /**
     * Returns rotation acceleration multiplier.
     * Real players vary speed based on distance, attention, etc.
     * Range: 0.7 (lazy) to 1.3 (focused)
     */
    public static float rotationSpeedMultiplier() {
        float base = 1.0F + (float)(RNG.nextGaussian() * 0.15);
        if (base < 0.7F) base = 0.7F;
        if (base > 1.3F) base = 1.3F;
        return base;
    }

    /**
     * Whether the player should overshoot the target this tick.
     * ~12% chance when angle is small (fine correction phase).
     */
    public static boolean shouldOvershoot() {
        return RNG.nextInt(100) < 12;
    }

    /**
     * Returns overshoot amount in degrees.
     * Typically ±3-8° past the target.
     */
    public static float overshootDegrees() {
        float sign = RNG.nextBoolean() ? 1.0F : -1.0F;
        return sign * (3.0F + RNG.nextFloat() * 5.0F);
    }

    /**
     * Returns micro-correction jitter in degrees.
     * Applied to rotation each tick to prevent perfect tracking.
     */
    public static float microJitter() {
        return (float)(RNG.nextGaussian() * 0.8);
    }

    /**
     * Whether the player is momentarily looking away (distraction).
     * ~3% chance per tick during tracking.
     */
    public static boolean isDistracted() {
        return RNG.nextInt(100) < 3;
    }

    /**
     * Returns the distraction yaw offset in degrees (±15-40°).
     */
    public static float distractionYaw() {
        float sign = RNG.nextBoolean() ? 1.0F : -1.0F;
        return sign * (15.0F + RNG.nextFloat() * 25.0F);
    }

    // =====================================================================
    // Movement
    // =====================================================================

    /**
     * Whether to toggle sprint off briefly (simulate sprint-reset or stamina).
     * Called every tick during sprint. ~6% chance per tick.
     */
    public static boolean shouldToggleSprintOff() {
        return RNG.nextInt(100) < 6;
    }

    /**
     * How many ticks to stay sprinting before a toggle.
     * Range: 3-8 ticks.
     */
    public static int sprintBurstTicks() {
        return 3 + RNG.nextInt(6);
    }

    /**
     * Whether to perform a micro-pause (stop moving briefly).
     * ~4% chance per tick when moving.
     */
    public static boolean shouldMicroPause() {
        return RNG.nextInt(100) < 4;
    }

    /**
     * Duration of a micro-pause in ticks.
     * Range: 1-3 ticks.
     */
    public static int microPauseTicks() {
        return 1 + RNG.nextInt(3);
    }

    /**
     * Whether to randomly strafe during combat approach.
     * ~15% chance per tick.
     */
    public static boolean shouldStrafe() {
        return RNG.nextInt(100) < 15;
    }

    /**
     * Returns strafe direction: -1 (left), 0 (straight), +1 (right).
     * Weighted toward forward movement.
     */
    public static float strafeDirection() {
        int r = RNG.nextInt(100);
        if (r < 55) return 0.0F;       // 55% straight
        if (r < 78) return -0.5F;      // 23% slight left
        if (r < 93) return 0.5F;       // 15% slight right
        return RNG.nextBoolean() ? -0.7F : 0.7F; // 7% hard strafe
    }

    /**
     * Whether to randomly change strafe direction.
     * ~10% chance per tick when strafing.
     */
    public static boolean shouldChangeStrafe() {
        return RNG.nextInt(100) < 10;
    }

    /**
     * Whether to randomly jump during combat movement.
     * ~8% chance per tick when on ground and moving.
     */
    public static boolean shouldRandomJump() {
        return RNG.nextInt(100) < 8;
    }

    // =====================================================================
    // Targeting
    // =====================================================================

    /**
     * Returns human-like yaw tolerance for attack validity.
     * Real players attack when roughly looking at target.
     * Range: 25-40° (average ~32°)
     */
    public static float attackYawTolerance() {
        float base = 30.0F + (float)(RNG.nextGaussian() * 5.0);
        if (base < 22.0F) base = 22.0F;
        if (base > 42.0F) base = 42.0F;
        return base;
    }

    /**
     * Whether to target-switch occasionally even when current target is valid.
     * ~5% chance per evaluation cycle (simulates human distraction).
     */
    public static boolean shouldDistractionRetarget() {
        return RNG.nextInt(100) < 5;
    }

    /**
     * Returns a score penalty for targets that look suspicious.
     * Filter out players with impossible movement patterns.
     */
    public static boolean looksLikeBot(net.minecraft.entity.player.EntityPlayer player) {
        if (player == null) return true;
        // Players moving at impossible speed
        double speed = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        if (speed > 0.6D) return true; // Sprint speed is ~0.28
        // Players with 0 health but not dead (lagged out)
        if (player.getHealth() <= 0F && !player.isDead) return true;
        // Players in creative mode
        if (player.capabilities != null && player.capabilities.isCreativeMode) return true;
        return false;
    }

    // =====================================================================
    // Utility
    // =====================================================================

    /**
     * Gaussian random centered at 0 with given standard deviation.
     */
    public static float gaussian(float sd) {
        return (float)(RNG.nextGaussian() * sd);
    }

    /**
     * Random float in range [min, max].
     */
    public static float range(float min, float max) {
        return min + RNG.nextFloat() * (max - min);
    }

    /**
     * Random int in range [min, max] inclusive.
     */
    public static int range(int min, int max) {
        return min + RNG.nextInt(max - min + 1);
    }
}
