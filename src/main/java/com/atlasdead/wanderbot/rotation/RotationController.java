package com.atlasdead.wanderbot.rotation;

import com.atlasdead.wanderbot.humanization.Humanizer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Deterministic rotation controller used by navigation/combat pathfinding.
 * No random rotation perturbation is applied.
 */
public class RotationController {
    private float yawVelocity;
    private float pitchVelocity;
    private float lastYawError;
    private int sprintBurstRemaining;
    private int microPauseRemaining;

    public float tick(EntityPlayerSP player, double targetX, double targetY, double targetZ, float yawBias) {
        double dx = targetX - player.posX;
        double dy = targetY - (player.posY + player.getEyeHeight());
        double dz = targetZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) return lastYawError;

        float targetYaw = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        targetYaw += yawBias;
        float targetPitch = (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));

        // Deterministic pathfinding rotation: no overshoot, distraction, noise, or jitter.

        float delta = MathHelper.wrapAngleTo180_float(targetYaw - player.rotationYaw);
        lastYawError = Math.abs(delta);

        // Variable speed based on distance and context
        float speedMult = 1.0F;
        float desired;
        if (lastYawError > 120.0F) desired = 28.0F * speedMult;
        else if (lastYawError > 80.0F) desired = 24.0F * speedMult;
        else if (lastYawError > 45.0F) desired = 19.0F * speedMult;
        else if (lastYawError > 20.0F) desired = 13.0F * speedMult;
        else if (lastYawError > 7.0F) desired = 7.0F * speedMult;
        else desired = 2.8F * speedMult;

        if (delta < 0.0F) desired = -desired;
        float acceleration = lastYawError > 45.0F ? 6.5F * speedMult : 4.0F * speedMult;
        yawVelocity = approach(yawVelocity, desired, acceleration);

        float step = Math.min(lastYawError, Math.max(1.0F, Math.abs(yawVelocity)));
        player.rotationYaw += delta < 0.0F ? -step : step;
        player.rotationYawHead = player.rotationYaw;
        player.renderYawOffset = player.rotationYaw;

        // Deterministic pitch tracking for pathfinding.
        float pitchDelta = MathHelper.wrapAngleTo180_float(targetPitch - player.rotationPitch);
        float desiredPitch = pitchDelta * 0.28F;
        if (desiredPitch > 5.5F) desiredPitch = 5.5F;
        if (desiredPitch < -5.5F) desiredPitch = -5.5F;
        pitchVelocity = approach(pitchVelocity, desiredPitch, 1.5F * speedMult);
        player.rotationPitch += pitchVelocity;
        if (player.rotationPitch > 89.0F) player.rotationPitch = 89.0F;
        if (player.rotationPitch < -89.0F) player.rotationPitch = -89.0F;
        return lastYawError;
    }

    /**
     * Sprint toggle controller for combat movement.
     * Returns true if sprint should be on, false if it should be toggled off.
     * Simulates sprint-reset pattern (every 3-8 ticks during combat).
     */
    public boolean shouldSprint(boolean currentSprint) {
        if (sprintBurstRemaining > 0) {
            sprintBurstRemaining--;
            return currentSprint;
        }
        if (Humanizer.shouldToggleSprintOff()) {
            sprintBurstRemaining = Humanizer.sprintBurstTicks();
            return false; // Toggle off briefly
        }
        return currentSprint;
    }

    /**
     * Micro-pause controller.
     * Returns true if movement should be paused this tick.
     */
    public boolean shouldMicroPause() {
        if (microPauseRemaining > 0) {
            microPauseRemaining--;
            return true;
        }
        if (Humanizer.shouldMicroPause()) {
            microPauseRemaining = Humanizer.microPauseTicks();
            return true;
        }
        return false;
    }

    public float getLastYawError() { return lastYawError; }

    public void reset() {
        yawVelocity = 0;
        pitchVelocity = 0;
        lastYawError = 0;
        sprintBurstRemaining = 0;
        microPauseRemaining = 0;
    }

    private float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        if (current > target) return Math.max(target, current - amount);
        return current;
    }
}
