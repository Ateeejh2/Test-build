package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Single execution gateway between the master Pit policy and low-level controllers.
 * It never performs movement itself; it only decides which subsystem owns the tick.
 */
public final class PitExecutionOrchestrator {
    public enum Owner {
        NONE,
        WAIT,
        RECOVERY,
        COMBAT,
        NAVIGATION
    }

    public static final class Directive {
        public final Owner owner;
        public final PitMasterDecisionEngine.Action action;
        public final String reason;
        public final EntityPlayer target;
        public final boolean clearPath;
        public final boolean releaseMovement;

        private Directive(Owner owner, PitMasterDecisionEngine.Action action, String reason,
                          EntityPlayer target, boolean clearPath, boolean releaseMovement) {
            this.owner = owner;
            this.action = action;
            this.reason = reason;
            this.target = target;
            this.clearPath = clearPath;
            this.releaseMovement = releaseMovement;
        }
    }

    public Directive resolve(EntityPlayerSP self, PitMasterDecisionEngine.Decision decision,
                             EntityPlayer target, boolean recoveryActive) {
        if (self == null || decision == null) {
            return new Directive(Owner.WAIT, PitMasterDecisionEngine.Action.WAIT,
                    "missing-runtime", null, true, true);
        }

        PitMasterDecisionEngine.Action action = decision.action == null
                ? PitMasterDecisionEngine.Action.WAIT : decision.action;

        switch (action) {
            case WAIT:
            case EVENT:
                return new Directive(Owner.WAIT, action, decision.reason, null, true, true);
            case RECOVER:
                return new Directive(Owner.RECOVERY, action, decision.reason, target, true, false);
            case ATTACK:
            case APPROACH:
            case REPOSITION:
                if (WanderBotSettings.combatEnabled && target != null) {
                    return new Directive(Owner.COMBAT, action, decision.reason, target, true, false);
                }
                return navigation(action, "combat-disabled-or-no-target", target);
            case RETARGET:
            case DISENGAGE:
                return new Directive(Owner.COMBAT, action, decision.reason, target, true, true);
            case NAVIGATE:
            case SEARCH:
            default:
                return navigation(action, decision.reason, target);
        }
    }

    private Directive navigation(PitMasterDecisionEngine.Action action, String reason, EntityPlayer target) {
        return new Directive(Owner.NAVIGATION, action, reason, target, false, false);
    }
}
