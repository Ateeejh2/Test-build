package com.atlasdead.wanderbot.humanization;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Random;

/** Central randomization utility used by combat, rotation and movement. */
public final class Humanizer {
    private static final Random RNG = new Random();

    private Humanizer() {}

    public static int attackDelayTicks() {
        if (!enabled()) return 6;
        int r = RNG.nextInt(100);
        if (r < 20) return 5;
        if (r < 80) return 6;
        if (r < 95) return 7;
        return 8;
    }

    public static boolean shouldHesitate() {
        return chance(WanderBotSettings.attackHesitationChance);
    }

    public static boolean shouldMiss() {
        return chance(WanderBotSettings.attackMissChance);
    }

    public static float aimErrorDegrees() {
        if (!enabled()) return 0.0F;
        float value = gaussian(WanderBotSettings.aimErrorSD);
        value = clamp(value, -8.0F, 8.0F);
        if (chance(5.0F)) value = clamp(value * 2.0F, -12.0F, 12.0F);
        return value;
    }

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

    public static boolean shouldToggleSprintOff() {
        return chance(WanderBotSettings.sprintToggleChance);
    }

    public static int sprintBurstTicks() {
        return enabled() ? 3 + RNG.nextInt(6) : 6;
    }

    public static boolean shouldMicroPause() {
        return chance(WanderBotSettings.microPauseChance);
    }

    public static int microPauseTicks() {
        return enabled() ? 1 + RNG.nextInt(3) : 0;
    }

    public static boolean shouldStrafe() {
        return chance(WanderBotSettings.strafeChance);
    }

    public static float strafeDirection() {
        if (!enabled()) return 0.0F;
        int r = RNG.nextInt(100);
        if (r < 55) return 0.0F;
        if (r < 78) return -0.5F;
        if (r < 93) return 0.5F;
        return randomSign() * 0.7F;
    }

    public static boolean shouldChangeStrafe() {
        return chance(10.0F);
    }

    public static boolean shouldRandomJump() {
        return chance(8.0F);
    }

    public static float attackYawTolerance() {
        if (!enabled()) return 30.0F;
        return clamp(30.0F + gaussian(5.0F), 22.0F, 42.0F);
    }

    public static boolean shouldDistractionRetarget() {
        return chance(5.0F);
    }

    public static boolean looksLikeBot(EntityPlayer player) {
        if (player == null) return true;
        double speed = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
        if (speed > 0.6D) return true;
        if (player.getHealth() <= 0F && !player.isDead) return true;
        return player.capabilities != null && player.capabilities.isCreativeMode;
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
