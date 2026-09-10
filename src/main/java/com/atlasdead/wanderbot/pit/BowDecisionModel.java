package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Decides whether the bot should use a bow against the chaser.
 * This is a decision-only layer; actual bow input is not implemented here.
 *
 * Decision: BOW or SKIP
 */
public final class BowDecisionModel {

    public enum Decision {
        BOW,
        SKIP
    }

    public static final class Result {
        public final Decision decision;
        public final String reason;
        public final double bowScore;

        Result(Decision decision, String reason, double bowScore) {
            this.decision = decision;
            this.reason = reason;
            this.bowScore = bowScore;
        }
    }

    /**
     * Evaluate whether bow usage is appropriate against the chaser.
     *
     * @param self     the bot player
     * @param chaser   the chasing player
     * @param context  current combat context
     * @return BOW or SKIP with reasoning
     */
    public Result evaluate(EntityPlayerSP self, EntityPlayer chaser, CombatContext context) {
        if (self == null || chaser == null || chaser.isDead || chaser.getHealth() <= 0F) {
            return new Result(Decision.SKIP, "no-valid-chaser", 0D);
        }

        double distance = self.getDistanceToEntity(chaser);
        float healthRatio = context.maxHealth > 0F ? context.selfHealth / context.maxHealth : 0F;

        // Distance check
        if (distance < WanderBotSettings.bowMinDistance) {
            return new Result(Decision.SKIP, "too-close", 0D);
        }
        if (distance > WanderBotSettings.bowMaxDistance) {
            return new Result(Decision.SKIP, "too-far", 0D);
        }

        // Health check: need sufficient health to stand still and bow
        if (healthRatio < WanderBotSettings.bowMinHealth) {
            return new Result(Decision.SKIP, "health-too-low", 0D);
        }

        // LOS check
        if (!self.canEntityBeSeen(chaser)) {
            return new Result(Decision.SKIP, "no-los", 0D);
        }

        // Crowd pressure: don't bow if surrounded
        if (context.nearbyThreatCount >= 2) {
            return new Result(Decision.SKIP, "crowded", 0D);
        }

        // Compute bow score
        double bowScore = 0D;

        // Distance preference: 10-20 blocks is ideal bow range
        double idealDist = 15.0D;
        double distPenalty = Math.abs(distance - idealDist) * 0.5D;
        bowScore += 50D - distPenalty;

        // Health margin
        bowScore += (healthRatio - WanderBotSettings.bowMinHealth) * 30D;

        // Chaser is approaching: bow is more valuable
        if (context.chaserState == ChaserDetector.ChaseState.CHASE_LIKELY) {
            bowScore += 15D;
        }

        // Decision: score threshold
        if (bowScore >= 30D) {
            return new Result(Decision.BOW, "bow-favorable", bowScore);
        }

        return new Result(Decision.SKIP, "score-low", bowScore);
    }
}
