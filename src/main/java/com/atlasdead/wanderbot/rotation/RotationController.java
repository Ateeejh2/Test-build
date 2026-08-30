package com.atlasdead.wanderbot.rotation;

import com.atlasdead.wanderbot.humanization.Humanizer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.MathHelper;

/**
 * Rotation controller with normal humanized combat rotation and a deterministic
 * smooth path-rotation mode. Path rotation deliberately avoids random target
 * offsets so the view remains aligned with the rendered waypoint.
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

    // Path-rotation-only state. The rendered waypoint can move by a small amount
    // every tick, so keep a stable target heading and ease it toward larger
    // changes instead of feeding raw waypoint yaw directly into the steering loop.
    private boolean pathYawInitialized;
    private float pathTargetYaw;
    private float pathRequestedYaw;

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
        float jitter = Humanizer.microJitter();
        targetYaw += noise + jitter;

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
     * Deterministic smooth rotation used when following a rendered path.
     *
     * The waypoint direction is first stabilized into pathTargetYaw. Tiny
     * direction changes (<= 1.5 degrees) are ignored, while larger changes are
     * eased into the target. The actual player yaw is then approached with a
     * bounded acceleration curve. No jitter, random distraction, or overshoot
     * is used here, keeping rotation aligned with the rendered route.
     */
    public float tickPath(EntityPlayerSP player, double targetX, double targetY, double targetZ) {
        double dx = targetX - player.posX;
        double dy = targetY - (player.posY + player.getEyeHeight());
        double dz = targetZ - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001D) return lastYawError;

        float rawTargetYaw = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float targetPitch = (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));

        if (!pathYawInitialized) {
            pathYawInitialized = true;
            pathTargetYaw = rawTargetYaw;
            pathRequestedYaw = rawTargetYaw;
        } else {
            float requestDelta = MathHelper.wrapAngleTo180_float(rawTargetYaw - pathRequestedYaw);
            pathRequestedYaw = rawTargetYaw;

            // Ignore tiny waypoint-direction noise instead of making the view
            // chase one-degree changes every tick.
            if (Math.abs(requestDelta) > 1.5F) {
                float follow;
                float magnitude = Math.abs(requestDelta);
                if (magnitude > 45.0F) follow = 0.55F;
                else if (magnitude > 20.0F) follow = 0.40F;
                else follow = 0.28F;

                float targetDelta = MathHelper.wrapAngleTo180_float(rawTargetYaw - pathTargetYaw);
                pathTargetYaw += targetDelta * follow;
            }
        }

        float delta = MathHelper.wrapAngleTo180_float(pathTargetYaw - player.rotationYaw);
        lastYawError = Math.abs(delta);

        // Smooth acceleration by error size. Small errors settle gently instead
        // of producing visible 1-2 degree left/right corrections.
        float desired;
        if (lastYawError > 100.0F) desired = 16.0F;
        else if (lastYawError > 55.0F) desired = 12.0F;
        else if (lastYawError > 25.0F) desired = 8.0F;
        else if (lastYawError > 8.0F) desired = 4.5F;
        else if (lastYawError > 2.0F) desired = 2.0F;
        else desired = 0.0F;

        if (delta < 0.0F) desired = -desired;
        float acceleration = lastYawError > 35.0F ? 2.6F : 1.8F;

        // Prevent stale velocity from carrying the view across a newly smoothed
        // target when the desired direction changes.
        if (desired != 0.0F && yawVelocity != 0.0F
                && ((desired > 0.0F && yawVelocity < 0.0F)
                || (desired < 0.0F && yawVelocity > 0.0F))) {
            yawVelocity = 0.0F;
        }
        yawVelocity = approach(yawVelocity, desired, acceleration);

        float step = Math.min(lastYawError, Math.max(0.35F, Math.abs(yawVelocity)));
        if (lastYawError <= 1.5F) {
            step = Math.min(step, 0.6F);
        }
        if (delta < 0.0F) step = -step;
        player.rotationYaw += step;
        player.rotationYawHead = player.rotationYaw;
        player.renderYawOffset = player.rotationYaw;

        float pitchDelta = MathHelper.wrapAngleTo180_float(targetPitch - player.rotationPitch);
        float desiredPitch = Math.max(-3.5F, Math.min(3.5F, pitchDelta * 0.28F));
        pitchVelocity = approach(pitchVelocity, desiredPitch, 1.0F);
        player.rotationPitch += pitchVelocity;
        player.rotationPitch = Math.max(-89.0F, Math.min(89.0F, player.rotationPitch));
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
        pathYawInitialized = false;
        pathTargetYaw = 0;
        pathRequestedYaw = 0;
    }

    private float approach(float current, float target, float amount) {
        if (current < target) return Math.min(target, current + amount);
        if (current > target) return Math.max(target, current - amount);
        return current;
    }
}
