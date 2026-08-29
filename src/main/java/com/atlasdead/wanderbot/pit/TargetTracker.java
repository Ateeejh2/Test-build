package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Target selection for the Pit combat layer.
 *
 * Scans every player currently loaded by the client instead of limiting
 * acquisition to a 30-block search box. Spawn players are never eligible.
 */
public class TargetTracker {
    private static final double SPAWN_MIN_X = -13.0D;
    private static final double SPAWN_MAX_X = 16.0D;
    private static final double SPAWN_MIN_Z = -14.0D;
    private static final double SPAWN_MAX_Z = 19.0D;
    private static final double SPAWN_MIN_Y = 110.0D;

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

        List<EntityPlayer> candidates = new ArrayList<EntityPlayer>();
        List<EntityPlayer> players = world.playerEntities;
        if (players == null) {
            clear();
            return null;
        }

        // The requested scan mode is the complete set of player entities currently
        // known by the client. maxRange is retained for API compatibility.
        for (EntityPlayer candidate : players) {
            if (!isValidCandidate(candidate, self)) continue;
            if (isInSpawn(candidate)) continue;
            if (zones != null && zones.isPlayerProtected(candidate)) continue;
            if (!isEligiblePitArmor(candidate)) continue;
            candidates.add(candidate);
        }

        if (candidates.isEmpty()) {
            clear();
            return null;
        }

        candidates.sort(new Comparator<EntityPlayer>() {
            @Override
            public int compare(EntityPlayer a, EntityPlayer b) {
                return Double.compare(self.getDistanceToEntity(a), self.getDistanceToEntity(b));
            }
        });

        EntityPlayer nearest = candidates.get(0);
        target = nearest;
        targetScore = score(world, self, nearest, maxRange);
        targetArmor = armorProfile(nearest);
        return nearest;
    }

    /** True when the player is inside the configured Pit spawn volume. */
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

    /** Returns true when the player matches the Pit target armor policy. */
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
        double scoreRange = Math.max(32.0D, Math.min(256.0D, distance + 1.0D));
        double health = MathHelper.clamp_float(p.getHealth(), 0.0F, p.getMaxHealth());
        double maxHealth = Math.max(1.0F, p.getMaxHealth());
        double healthRatio = health / maxHealth;

        double score = 0.0D;
        score += 38.0D * (1.0D - clamp01(distance / scoreRange));
        score += 26.0D * (1.0D - healthRatio);
        score += armor.chainPieces * 7.0D;
        score += armor.ironPieces * 4.0D;
        score += Math.max(0, 4 - armor.eligiblePieces) * 1.5D;
        score += self.canEntityBeSeen(p) ? 24.0D : -10.0D;

        double vertical = Math.abs(self.posY - p.posY);
        score -= Math.min(10.0D, vertical * 2.5D);

        int pressure = countEligiblePlayersNear(world, self, p, 6.0D);
        score -= pressure * 8.0D;

        if (distance < 2.25D) score -= (2.25D - distance) * 6.0D;
        return score;
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

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public EntityPlayer getTarget() { return target; }
    public double getTargetScore() { return targetScore; }
    public ArmorProfile getTargetArmor() { return targetArmor; }

    public void clear() {
        target = null;
        targetScore = Double.NEGATIVE_INFINITY;
        targetArmor = ArmorProfile.none();
    }

    /** Target remains viable at any loaded-player distance, but never in spawn. */
    public boolean isViable(EntityPlayerSP self, double maxRange) {
        return target != null
                && isValidCandidate(target, self)
                && !isInSpawn(target)
                && isEligiblePitArmor(target);
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
