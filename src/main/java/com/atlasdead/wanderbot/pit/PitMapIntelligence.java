package com.atlasdead.wanderbot.pit;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Lightweight runtime map model for the Pit recreation.
 * It does not assume a particular world seed or fixed coordinates: the protected
 * spawn anchor is used as the primary reference and nearby terrain is sampled on demand.
 */
public class PitMapIntelligence {
    public enum Zone { UNKNOWN, SPAWN, NORTHWEST, NORTHEAST, SOUTHWEST, SOUTHEAST, MID }

    private double centerX;
    private double centerZ;
    private boolean centerKnown;
    private long lastScan;
    private double hazard;
    private double openness;
    private int unsafeDrops;
    private int blockedDirections;
    private Zone zone = Zone.UNKNOWN;

    public void reset() {
        centerKnown = false;
        lastScan = 0L;
        hazard = 0.0D;
        openness = 0.0D;
        unsafeDrops = 0;
        blockedDirections = 0;
        zone = Zone.UNKNOWN;
    }

    public void update(Minecraft mc, PitZoneManager zones) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || zones == null) return;
        if (!zones.hasAnchor()) return;

        // In the Pit layout the spawn anchor is a stable reference. For local navigation,
        // the combat center is approximated toward the center-facing direction of the arena
        // by using the first safe exploration vector found around the player. Until that is
        // known, spawn itself is the conservative reference point.
        if (!centerKnown) {
            centerX = zones.getSpawnX();
            centerZ = zones.getSpawnZ();
            centerKnown = true;
        }

        long now = System.currentTimeMillis();
        if (now - lastScan < 120L) {
            zone = classify(mc.thePlayer, zones);
            return;
        }
        lastScan = now;
        scanLocal(mc.theWorld, mc.thePlayer);
        zone = classify(mc.thePlayer, zones);
    }

    private void scanLocal(World world, EntityPlayerSP player) {
        double danger = 0.0D;
        int blocked = 0;
        int drops = 0;
        int samples = 0;

        int[][] dirs = {{0,-1},{1,0},{0,1},{-1,0}};
        for (int[] d : dirs) {
            if (!isDirectionWalkable(world, player, d[0], d[1])) blocked++;
            if (hasUnsafeDrop(world, player, d[0], d[1])) {
                drops++;
                danger += 0.25D;
            }
            samples++;
        }

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                BlockPos feet = new BlockPos(player.posX + dx, player.posY, player.posZ + dz);
                BlockPos support = feet.down();
                Block block = world.getBlockState(support).getBlock();
                Material material = block.getMaterial();
                if (material == Material.lava || material == Material.fire) danger += 0.05D;
                if (material == Material.water) danger += 0.01D;
                samples++;
            }
        }

        blockedDirections = blocked;
        unsafeDrops = drops;
        openness = 1.0D - (blocked / 4.0D);
        hazard = Math.min(1.0D, danger + drops * 0.12D + blocked * 0.05D);
    }

    private boolean isDirectionWalkable(World world, EntityPlayerSP player, int dx, int dz) {
        int x = (int) Math.floor(player.posX + dx);
        int y = (int) Math.floor(player.posY);
        int z = (int) Math.floor(player.posZ + dz);
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        BlockPos support = feet.down();
        return world.isAirBlock(feet) && world.isAirBlock(head)
                && world.getBlockState(support).getBlock().getMaterial().blocksMovement();
    }

    private boolean hasUnsafeDrop(World world, EntityPlayerSP player, int dx, int dz) {
        int x = (int) Math.floor(player.posX + dx);
        int z = (int) Math.floor(player.posZ + dz);
        int baseY = (int) Math.floor(player.posY) - 1;
        int air = 0;
        for (int i = 0; i < 4; i++) {
            BlockPos p = new BlockPos(x, baseY - i, z);
            if (world.isAirBlock(p)) air++;
            else break;
        }
        return air >= 3;
    }

    private Zone classify(EntityPlayerSP player, PitZoneManager zones) {
        if (zones.isSelfProtected(player)) return Zone.SPAWN;
        double dx = player.posX - centerX;
        double dz = player.posZ - centerZ;
        double d2 = dx * dx + dz * dz;
        if (d2 < 16.0D) return Zone.MID;
        if (dx >= 0 && dz < 0) return Zone.NORTHEAST;
        if (dx < 0 && dz < 0) return Zone.NORTHWEST;
        if (dx >= 0) return Zone.SOUTHEAST;
        return Zone.SOUTHWEST;
    }

    public Zone zone() { return zone; }
    public double hazard() { return hazard; }
    public double openness() { return openness; }
    public int unsafeDrops() { return unsafeDrops; }
    public int blockedDirections() { return blockedDirections; }
    public boolean centerKnown() { return centerKnown; }
    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }

    /** Returns true when a proposed local vector points toward an unsafe drop/hazard. */
    public boolean shouldAvoid(EntityPlayerSP player, World world, double dirX, double dirZ) {
        if (player == null || world == null) return true;
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001D) return false;
        int sx = (int) Math.signum(dirX / len);
        int sz = (int) Math.signum(dirZ / len);
        if (hasUnsafeDrop(world, player, sx, sz)) return true;
        int x = (int) Math.floor(player.posX + sx);
        int y = (int) Math.floor(player.posY);
        int z = (int) Math.floor(player.posZ + sz);
        Material m = world.getBlockState(new BlockPos(x, y - 1, z)).getBlock().getMaterial();
        return m == Material.lava || m == Material.fire;
    }
}
