package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/**
 * Long-horizon strategy for the private Pit recreation.
 *
 * This layer evaluates whether a candidate fight is worth taking given the
 * current streak value, local crowd pressure, health, distance and whether the
 * fight is drifting toward a protected spawn area. It does not move or attack;
 * it only produces a strategic recommendation for the lower-level controllers.
 */
public class StreakStrategyEngine {
    public enum Action {
        HUNT,
        ENGAGE,
        HOLD,
        DISENGAGE,
        RETARGET
    }

    public static final class Result {
        public final Action action;
        public final double fightValue;
        public final double risk;
        public final double reward;
        public final String reason;

        public Result(Action action, double fightValue, double risk, double reward, String reason) {
            this.action = action;
            this.fightValue = fightValue;
            this.risk = risk;
            this.reward = reward;
            this.reason = reason;
        }

        public static Result hold(String reason) {
            return new Result(Action.HOLD, 0.0D, 0.0D, 0.0D, reason);
        }
    }

    private final StreakManager streak;
    private final TargetTracker targets;
    private final PitZoneManager zones;
    private final StreakControlManager streakControl;
    private final PitMapIntelligence map;

    public StreakStrategyEngine(StreakManager streak, TargetTracker targets, PitZoneManager zones) {
        this(streak, targets, zones, null);
    }

    public StreakStrategyEngine(StreakManager streak, TargetTracker targets, PitZoneManager zones, PitMapIntelligence map) {
        this.streak = streak;
        this.targets = targets;
        this.zones = zones;
        this.map = map;
        this.streakControl = streak.getControl();
    }

    public Result evaluate(World world, EntityPlayerSP self, EntityPlayer target) {
        if (self == null || target == null) return Result.hold("no target");
        if (target.isDead || target.getHealth() <= 0.0F) return Result.hold("target invalid");
        if (!targets.isEligibleForStrategy(target) || (zones != null && zones.isPlayerProtected(target))) {
            return new Result(Action.RETARGET, -100.0D, 100.0D, 0.0D, "target not eligible");
        }
        if (zones != null && zones.isSelfProtected(self)) {
            return new Result(Action.HOLD, -50.0D, 50.0D, 0.0D, "self protected");
        }

        int currentStreak = streak.getEffectiveStreak();
        double distance = self.getDistanceToEntity(target);
        double selfHealthRatio = healthRatio(self);
        double targetHealthRatio = healthRatio(target);
        int localPressure = countEligibleNearby(world, target, self, 6.5D);
        double riskMultiplier = streak.getStreakRiskMultiplier();

        double reward = 42.0D;
        reward += (1.0D - targetHealthRatio) * 26.0D;
        reward += Math.max(0.0D, 18.0D - distance) * 1.4D;
        if (target.canEntityBeSeen(self)) reward += 9.0D;
        if (target.getUniqueID() != null && target == targets.getTarget()) reward += 4.0D;

        double risk = 0.0D;
        risk += (1.0D - selfHealthRatio) * 55.0D * riskMultiplier;
        risk += Math.min(34.0D, localPressure * 11.0D) * riskMultiplier;

        PitStreakCatalog.Definition activeMegastreak = streakControl == null ? null : streakControl.getActiveMegastreak();
        MegastreakProfileEngine.Profile profile = MegastreakProfileEngine.profile(activeMegastreak);
        reward = MegastreakProfileEngine.adjustedReward(profile, reward);
        risk = MegastreakProfileEngine.adjustedThreatCost(profile, risk);
        // A selected megastreak changes what the long-horizon controller values,
        // but never overrides hard safety conditions.
        if (activeMegastreak != null && streakControl != null) {
            int remaining = streakControl.killsRemaining(currentStreak);
            if (remaining <= 5) reward += 8.0D;
            if (remaining <= 2) reward += 6.0D;
            if (profile.wantsHighStreak && currentStreak >= 20) risk += profile.preserveBias * 5.0D;
        }
        if (distance < 2.15D && localPressure > 0) risk += 10.0D * riskMultiplier;
        if (Math.abs(self.posY - target.posY) > 3.0D) risk += 12.0D * riskMultiplier;
        if (map != null) {
            risk += map.hazard() * 24.0D * riskMultiplier;
            reward += (map.openness() - 0.5D) * 4.0D;
            if (map.zone() == PitMapIntelligence.Zone.SPAWN) risk += 40.0D;
        }
        if (currentStreak >= 20) risk += 7.0D;
        if (currentStreak >= 40) risk += 13.0D;

        // High-streak fights need a larger reward margin. Low-streak fights can
        // accept more volatility because building the streak is itself valuable.
        double threshold;
        switch (streak.getTier()) {
            case EXTREME: threshold = 58.0D; break;
            case HIGH: threshold = 48.0D; break;
            case HOT: threshold = 40.0D; break;
            case ESTABLISHED: threshold = 33.0D; break;
            case BUILDING: threshold = 26.0D; break;
            default: threshold = 22.0D; break;
        }

        double value = reward - risk;
        if (selfHealthRatio <= 0.30D) return new Result(Action.DISENGAGE, value, risk, reward, "low health");
        if (localPressure >= 3 && currentStreak >= 10) return new Result(Action.DISENGAGE, value, risk, reward, "crowd pressure");
        if (zones != null && zones.isPlayerProtected(target)) return new Result(Action.RETARGET, value, risk, reward, "target entered spawn");
        if (value < threshold * 0.55D) return new Result(Action.RETARGET, value, risk, reward, "poor fight value");
        if (value < threshold) return new Result(Action.HOLD, value, risk, reward, "marginal fight");
        if (distance <= 4.0D) return new Result(Action.ENGAGE, value, risk, reward, "favorable engagement");
        return new Result(Action.HUNT, value, risk, reward, "favorable hunt");
    }

    public double targetScoreWithStrategy(World world, EntityPlayerSP self, EntityPlayer target, double baseScore) {
        Result result = evaluate(world, self, target);
        double adjusted = baseScore + result.reward - result.risk;
        if (result.action == Action.DISENGAGE) adjusted -= 120.0D;
        if (result.action == Action.RETARGET) adjusted -= 180.0D;
        if (result.action == Action.HOLD) adjusted -= 8.0D;
        return adjusted;
    }

    public PitMapIntelligence getMapIntelligence() { return map; }

    public MegastreakProfileEngine.Profile getActiveMegastreakProfile() {
        return MegastreakProfileEngine.profile(streakControl == null ? null : streakControl.getActiveMegastreak());
    }

    private double healthRatio(EntityPlayer player) {
        double max = Math.max(1.0D, player.getMaxHealth());
        return Math.max(0.0D, Math.min(1.0D, player.getHealth() / max));
    }

    private int countEligibleNearby(World world, EntityPlayer center, EntityPlayerSP self, double radius) {
        if (world == null || center == null) return 0;
        java.util.List<EntityPlayer> players = world.getEntitiesWithinAABB(
                EntityPlayer.class, center.getEntityBoundingBox().expand(radius, radius, radius));
        int count = 0;
        for (EntityPlayer p : players) {
            if (p == null || p == self || p == center || p.isDead || p.getHealth() <= 0.0F) continue;
            if (p.isInvisible()) continue;
            if (targets.isEligibleForStrategy(p)) count++;
        }
        return count;
    }
}
