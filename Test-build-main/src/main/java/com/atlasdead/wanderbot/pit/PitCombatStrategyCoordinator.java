package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Connects Pit rules, megastreak profile, threat and combat state into a single
 * policy decision. It never performs movement or network actions itself.
 */
public final class PitCombatStrategyCoordinator {
    public enum Verdict {
        HOLD,
        ENGAGE,
        APPROACH,
        DISENGAGE,
        REASSESS
    }

    public static final class Result {
        public final Verdict verdict;
        public final double score;
        public final double threat;
        public final double reward;
        public final String reason;

        Result(Verdict verdict, double score, double threat, double reward, String reason) {
            this.verdict = verdict;
            this.score = score;
            this.threat = threat;
            this.reward = reward;
            this.reason = reason;
        }
    }

    private final Minecraft mc;
    private final PitZoneManager zones;
    private final TargetTracker targets;
    private final StreakManager streak;
    private final StreakStrategyEngine strategy;
    private final CombatThreatField threatField;
    private final PitMapIntelligence map;
    private final PitEventStrategy eventStrategy = new PitEventStrategy();

    public PitCombatStrategyCoordinator(Minecraft mc, PitZoneManager zones,
                                        TargetTracker targets, StreakManager streak,
                                        StreakStrategyEngine strategy) {
        this.mc = mc;
        this.zones = zones;
        this.targets = targets;
        this.streak = streak;
        this.strategy = strategy;
        this.threatField = new CombatThreatField(targets, zones);
        this.map = null;
    }

    public Result evaluate(EntityPlayerSP self, EntityPlayer target, PitEventDetector.Result event) {
        if (self == null || target == null || mc == null || mc.theWorld == null) {
            return new Result(Verdict.HOLD, 0.0D, 0.0D, 0.0D, "no-context");
        }
        if (zones != null && (zones.isSelfProtected(self) || zones.isPlayerProtected(target))) {
            return new Result(Verdict.DISENGAGE, -100.0D, 100.0D, 0.0D, "protected-zone");
        }

        PitEventStrategy.Result eventPlan = eventStrategy.evaluate(event, null, self, target);
        if (eventPlan.action == PitEventStrategy.Action.PAUSE_COMBAT) {
            return new Result(Verdict.HOLD, 0.0D, 100.0D, 0.0D, eventPlan.reason);
        }

        CombatThreatField.Snapshot threats = threatField.sample(mc.theWorld, self, target, 7.5D);
        StreakStrategyEngine.Result strategic = strategy == null
                ? null : strategy.evaluate(mc.theWorld, self, target);
        MegastreakProfileEngine.Profile profile = strategy == null
                ? null : strategy.getActiveMegastreakProfile();

        double rawThreat = threats.totalPressure;
        PitMapIntelligence localMap = strategy == null ? null : strategy.getMapIntelligence();
        if (localMap != null) rawThreat += localMap.hazard() * 18.0D;
        double rawReward = strategic == null ? 0.0D : strategic.reward;
        double threatWeight = profile == null ? 1.0D : (1.0D + profile.preserveBias * 0.65D);
        double rewardWeight = profile == null ? 1.0D : (0.90D + profile.targetAggression * 0.25D);
        double weightedThreat = rawThreat * threatWeight;
        double weightedReward = rawReward * rewardWeight * eventPlan.targetBias;
        weightedThreat *= eventPlan.riskMultiplier;

        if (eventPlan.action == PitEventStrategy.Action.EVENT_CENTER) {
            return new Result(Verdict.APPROACH, weightedReward - weightedThreat, weightedThreat,
                    weightedReward, "event-center");
        }
        if (eventPlan.action == PitEventStrategy.Action.EVENT_SURVIVE && weightedThreat > weightedReward) {
            return new Result(Verdict.DISENGAGE, weightedReward - weightedThreat, weightedThreat,
                    weightedReward, "event-survival");
        }
        if (event != null && event.major) {
            return new Result(Verdict.HOLD, weightedReward - weightedThreat, weightedThreat,
                    weightedReward, "major-event");
        }
        if (streak != null && streak.shouldProtectStreak() && threats.crossfire) {
            return new Result(Verdict.DISENGAGE, weightedReward - weightedThreat,
                    weightedThreat, weightedReward, "streak-protection-crossfire");
        }
        if (threats.surrounded && weightedThreat > weightedReward + 12.0D) {
            return new Result(Verdict.DISENGAGE, weightedReward - weightedThreat,
                    weightedThreat, weightedReward, "surrounded");
        }

        double distance = self.getDistanceToEntity(target);
        boolean visible = self.canEntityBeSeen(target);
        if (visible && distance <= 3.20D && weightedReward >= weightedThreat * 0.45D) {
            return new Result(Verdict.ENGAGE, weightedReward - weightedThreat,
                    weightedThreat, weightedReward, "combat-window");
        }
        if (distance <= 6.0D && weightedReward > weightedThreat * 0.60D) {
            return new Result(Verdict.APPROACH, weightedReward - weightedThreat,
                    weightedThreat, weightedReward, "favorable-approach");
        }
        if (weightedThreat > weightedReward + 18.0D) {
            return new Result(Verdict.DISENGAGE, weightedReward - weightedThreat,
                    weightedThreat, weightedReward, "threat-dominant");
        }
        return new Result(Verdict.REASSESS, weightedReward - weightedThreat,
                weightedThreat, weightedReward, "reassess");
    }
}
