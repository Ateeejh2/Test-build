package com.atlasdead.wanderbot.rotation;

import com.atlasdead.wanderbot.pit.CombatTrackingModel;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MathHelper;

/**
 * Combat-specific aim layer built on ordinary client-side rotation state.
 * It is intentionally conservative: no packet manipulation or anti-cheat bypass logic.
 */
public final class AimController {
    public static final class Result {
        public final float yawError;
        public final float pitchError;
        public final double aimX;
        public final double aimY;
        public final double aimZ;
        public final boolean acquired;
        public final boolean aligned;
        public final String mode;

        private Result(float yawError, float pitchError, double aimX, double aimY, double aimZ,
                       boolean acquired, boolean aligned, String mode) {
            this.yawError = yawError;
            this.pitchError = pitchError;
            this.aimX = aimX;
            this.aimY = aimY;
            this.aimZ = aimZ;
            this.acquired = acquired;
            this.aligned = aligned;
            this.mode = mode;
        }
    }

    private final RotationController rotation;
    private float lastYawError = 180.0F;
    private float lastPitchError = 90.0F;
    private double lastAimX;
    private double lastAimY;
    private double lastAimZ;

    public AimController(RotationController rotation) {
        this.rotation = rotation;
    }

    public void reset() {
        lastYawError = 180.0F;
        lastPitchError = 90.0F;
        lastAimX = lastAimY = lastAimZ = 0.0D;
    }

    public Result update(EntityPlayerSP self, EntityPlayer target,
                         CombatTrackingModel.Snapshot tracking,
                         double distance, boolean visible, boolean combatIntent) {
        if (self == null || target == null) {
            return new Result(180.0F, 90.0F, 0.0D, 0.0D, 0.0D, false, false, "IDLE");
        }

        double[] aim = selectAimPoint(self, target, tracking, distance);
        lastAimX = aim[0];
        lastAimY = aim[1];
        lastAimZ = aim[2];

        if (!combatIntent) {
            lastYawError = horizontalYawError(self, aim[0], aim[2]);
            lastPitchError = pitchError(self, aim[0], aim[1], aim[2]);
            return new Result(lastYawError, lastPitchError, aim[0], aim[1], aim[2], false, false, "TRACK");
        }

        lastYawError = rotation.tick(self, aim[0], aim[1], aim[2], 0.0F);
        lastPitchError = pitchError(self, aim[0], aim[1], aim[2]);

        boolean aligned = visible && lastYawError <= yawAcquireThreshold(distance)
                && lastPitchError <= pitchAcquireThreshold(distance);
        String mode;
        if (!visible) mode = "REACQUIRE";
        else if (tracking != null && tracking.reversal) mode = "REVERSAL";
        else if (tracking != null && tracking.suddenStop) mode = "STOP_ADJUST";
        else if (tracking != null && (tracking.jumping || tracking.descending)) mode = "VERTICAL_TRACK";
        else mode = "TRACK";

        return new Result(lastYawError, lastPitchError, aim[0], aim[1], aim[2], true, aligned, mode);
    }

    private double[] selectAimPoint(EntityPlayerSP self, EntityPlayer target,
                                    CombatTrackingModel.Snapshot tracking, double distance) {
        double lead = MathHelper.clamp_double(distance / 11.0D, 0.035D, 0.20D);
        if (tracking != null) {
            if (tracking.reversal) lead *= 0.45D;
            if (tracking.suddenStop) lead *= 0.55D;
            if (tracking.jumping || tracking.descending) lead *= 0.70D;
        }

        double x = tracking != null ? tracking.predictedX : target.posX + target.motionX * lead;
        double z = tracking != null ? tracking.predictedZ : target.posZ + target.motionZ * lead;

        // Aim near upper torso instead of feet/head extremes. The vertical offset is
        // slightly reduced while the opponent is in the air to keep the point stable.
        double eye = target.getEyeHeight();
        double vertical = target.posY + eye * 0.78D;
        if (tracking != null) {
            vertical = tracking.predictedY + eye * 0.78D;
            if (tracking.jumping) vertical -= 0.03D;
            if (tracking.descending) vertical += 0.03D;
        }
        return new double[] {x, vertical, z};
    }

    private float horizontalYawError(EntityPlayerSP self, double x, double z) {
        double dx = x - self.posX;
        double dz = z - self.posZ;
        if (dx * dx + dz * dz < 1.0E-8D) return 0.0F;
        float desired = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        return Math.abs(MathHelper.wrapAngleTo180_float(desired - self.rotationYaw));
    }

    private float pitchError(EntityPlayerSP self, double x, double y, double z) {
        double dx = x - self.posX;
        double dy = y - (self.posY + self.getEyeHeight());
        double dz = z - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-6D) return 0.0F;
        float desired = (float)(-(Math.atan2(dy, horizontal) * 180.0D / Math.PI));
        return Math.abs(MathHelper.wrapAngleTo180_float(desired - self.rotationPitch));
    }

    private float yawAcquireThreshold(double distance) {
        if (distance < 2.3D) return 10.0F;
        if (distance < 3.2D) return 13.0F;
        if (distance < 4.5D) return 16.0F;
        return 20.0F;
    }

    private float pitchAcquireThreshold(double distance) {
        if (distance < 2.3D) return 12.0F;
        if (distance < 3.2D) return 15.0F;
        return 18.0F;
    }

    public float getLastYawError() { return lastYawError; }
    public float getLastPitchError() { return lastPitchError; }
    public double getLastAimX() { return lastAimX; }
    public double getLastAimY() { return lastAimY; }
    public double getLastAimZ() { return lastAimZ; }
}
