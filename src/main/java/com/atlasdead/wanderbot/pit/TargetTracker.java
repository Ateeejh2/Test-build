package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Target ranking for the user's private Pit test environment.
 *
 * Hard eligibility rules:
 *  - at least one iron or chainmail armor piece
 *  - any diamond armor piece excludes the player
 *  - non-combat/invalid players are excluded
 *
 * Ranking then considers armor composition, health, distance, line of sight,
 * vertical separation, local crowd pressure and target stickiness.
 */
public class TargetTracker {
    private final TargetScanCache scanCache = new TargetScanCache();
    private EntityPlayer target;
    private double targetScore = Double.NEGATIVE_INFINITY;
    private ArmorProfile targetArmor = ArmorProfile.none();

    public EntityPlayer findBest(final World world, final EntityPlayerSP self, final double maxRange, final PitZoneManager zones) {
        return findBest(world, self, maxRange, zones, null);
    }

    public EntityPlayer findBest(final World world, final EntityPlayerSP self, final double maxRange, final PitZoneManager zones, final StreakStrategyEngine strategy) {
        if (world == null || self == null) {
            clear();
            return null;
        }

        final long nowMs = System.currentTimeMillis();
        EntityPlayer cached = scanCache.get(world, self, maxRange, nowMs, 70L);
        if (cached != null) {
            target = cached;
            targetArmor = armorProfile(cached);
            return cached;
        }

        List<EntityPlayer> candidates = new ArrayList<EntityPlayer>();
        AxisAlignedBB box = self.getEntityBoundingBox().expand(maxRange, maxRange, maxRange);
        List<EntityPlayer> players = world.getEntitiesWithinAABB(EntityPlayer.class, box);

        for (EntityPlayer candidate : players) {
            if (!isValidCandidate(candidate, self)) continue;
            if (zones != null && zones.isPlayerProtected(candidate)) continue;

            double distance = self.getDistanceToEntity(candidate);
            if (distance > maxRange) continue;
            candidates.add(candidate);
        }

        final java.util.Map<Integer, Double> scoreCache = new java.util.HashMap<Integer, Double>();
        for (EntityPlayer candidate : candidates) {
            double value = score(world, self, candidate, maxRange);
            if (strategy != null) value = strategy.targetScoreWithStrategy(world, self, candidate, value);
            scoreCache.put(candidate.getEntityId(), value);
        }
        candidates.sort(new Comparator<EntityPlayer>() {
            @Override
            public int compare(EntityPlayer a, EntityPlayer b) {
                return Double.compare(scoreCache.get(b.getEntityId()), scoreCache.get(a.getEntityId()));
            }
        });

        if (candidates.isEmpty()) {
            clear();
            return null;
        }

        EntityPlayer best = candidates.get(0);
        double bestScore = strategy == null ? score(world, self, best, maxRange) : strategy.targetScoreWithStrategy(world, self, best, score(world, self, best, maxRange));

        // Target stickiness: do not swap targets for tiny score differences.
        // This prevents oscillation when two eligible players are similarly ranked.
        if (target != null && candidates.contains(target)) {
            double lockedScore = strategy == null ? score(world, self, target, maxRange) : strategy.targetScoreWithStrategy(world, self, target, score(world, self, target, maxRange));
            if (target != best && lockedScore + 12.0D >= bestScore) {
                best = target;
                bestScore = lockedScore;
            }
        }

        target = best;
        targetScore = bestScore;
        targetArmor = armorProfile(best);
        scanCache.put(world, self, maxRange, nowMs, best);
        return best;
    }

    private boolean isValidCandidate(EntityPlayer candidate, EntityPlayerSP self) {
        if (candidate == null || candidate == self || candidate.isDead) return false;
        if (candidate.isInvisible()) return false;
        return candidate.getHealth() > 0.0F;
    }

    /**
     * Returns true when the player matches the private Pit target policy.
     *
     * Eligible:
     * - at least one iron or chainmail slot
     * - zero diamond slots
     *
     * Leather-only, gold-only, naked and unrelated armor combinations are ignored.
     */
    public boolean isEligibleForStrategy(EntityPlayer player) {
        return isEligiblePitArmor(player);
    }

    private boolean isEligiblePitArmor(EntityPlayer player) {
        ArmorProfile profile = armorProfile(player);
        return profile.hasIronOrChain && !profile.hasDiamond;
    }

    private ArmorProfile armorProfile(EntityPlayer player) {
        boolean hasIron = false;
        boolean hasChain = false;
        boolean hasDiamond = false;
        int ironPieces = 0;
        int chainPieces = 0;
        int eligiblePieces = 0;
        int armorPieces = 0;

        ItemStack[] armor = player.inventory.armorInventory;
        if (armor == null) return ArmorProfile.none();

        for (ItemStack stack : armor) {
            if (stack == null || !(stack.getItem() instanceof ItemArmor)) continue;
            armorPieces++;

            ItemArmor item = (ItemArmor) stack.getItem();
            ItemArmor.ArmorMaterial material = item.getArmorMaterial();

            if (material == ItemArmor.ArmorMaterial.DIAMOND) {
                hasDiamond = true;
            } else if (material == ItemArmor.ArmorMaterial.IRON) {
                hasIron = true;
                ironPieces++;
                eligiblePieces++;
            } else if (material == ItemArmor.ArmorMaterial.CHAIN) {
                hasChain = true;
                chainPieces++;
                eligiblePieces++;
            }
        }

        return new ArmorProfile(hasIron, hasChain, hasDiamond, ironPieces, chainPieces, eligiblePieces, armorPieces);
    }

    private double score(World world, EntityPlayerSP self, EntityPlayer p, double maxRange) {
        ArmorProfile armor = armorProfile(p);
        double distance = self.getDistanceToEntity(p);
        double health = MathHelper.clamp_float(p.getHealth(), 0.0F, p.getMaxHealth());
        double maxHealth = Math.max(1.0F, p.getMaxHealth());
        double healthRatio = health / maxHealth;

        // Lower distance is better, but distance is deliberately not dominant.
        double score = 0.0D;
        score += 38.0D * (1.0D - clamp01(distance / Math.max(1.0D, maxRange)));

        // Lower current health is a useful opportunity signal while still
        // retaining a meaningful distance/visibility contribution.
        score += 26.0D * (1.0D - healthRatio);

        // Armor composition: chain is slightly preferred as the easier eligible
        // armor class; iron remains fully eligible and competitive.
        score += armor.chainPieces * 7.0D;
        score += armor.ironPieces * 4.0D;
        score += Math.max(0, 4 - armor.eligiblePieces) * 1.5D;

        // Clear sight is strongly preferred; blocked targets remain selectable so
        // the Navigation Core can route around the obstruction.
        score += self.canEntityBeSeen(p) ? 24.0D : -10.0D;

        // Small vertical differences are easier to maintain in combat.
        double vertical = Math.abs(self.posY - p.posY);
        score -= Math.min(10.0D, vertical * 2.5D);

        // Crowd pressure: several nearby eligible players make an otherwise good
        // target more dangerous to approach.
        int pressure = countEligiblePlayersNear(world, self, p, 6.0D);
        score -= pressure * 8.0D;

        // Prefer targets that are not already extremely close to the bot's own
        // position; that gives the controller a stable approach window.
        if (distance < 2.25D) score -= (2.25D - distance) * 6.0D;

        // Stable target lock to reduce rapid target switching.
        if (p == target) score += 14.0D;

        return score;
    }

    private int countEligiblePlayersNear(World world, EntityPlayerSP self, EntityPlayer targetPlayer, double radius) {
        if (world == null || targetPlayer == null) return 0;
        AxisAlignedBB box = targetPlayer.getEntityBoundingBox().expand(radius, radius, radius);
        List<EntityPlayer> nearby = world.getEntitiesWithinAABB(EntityPlayer.class, box);
        int count = 0;
        for (EntityPlayer player : nearby) {
            if (player == null || player == self || player == targetPlayer || player.isDead) continue;
            if (player.getHealth() <= 0.0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            if (isEligiblePitArmor(player)) count++;
        }
        return count;
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public EntityPlayer getTarget() { return target; }

    public double getTargetScore() { return targetScore; }

    public ArmorProfile getTargetArmor() { return targetArmor; }

    public void clear() {
        scanCache.invalidate();
        target = null;
        targetScore = Double.NEGATIVE_INFINITY;
        targetArmor = ArmorProfile.none();
    }

    public boolean isViable(EntityPlayerSP self, double maxRange) {
        return target != null
                && !target.isDead
                && target.getHealth() > 0.0F
                && self.getDistanceToEntity(target) <= maxRange;
    }

    public BlockPos targetFeet() {
        return target == null ? null : new BlockPos(target.posX, target.posY, target.posZ);
    }

    public static final class ArmorProfile {
        public final boolean hasIron;
        public final boolean hasChain;
        public final boolean hasDiamond;
        public final boolean hasIronOrChain;
        public final int ironPieces;
        public final int chainPieces;
        public final int eligiblePieces;
        public final int armorPieces;

        public ArmorProfile(boolean hasIron, boolean hasChain, boolean hasDiamond,
                             int ironPieces, int chainPieces, int eligiblePieces, int armorPieces) {
            this.hasIron = hasIron;
            this.hasChain = hasChain;
            this.hasDiamond = hasDiamond;
            this.hasIronOrChain = hasIron || hasChain;
            this.ironPieces = ironPieces;
            this.chainPieces = chainPieces;
            this.eligiblePieces = eligiblePieces;
            this.armorPieces = armorPieces;
        }

        public static ArmorProfile none() {
            return new ArmorProfile(false, false, false, 0, 0, 0, 0);
        }

        @Override
        public String toString() {
            return "ArmorProfile{" +
                    "iron=" + ironPieces +
                    ", chain=" + chainPieces +
                    ", diamond=" + hasDiamond +
                    '}';
        }
    }
}
