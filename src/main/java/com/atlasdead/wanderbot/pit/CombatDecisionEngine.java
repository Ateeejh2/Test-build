package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

import java.util.List;

/**
 * Pit combat policy for the user's private recreation server.
 *
 * This class does not press keys itself. It answers one question each tick:
 * what should the bot do with its current target?
 */
public class CombatDecisionEngine {
    public enum Action {
        NONE,
        APPROACH,
        ATTACK,
        DISENGAGE,
        RETARGET
    }

    public static final class Result {
        public final Action action;
        public final double threatScore;
        public final double combatScore;
        public final String reason;

        Result(Action action, double threatScore, double combatScore, String reason) {
            this.action = action;
            this.threatScore = threatScore;
            this.combatScore = combatScore;
            this.reason = reason;
        }

        public static Result none(String reason) {
            return new Result(Action.NONE, 0.0D, 0.0D, reason);
        }
    }

    private final Minecraft mc;
    private final PitZoneManager zones;
    private final TargetTracker targets;
    private final StreakManager streak;
    private final CombatReconstructionModel reconstruction;
    private final CombatStateMachine stateMachine = new CombatStateMachine();

    public CombatDecisionEngine(Minecraft mc, PitZoneManager zones, TargetTracker targets) {
        this(mc, zones, targets, null);
    }

    public CombatDecisionEngine(Minecraft mc, PitZoneManager zones, TargetTracker targets, StreakManager streak) {
        this.mc = mc;
        this.zones = zones;
        this.targets = targets;
        this.streak = streak;
        this.reconstruction = new CombatReconstructionModel(mc, zones, targets);
    }

    public Result evaluate(EntityPlayerSP self, EntityPlayer target) {
        if (self == null || mc == null || mc.theWorld == null) { stateMachine.reset(); return Result.none("no-world"); }
        if (target == null || target.isDead || target.getHealth() <= 0.0F) { stateMachine.reset(); return Result.none("no-target"); }
        if (zones != null && (zones.isSelfProtected(self) || zones.isPlayerProtected(target))) {
            stateMachine.reset(); return new Result(Action.DISENGAGE, 100.0D, 0.0D, "protected-zone");
        }
        if (!targets.isViable(self, WanderBotSettings.targetScanRange)) return Result.none("target-invalid");

        double distance = self.getDistanceToEntity(target);
        double vertical = Math.abs(self.posY - target.posY);
        double targetSpeed = Math.sqrt(target.motionX * target.motionX + target.motionZ * target.motionZ);
        double targetAwayVelocity = ((target.posX - self.posX) * target.motionX + (target.posZ - self.posZ) * target.motionZ);
        boolean visible = self.canEntityBeSeen(target);
        float yawError = angleToTarget(self, target);
        CombatReconstructionModel.State reconstructed = reconstruction.rebuild(self, target, 7.0D);
        int localPressure = reconstructed.eligibleNearby;
        double selfRatio = healthRatio(self);
        double targetRatio = healthRatio(target);

        double streakRisk = streak == null ? 1.0D : streak.getStreakRiskMultiplier();
        double threat = 0.0D;
        threat += Math.max(0.0D, (0.45D - selfRatio) * 120.0D);
        threat += localPressure * 18.0D * streakRisk;
        threat += Math.min(8.0D, targetSpeed * 4.0D);
        if (targetAwayVelocity > 0.35D) threat += 3.0D;
        threat += Math.min(18.0D, vertical * 3.0D) * streakRisk;
        if (!visible) threat += distance > 7.0D ? 16.0D : 6.0D;
        if (distance > 13.0D) threat += 10.0D * streakRisk;

        // Combat opportunity: closer, visible, lower-HP targets are more attractive.
        double combat = 0.0D;
        combat += Math.max(0.0D, 36.0D - distance * 2.4D);
        combat += (1.0D - targetRatio) * 30.0D;
        combat += visible ? 18.0D : -4.0D;
        combat -= Math.min(14.0D, vertical * 3.0D);
        combat -= localPressure * 9.0D;
        combat += Math.min(5.0D, targetSpeed * 2.0D);
        TargetTracker.ArmorProfile armor = targets.getTargetArmor();
        if (armor != null) {
            combat += armor.chainPieces * 2.5D;
            combat += armor.ironPieces * 1.5D;
        }

        // Hard safety exits first. The engine intentionally becomes conservative
        // before the navigation layer is asked to continue chasing the target.
        if (selfRatio <= WanderBotSettings.retreatHealth) {
            return stabilized(new Result(Action.DISENGAGE, threat + 35.0D, combat, "self-low-health"), self, target);
        }
        if (selfRatio <= 0.32D && localPressure >= 2) {
            return stabilized(new Result(Action.DISENGAGE, threat + 25.0D, combat, "outnumbered-low-health"), self, target);
        }
        if (reconstructed.crowding && distance > 5.0D) {
            return stabilized(new Result(Action.DISENGAGE, threat + 20.0D * streakRisk, combat, "crowded-target"), self, target);
        }
        if (streak != null && streak.isHighValueStreak() && localPressure >= 2 && distance > 4.5D) {
            return stabilized(new Result(Action.DISENGAGE, threat + 18.0D, combat, "high-streak-preservation"), self, target);
        }
        if (vertical >= 4.5D && distance > 6.0D) {
            return stabilized(new Result(Action.DISENGAGE, threat + 10.0D, combat, "bad-height"), self, target);
        }

        // Close-range combat window. Rotation is deliberately checked here too,
        // so the movement layer does not repeatedly stop/start around a target.
        if (distance <= WanderBotSettings.combatRange && visible && yawError <= 30.0F && vertical < 2.4D) {
            return stabilized(new Result(Action.ATTACK, threat, combat + 14.0D, "attack-window"), self, target);
        }

        // When a different eligible target becomes substantially better, give the
        // caller permission to drop the current target rather than blindly chasing.
        EntityPlayer best = targets.findBest(mc.theWorld, self, WanderBotSettings.targetScanRange, zones);
        if (best != null && best != target) {
            double bestDistance = self.getDistanceToEntity(best);
            double bestHealthRatio = healthRatio(best);
            double candidateBonus = Math.max(0.0D, 24.0D - bestDistance * 1.8D)
                    + (1.0D - bestHealthRatio) * 18.0D;
            double switchMargin = streak != null && streak.shouldProtectStreak() ? 15.0D : 10.0D;
            if (candidateBonus > combat + switchMargin) {
                return stabilized(new Result(Action.RETARGET, threat, candidateBonus, "better-target"), self, target);
            }
        }

        Result raw = new Result(Action.APPROACH, threat, combat, visible ? "approach-visible" : "approach-pathing");
        return stabilized(raw, self, target);
    }

    private Result stabilized(Result raw, EntityPlayerSP self, EntityPlayer target) {
        CombatStateMachine.Decision d = stateMachine.update(self, target, raw, System.currentTimeMillis());
        String reason = raw.reason + "|state=" + d.phase.name();
        return new Result(d.action, raw.threatScore, raw.combatScore, reason);
    }

    private double healthRatio(EntityPlayer player) {
        if (player == null) return 0.0D;
        float max = Math.max(1.0F, player.getMaxHealth());
        return Math.max(0.0D, Math.min(1.0D, player.getHealth() / max));
    }

    private float angleToTarget(EntityPlayerSP self, EntityPlayer target) {
        double dx = target.posX - self.posX;
        double dz = target.posZ - self.posZ;
        float desired = (float)(Math.atan2(dz, dx) * 180.0D / Math.PI) - 90.0F;
        float delta = desired - self.rotationYaw;
        while (delta > 180.0F) delta -= 360.0F;
        while (delta < -180.0F) delta += 360.0F;
        return Math.abs(delta);
    }

    private int countEligiblePlayersNear(EntityPlayer target, double radius) {
        AxisAlignedBB box = target.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> nearby = mc.theWorld.getEntitiesWithinAABB(EntityPlayer.class, box);
        int count = 0;
        for (EntityPlayer player : nearby) {
            if (player == null || player == mc.thePlayer || player == target) continue;
            if (player.isDead || player.getHealth() <= 0.0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            if (zones != null && zones.isPlayerProtected(player)) continue;
            TargetTracker.ArmorProfile armor = armorOf(player);
            if (armor.hasIronOrChain && !armor.hasDiamond) count++;
        }
        return count;
    }

    private TargetTracker.ArmorProfile armorOf(EntityPlayer player) {
        // Delegate armor classification through the tracker without changing its
        // hard target eligibility rule.
        if (player == targets.getTarget()) return targets.getTargetArmor();

        boolean iron = false, chain = false, diamond = false;
        int ironPieces = 0, chainPieces = 0, eligible = 0, total = 0;
        if (player.inventory.armorInventory != null) {
            for (net.minecraft.item.ItemStack stack : player.inventory.armorInventory) {
                if (stack == null || !(stack.getItem() instanceof net.minecraft.item.ItemArmor)) continue;
                total++;
                net.minecraft.item.ItemArmor item = (net.minecraft.item.ItemArmor) stack.getItem();
                net.minecraft.item.ItemArmor.ArmorMaterial mat = item.getArmorMaterial();
                if (mat == net.minecraft.item.ItemArmor.ArmorMaterial.DIAMOND) diamond = true;
                else if (mat == net.minecraft.item.ItemArmor.ArmorMaterial.IRON) { iron = true; ironPieces++; eligible++; }
                else if (mat == net.minecraft.item.ItemArmor.ArmorMaterial.CHAIN) { chain = true; chainPieces++; eligible++; }
            }
        }
        return new TargetTracker.ArmorProfile(iron, chain, diamond, ironPieces, chainPieces, eligible, total);
    }

    public double getThreat(EntityPlayerSP self, EntityPlayer target) {
        Result r = evaluate(self, target);
        return r.threatScore;
    }
}
