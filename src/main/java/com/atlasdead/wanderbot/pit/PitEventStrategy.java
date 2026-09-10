package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Event-aware strategy layer for the Pit ruleset. It is policy-only: it does not
 * press keys or manipulate packets. It translates an observed event into a
 * combat/navigation preference that higher-level controllers can consume.
 */
public final class PitEventStrategy {
    public enum Action {
        NORMAL,
        PAUSE_COMBAT,
        EVENT_FOCUS,
        EVENT_CENTER,
        EVENT_SURVIVE,
        EVENT_REASSESS
    }

    public static final class Result {
        public final Action action;
        public final String eventName;
        public final boolean combatAllowed;
        public final boolean prioritizeEventObjective;
        public final double riskMultiplier;
        public final double targetBias;
        public final String reason;

        private Result(Action action, String eventName, boolean combatAllowed,
                       boolean prioritizeEventObjective, double riskMultiplier,
                       double targetBias, String reason) {
            this.action = action;
            this.eventName = eventName;
            this.combatAllowed = combatAllowed;
            this.prioritizeEventObjective = prioritizeEventObjective;
            this.riskMultiplier = riskMultiplier;
            this.targetBias = targetBias;
            this.reason = reason;
        }

        public static Result normal() {
            return new Result(Action.NORMAL, null, true, false, 1.0D, 1.0D, "normal");
        }
    }

    public Result evaluate(PitEventDetector.Result event, PitRulesEngine.State rules,
                           EntityPlayerSP self, EntityPlayer target) {
        if (event == null || !event.available || event.eventName == null || event.eventName.isEmpty()) {
            return Result.normal();
        }
        String name = event.eventName.toLowerCase(java.util.Locale.ROOT);

        if (event.major) {
            if (name.contains("spire")) {
                return new Result(Action.PAUSE_COMBAT, event.eventName, false, true,
                        1.35D, 0.0D, "spire-special-rules");
            }
            if (name.contains("raffle")) {
                return new Result(Action.EVENT_CENTER, event.eventName, true, true,
                        1.15D, 0.55D, "raffle-objective");
            }
            if (name.contains("robbery")) {
                return new Result(Action.EVENT_FOCUS, event.eventName, true, true,
                        1.30D, 0.95D, "robbery-stash-value");
            }
            if (name.contains("rage pit")) {
                return new Result(Action.EVENT_FOCUS, event.eventName, true, true,
                        1.20D, 1.15D, "rage-pit-damage-objective");
            }
            if (name.contains("pizza")) {
                return new Result(Action.EVENT_FOCUS, event.eventName, true, true,
                        1.10D, 0.85D, "pizza-event");
            }
            if (name.contains("beast")) {
                return new Result(Action.EVENT_SURVIVE, event.eventName, true, true,
                        1.45D, 0.90D, "beast-high-risk");
            }
            if (name.contains("squads") || name.contains("team deathmatch")) {
                return new Result(Action.EVENT_FOCUS, event.eventName, true, true,
                        1.25D, 1.05D, "team-combat");
            }
            if (name.contains("blockhead")) {
                return new Result(Action.EVENT_SURVIVE, event.eventName, true, true,
                        1.20D, 0.75D, "blockhead-survival");
            }
            return new Result(Action.EVENT_REASSESS, event.eventName, false, true,
                    1.15D, 0.80D, "major-event");
        }

        if (event.minor) {
            if (name.contains("2x rewards") || name.contains("everyone gets a bounty")) {
                return new Result(Action.EVENT_FOCUS, event.eventName, true, true,
                        1.10D, 1.10D, "reward-window");
            }
            if (name.contains("king of the hill") || name.contains("king of the ladder")) {
                return new Result(Action.EVENT_CENTER, event.eventName, true, true,
                        1.20D, 0.95D, "control-objective");
            }
            if (name.contains("care package") || name.contains("dragon egg") || name.contains("giant cake")) {
                return new Result(Action.EVENT_REASSESS, event.eventName, true, true,
                        1.05D, 0.70D, "world-objective");
            }
            if (name.contains("auction") || name.contains("quick maths")) {
                return new Result(Action.NORMAL, event.eventName, true, false,
                        1.00D, 1.00D, "non-combat-minor-event");
            }
        }

        return Result.normal();
    }
}
