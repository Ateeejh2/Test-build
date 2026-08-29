package com.atlasdead.wanderbot.pit;

import net.minecraft.util.BlockPos;

/**
 * Immutable data class representing a single escape route candidate
 * evaluated during the RETREAT phase. Each candidate is scored on
 * multiple dimensions to find the most survivable retreat destination.
 */
public final class EscapeCandidate {
    public final BlockPos position;
    public final double escapeScore;
    public final double minThreatDistance;
    public final double avgThreatDistance;
    public final double coverScore;
    public final double lineOfSightBreak;
    public final double footingScore;
    public final double fallRisk;
    public final double deadEndRisk;
    public final double escapeRouteQuality;

    EscapeCandidate(BlockPos position, double escapeScore, double minThreatDistance,
                    double avgThreatDistance, double coverScore, double lineOfSightBreak,
                    double footingScore, double fallRisk, double deadEndRisk,
                    double escapeRouteQuality) {
        this.position = position;
        this.escapeScore = escapeScore;
        this.minThreatDistance = minThreatDistance;
        this.avgThreatDistance = avgThreatDistance;
        this.coverScore = coverScore;
        this.lineOfSightBreak = lineOfSightBreak;
        this.footingScore = footingScore;
        this.fallRisk = fallRisk;
        this.deadEndRisk = deadEndRisk;
        this.escapeRouteQuality = escapeRouteQuality;
    }

    @Override
    public String toString() {
        return "EscapeCandidate{score=" + String.format("%.1f", escapeScore)
                + ", minThreat=" + String.format("%.1f", minThreatDistance)
                + ", cover=" + String.format("%.1f", coverScore)
                + ", los=" + String.format("%.1f", lineOfSightBreak)
                + ", pos=" + position + "}";
    }
}
