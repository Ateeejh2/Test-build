package com.atlasdead.wanderbot.pit;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Lightweight tactical model for close-quarters Pit combat.
 * It converts relative motion into a stable preferred distance and strafe
 * direction without injecting packets or bypassing server-side validation.
 */
public final class CombatTacticalModel {
    public static final class State {
        public final double horizontalDistance;
        public final double verticalDistance;
        public final double closingRate;
        public final double targetSpeed;
        public final double relativeSpeed;
        public final float preferredDistance;
        public final int strafeSign;
        public final boolean targetApproaching;
        public final boolean targetRetreating;

        State(double horizontalDistance, double verticalDistance, double closingRate,
              double targetSpeed, double relativeSpeed, float preferredDistance,
              int strafeSign, boolean targetApproaching, boolean targetRetreating) {
            this.horizontalDistance = horizontalDistance;
            this.verticalDistance = verticalDistance;
            this.closingRate = closingRate;
            this.targetSpeed = targetSpeed;
            this.relativeSpeed = relativeSpeed;
            this.preferredDistance = preferredDistance;
            this.strafeSign = strafeSign;
            this.targetApproaching = targetApproaching;
            this.targetRetreating = targetRetreating;
        }
    }

    private double lastDistance = -1.0D;
    private long lastTime;
    private int lockedStrafe = 1;
    private long strafeLockUntil;
    private final CombatTrackingModel tracking = new CombatTrackingModel();
    private CombatTrackingModel.Snapshot trackingState;

    public void reset() {
        lastDistance = -1.0D;
        lastTime = 0L;
        lockedStrafe = 1;
        strafeLockUntil = 0L;
        tracking.reset();
        trackingState = null;
    }

    public State update(EntityPlayer self, EntityPlayer target, long now) {
        if (self == null || target == null) {
            return new State(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 2.35F, lockedStrafe, false, false);
        }

        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double vertical = Math.abs(target.posY - self.posY);

        double targetSpeed = Math.sqrt(target.motionX * target.motionX + target.motionZ * target.motionZ);
        trackingState = tracking.update(self, target, now, horizontal);
        double relativeX = target.motionX - self.motionX;
        double relativeZ = target.motionZ - self.motionZ;
        double relativeSpeed = Math.sqrt(relativeX * relativeX + relativeZ * relativeZ);

        double closingRate = 0.0D;
        if (lastDistance >= 0.0D && lastTime > 0L && now > lastTime) {
            double seconds = Math.min(0.20D, (now - lastTime) / 1000.0D);
            if (seconds > 0.0D) closingRate = (lastDistance - horizontal) / seconds;
        }
        lastDistance = horizontal;
        lastTime = now;

        // Keep combat around a modest vanilla melee spacing. Fast targets get
        // a slightly larger preferred gap so movement has room to react.
        float preferred = (float)Math.max(2.15D, Math.min(3.05D, 2.30D + targetSpeed * 0.65D));

        if (now >= strafeLockUntil) {
            double targetHeadingX = target.motionX;
            double targetHeadingZ = target.motionZ;
            double cross = targetHeadingX * dz - targetHeadingZ * dx;
            if (Math.abs(cross) > 0.015D) {
                lockedStrafe = cross > 0.0D ? -1 : 1;
            } else {
                // Use the relative position to keep the orbit side stable.
                double side = self.motionX * dz - self.motionZ * dx;
                if (Math.abs(side) > 0.02D) lockedStrafe = side > 0.0D ? -1 : 1;
            }
            strafeLockUntil = now + 700L;
        }

        return new State(
                horizontal,
                vertical,
                closingRate,
                targetSpeed,
                relativeSpeed,
                preferred,
                lockedStrafe,
                closingRate > 0.18D,
                closingRate < -0.18D
        );
    }

    public CombatTrackingModel.Snapshot getTrackingState() {
        return trackingState;
    }
}
