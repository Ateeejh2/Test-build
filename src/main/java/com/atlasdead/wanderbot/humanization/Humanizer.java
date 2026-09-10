package com.atlasdead.wanderbot.humanization;

import com.atlasdead.wanderbot.config.WanderBotSettings;

import java.util.Random;

/** Conservative variation used only while WanderBot itself owns rotation. */
public final class Humanizer {
    private static final Random RNG = new Random();

    private Humanizer() {}

    public static float rotationSpeedMultiplier() {
        if (!enabled()) return 1.0F;
        return clamp(1.0F + gaussian(WanderBotSettings.rotationSpeedSD), 0.7F, 1.3F);
    }

    public static boolean shouldOvershoot() {
        return chance(WanderBotSettings.overshootChance);
    }

    public static float overshootDegrees() {
        if (!enabled()) return 0.0F;
        return randomSign() * (3.0F + RNG.nextFloat() * 5.0F);
    }

    public static float microJitter() {
        return enabled() ? gaussian(0.8F) : 0.0F;
    }

    public static boolean isDistracted() {
        return chance(WanderBotSettings.distractionChance);
    }

    public static float distractionYaw() {
        if (!enabled()) return 0.0F;
        return randomSign() * (15.0F + RNG.nextFloat() * 25.0F);
    }


    public static float gaussian(float standardDeviation) {
        if (!enabled()) return 0.0F;
        return (float) (RNG.nextGaussian() * standardDeviation);
    }

    /** Returns a random integer in the inclusive [minInclusive, maxInclusive] range. */
    public static int range(int minInclusive, int maxInclusive) {
        if (maxInclusive <= minInclusive) return minInclusive;
        return minInclusive + RNG.nextInt(maxInclusive - minInclusive + 1);
    }

    private static boolean enabled() {
        return WanderBotSettings.humanizationEnabled;
    }

    private static boolean chance(float percent) {
        if (!enabled() || percent <= 0.0F) return false;
        if (percent >= 100.0F) return true;
        return RNG.nextFloat() * 100.0F < percent;
    }

    private static float randomSign() {
        return RNG.nextBoolean() ? 1.0F : -1.0F;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
