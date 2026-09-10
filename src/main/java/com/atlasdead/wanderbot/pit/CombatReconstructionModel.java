package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs the local combat picture when more eligible players enter the
 * engagement area. This deliberately remains a decision aid; it does not
 * inject packets or bypass server-side validation.
 */
public final class CombatReconstructionModel {
    public static final class State {
        public final int eligibleNearby;
        public final int visibleNearby;
        public final double nearestDistance;
        public final boolean crowding;
        public final boolean currentTargetStillBest;
        public final EntityPlayer nearestAlternative;

        State(int eligibleNearby, int visibleNearby, double nearestDistance,
              boolean crowding, boolean currentTargetStillBest,
              EntityPlayer nearestAlternative) {
            this.eligibleNearby = eligibleNearby;
            this.visibleNearby = visibleNearby;
            this.nearestDistance = nearestDistance;
            this.crowding = crowding;
            this.currentTargetStillBest = currentTargetStillBest;
            this.nearestAlternative = nearestAlternative;
        }
    }

    private final Minecraft mc;
    private final PitZoneManager zones;
    private final TargetTracker targets;

    public CombatReconstructionModel(Minecraft mc, PitZoneManager zones, TargetTracker targets) {
        this.mc = mc;
        this.zones = zones;
        this.targets = targets;
    }

    public State rebuild(EntityPlayerSP self, EntityPlayer current, double radius) {
        if (mc == null || mc.theWorld == null || self == null) {
            return new State(0, 0, Double.POSITIVE_INFINITY, false, true, null);
        }

        AxisAlignedBB box = self.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> nearby = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        List<EntityPlayer> eligible = new ArrayList<EntityPlayer>();
        int visible = 0;
        double nearest = Double.POSITIVE_INFINITY;
        EntityPlayer nearestAlternative = null;

        for (EntityPlayer player : nearby) {
            if (!isEligible(self, current, player)) continue;
            eligible.add(player);
            double distance = self.getDistanceToEntity(player);
            if (self.canEntityBeSeen(player)) visible++;
            if (player != current && distance < nearest) {
                nearest = distance;
                nearestAlternative = player;
            }
        }

        boolean crowding = eligible.size() >= Math.max(1, WanderBotSettings.crowdThreshold);
        boolean currentBest = current == null || isCurrentCompetitive(self, current, eligible);
        return new State(eligible.size(), visible, nearest, crowding, currentBest, nearestAlternative);
    }

    private boolean isEligible(EntityPlayerSP self, EntityPlayer current, EntityPlayer player) {
        if (player == null || player == self || player == current) return false;
        if (player.isDead || player.getHealth() <= 0.0F || player.isInvisible()) return false;
        if (player.capabilities != null && player.capabilities.isCreativeMode) return false;
        if (TargetTracker.hasDiamondArmor(player)) return false;
        if (zones != null && zones.isPlayerProtected(player)) return false;
        return targets != null && targets.isEligibleForStrategy(player);
    }

    private boolean isCurrentCompetitive(EntityPlayerSP self, EntityPlayer current, List<EntityPlayer> eligible) {
        if (current == null) return true;
        double currentDistance = self.getDistanceToEntity(current);
        for (EntityPlayer alternative : eligible) {
            if (self.getDistanceToEntity(alternative) + 1.2D < currentDistance) return false;
        }
        return true;
    }
}
