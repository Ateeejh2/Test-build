package com.atlasdead.wanderbot.rotation;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/** Fast, acceleration-based, low-noise humanized steering. */
public class RotationController {
    private float yawVelocity;
    private float pitchVelocity;
    private float noise;
    private float lastYawError;

    public float tick(EntityPlayerSP player, double targetX, double targetY, double targetZ, float yawBias) {
        double dx = targetX - player.posX;
        double dy = targetY - (player.posY + player.getEyeHeight());
        double dz = targetZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) return lastYawError;

        float targetYaw = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        targetYaw += yawBias;
        float targetPitch = (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));

        // Very small correlated noise, not random jitter.
        noise = noise * 0.92F + (float)((Math.random() - 0.5D) * 0.12D);
        targetYaw += noise;

        float delta = MathHelper.wrapAngleTo180_float(targetYaw - player.rotationYaw);
        lastYawError = Math.abs(delta);

        float desired;
        if (lastYawError > 120.0F) desired = 28.0F;
        else if (lastYawError > 80.0F) desired = 24.0F;
        else if (lastYawError > 45.0F) desired = 19.0F;
        else if (lastYawError > 20.0F) desired = 13.0F;
        else if (lastYawError > 7.0F) desired = 7.0F;
        else desired = 2.8F;

        if (delta < 0.0F) desired = -desired;
        float acceleration = lastYawError > 45.0F ? 6.5F : 4.0F;
        yawVelocity = approach(yawVelocity, desired, acceleration);

        float step = Math.min(lastYawError, Math.max(1.0F, Math.abs(yawVelocity)));
        player.rotationYaw += delta < 0.0F ? -step : step;
        player.rotationYawHead = player.rotationYaw;
        player.renderYawOffset = player.rotationYaw;

        float pitchDelta = MathHelper.wrapAngleTo180_float(targetPitch - player.rotationPitch);
        float desiredPitch = pitchDelta * 0.30F;
        if (desiredPitch > 5.0F) desiredPitch = 5.0F;
        if (desiredPitch < -5.0F) desiredPitch = -5.0F;
        pitchVelocity = approach(pitchVelocity, desiredPitch, 1.5F);
        player.rotationPitch += pitchVelocity;
        if (player.rotationPitch > 89.0F) player.rotationPitch = 89.0F;
        if (player.rotationPitch < -89.0F) player.rotationPitch = -89.0F;
        return lastYawError;
    }

    public float getLastYawError() { return lastYawError; }

    private float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        if (current > target) return Math.max(target, current - amount);
        return current;
    }
}
