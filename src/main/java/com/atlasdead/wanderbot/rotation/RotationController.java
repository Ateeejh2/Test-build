package com.atlasdead.wanderbot.rotation;

import com.atlasdead.wanderbot.humanization.Humanizer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Rotation controller with normal humanized combat rotation and deterministic
 * path-following yaw control. Path rotation never owns pitch because a movement
 * checkpoint is a locomotion target, not an aiming target.
 */
public class RotationController {
    private float yawVelocity;
    private float pitchVelocity;
    private float noise;
    private float lastYawError;
    private int overshootTicks;
    private float overshootAmount;
    private int distractionTicks;
    private float distractionYaw;
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

        if (distractionTicks > 0) {
            distractionTicks--;
            targetYaw += distractionYaw;
        } else if (Humanizer.isDistracted()) {
            distractionTicks = Humanizer.range(2, 6);
            distractionYaw = Humanizer.distractionYaw();
            targetYaw += distractionYaw;
        }

        if (overshootTicks > 0) {
            overshootTicks--;
            targetYaw += overshootAmount;
        } else if (Humanizer.shouldOvershoot() && lastYawError < 15.0F) {
            overshootTicks = Humanizer.range(2, 4);
            overshootAmount = Humanizer.overshootDegrees();
        }

        noise = noise * 0.88F + (float)((Math.random() - 0.5D) * 0.18D);
        targetYaw += noise + Humanizer.microJitter();

        float delta = MathHelper.wrapAngleTo180_float(targetYaw - player.rotationYaw);
        lastYawError = Math.abs(delta);

        float speedMult = Humanizer.rotationSpeedMultiplier();
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

        float pitchDelta = MathHelper.wrapAngleTo180_float(targetPitch - player.rotationPitch);
        float pitchNoise = Humanizer.gaussian(0.3F);
        float desiredPitch = (pitchDelta + pitchNoise) * 0.28F;
        if (desiredPitch > 5.5F) desiredPitch = 5.5F;
        if (desiredPitch < -5.5F) desiredPitch = -5.5F;
        pitchVelocity = approach(pitchVelocity, desiredPitch, 1.5F * speedMult);
        player.rotationPitch += pitchVelocity;
        if (player.rotationPitch > 89.0F) player.rotationPitch = 89.0F;
        if (player.rotationPitch < -89.0F) player.rotationPitch = -89.0F;
        return lastYawError;
    }

    /**
     * Deterministic path-following yaw. The target is the horizontal direction
     * of the active checkpoint; pitch is intentionally untouched.
     */
    public float tickPath(EntityPlayerSP player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.posX;
        double dz = targetZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) {
            yawVelocity = 0.0F;
            lastYawError = 0.0F;
            return 0.0F;
        }

        float targetYaw = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float delta = MathHelper.wrapAngleTo180_float(targetYaw - player.rotationYaw);
        lastYawError = Math.abs(delta);

        float desired;
        if (lastYawError > 90.0F) desired = 18.0F;
        else if (lastYawError > 45.0F) desired = 14.0F;
        else if (lastYawError > 20.0F) desired = 10.0F;
        else if (lastYawError > 6.0F) desired = 6.0F;
        else desired = 2.2F;

        if (delta < 0.0F) desired = -desired;
        float acceleration = lastYawError > 35.0F ? 3.5F : 2.4F;
        yawVelocity = approach(yawVelocity, desired, acceleration);

        float step = Math.min(lastYawError, Math.max(0.75F, Math.abs(yawVelocity)));
        if (lastYawError <= 1.5F) step = Math.min(step, 0.6F);
        player.rotationYaw += delta < 0.0F ? -step : step;
        player.rotationYawHead = player.rotationYaw;
        player.renderYawOffset = player.rotationYaw;

        // Do not modify pitch here. A checkpoint directly below the eye is still
        // a horizontal movement waypoint and should never pull the camera down.
        return lastYawError;
    }

    public boolean shouldSprint(boolean currentSprint) {
        if (sprintBurstRemaining > 0) {
            sprintBurstRemaining--;
            return currentSprint;
        }
        if (Humanizer.shouldToggleSprintOff()) {
            sprintBurstRemaining = Humanizer.sprintBurstTicks();
            return false;
        }
        return currentSprint;
    }

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
        noise = 0;
        lastYawError = 0;
        overshootTicks = 0;
        overshootAmount = 0;
        distractionTicks = 0;
        distractionYaw = 0;
        sprintBurstRemaining = 0;
        microPauseRemaining = 0;
    }

    private float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        if (current > target) return Math.max(target, current - amount);
        return current;
    }
}
