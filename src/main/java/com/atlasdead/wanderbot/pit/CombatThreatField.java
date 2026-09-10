package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Local combat threat field. It describes how much pressure exists around the
 * current fight and exposes the strongest nearby eligible opponents for the
 * decision layer. It is observation-only; it does not perform attacks.
 */
public final class CombatThreatField {
    public static final class Threat {
        public final EntityPlayer player;
        public final double distance;
        public final double pressure;
        public final boolean visible;
        public final double closingRate;

        Threat(EntityPlayer player, double distance, double pressure, boolean visible, double closingRate) {
            this.player = player;
            this.distance = distance;
            this.pressure = pressure;
            this.visible = visible;
            this.closingRate = closingRate;
        }
    }

    public static final class Snapshot {
        public final int nearbyEligible;
        public final int visibleNearby;
        public final int closeThreats;
        public final double totalPressure;
        public final double nearestThreatDistance;
        public final boolean surrounded;
        public final boolean crossfire;
        public final List<Threat> threats;

        Snapshot(int nearbyEligible, int visibleNearby, int closeThreats, double totalPressure,
                 double nearestThreatDistance, boolean surrounded, boolean crossfire, List<Threat> threats) {
            this.nearbyEligible = nearbyEligible;
            this.visibleNearby = visibleNearby;
            this.closeThreats = closeThreats;
            this.totalPressure = totalPressure;
            this.nearestThreatDistance = nearestThreatDistance;
            this.surrounded = surrounded;
            this.crossfire = crossfire;
            this.threats = threats;
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0.0D, Double.POSITIVE_INFINITY, false, false,
                    java.util.Collections.<Threat>emptyList());
        }
    }

    private final TargetTracker targets;
    private final PitZoneManager zones;
    private final ThreatSnapshotCache cache = new ThreatSnapshotCache();

    public CombatThreatField(TargetTracker targets, PitZoneManager zones) {
        this.targets = targets;
        this.zones = zones;
    }

    public Snapshot sample(World world, EntityPlayerSP self, EntityPlayer currentTarget, double radius) {
        if (world == null || self == null) return Snapshot.empty();

        double safeRadius = Math.max(2.0D, radius);
        CombatThreatField.Snapshot cached = cache.get(world, self, currentTarget, safeRadius, System.currentTimeMillis(), 70L);
        if (cached != null) return cached;
        List<EntityPlayer> players = world.getEntitiesWithinAABB(
                EntityPlayer.class, self.getEntityBoundingBox().expand(safeRadius, safeRadius, safeRadius));
        List<Threat> threats = new ArrayList<Threat>();
        int visible = 0;
        int close = 0;
        double pressure = 0.0D;
        double nearest = Double.POSITIVE_INFINITY;

        for (EntityPlayer player : players) {
            if (player == null || player == self || player == currentTarget) continue;
            if (player.isDead || player.getHealth() <= 0.0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            if (targets != null && !targets.isEligibleForStrategy(player)) continue;
            if (zones != null && zones.isPlayerProtected(player)) continue;

            double distance = self.getDistanceToEntity(player);
            if (distance > safeRadius) continue;
            boolean canSee = self.canEntityBeSeen(player);
            if (canSee) visible++;
            if (distance <= 3.75D) close++;
            nearest = Math.min(nearest, distance);

            double distanceFactor = 1.0D - clamp01(distance / safeRadius);
            double healthFactor = 1.0D - clamp01(player.getHealth() / Math.max(1.0F, player.getMaxHealth()));
            double movement = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
            double motionFactor = Math.min(1.0D, movement / 0.35D);
            double local = 8.0D + 14.0D * distanceFactor + 8.0D * healthFactor + 5.0D * motionFactor + (canSee ? 7.0D : 0.0D);
            pressure += local;
            threats.add(new Threat(player, distance, local, canSee, 0.0D));
        }

        threats.sort(new Comparator<Threat>() {
            @Override
            public int compare(Threat a, Threat b) {
                int byPressure = Double.compare(b.pressure, a.pressure);
                return byPressure != 0 ? byPressure : Double.compare(a.distance, b.distance);
            }
        });

        int crowdThreshold = Math.max(1, Math.min(8, WanderBotSettings.crowdThreshold));
        int nearCrowdThreshold = Math.max(2, crowdThreshold - 1);
        boolean surrounded = close >= crowdThreshold
                || (visible >= nearCrowdThreshold && close >= Math.min(2, crowdThreshold));
        boolean crossfire = visible >= crowdThreshold
                || (close >= nearCrowdThreshold && threats.size() >= crowdThreshold + 1);
        Snapshot result = new Snapshot(threats.size(), visible, close, pressure, nearest, surrounded, crossfire, threats);
        cache.put(world, self, currentTarget, safeRadius, System.currentTimeMillis(), result);
        return result;
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
