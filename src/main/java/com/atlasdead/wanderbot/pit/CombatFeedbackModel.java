package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Observation-only feedback loop for combat decisions. It does not send
 * packets or try to infer anti-cheat rules. It records health/visibility/
 * distance transitions so higher-level logic can react to actual outcomes.
 */
public final class CombatFeedbackModel {
    public enum Event {
        NONE,
        HIT_CONFIRMED,
        SELF_DAMAGED,
        TARGET_DAMAGED,
        TARGET_LOST,
        RANGE_LOST,
        VISIBILITY_LOST
    }

    public static final class Snapshot {
        public final Event event;
        public final float selfHealth;
        public final float targetHealth;
        public final float selfDelta;
        public final float targetDelta;
        public final boolean targetVisible;
        public final double distance;

        Snapshot(Event event, float selfHealth, float targetHealth, float selfDelta, float targetDelta,
                  boolean targetVisible, double distance) {
            this.event = event;
            this.selfHealth = selfHealth;
            this.targetHealth = targetHealth;
            this.selfDelta = selfDelta;
            this.targetDelta = targetDelta;
            this.targetVisible = targetVisible;
            this.distance = distance;
        }
    }

    private EntityPlayer trackedTarget;
    private float previousSelfHealth = -1.0F;
    private float previousTargetHealth = -1.0F;
    private boolean previousVisible;
    private double previousDistance = -1.0D;
    private long lastTargetDamageAt;

    public void reset() {
        trackedTarget = null;
        previousSelfHealth = -1.0F;
        previousTargetHealth = -1.0F;
        previousVisible = false;
        previousDistance = -1.0D;
        lastTargetDamageAt = 0L;
    }

    public Snapshot update(EntityPlayerSP self, EntityPlayer target, long now) {
        if (self == null || target == null) {
            reset();
            return new Snapshot(Event.NONE, self == null ? -1.0F : self.getHealth(), -1.0F, 0.0F, 0.0F, false, 0.0D);
        }
        if (trackedTarget != target) {
            trackedTarget = target;
            previousSelfHealth = self.getHealth();
            previousTargetHealth = target.getHealth();
            previousVisible = self.canEntityBeSeen(target);
            previousDistance = self.getDistanceToEntity(target);
            lastTargetDamageAt = 0L;
            return new Snapshot(Event.NONE, self.getHealth(), target.getHealth(), 0.0F, 0.0F, previousVisible, previousDistance);
        }

        float selfHealth = self.getHealth();
        float targetHealth = target.getHealth();
        float selfDelta = selfHealth - previousSelfHealth;
        float targetDelta = targetHealth - previousTargetHealth;
        boolean visible = self.canEntityBeSeen(target);
        double distance = self.getDistanceToEntity(target);
        Event event = Event.NONE;

        if (target.isDead || targetHealth <= 0.0F) {
            event = Event.TARGET_DAMAGED;
        } else if (targetDelta < -0.01F) {
            event = Event.TARGET_DAMAGED;
            lastTargetDamageAt = now;
        } else if (selfDelta < -0.01F) {
            event = Event.SELF_DAMAGED;
        } else if (previousVisible && !visible) {
            event = Event.VISIBILITY_LOST;
        } else if (previousDistance <= 4.5D && distance > 5.25D) {
            event = Event.RANGE_LOST;
        } else if (lastTargetDamageAt > 0L && now - lastTargetDamageAt < 180L && selfDelta <= 0.01F) {
            event = Event.HIT_CONFIRMED;
        } else if (distance > 7.0D && !visible) {
            event = Event.TARGET_LOST;
        }

        previousSelfHealth = selfHealth;
        previousTargetHealth = targetHealth;
        previousVisible = visible;
        previousDistance = distance;
        return new Snapshot(event, selfHealth, targetHealth, selfDelta, targetDelta, visible, distance);
    }
}
