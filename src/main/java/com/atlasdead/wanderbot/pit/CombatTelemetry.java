package com.atlasdead.wanderbot.pit;

/** Lightweight immutable snapshot of the active combat situation for HUD/debugging and higher-level policy. */
public final class CombatTelemetry {
    public final CombatStateMachine.Phase phase;
    public final CombatDecisionEngine.Action action;
    public final double threatScore;
    public final double combatScore;
    public final int crowdPressure;
    public final boolean visible;
    public final double distance;
    public final CombatNavigationCoordinator.Outcome navigationOutcome;
    public final String megastreakId;
    public final int streak;
    public final String reason;
    public final String feedback;
    public final float selfHealth;
    public final float targetHealth;

    // Combat Phase Controller fields
    public final String combatPhase;
    public final String chaserName;
    public final double escapeScore;
    public final String decisionReason;

    public CombatTelemetry(CombatStateMachine.Phase phase,
                           CombatDecisionEngine.Action action,
                           double threatScore,
                           double combatScore,
                           int crowdPressure,
                           boolean visible,
                           double distance,
                           CombatNavigationCoordinator.Outcome navigationOutcome,
                           String megastreakId,
                           int streak,
                           String reason) {
        this.phase = phase;
        this.action = action;
        this.threatScore = threatScore;
        this.combatScore = combatScore;
        this.crowdPressure = crowdPressure;
        this.visible = visible;
        this.distance = distance;
        this.navigationOutcome = navigationOutcome;
        this.megastreakId = megastreakId;
        this.streak = streak;
        this.reason = reason;
        this.feedback = "none";
        this.selfHealth = -1.0F;
        this.targetHealth = -1.0F;
        this.combatPhase = null;
        this.chaserName = null;
        this.escapeScore = 0D;
        this.decisionReason = null;
    }

    public CombatTelemetry(CombatStateMachine.Phase phase,
                           CombatDecisionEngine.Action action,
                           double threatScore, double combatScore, int crowdPressure,
                           boolean visible, double distance,
                           CombatNavigationCoordinator.Outcome navigationOutcome,
                           String megastreakId, int streak, String reason,
                           String feedback, float selfHealth, float targetHealth) {
        this.phase = phase;
        this.action = action;
        this.threatScore = threatScore;
        this.combatScore = combatScore;
        this.crowdPressure = crowdPressure;
        this.visible = visible;
        this.distance = distance;
        this.navigationOutcome = navigationOutcome;
        this.megastreakId = megastreakId;
        this.streak = streak;
        this.reason = reason;
        this.feedback = feedback;
        this.selfHealth = selfHealth;
        this.targetHealth = targetHealth;
        this.combatPhase = null;
        this.chaserName = null;
        this.escapeScore = 0D;
        this.decisionReason = null;
    }

    public CombatTelemetry withFeedback(String feedback, float selfHealth, float targetHealth) {
        return new CombatTelemetry(phase, action, threatScore, combatScore, crowdPressure, visible, distance,
                navigationOutcome, megastreakId, streak, reason, feedback == null ? "none" : feedback,
                selfHealth, targetHealth, combatPhase, chaserName, escapeScore, decisionReason);
    }

    private CombatTelemetry(CombatStateMachine.Phase phase,
                           CombatDecisionEngine.Action action,
                           double threatScore, double combatScore, int crowdPressure,
                           boolean visible, double distance,
                           CombatNavigationCoordinator.Outcome navigationOutcome,
                           String megastreakId, int streak, String reason,
                           String feedback, float selfHealth, float targetHealth,
                           String combatPhase, String chaserName, double escapeScore, String decisionReason) {
        this.phase = phase;
        this.action = action;
        this.threatScore = threatScore;
        this.combatScore = combatScore;
        this.crowdPressure = crowdPressure;
        this.visible = visible;
        this.distance = distance;
        this.navigationOutcome = navigationOutcome;
        this.megastreakId = megastreakId;
        this.streak = streak;
        this.reason = reason;
        this.feedback = feedback == null ? "none" : feedback;
        this.selfHealth = selfHealth;
        this.targetHealth = targetHealth;
        this.combatPhase = combatPhase;
        this.chaserName = chaserName;
        this.escapeScore = escapeScore;
        this.decisionReason = decisionReason;
    }

    public CombatTelemetry withPhaseInfo(String combatPhase, String chaserName, double escapeScore, String decisionReason) {
        return new CombatTelemetry(phase, action, threatScore, combatScore, crowdPressure, visible, distance,
                navigationOutcome, megastreakId, streak, reason, feedback,
                selfHealth, targetHealth, combatPhase, chaserName, escapeScore, decisionReason);
    }

    public static CombatTelemetry idle() {
        return new CombatTelemetry(CombatStateMachine.Phase.IDLE,
                CombatDecisionEngine.Action.NONE, 0.0D, 0.0D, 0, false,
                0.0D, CombatNavigationCoordinator.Outcome.NONE, "none", 0, "idle");
    }
}
