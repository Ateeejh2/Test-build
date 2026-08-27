package com.atlasdead.wanderbot.pit;

/**
 * Combines Pit event state with the currently selected megastreak.
 * This is a policy-only layer: it produces strategy modifiers and never sends
 * movement, click, or network input itself.
 */
public final class PitEventStreakStrategy {
    public static final class Result {
        public final String eventName;
        public final boolean active;
        public final boolean combatAllowed;
        public final boolean prioritizeEvent;
        public final double riskMultiplier;
        public final double rewardMultiplier;
        public final double streakPreservationBonus;
        public final String reason;

        Result(String eventName, boolean active, boolean combatAllowed, boolean prioritizeEvent,
               double riskMultiplier, double rewardMultiplier, double streakPreservationBonus, String reason) {
            this.eventName = eventName;
            this.active = active;
            this.combatAllowed = combatAllowed;
            this.prioritizeEvent = prioritizeEvent;
            this.riskMultiplier = riskMultiplier;
            this.rewardMultiplier = rewardMultiplier;
            this.streakPreservationBonus = streakPreservationBonus;
            this.reason = reason;
        }

        public static Result normal() {
            return new Result(null, false, true, false, 1.0D, 1.0D, 0.0D, "normal");
        }
    }

    public Result evaluate(PitEventDetector.Result event, PitStreakCatalog.Definition mega, int streak) {
        if (event == null || !event.available || event.eventName == null || event.eventName.isEmpty()) {
            return Result.normal();
        }

        String name = event.eventName.toLowerCase(java.util.Locale.ROOT);
        double risk = event.major ? 1.20D : 1.05D;
        double reward = 1.00D;
        boolean combatAllowed = !event.major;
        boolean prioritize = false;
        double preservation = 0.0D;
        String reason = event.major ? "major-event" : "minor-event";

        if (name.contains("spire")) {
            combatAllowed = false;
            prioritize = true;
            risk = 1.40D;
            reason = "spire";
        } else if (name.contains("rage pit")) {
            prioritize = true;
            risk = 1.15D;
            reward = 1.15D;
            reason = "rage-pit";
        } else if (name.contains("robbery")) {
            prioritize = true;
            risk = 1.25D;
            reward = 1.10D;
            reason = "robbery";
        } else if (name.contains("raffle")) {
            prioritize = true;
            risk = 1.10D;
            reward = 1.05D;
            reason = "raffle";
        } else if (name.contains("2x rewards") || name.contains("everyone gets a bounty")) {
            prioritize = true;
            risk = 1.08D;
            reward = 1.15D;
            reason = "reward-window";
        }

        // As streak value grows, event-related volatility matters more.
        if (streak >= 20) {
            preservation += 0.08D;
            risk += 0.05D;
        }
        if (streak >= 40) {
            preservation += 0.12D;
            risk += 0.07D;
        }
        if (mega != null) {
            risk += Math.min(0.20D, mega.riskRank * 0.01D);
            reward += Math.min(0.15D, mega.effectRank * 0.005D);
            if (mega.deathReward > 0) preservation *= 0.85D;
        }

        return new Result(event.eventName, true, combatAllowed, prioritize, risk, reward, preservation, reason);
    }
}
