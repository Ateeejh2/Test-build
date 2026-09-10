package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.pathfinding.CombatPathFinder;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Stable loaded-player target selection for the Pit combat layer. */
public class TargetTracker {
    /** Practical infinity used only by callers that intentionally scan loaded players. */
    public static final double LOADED_PLAYER_SCAN_RANGE = 1000000.0D;

    private static final double SPAWN_MIN_X = -13.0D;
    private static final double SPAWN_MAX_X = 16.0D;
    private static final double SPAWN_MIN_Z = -14.0D;
    private static final double SPAWN_MAX_Z = 19.0D;
    private static final double SPAWN_MIN_Y = 110.0D;

    private static final long PATH_CHECK_CACHE_MS = 750L;
    private static final long PATH_CACHE_PRUNE_MS = 5000L;
    private static final long TARGET_RESCAN_MS = 200L;
    private static final double TARGET_SWITCH_MARGIN = 12.0D;

    private final CombatPathFinder pathProbe = new CombatPathFinder();
    private final Map<Integer, PathCheck> pathCache = new HashMap<Integer, PathCheck>();

    private EntityPlayer target;
    private double targetScore = Double.NEGATIVE_INFINITY;
    private ArmorProfile targetArmor = ArmorProfile.none();
    private long lastFullScanAt;
    private long lastCachePruneAt;

    /** Acquires from all currently loaded eligible players. */
    public EntityPlayer findBest(final World world, final EntityPlayerSP self,
                                 final PitZoneManager zones) {
        if (world == null || self == null) {
            clear();
            return null;
        }

        long now = System.currentTimeMillis();
        prunePathCache(now);

        // Keep a valid current target stable and only run the expensive loaded-player
        // sweep a few times per second.
        if (target != null) {
            if (!isValidCandidate(target, self)
                    || isInSpawn(target)
                    || (zones != null && zones.isPlayerProtected(target))
                    || !isEligiblePitArmor(target)) {
                clearTargetOnly();
            } else if (now - lastFullScanAt < TARGET_RESCAN_MS) {
                return target;
            }
        }

        lastFullScanAt = now;
        List<EntityPlayer> players = world.playerEntities;
        if (players == null || players.isEmpty()) {
            clearTargetOnly();
            return null;
        }

        // Parse armor once per candidate. The previous implementation reparsed every
        // player's armor inside every candidate's crowd-pressure score (O(n^2) armor
        // scans in a busy lobby). Pressure is now computed over this prepared list.
        List<CandidateScore> ranked = new ArrayList<CandidateScore>();
        for (EntityPlayer candidate : players) {
            if (!isValidCandidate(candidate, self)) continue;
            if (isInSpawn(candidate)) continue;
            if (zones != null && zones.isPlayerProtected(candidate)) continue;
            ArmorProfile armor = armorProfile(candidate);
            if (!armor.hasIronOrChain || armor.hasDiamond) continue;
            ranked.add(new CandidateScore(candidate, armor));
        }
        for (CandidateScore candidate : ranked) {
            candidate.score = score(self, candidate.player, candidate.armor, ranked);
        }
        Collections.sort(ranked, CandidateScore.BY_SCORE_DESC);

        EntityPlayer best = null;
        ArmorProfile bestArmor = ArmorProfile.none();
        double bestScore = Double.NEGATIVE_INFINITY;
        for (CandidateScore candidate : ranked) {
            if (!cachedPathAvailable(world, self, candidate.player, now)) continue;
            best = candidate.player;
            bestArmor = candidate.armor;
            bestScore = candidate.score;
            break;
        }

        if (best == null) {
            clearTargetOnly();
            return null;
        }

        if (target != null && target != best && isValidCandidate(target, self)
                && cachedPathAvailable(world, self, target, now)) {
            CandidateScore current = findCandidate(ranked, target);
            if (current != null && bestScore <= current.score + TARGET_SWITCH_MARGIN) {
                targetScore = current.score;
                targetArmor = current.armor;
                return target;
            }
        }

        target = best;
        targetScore = bestScore;
        targetArmor = bestArmor;
        return best;
    }

    /** Check reachability without disturbing the active CombatPathFinder. */
    private boolean cachedPathAvailable(World world, EntityPlayerSP self, EntityPlayer candidate, long now) {
        int id = candidate.getEntityId();
        PathCheck cached = pathCache.get(id);
        if (cached != null && now - cached.checkedAt <= PATH_CHECK_CACHE_MS) {
            return cached.reachable;
        }

        boolean reachable = pathProbe.canReachTarget(world, self, candidate, 3000);
        pathCache.put(id, new PathCheck(now, reachable));
        return reachable;
    }

    private void prunePathCache(long now) {
        if (now - lastCachePruneAt < PATH_CACHE_PRUNE_MS) return;
        lastCachePruneAt = now;
        Iterator<Map.Entry<Integer, PathCheck>> it = pathCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, PathCheck> entry = it.next();
            if (now - entry.getValue().checkedAt > PATH_CACHE_PRUNE_MS) it.remove();
        }
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
        return candidate.capabilities == null || !candidate.capabilities.isCreativeMode;
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

        return new ArmorProfile(hasIron, hasChain, hasDiamond,
                ironPieces, chainPieces, eligiblePieces, armorPieces);
    }

    private double score(EntityPlayerSP self, EntityPlayer p, ArmorProfile armor,
                         List<CandidateScore> eligible) {
        double distance = self.getDistanceToEntity(p);
        double health = MathHelper.clamp_float(p.getHealth(), 0.0F, p.getMaxHealth());
        double maxHealth = Math.max(1.0F, p.getMaxHealth());
        double healthRatio = health / maxHealth;

        double distanceScore = Math.max(0.0D, 100.0D - distance * 3.0D);
        double healthScore = (1.0D - healthRatio) * 18.0D;
        double visibilityScore = self.canEntityBeSeen(p) ? 10.0D : -6.0D;
        double armorScore = Math.min(7.0D, armor.chainPieces * 2.0D + armor.ironPieces);
        double verticalPenalty = Math.min(8.0D, Math.abs(self.posY - p.posY) * 1.5D);
        int pressure = countEligiblePlayersNear(self, p, eligible, 6.0D);
        double pressurePenalty = Math.min(8.0D, pressure * 2.0D);

        return distanceScore + healthScore + visibilityScore + armorScore
                - verticalPenalty - pressurePenalty;
    }

    private int countEligiblePlayersNear(EntityPlayerSP self, EntityPlayer targetPlayer,
                                         List<CandidateScore> eligible, double radius) {
        if (targetPlayer == null || eligible == null || eligible.isEmpty()) return 0;
        int count = 0;
        double radiusSq = radius * radius;
        for (CandidateScore candidate : eligible) {
            EntityPlayer player = candidate.player;
            if (player == null || player == self || player == targetPlayer) continue;
            double dx = player.posX - targetPlayer.posX;
            double dy = player.posY - targetPlayer.posY;
            double dz = player.posZ - targetPlayer.posZ;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) count++;
        }
        return count;
    }

    private CandidateScore findCandidate(List<CandidateScore> ranked, EntityPlayer player) {
        if (ranked == null || player == null) return null;
        for (CandidateScore candidate : ranked) {
            if (candidate.player == player) return candidate;
        }
        return null;
    }

    public EntityPlayer getTarget() { return target; }
    public double getTargetScore() { return targetScore; }
    public ArmorProfile getTargetArmor() { return targetArmor; }

    /** Drops only the active target while preserving short-lived reachability cache entries. */
    public void clearTarget() {
        clearTargetOnly();
    }

    /** Full lifecycle reset, including reachability cache. */
    public void clear() {
        clearTargetOnly();
        pathCache.clear();
        lastCachePruneAt = 0L;
        lastFullScanAt = 0L;
    }

    private void clearTargetOnly() {
        target = null;
        targetScore = Double.NEGATIVE_INFINITY;
        targetArmor = ArmorProfile.none();
    }

    /**
     * Validates the tracked target and, unlike the previous implementation,
     * actually enforces the supplied maximum distance.
     */
    public boolean isViable(EntityPlayerSP self, double maxRange) {
        if (self == null || target == null || !isValidCandidate(target, self)
                || isInSpawn(target) || !isEligiblePitArmor(target)) return false;
        return maxRange <= 0.0D
                || maxRange >= LOADED_PLAYER_SCAN_RANGE
                || self.getDistanceToEntity(target) <= maxRange;
    }

    public BlockPos targetFeet() {
        return target == null ? null : new BlockPos(target.posX, target.posY, target.posZ);
    }


    private static final class CandidateScore {
        static final Comparator<CandidateScore> BY_SCORE_DESC = new Comparator<CandidateScore>() {
            @Override
            public int compare(CandidateScore a, CandidateScore b) {
                return Double.compare(b.score, a.score);
            }
        };

        final EntityPlayer player;
        final ArmorProfile armor;
        double score;

        CandidateScore(EntityPlayer player, ArmorProfile armor) {
            this.player = player;
            this.armor = armor;
        }
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
