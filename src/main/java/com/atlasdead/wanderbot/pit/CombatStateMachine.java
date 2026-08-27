package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Stabilizes combat intent across ticks so short-lived observations do not
 * cause rapid ATTACK/APPROACH/RETARGET oscillation.
 *
 * This is a policy layer only; it does not alter packets or server validation.
 */
public final class CombatStateMachine {
    public enum Phase {
        IDLE,
        ACQUIRE,
        APPROACH,
        ENGAGE,
        MAINTAIN,
        REASSESS,
        BREAK_CONTACT,
        RECOVERY
    }

    public static final class Decision {
        public final CombatDecisionEngine.Action action;
        public final Phase phase;
        public final boolean lockedTarget;
        public final long phaseUntil;
        public final String reason;

        Decision(CombatDecisionEngine.Action action, Phase phase, boolean lockedTarget,
                 long phaseUntil, String reason) {
            this.action = action;
            this.phase = phase;
            this.lockedTarget = lockedTarget;
            this.phaseUntil = phaseUntil;
            this.reason = reason;
        }
    }

    private EntityPlayer target;
    private Phase phase = Phase.IDLE;
    private long phaseUntil;
    private long lastReassess;
    private int targetEntityId = -1;

    public void reset() {
        target = null;
        phase = Phase.IDLE;
        phaseUntil = 0L;
        lastReassess = 0L;
        targetEntityId = -1;
    }

    public Decision update(EntityPlayerSP self, EntityPlayer candidate,
                           CombatDecisionEngine.Result raw, long now) {
        if (self == null || candidate == null || raw == null) {
            phase = Phase.IDLE;
            target = null;
            targetEntityId = -1;
            return new Decision(CombatDecisionEngine.Action.NONE, phase, false, now, "no-context");
        }

        if (target == null || targetEntityId != candidate.getEntityId()) {
            target = candidate;
            targetEntityId = candidate.getEntityId();
            phase = Phase.ACQUIRE;
            phaseUntil = now + 180L;
            lastReassess = now;
        }

        if (raw.action == CombatDecisionEngine.Action.DISENGAGE) {
            phase = Phase.BREAK_CONTACT;
            phaseUntil = now + 350L;
            return new Decision(raw.action, phase, false, phaseUntil, raw.reason);
        }

        if (raw.action == CombatDecisionEngine.Action.NONE) {
            phase = Phase.REASSESS;
            phaseUntil = now + 220L;
            return new Decision(raw.action, phase, false, phaseUntil, raw.reason);
        }

        if (raw.action == CombatDecisionEngine.Action.RETARGET) {
            if (now - lastReassess >= 220L) {
                phase = Phase.REASSESS;
                phaseUntil = now + 180L;
                lastReassess = now;
                return new Decision(raw.action, phase, false, phaseUntil, raw.reason);
            }
            // Suppress rapid retarget churn; continue the current movement intent.
            return stableFallback(raw, now, "retarget-debounced");
        }

        if (phase == Phase.BREAK_CONTACT && now < phaseUntil) {
            return new Decision(CombatDecisionEngine.Action.DISENGAGE, phase, false, phaseUntil, "break-contact-lock");
        }

        if (raw.action == CombatDecisionEngine.Action.ATTACK) {
            if (phase == Phase.ACQUIRE && now < phaseUntil) {
                return new Decision(CombatDecisionEngine.Action.APPROACH, Phase.APPROACH,
                        true, now + 120L, "acquire-settle");
            }
            phase = Phase.ENGAGE;
            phaseUntil = now + 120L;
            return new Decision(raw.action, phase, true, phaseUntil, raw.reason);
        }

        if (raw.action == CombatDecisionEngine.Action.APPROACH) {
            if (phase == Phase.ENGAGE && raw.combatScore > raw.threatScore + 6.0D) {
                phase = Phase.MAINTAIN;
            } else {
                phase = Phase.APPROACH;
            }
            phaseUntil = Math.max(phaseUntil, now + 90L);
            return new Decision(raw.action, phase, true, phaseUntil, raw.reason);
        }

        phase = Phase.MAINTAIN;
        phaseUntil = now + 100L;
        return new Decision(raw.action, phase, true, phaseUntil, raw.reason);
    }

    private Decision stableFallback(CombatDecisionEngine.Result raw, long now, String reason) {
        CombatDecisionEngine.Action fallback = raw.combatScore >= raw.threatScore
                ? CombatDecisionEngine.Action.APPROACH
                : CombatDecisionEngine.Action.DISENGAGE;
        if (fallback == CombatDecisionEngine.Action.DISENGAGE) {
            phase = Phase.BREAK_CONTACT;
            phaseUntil = now + 260L;
        } else {
            phase = Phase.APPROACH;
            phaseUntil = now + 120L;
        }
        return new Decision(fallback, phase, true, phaseUntil, reason);
    }

    public Phase getPhase() { return phase; }
    public EntityPlayer getTarget() { return target; }
}
