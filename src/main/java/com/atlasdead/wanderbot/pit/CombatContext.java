package com.atlasdead.wanderbot.pit;

import net.minecraft.entity.player.EntityPlayer;

/**
 * Shared mutable context that CombatPhaseController updates each tick and
 * that all combat subsystems read. This avoids passing large parameter
 * lists between components and keeps subsystems decoupled from the phase
 * controller's internal state.
 *
 * Only CombatPhaseController should write to this object. All other
 * subsystems should treat it as read-only.
 */
public final class CombatContext {

    /** Combat phases managed by CombatPhaseController. */
    public enum Phase {
        SEARCH,
        ENGAGE,
        RETREAT,
        REAR_CHECK,
        BOW_DECISION
    }

    // --- Self state ---
    public float selfHealth;
    public float maxHealth;
    public double selfX, selfY, selfZ;

    // --- Current combat target ---
    public EntityPlayer combatTarget;

    // --- Chaser ---
    public EntityPlayer chaserTarget;
    public ChaserDetector.ChaseState chaserState = ChaserDetector.ChaseState.NOT_CHASING;

    // --- Threat ---
    public EntityPlayer nearestThreat;
    public int nearbyThreatCount;
    public double threatScore;

    // --- Distances ---
    public double targetDistance;

    // --- Phase state ---
    public Phase currentPhase = Phase.SEARCH;
    public Phase previousPhase = Phase.SEARCH;
    public int phaseTicks;

    // --- Escape ---
    public EscapeCandidate bestEscapeCandidate;
    public double escapeScore;

    // --- Combat timing ---
    public boolean lineOfSight;
    public int timeSinceLastDamage;
    public int ticksSinceRetreat;

    // --- Decision output ---
    public String lastDecision = "";
    public String lastDecisionReason = "";

    // --- Attack state ---
    public int attackCooldown;
    public boolean canAttack;

    public void reset() {
        selfHealth = 0F;
        maxHealth = 0F;
        selfX = 0D; selfY = 0D; selfZ = 0D;
        combatTarget = null;
        chaserTarget = null;
        chaserState = ChaserDetector.ChaseState.NOT_CHASING;
        nearestThreat = null;
        nearbyThreatCount = 0;
        threatScore = 0D;
        targetDistance = 0D;
        currentPhase = Phase.SEARCH;
        previousPhase = Phase.SEARCH;
        phaseTicks = 0;
        bestEscapeCandidate = null;
        escapeScore = 0D;
        lineOfSight = false;
        timeSinceLastDamage = 0;
        ticksSinceRetreat = 0;
        lastDecision = "";
        lastDecisionReason = "";
        attackCooldown = 0;
        canAttack = false;
    }

    public void transitionTo(Phase newPhase) {
        previousPhase = currentPhase;
        currentPhase = newPhase;
        phaseTicks = 0;
    }

    public float healthRatio() {
        return maxHealth > 0F ? selfHealth / maxHealth : 0F;
    }

    /** Human-readable summary for HUD / debug. */
    public String debugSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Phase: ").append(currentPhase);
        sb.append(" | HP: ").append(String.format("%.0f", selfHealth)).append("/").append(String.format("%.0f", maxHealth));
        if (combatTarget != null) {
            sb.append(" | Target: ").append(combatTarget.getName());
            sb.append(" @ ").append(String.format("%.1f", targetDistance));
        }
        if (chaserTarget != null) {
            sb.append(" | Chaser: ").append(chaserTarget.getName());
            sb.append(" (").append(chaserState).append(")");
        }
        sb.append(" | Threat: ").append(String.format("%.1f", threatScore));
        sb.append(" | Nearby: ").append(nearbyThreatCount);
        if (bestEscapeCandidate != null) {
            sb.append(" | Escape: ").append(String.format("%.1f", bestEscapeCandidate.escapeScore));
        }
        sb.append(" | Decision: ").append(lastDecision);
        return sb.toString();
    }
}
