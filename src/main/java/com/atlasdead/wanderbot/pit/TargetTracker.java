package com.atlasdead.wanderbot.pit;

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
import java.util.List;

/** Stable loaded-player target selection for the Pit combat layer. */
public class TargetTracker {
    /** Practical infinity used only by callers that intentionally scan loaded players. */
    public static final double LOADED_PLAYER_SCAN_RANGE = 1000000.0D;
    /**
     * Keep active combat targets inside the local combat planner's useful envelope.
     * Players beyond this range are handled by target-search navigation first.
     */
    public static final double LOCAL_COMBAT_TRACK_RANGE = 58.0D;

    private static final double SPAWN_MIN_X = -13.0D;
    private static final double SPAWN_MAX_X = 16.0D;
    private static final double SPAWN_MIN_Z = -14.0D;
    private static final double SPAWN_MAX_Z = 19.0D;
    private static final double SPAWN_MIN_Y = 110.0D;

    private static final long TARGET_RESCAN_MS = 200L;
    private static final double TARGET_SWITCH_MARGIN = 12.0D;

    private EntityPlayer target;
    private double targetScore = Double.NEGATIVE_INFINITY;
    private ArmorProfile targetArmor = ArmorProfile.none();
    private long lastFullScanAt;

    private int lastLoadedPlayers;
    private int lastUsableCandidates;
    private int lastLocalCandidates;

    /**
     * Acquires a combat target from currently loaded players.
     *
     * <p>Target acquisition deliberately does <strong>not</strong> run A* first.
     * Reachability is a movement concern and rejecting a player because one bounded
     * path probe failed caused SEARCH to stall forever on complex Pit terrain.</p>
     */
    public EntityPlayer findBest(final World world, final EntityPlayerSP self,
                                 final PitZoneManager zones) {
        if (world == null || self == null) {
            clear();
            return null;
        }

        long now = System.currentTimeMillis();

        // Keep a valid current target stable and only run the loaded-player sweep
        // a few times per second. A target that has moved too far away is released
        // so target-search navigation can close the distance cheaply.
        if (target != null) {
            if (!isUsableCandidate(target, self, zones)
                    || self.getDistanceToEntity(target) > LOCAL_COMBAT_TRACK_RANGE) {
                clearTargetOnly();
            } else if (now - lastFullScanAt < TARGET_RESCAN_MS) {
                return target;
            }
        }

        lastFullScanAt = now;
        List<EntityPlayer> players = world.playerEntities;
        lastLoadedPlayers = players == null ? 0 : players.size();
        lastUsableCandidates = 0;
        lastLocalCandidates = 0;
        if (players == null || players.isEmpty()) {
            clearTargetOnly();
            return null;
        }

        // Diamond armor is a hard target exclusion. Other armor types remain
        // eligible and iron/chain can still influence target preference.
        List<CandidateScore> ranked = new ArrayList<CandidateScore>();
        for (EntityPlayer candidate : players) {
            if (!isUsableCandidate(candidate, self, zones)) continue;
            lastUsableCandidates++;

            double distance = self.getDistanceToEntity(candidate);
            if (distance > LOCAL_COMBAT_TRACK_RANGE) continue;
            lastLocalCandidates++;

            ArmorProfile armor = armorProfile(candidate);
            CandidateScore prepared = new CandidateScore(candidate, armor);
            ranked.add(prepared);
        }

        for (CandidateScore candidate : ranked) {
            candidate.score = score(self, candidate.player, candidate.armor, ranked);
        }
        Collections.sort(ranked, CandidateScore.BY_SCORE_DESC);

        if (ranked.isEmpty()) {
            clearTargetOnly();
            return null;
        }

        CandidateScore best = ranked.get(0);

        // Target hysteresis prevents rapid switching while somebody only becomes
        // marginally better for a tick or two.
        if (target != null && target != best.player && isUsableCandidate(target, self, zones)) {
            CandidateScore current = findCandidate(ranked, target);
            if (current != null && best.score <= current.score + TARGET_SWITCH_MARGIN) {
                targetScore = current.score;
                targetArmor = current.armor;
                return target;
            }
        }

        target = best.player;
        targetScore = best.score;
        targetArmor = best.armor;
        return target;
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

    /** Basic player validity shared by target selection and search navigation. */
    public static boolean isBasicCandidate(EntityPlayer candidate, EntityPlayerSP self) {
        if (candidate == null || candidate == self || candidate.isDead) return false;
        if (candidate.isInvisible()) return false;
        if (candidate.getHealth() <= 0.0F) return false;
        return candidate.capabilities == null || !candidate.capabilities.isCreativeMode;
    }

    private boolean isUsableCandidate(EntityPlayer candidate, EntityPlayerSP self, PitZoneManager zones) {
        if (!isBasicCandidate(candidate, self)) return false;
        if (isInSpawn(candidate)) return false;
        if (hasDiamondArmor(candidate)) return false;
        return zones == null || !zones.isPlayerProtected(candidate);
    }

    /** Returns true when the player has at least one diamond armor piece equipped. */
    public static boolean hasDiamondArmor(EntityPlayer player) {
        if (player == null || player.inventory == null || player.inventory.armorInventory == null) return false;
        for (ItemStack stack : player.inventory.armorInventory) {
            if (stack == null || !(stack.getItem() instanceof ItemArmor)) continue;
            ItemArmor item = (ItemArmor) stack.getItem();
            if (item.getArmorMaterial() == ItemArmor.ArmorMaterial.DIAMOND) return true;
        }
        return false;
    }

    /**
     * Generic combat-opponent validity used by pressure/threat models. Diamond
     * players remain visible to risk models even though target selection excludes them.
     */
    public boolean isEligibleForStrategy(EntityPlayer player) {
        if (player == null || player.isDead || player.isInvisible() || player.getHealth() <= 0.0F) return false;
        return player.capabilities == null || !player.capabilities.isCreativeMode;
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
        double visibilityScore = self.canEntityBeSeen(p) ? 10.0D : -4.0D;

        // Diamond players never reach scoring. Iron/chain remain a preference;
        // no-armor and other non-diamond armor players are still valid targets.
        double armorScore = Math.min(7.0D, armor.chainPieces * 2.0D + armor.ironPieces);
        if (armor.hasIronOrChain) armorScore += 5.0D;

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
    public int getLastLoadedPlayers() { return lastLoadedPlayers; }
    public int getLastUsableCandidates() { return lastUsableCandidates; }
    public int getLastLocalCandidates() { return lastLocalCandidates; }

    /** Drops only the active target. */
    public void clearTarget() {
        clearTargetOnly();
    }

    /** Full lifecycle reset. */
    public void clear() {
        clearTargetOnly();
        lastFullScanAt = 0L;
        lastLoadedPlayers = 0;
        lastUsableCandidates = 0;
        lastLocalCandidates = 0;
    }

    private void clearTargetOnly() {
        target = null;
        targetScore = Double.NEGATIVE_INFINITY;
        targetArmor = ArmorProfile.none();
    }

    /** Validates the tracked target and enforces the supplied maximum distance. */
    public boolean isViable(EntityPlayerSP self, double maxRange) {
        if (self == null || target == null || !isBasicCandidate(target, self)
                || isInSpawn(target) || hasDiamondArmor(target)) return false;
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
