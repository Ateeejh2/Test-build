package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.List;

/**
 * Combat v5 control arbitration. Converts tactical observations into a stable
 * combat envelope: approach, maintain distance, or yield to retargeting.
 * It only reasons about ordinary in-game movement/attack state.
 */
public final class CombatControlModel {
    public enum Phase { APPROACH, MAINTAIN, RETREAT, REASSESS }

    public static final class Decision {
        public final Phase phase;
        public final boolean targetStillValid;
        public final boolean requestRetarget;
        public final boolean allowAttack;
        public final double desiredDistance;
        public final double lateralBias;
        public final String reason;

        Decision(Phase phase, boolean targetStillValid, boolean requestRetarget,
                 boolean allowAttack, double desiredDistance, double lateralBias, String reason) {
            this.phase = phase;
            this.targetStillValid = targetStillValid;
            this.requestRetarget = requestRetarget;
            this.allowAttack = allowAttack;
            this.desiredDistance = desiredDistance;
            this.lateralBias = lateralBias;
            this.reason = reason;
        }
    }

    private final Minecraft mc;
    private final PitZoneManager zones;
    private final TargetTracker targets;
    private long reassessUntil;
    private int heldTargetEntityId = -1;

    public CombatControlModel(Minecraft mc, PitZoneManager zones, TargetTracker targets) {
        this.mc = mc;
        this.zones = zones;
        this.targets = targets;
    }

    public void reset() {
        reassessUntil = 0L;
        heldTargetEntityId = -1;
    }

    public Decision evaluate(EntityPlayerSP self, EntityPlayer target,
                             CombatTacticalModel.State tactical,
                             long now) {
        if (self == null || target == null) {
            return new Decision(Phase.REASSESS, false, false, false, 2.4D, 0.0D, "no-target");
        }
        if (zones != null && (zones.isSelfProtected(self) || zones.isPlayerProtected(target))) {
            return new Decision(Phase.RETREAT, false, false, false, 2.6D, 0.0D, "protected-zone");
        }
        if (targets != null && !targets.isViable(self, 18.0D)) {
            return new Decision(Phase.REASSESS, false, true, false, 2.4D, 0.0D, "target-invalid");
        }

        if (heldTargetEntityId != target.getEntityId()) {
            heldTargetEntityId = target.getEntityId();
            reassessUntil = now + 650L;
        }

        int nearby = countEligibleOpponents(self, target, 5.5D);
        double distance = self.getDistanceToEntity(target);
        double desired = tactical == null ? 2.45D : tactical.preferredDistance;
        if (nearby >= 2) desired += 0.20D;
        if (tactical != null && tactical.targetRetreating) desired += 0.10D;

        boolean targetVisible = self.canEntityBeSeen(target);
        boolean tooFar = distance > desired + 0.65D;
        boolean tooClose = distance < desired - 0.55D;

        EntityPlayer best = targets == null ? null : targets.findBest(mc.theWorld, self, 18.0D, zones);
        boolean betterTarget = best != null && best != target && materiallyBetter(best, target, self);

        // Avoid churn: a better target must remain meaningfully better than the
        // currently locked target unless the current target has become invalid.
        if (betterTarget && now >= reassessUntil) {
            reassessUntil = now + 450L;
            return new Decision(Phase.REASSESS, true, true, false, desired, lateralBias(tactical), "better-target");
        }

        if (nearby >= 3 && distance > desired + 0.25D) {
            return new Decision(Phase.RETREAT, true, false, false, desired + 0.25D,
                    lateralBias(tactical), "multi-opponent-pressure");
        }
        if (nearby >= 2 && distance > desired + 0.85D && tactical != null && tactical.targetRetreating) {
            return new Decision(Phase.REASSESS, true, false, false, desired,
                    lateralBias(tactical), "retreating-through-crowd");
        }
        if (tooFar) {
            return new Decision(Phase.APPROACH, true, false, false, desired,
                    lateralBias(tactical), targetVisible ? "close-gap" : "path-to-target");
        }
        if (tooClose) {
            return new Decision(Phase.MAINTAIN, true, false, targetVisible && distance <= 3.20D,
                    desired, lateralBias(tactical), "hold-melee-gap");
        }

        boolean allowAttack = targetVisible && distance <= 3.20D;
        return new Decision(Phase.MAINTAIN, true, false, allowAttack, desired,
                lateralBias(tactical), allowAttack ? "attack-envelope" : "maintain-envelope");
    }

    private boolean materiallyBetter(EntityPlayer a, EntityPlayer b, EntityPlayerSP self) {
        if (a == null || b == null) return false;
        double ad = self.getDistanceToEntity(a);
        double bd = self.getDistanceToEntity(b);
        double ah = Math.max(0.0D, Math.min(1.0D, a.getHealth() / Math.max(1.0F, a.getMaxHealth())));
        double bh = Math.max(0.0D, Math.min(1.0D, b.getHealth() / Math.max(1.0F, b.getMaxHealth())));
        boolean aVisible = self.canEntityBeSeen(a);
        boolean bVisible = self.canEntityBeSeen(b);
        double aScore = (aVisible ? 10.0D : 0.0D) + (1.0D - ah) * 8.0D - ad * 1.7D;
        double bScore = (bVisible ? 10.0D : 0.0D) + (1.0D - bh) * 8.0D - bd * 1.7D;
        return aScore > bScore + 4.5D;
    }

    private int countEligibleOpponents(EntityPlayerSP self, EntityPlayer target, double radius) {
        if (mc == null || mc.theWorld == null) return 0;
        AxisAlignedBB box = target.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> nearby = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        int count = 0;
        for (EntityPlayer player : nearby) {
            if (player == null || player == self || player == target) continue;
            if (player.isDead || player.getHealth() <= 0.0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            if (zones != null && zones.isPlayerProtected(player)) continue;
            if (targets != null && targets.isEligibleForStrategy(player)) count++;
        }
        return count;
    }

    private double lateralBias(CombatTacticalModel.State tactical) {
        if (tactical == null) return 0.0D;
        double bias = tactical.strafeSign;
        if (tactical.targetApproaching) bias *= 0.65D;
        if (tactical.targetRetreating) bias *= 1.15D;
        CombatTrackingModel.Snapshot tracked = null;
        if (tactical != null) tracked = null; // tracking is consumed by CombatExecutionController
        return bias;
    }
}
