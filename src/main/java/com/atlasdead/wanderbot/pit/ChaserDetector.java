package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * History-based chaser detector. For each nearby player, maintains a short
 * sliding window of observations (distance, approach vector, LOS) and uses
 * that history to classify behaviour into NOT_CHASING, CHASE_UNCERTAIN,
 * or CHASE_LIKELY.
 *
 * A single-frame dot product is NOT used alone; only sustained approach
 * over multiple ticks in the rear arc qualifies as CHASE_LIKELY.
 */
public final class ChaserDetector {

    public enum ChaseState {
        NOT_CHASING,
        CHASE_UNCERTAIN,
        CHASE_LIKELY
    }

    /** Per-player sliding window entry. */
    private static final class Observation {
        final double distance;
        final double approachDot;
        final boolean lineOfSight;
        final boolean inRearArc;
        Observation(double distance, double approachDot, boolean lineOfSight, boolean inRearArc) {
            this.distance = distance;
            this.approachDot = approachDot;
            this.lineOfSight = lineOfSight;
            this.inRearArc = inRearArc;
        }
    }

    /** Aggregated chaser result for a single player. */
    public static final class ChaserResult {
        public final EntityPlayer player;
        public final ChaseState state;
        public final int approachingTicks;
        public final double avgDistance;
        public final double avgApproachDot;
        public final int losTicks;

        ChaserResult(EntityPlayer player, ChaseState state, int approachingTicks,
                     double avgDistance, double avgApproachDot, int losTicks) {
            this.player = player;
            this.state = state;
            this.approachingTicks = approachingTicks;
            this.avgDistance = avgDistance;
            this.avgApproachDot = avgApproachDot;
            this.losTicks = losTicks;
        }
    }

    /** Max observations per player in the sliding window. */
    private static final int WINDOW_SIZE = 12;

    private final Map<Integer, List<Observation>> history = new HashMap<Integer, List<Observation>>();

    public void reset() {
        history.clear();
    }

    /**
     * Scan all nearby players and classify each as a potential chaser.
     * Returns the most threatening chaser (or null if none).
     */
    public ChaserResult detect(EntityPlayerSP self) {
        if (self == null || self.worldObj == null) return null;

        double maxDist = WanderBotSettings.chaserMaxDistance;
        double arcRad = Math.toRadians(WanderBotSettings.chaserDetectArc);

        AxisAlignedBB box = self.getEntityBoundingBox().expand(maxDist, maxDist, maxDist);
        List<EntityPlayer> players = self.worldObj.getEntitiesWithinAABB(EntityPlayer.class, box);

        double bestScore = Double.NEGATIVE_INFINITY;
        ChaserResult best = null;

        for (EntityPlayer player : players) {
            if (player == null || player == self || player.isDead || player.getHealth() <= 0F) continue;
            if (player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;

            double dx = player.posX - self.posX;
            double dz = player.posZ - self.posZ;
            double dist = Math.sqrt(dx * dx + dz * dz);

            if (dist < WanderBotSettings.chaserMinDistance || dist > maxDist) continue;

            // Rear arc check: is player behind us?
            double selfYawRad = Math.toRadians(self.rotationYaw);
            double forwardX = -Math.sin(selfYawRad);
            double forwardZ = Math.cos(selfYawRad);
            double toPlayerX = dx / dist;
            double toPlayerZ = dz / dist;
            double dot = forwardX * toPlayerX + forwardZ * toPlayerZ;
            boolean inRearArc = dot < -Math.cos(arcRad / 2.0D);

            // Approach dot: are they moving towards us?
            double playerSpeed = Math.sqrt(player.motionX * player.motionX + player.motionZ * player.motionZ);
            double approachDot = 0D;
            if (playerSpeed > 0.01D) {
                double pmx = player.motionX / playerSpeed;
                double pmz = player.motionZ / playerSpeed;
                double toSelfX = -toPlayerX;
                double toSelfZ = -toPlayerZ;
                approachDot = pmx * toSelfX + pmz * toSelfZ;
            }

            boolean los = self.canEntityBeSeen(player);

            // Update history
            List<Observation> obs = history.get(player.getEntityId());
            if (obs == null) {
                obs = new ArrayList<Observation>(WINDOW_SIZE);
                history.put(player.getEntityId(), obs);
            }
            obs.add(new Observation(dist, approachDot, los, inRearArc));
            while (obs.size() > WINDOW_SIZE) obs.remove(0);

            // Classify
            ChaseState state = classify(obs);

            // Score: prefer closer, more persistent chasers
            double score = 0D;
            if (state == ChaseState.CHASE_LIKELY) score = 100D + (maxDist - dist);
            else if (state == ChaseState.CHASE_UNCERTAIN) score = 50D + (maxDist - dist) * 0.5D;
            else continue;

            if (score > bestScore) {
                bestScore = score;
                best = buildResult(player, state, obs);
            }
        }

        // Prune stale entries
        pruneHistory(players);

        return best;
    }

    /** Classify a player's observation window into a ChaseState. */
    private ChaseState classify(List<Observation> obs) {
        if (obs.size() < 3) return ChaseState.NOT_CHASING;

        int rearTicks = 0;
        int approachTicks = 0;
        int losTicks = 0;
        double distSum = 0D;
        double approachSum = 0D;
        int n = obs.size();

        for (int i = 0; i < n; i++) {
            Observation o = obs.get(i);
            distSum += o.distance;
            approachSum += o.approachDot;
            if (o.inRearArc) rearTicks++;
            if (o.approachDot > WanderBotSettings.chaserDotThreshold) approachTicks++;
            if (o.lineOfSight) losTicks++;
        }

        double avgDist = distSum / n;
        double avgApproach = approachSum / n;

        // CHASE_LIKELY: sustained approach in rear arc with LOS
        if (approachTicks >= WanderBotSettings.chaserPersistence
                && rearTicks >= WanderBotSettings.chaserPersistence - 1
                && losTicks >= WanderBotSettings.chaserPersistence - 2) {
            return ChaseState.CHASE_LIKELY;
        }

        // CHASE_UNCERTAIN: partial indicators
        if (approachTicks >= 2 || (rearTicks >= 3 && losTicks >= 2)) {
            return ChaseState.CHASE_UNCERTAIN;
        }

        return ChaseState.NOT_CHASING;
    }

    private ChaserResult buildResult(EntityPlayer player, ChaseState state, List<Observation> obs) {
        int n = obs.size();
        double distSum = 0D, approachSum = 0D;
        int losTicks = 0, approachTicks = 0;
        for (Observation o : obs) {
            distSum += o.distance;
            approachSum += o.approachDot;
            if (o.lineOfSight) losTicks++;
            if (o.approachDot > WanderBotSettings.chaserDotThreshold) approachTicks++;
        }
        return new ChaserResult(player, state, approachTicks, distSum / n, approachSum / n, losTicks);
    }

    private void pruneHistory(List<EntityPlayer> current) {
        java.util.Set<Integer> currentIds = new java.util.HashSet<Integer>();
        for (EntityPlayer p : current) currentIds.add(p.getEntityId());
        java.util.Iterator<Map.Entry<Integer, List<Observation>>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, List<Observation>> e = it.next();
            if (!currentIds.contains(e.getKey())) it.remove();
        }
    }
}
