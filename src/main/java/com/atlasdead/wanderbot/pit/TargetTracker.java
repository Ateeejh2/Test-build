package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.pathfinding.CombatPathFinder;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Target selection for the Pit combat layer.
 *
 * The active target is reevaluated by score, but never replaced by a tiny score
 * fluctuation. A target is immediately released once it is dead or otherwise
 * invalid, so a new target can be acquired promptly after a kill.
 */
public class TargetTracker {
    private static final double SPAWN_MIN_X = -13.0D;
    private static final double SPAWN_MAX_X = 16.0D;
    private static final double SPAWN_MIN_Z = -14.0D;
    private static final double SPAWN_MAX_Z = 19.0D;
    private static final double SPAWN_MIN_Y = 110.0D;

    private static final long PATH_CHECK_CACHE_MS = 750L;
    private static final double TARGET_SWITCH_MARGIN = 12.0D;

    private final CombatPathFinder pathProbe = new CombatPathFinder();
    private final Map<Integer, PathCheck> pathCache = new HashMap<Integer, PathCheck>();

    private EntityPlayer target;
    private double targetScore = Double.NEGATIVE_INFINITY;
    private ArmorProfile targetArmor = ArmorProfile.none();

    public EntityPlayer findBest(final World world, final EntityPlayerSP self, final double maxRange,
                                 final PitZoneManager zones) {
        return findBest(world, self, maxRange, zones, null);
    }

    public EntityPlayer findBest(final World world, final EntityPlayerSP self, final double maxRange,
                                 final PitZoneManager zones, final StreakStrategyEngine strategy) {
        if (world == null || self == null) {
            clear();
            return null;
        }

        long now = System.currentTimeMillis();

        // The current target remains the preferred target while alive and valid.
        // It may still be replaced when another valid candidate is substantially
        // better according to the same score function.
        if (target != null) {
            if (!isValidCandidate(target, self)
                    || isInSpawn(target)
                    || (zones != null && zones.isPlayerProtected(target))
                    || !isEligiblePitArmor(target)) {
                clear();
            } else {
                targetScore = score(world, self, target, maxRange);
                targetArmor = armorProfile(target);
            }
        }

        List<EntityPlayer> players = world.playerEntities;
        if (players == null) {
            clear();
            return null;
        }

        EntityPlayer best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (EntityPlayer candidate : players) {
            if (!isValidCandidate(candidate, self)) continue;
            if (isInSpawn(candidate)) continue;
            if (zones != null && zones.isPlayerProtected(candidate)) continue;
            if (!isEligiblePitArmor(candidate)) continue;
            if (!cachedPathAvailable(world, self, candidate, now)) continue;

            double candidateScore = score(world, self, candidate, maxRange);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                best = candidate;
            }
        }

        if (best == null) {
            clear();
            return null;
        }

        // Keep the current target unless another candidate is clearly better.
        // This prevents rapid target flicker while still allowing genuinely better
        // targets to be selected during an ongoing fight.
        if (target != null && target != best && isValidCandidate(target, self)) {
            double currentScore = score(world, self, target, maxRange);
            if (bestScore <= currentScore + TARGET_SWITCH_MARGIN) {
                targetScore = currentScore;
                targetArmor = armorProfile(target);
                return target;
            }
        }

        target = best;
        targetScore = bestScore;
        targetArmor = armorProfile(best);
        return best;
    }

    /** Check reachability without disturbing the active CombatPathFinder. */
    private boolean cachedPathAvailable(World world, EntityPlayerSP self, EntityPlayer candidate, long now) {
        if (candidate == null) return false;
        int id = candidate.getEntityId();
        PathCheck cached = pathCache.get(id);
        if (cached != null && now - cached.checkedAt <= PATH_CHECK_CACHE_MS) {
            return cached.reachable;
        }

        boolean reachable = pathProbe.canReachTarget(world, self, candidate, 3000);
        pathCache.put(id, new PathCheck(now, reachable));
        return reachable;
    }

    public static boolean isInSpawn(EntityPlayer player) {
        if (player == null) return false;
        return isInSpawn(player.posX, player.posY, player.posZ);
    }

    public static boolean isInSpawn(double x, double y, double z) {
        return x >= SPAWN_MIN_X && x <= SPAWN_MAX_X
                && z >= SPAWN_MIN_Z && z <= SPAWN_MAX_Z
                && y >= SPAWN_MIN_Y;
    }

    private boolean isValidCandidate(EntityPlayer candidate, EntityPlayerSP self) {
        if (candidate == null || candidate == self || candidate.isDead) return false;
        if (candidate.isInvisible()) return false;
        if (candidate.getHealth() <= 0.0F) return false;
        if (candidate.capabilities != null && candidate.capabilities.isCreativeMode) return false;
        return true;
    }

    public boolean isEligibleForStrategy(EntityPlayer player) {
        return isEligiblePitArmor(player);
    }

    private boolean isEligiblePitArmor(EntityPlayer player) {
        if (player == null) return false;
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

        // Distance is the dominant signal. Other properties only provide modest
        // tie-breaking bonuses and cannot easily overpower a large distance gap.
        double distanceScore = Math.max(0.0D, 100.0D - distance * 3.0D);
        double healthScore = (1.0D - healthRatio) * 18.0D;
        double visibilityScore = self.canEntityBeSeen(p) ? 10.0D : -6.0D;
        double armorScore = Math.min(7.0D,
                armor.chainPieces * 2.0D + armor.ironPieces * 1.0D);

        double vertical = Math.abs(self.posY - p.posY);
        double verticalPenalty = Math.min(8.0D, vertical * 1.5D);

        int pressure = countEligiblePlayersNear(world, self, p, 6.0D);
        double pressurePenalty = Math.min(8.0D, pressure * 2.0D);

        double rangePenalty = maxRange > 0.0D && distance > maxRange
                ? Math.min(50.0D, (distance - maxRange) * 4.0D)
                : 0.0D;

        return distanceScore
                + healthScore
                + visibilityScore
                + armorScore
                - verticalPenalty
                - pressurePenalty
                - rangePenalty;
    }

    private int countEligiblePlayersNear(World world, EntityPlayerSP self, EntityPlayer targetPlayer, double radius) {
        if (world == null || targetPlayer == null) return 0;
        List<EntityPlayer> players = world.playerEntities;
        if (players == null) return 0;
        int count = 0;
        for (EntityPlayer player : players) {
            if (player == null || player == self || player == targetPlayer || player.isDead) continue;
            if (player.getHealth() <= 0.0F || player.isInvisible()) continue;
            if (player.capabilities != null && player.capabilities.isCreativeMode) continue;
            if (isInSpawn(player)) continue;
            if (isEligiblePitArmor(player) && player.getDistanceToEntity(targetPlayer) <= radius) count++;
        }
        return count;
    }

    public EntityPlayer getTarget() { return target; }
    public double getTargetScore() { return targetScore; }
    public ArmorProfile getTargetArmor() { return targetArmor; }

    public void clear() {
        target = null;
        targetScore = Double.NEGATIVE_INFINITY;
        targetArmor = ArmorProfile.none();
        pathCache.clear();
    }

    public boolean isViable(EntityPlayerSP self, double maxRange) {
        return target != null
                && isValidCandidate(target, self)
                && !isInSpawn(target)
                && isEligiblePitArmor(target);
    }

    public BlockPos targetFeet() {
        return target == null ? null : new BlockPos(target.posX, target.posY, target.posZ);
    }

    private static final class PathCheck {
        final long checkedAt;
        final boolean reachable;

        PathCheck(long checkedAt, boolean reachable) {
            this.checkedAt = checkedAt;
            this.reachable = reachable;
        }
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
