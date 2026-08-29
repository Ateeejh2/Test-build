package com.atlasdead.wanderbot.pit;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Short-horizon opponent tracking for client-side Pit combat.
 * Uses recent vanilla entity motion to classify lateral movement, stops,
 * reversals, and jumps, and produces a conservative aim lead.
 */
public final class CombatTrackingModel {
    public static final class Snapshot {
        public final double lateralSpeed;
        public final double lateralAcceleration;
        public final double forwardSpeed;
        public final double verticalSpeed;
        public final boolean suddenStop;
        public final boolean reversal;
        public final boolean jumping;
        public final boolean descending;
        public final int lateralSign;
        public final double predictedX;
        public final double predictedY;
        public final double predictedZ;

        Snapshot(double lateralSpeed, double lateralAcceleration, double forwardSpeed,
                 double verticalSpeed, boolean suddenStop, boolean reversal,
                 boolean jumping, boolean descending, int lateralSign,
                 double predictedX, double predictedY, double predictedZ) {
            this.lateralSpeed = lateralSpeed;
            this.lateralAcceleration = lateralAcceleration;
            this.forwardSpeed = forwardSpeed;
            this.verticalSpeed = verticalSpeed;
            this.suddenStop = suddenStop;
            this.reversal = reversal;
            this.jumping = jumping;
            this.descending = descending;
            this.lateralSign = lateralSign;
            this.predictedX = predictedX;
            this.predictedY = predictedY;
            this.predictedZ = predictedZ;
        }
    }

    private EntityPlayer tracked;
    private double lastLateral;
    private int lastLateralSign;
    private long lastTime;
    private double lastDistance;

    public void reset() {
        tracked = null;
        lastLateral = 0.0D;
        lastLateralSign = 0;
        lastTime = 0L;
        lastDistance = -1.0D;
    }

    public Snapshot update(EntityPlayer self, EntityPlayer target, long now, double distance) {
        if (self == null || target == null) {
            return new Snapshot(0.0D, 0.0D, 0.0D, 0.0D, false, false, false, false, 0,
                    target == null ? 0.0D : target.posX,
                    target == null ? 0.0D : target.posY,
                    target == null ? 0.0D : target.posZ);
        }

        if (target != tracked) {
            tracked = target;
            lastLateral = 0.0D;
            lastLateralSign = 0;
            lastTime = now;
            lastDistance = distance;
        }

        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double inv = horizontal < 1.0E-4D ? 0.0D : 1.0D / horizontal;
        double forwardX = dx * inv;
        double forwardZ = dz * inv;
        double rightX = -forwardZ;
        double rightZ = forwardX;

        double lateral = target.motionX * rightX + target.motionZ * rightZ;
        double forward = target.motionX * forwardX + target.motionZ * forwardZ;
        double vertical = target.motionY;

        double dt = lastTime == 0L ? 0.05D : Math.max(0.02D, Math.min(0.15D, (now - lastTime) / 1000.0D));
        double lateralAcceleration = (lateral - lastLateral) / dt;
        int sign = Math.abs(lateral) < 0.035D ? 0 : (lateral > 0.0D ? 1 : -1);

        boolean reversal = sign != 0 && lastLateralSign != 0 && sign != lastLateralSign
                && Math.abs(lateral) > 0.06D && Math.abs(lastLateral) > 0.06D;
        boolean suddenStop = Math.abs(lastLateral) > 0.16D && Math.abs(lateral) < 0.055D;
        boolean jumping = target.motionY > 0.10D && !target.onGround;
        boolean descending = target.motionY < -0.10D && !target.onGround;

        // Shorter lead after a reversal/stop to avoid overshooting the new direction.
        double lead = Math.max(0.04D, Math.min(0.22D, distance / 14.0D));
        if (reversal) lead *= 0.45D;
        else if (suddenStop) lead *= 0.55D;
        if (jumping || descending) lead = Math.min(lead, 0.12D);

        double predictedX = target.posX + target.motionX * lead;
        double predictedZ = target.posZ + target.motionZ * lead;
        double predictedY = target.posY + target.motionY * lead;

        // Add a small lateral acceleration correction only when the direction is stable.
        if (!reversal && !suddenStop && Math.abs(lateralAcceleration) > 0.20D) {
            double correction = Math.max(-0.08D, Math.min(0.08D, lateralAcceleration * lead * lead * 0.5D));
            predictedX += rightX * correction;
            predictedZ += rightZ * correction;
        }

        lastLateral = lateral;
        lastLateralSign = sign == 0 ? lastLateralSign : sign;
        lastTime = now;
        lastDistance = distance;

        return new Snapshot(
                Math.abs(lateral),
                lateralAcceleration,
                forward,
                vertical,
                suddenStop,
                reversal,
                jumping,
                descending,
                sign,
                predictedX,
                predictedY,
                predictedZ
        );
    }
}
