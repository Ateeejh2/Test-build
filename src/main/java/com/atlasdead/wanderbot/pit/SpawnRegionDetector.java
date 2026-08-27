package com.atlasdead.wanderbot.pit;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

/**
 * Automatically estimates the protected Pit spawn region around the known map origin (0,0).
 * Slime is treated only as a weak supporting feature because slime blocks can also occur
 * in combat terrain. The detector therefore combines central-hole geometry, radial symmetry,
 * solid-floor continuity, and safe-state observations instead of keying off one block type.
 */
public class SpawnRegionDetector {
    private static final int MAX_SCAN_RADIUS = 48;
    private static final int DEFAULT_RADIUS = 14;
    private static final int MIN_RADIUS = 6;

    private double centerX = 0.0D;
    private double centerZ = 0.0D;
    private double radius = DEFAULT_RADIUS;
    private double yTolerance = 9.0D;
    private double confidence;
    private boolean calibrated;
    private long lastScanMs;
    private int safeSamples;
    private double maxObservedSafeDistance;

    public void reset() {
        centerX = 0.0D;
        centerZ = 0.0D;
        radius = DEFAULT_RADIUS;
        yTolerance = 9.0D;
        confidence = 0.0D;
        calibrated = false;
        lastScanMs = 0L;
        safeSamples = 0;
        maxObservedSafeDistance = 0.0D;
    }

    public void update(Minecraft mc, EntityPlayerSP self, boolean waiting, boolean warmup) {
        if (mc == null || self == null || mc.theWorld == null) return;
        long now = System.currentTimeMillis();
        if (waiting || warmup) {
            observeSafePosition(self);
        }
        if (now - lastScanMs < 500L) return;
        lastScanMs = now;
        calibrateFromWorld(mc.theWorld, self.posY);
    }

    private void observeSafePosition(EntityPlayerSP self) {
        double dx = self.posX - centerX;
        double dz = self.posZ - centerZ;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d <= MAX_SCAN_RADIUS) {
            maxObservedSafeDistance = Math.max(maxObservedSafeDistance, d);
            safeSamples++;
        }
    }

    private void calibrateFromWorld(World world, double playerY) {
        int y = (int) Math.floor(playerY);
        int holeScore = centralHoleScore(world, y);
        double symmetry = radialSymmetry(world, y);
        double floorContinuity = radialFloorContinuity(world, y);
        double slimeSupport = symmetricSlimeSupport(world, y);

        double structuralScore = clamp01(holeScore * 0.45D + symmetry * 0.25D
                + floorContinuity * 0.20D + slimeSupport * 0.10D);

        double inferred = inferRadius(world, y, symmetry, floorContinuity);
        if (safeSamples > 0) inferred = Math.max(inferred, maxObservedSafeDistance + 2.0D);
        inferred = Math.max(MIN_RADIUS, Math.min(MAX_SCAN_RADIUS, inferred));

        radius = radius * 0.65D + inferred * 0.35D;
        confidence = Math.max(confidence * 0.7D, structuralScore);
        calibrated = confidence >= 0.50D;

        // Keep a conservative fallback when the structure fingerprint is weak.
        if (!calibrated) radius = DEFAULT_RADIUS;
    }

    private int centralHoleScore(World world, int y) {
        int air = 0;
        int total = 0;
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int dy = -2; dy <= 1; dy++) {
                    total++;
                    if (world.isAirBlock(new BlockPos(x, y + dy, z))) air++;
                }
            }
        }
        return total == 0 ? 0 : Math.min(1, air * 2 / total);
    }

    private double radialSymmetry(World world, int y) {
        int matches = 0;
        int samples = 0;
        for (int r = 5; r <= 24; r += 3) {
            BlockSig n = signature(world, 0, y, -r);
            BlockSig e = signature(world, r, y, 0);
            BlockSig s = signature(world, 0, y, r);
            BlockSig w = signature(world, -r, y, 0);
            if (n.equals(e)) matches++;
            if (n.equals(s)) matches++;
            if (n.equals(w)) matches++;
            samples += 3;
        }
        return samples == 0 ? 0.0D : (double) matches / samples;
    }

    private double radialFloorContinuity(World world, int y) {
        int solid = 0;
        int total = 0;
        for (int r = 2; r <= 24; r += 2) {
            int[][] pts = {{r,0},{0,r},{-r,0},{0,-r}};
            for (int[] p : pts) {
                total++;
                Block support = world.getBlockState(new BlockPos(p[0], y - 1, p[1])).getBlock();
                if (support.getMaterial().blocksMovement()) solid++;
            }
        }
        return total == 0 ? 0.0D : (double) solid / total;
    }

    private double symmetricSlimeSupport(World world, int y) {
        int symmetric = 0;
        int total = 0;
        for (int r = 4; r <= 20; r += 4) {
            boolean n = isSlime(world, 0, y - 1, -r);
            boolean e = isSlime(world, r, y - 1, 0);
            boolean s = isSlime(world, 0, y - 1, r);
            boolean w = isSlime(world, -r, y - 1, 0);
            int count = (n ? 1 : 0) + (e ? 1 : 0) + (s ? 1 : 0) + (w ? 1 : 0);
            if (count >= 2) symmetric++;
            total++;
        }
        return total == 0 ? 0.0D : (double) symmetric / total;
    }

    private double inferRadius(World world, int y, double symmetry, double continuity) {
        int best = DEFAULT_RADIUS;
        for (int r = 8; r <= 36; r += 2) {
            double shell = shellWalkableRatio(world, y, r);
            double next = shellWalkableRatio(world, y, r + 2);
            // A likely spawn boundary is a transition from a regular interior shell to
            // less regular terrain. Symmetry helps prevent battlefield stretches from winning.
            if (shell > 0.70D && next + 0.12D < shell && symmetry > 0.35D && continuity > 0.60D) {
                best = r;
                break;
            }
        }
        return best;
    }

    private double shellWalkableRatio(World world, int y, int r) {
        int walkable = 0;
        int total = 0;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI * 2.0D / 16.0D;
            int x = (int) Math.round(Math.cos(a) * r);
            int z = (int) Math.round(Math.sin(a) * r);
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos support = feet.down();
            if (world.isAirBlock(feet) && world.getBlockState(support).getBlock().getMaterial().blocksMovement()) {
                walkable++;
            }
            total++;
        }
        return total == 0 ? 0.0D : (double) walkable / total;
    }

    private BlockSig signature(World world, int x, int y, int z) {
        Block feet = world.getBlockState(new BlockPos(x, y, z)).getBlock();
        Block support = world.getBlockState(new BlockPos(x, y - 1, z)).getBlock();
        return new BlockSig(feet.getMaterial(), support.getMaterial(), feet == Blocks.slime_block, support == Blocks.slime_block);
    }

    private boolean isSlime(World world, int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).getBlock() == Blocks.slime_block;
    }

    private static final class BlockSig {
        private final Material feetMaterial;
        private final Material supportMaterial;
        private final boolean feetSlime;
        private final boolean supportSlime;

        private BlockSig(Material feetMaterial, Material supportMaterial, boolean feetSlime, boolean supportSlime) {
            this.feetMaterial = feetMaterial;
            this.supportMaterial = supportMaterial;
            this.feetSlime = feetSlime;
            this.supportSlime = supportSlime;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof BlockSig)) return false;
            BlockSig o = (BlockSig) other;
            return feetMaterial == o.feetMaterial
                    && supportMaterial == o.supportMaterial
                    && feetSlime == o.feetSlime
                    && supportSlime == o.supportSlime;
        }

        @Override
        public int hashCode() {
            int result = feetMaterial == null ? 0 : feetMaterial.hashCode();
            result = 31 * result + (supportMaterial == null ? 0 : supportMaterial.hashCode());
            result = 31 * result + (feetSlime ? 1 : 0);
            result = 31 * result + (supportSlime ? 1 : 0);
            return result;
        }
    }

    private double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public boolean isProtected(EntityPlayer player, double outsideMargin) {
        if (player == null) return false;
        double dx = player.posX - centerX;
        double dz = player.posZ - centerZ;
        double limit = radius + Math.max(0.0D, outsideMargin);
        return contains(player, outsideMargin, player.posY);
    }

    public boolean contains(EntityPlayer player, double outsideMargin, double anchorY) {
        if (player == null) return false;
        double dx = player.posX - centerX;
        double dz = player.posZ - centerZ;
        double limit = radius + Math.max(0.0D, outsideMargin);
        return dx * dx + dz * dz <= limit * limit && Math.abs(player.posY - anchorY) <= yTolerance;
    }

    public double getRadius() { return radius; }
    public double getConfidence() { return confidence; }
    public boolean isCalibrated() { return calibrated; }
    public double getCenterX() { return centerX; }
    public double getCenterZ() { return centerZ; }
}
