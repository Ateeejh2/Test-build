package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Final policy layer. It consumes the per-tick Pit snapshot and delegates only
 * the low-level combat/navigation mechanics to their existing controllers.
 */
public final class PitMasterDecisionEngine {
    public enum Action {
        WAIT,
        RECOVER,
        EVENT,
        SEARCH,
        NAVIGATE,
        APPROACH,
        ATTACK,
        REPOSITION,
        RETARGET,
        DISENGAGE
    }

    public static final class Decision {
        public final long tick;
        public final Action action;
        public final String reason;
        public final double threat;
        public final double reward;
        public final String targetName;
        public final String megastreak;
        public final PitRuntimeSnapshot snapshot;

        private Decision(long tick, Action action, String reason, double threat, double reward,
                         String targetName, String megastreak, PitRuntimeSnapshot snapshot) {
            this.tick = tick;
            this.action = action;
            this.reason = reason;
            this.threat = threat;
            this.reward = reward;
            this.targetName = targetName;
            this.megastreak = megastreak;
            this.snapshot = snapshot;
        }
    }

    private final Minecraft mc;
    private final PitDecisionEngine pit;
    private Decision lastDecision;

    public PitMasterDecisionEngine(Minecraft mc, PitDecisionEngine pit) {
        this.mc = mc;
        this.pit = pit;
    }

    public Decision evaluate(EntityPlayerSP self, EntityPlayer target) {
        PitRuntimeSnapshot snapshot = pit.getRuntimeSnapshot();
        if (self == null || mc == null || mc.theWorld == null || snapshot == null || snapshot.mode == PitMode.OFF) {
            return record(decision(snapshot, Action.WAIT, "runtime-off", 100.0D, 0.0D, null));
        }

        PitRulesEngine.State rules = snapshot.rules;
        if (snapshot.mode == PitMode.WAITING || snapshot.mode == PitMode.WARMUP
                || snapshot.mode == PitMode.PAUSED || snapshot.mode == PitMode.UNSAFE) {
            return record(decision(snapshot, Action.WAIT, "pit-not-active", 100.0D, 0.0D, target));
        }
        if (snapshot.event != null && snapshot.event.major) {
            return record(decision(snapshot, Action.EVENT, "major-event:" + snapshot.event.eventName, 90.0D, 0.0D, target));
        }
        if (rules != null && !pit.getRules().mayAcquireCombatTarget(rules)) {
            return record(decision(snapshot, Action.EVENT, "rules-block-combat", 90.0D, 0.0D, target));
        }
        if (self.getHealth() <= 0.0F || snapshot.mode == PitMode.RECOVERING) {
            return record(decision(snapshot, Action.RECOVER, "recovering", 100.0D, 0.0D, target));
        }
        if (!WanderBotSettings.combatEnabled) {
            return record(decision(snapshot, Action.NAVIGATE, "combat-disabled", 0.0D, 0.0D, null));
        }
        if (target == null) {
            return record(decision(snapshot, Action.SEARCH, "no-target", 0.0D, 0.0D, null));
        }
        if (pit.getZones().isPlayerProtected(target)) {
            return record(decision(snapshot, Action.SEARCH, "target-protected", 100.0D, 0.0D, null));
        }

        CombatDecisionEngine.Result combat = pit.evaluateCombat(self, target);
        Action mapped = map(combat.action);
        double reward = combat.combatScore;
        String reason = combat.reason;
        if (snapshot.megastreak != null) {
            reason += "|mega=" + snapshot.megastreak.id;
        }
        return record(decision(snapshot, mapped, reason, combat.threatScore, reward, target));
    }

    private Decision record(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    public Decision getLastDecision() { return lastDecision; }

    private Action map(CombatDecisionEngine.Action action) {
        if (action == null) return Action.SEARCH;
        switch (action) {
            case ATTACK: return Action.ATTACK;
            case APPROACH: return Action.APPROACH;
            case RETARGET: return Action.RETARGET;
            case DISENGAGE: return Action.DISENGAGE;
            default: return Action.SEARCH;
        }
    }

    private Decision decision(PitRuntimeSnapshot snapshot, Action action, String reason,
                              double threat, double reward, EntityPlayer target) {
        String targetName = target == null ? null : target.getName();
        String mega = snapshot != null && snapshot.megastreak != null ? snapshot.megastreak.displayName : null;
        return new Decision(snapshot == null ? 0L : snapshot.tick, action, reason, threat, reward,
                targetName, mega, snapshot);
    }
}
