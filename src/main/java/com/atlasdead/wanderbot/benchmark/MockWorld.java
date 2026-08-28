package com.atlasdead.wanderbot.benchmark;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.init.Blocks;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3Pool;
import net.minecraft.world.IWorldAccess;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal mock World for offline PathFinder benchmarking.
 * Only getBlockState() and getHeight() are fully functional.
 */
public class MockWorld extends World {

    private final Map<Long, IBlockState> blocks = new HashMap<Long, IBlockState>();
    private final int worldHeight;
    private int blockStateCalls;

    public MockWorld(int height) {
        super(null, new WorldSettings(0L, "survival", false, false, "default"),
                null, null, null);
        this.worldHeight = height;
        this.blockStateCalls = 0;
    }

    public void setBlock(int x, int y, int z, Block block) {
        blocks.put(key(x, y, z), block.getDefaultState());
    }

    public int getBlockStateCalls() { return blockStateCalls; }
    public void resetBlockStateCalls() { blockStateCalls = 0; }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFFL);
    }

    @Override
    public IBlockState getBlockState(BlockPos pos) {
        blockStateCalls++;
        IBlockState state = blocks.get(key(pos.getX(), pos.getY(), pos.getZ()));
        return state != null ? state : Blocks.air.getDefaultState();
    }

    @Override
    public int getHeight() { return worldHeight; }

    // --- Stubbed World abstract methods ---

    @Override public boolean isAirBlock(BlockPos pos) { return getBlockState(pos).getBlock() == Blocks.air; }
    @Override public boolean isBlockNormalCube(BlockPos pos) { return false; }
    @Override public boolean isBlockFullCube(BlockPos pos) { return false; }
    @Override public int getLightFromBlocksForSkyBlocks(BlockPos pos, int lightValue) { return 0; }
    @Override public int getSkylightSubtracted() { return 15; }
    @Override public float getSunBrightness(float p) { return 1.0F; }
    @Override public float getSunBrightnessBody(float p) { return 1.0F; }
    @Override public float getCelestialAngle(float p) { return 0.0F; }
    @Override public int getHighestBlockYPlusOne(BlockPos pos) { return 0; }
    @Override public boolean extendedLevelsInChunkCache() { return false; }
    @Override public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean def) { return false; }
    @Override public IChunkProvider getChunkProvider() { return null; }
    @Override public void markBlockForUpdate(BlockPos pos) {}
    @Override public void notifyNeighborsOfStateChange(BlockPos pos, Block block) {}
    @Override public void markBlocksDirtyVertical(int x1, int z1, int x2, int z2) {}
    @Override public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {}
    @Override public void playSoundEffect(double x, double y, double z, String s, float v, float p) {}
    @Override public void playSoundToNearExcept(Entity e, String s, float v, float p) {}
    @Override public void makeSound(Entity e, String s, float v, float p) {}
    @Override public void playSound(double x, double y, double z, String s, float v, float p, boolean d) {}
    @Override public void spawnParticle(String n, double x, double y, double z, double mx, double my, double mz) {}
    @Override public void spawnParticle(String n, double x, double y, double z, double mx, double my, double mz, int[] a) {}
    @Override public Entity spawnEntityInWorld(Entity e) { return e; }
    @Override public void loadEntities(List e) {}
    @Override public void unloadEntities(List e) {}
    @Override public void tick() {}
    @Override public boolean isBlockLoaded(BlockPos pos) { return true; }
    @Override public boolean isBlockLoaded(BlockPos pos, boolean ae) { return true; }
    @Override public boolean isAreaLoaded(int x1, int y1, int z1, int x2, int y2, int z2, boolean ae) { return true; }
    @Override public boolean isAreaLoaded(BlockPos p1, BlockPos p2) { return true; }
    @Override public boolean isAreaLoaded(BlockPos pos, int r) { return true; }
    @Override public boolean isAreaLoaded(BlockPos pos, int r, boolean ae) { return true; }
    @Override public boolean isAreaLoaded(int x, int y, int z, int r, boolean ae) { return true; }
    @Override public void addTileEntity(net.minecraft.tileentity.TileEntity te) {}
    @Override public void setTileEntity(BlockPos pos, net.minecraft.tileentity.TileEntity te) {}
    @Override public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) { return null; }
    @Override public void removeTileEntity(BlockPos pos) {}
    @Override public int getLight(BlockPos pos) { return 15; }
    @Override public int getLightFromNeighbors(BlockPos pos) { return 15; }
    @Override public int getLightFor(EnumSkyBlock sb, BlockPos pos) { return 15; }
    @Override public void setLightFor(EnumSkyBlock sb, BlockPos pos, int lv) {}
    @Override public int getLightSubtracted(BlockPos pos, int a) { return 15; }
    @Override public boolean canSeeSky(BlockPos pos) { return true; }
    @Override public BiomeGenBase getBiomeGenForCoords(BlockPos pos) { return BiomeGenBase.plains; }
    @Override public BiomeGenBase getBiomeGenForCoords(int x, int z) { return BiomeGenBase.plains; }
    @Override public int getHeightValue(int x, int z) { return 0; }
    @Override public int isBlockHighEnough(int x, int y, int z) { return y; }
    @Override public boolean worldInfoIsRaining() { return false; }
    @Override public boolean isThundering() { return false; }
    @Override public boolean isRaining() { return false; }
    @Override public boolean canLightningStrike(BlockPos pos) { return false; }
    @Override public boolean isBlockHumidAt(BlockPos pos) { return false; }
    @Override public boolean isBlockUnderwater(BlockPos pos) { return false; }
    @Override public Vec3Pool getWorldVec3Pool() { return new Vec3Pool(0, 0); }
    @Override public void addWorldAccess(IWorldAccess wa) {}
    @Override public void removeWorldAccess(IWorldAccess wa) {}
    @Override public List getLoadedEntityList() { return new ArrayList(); }
    @Override public List getLoadedTileEntityList() { return new ArrayList(); }
    @Override public List getLoadedEntityListWithinAABB(Class c, AxisAlignedBB b) { return new ArrayList(); }
    @Override public List selectEntitiesWithinAABB(Class c, AxisAlignedBB b, Entity e) { return new ArrayList(); }
    @Override public Entity findNearestEntityWithinAABB(Class c, AxisAlignedBB b, Entity e) { return null; }
    @Override public int countEntities(Class c) { return 0; }
    @Override public int countEntities(EnumCreatureType t, boolean ip) { return 0; }
    @Override public List getAABBPool(Class c, AxisAlignedBB b) { return new ArrayList(); }
    @Override public List getAABBPool(Entity e, AxisAlignedBB b) { return new ArrayList(); }
    @Override public boolean checkLightFor(EnumSkyBlock lt, BlockPos pos) { return false; }
    @Override public int getRealHeight() { return worldHeight; }
    @Override public boolean setBlockState(BlockPos pos, IBlockState ns, int f) { return false; }
    @Override public void notifyBlockStateNotify(BlockPos pos, Block b, IBlockState ns) {}
    @Override public void notifyNeighborsRespectDebug(BlockPos pos, Block b) {}
    @Override public void updateEntity(Entity e) {}
    @Override public void addEntity(Entity e) {}
    @Override public void removeEntity(Entity e) {}
    @Override public void removeEntityFromWorld(int id) {}
    @Override public boolean isBlockContainingMailbox(BlockPos pos) { return false; }
    @Override public int getCombinedLight(BlockPos pos, int lv) { return 0xF000F0 | lv; }
    @Override public boolean canBlockBePlaced(BlockPos pos, net.minecraft.item.ItemStack item) { return false; }
    @Override public void playAuxSFX(int st, BlockPos pos, int data) {}
    @Override public float getBlockDensity(Vec3 vec, AxisAlignedBB bb) { return 1.0F; }
    @Override public void addBlockEvent(BlockPos pos, Block b, int eid, int ep) {}
    @Override public int getStrongPower(BlockPos pos, EnumFacing d) { return 0; }
    @Override public int getRedstonePower(BlockPos pos, EnumFacing d) { return 0; }
    @Override public void notifyNeighborsOfStateExcept(BlockPos pos, Block b, EnumFacing sd) {}
    @Override public boolean isBlockPowered(BlockPos pos) { return false; }
    @Override public int getComparatorInputOverride(BlockPos pos) { return 0; }
    @Override public int isProvidingWeakPower(BlockPos pos, EnumFacing d) { return 0; }
    @Override public boolean canLightningStrikeAt(BlockPos pos) { return false; }
    @Override public boolean isBlockHighHumid(BlockPos pos) { return false; }

    // --- Terrain generators ---

    public static MockWorld openTerrain() {
        MockWorld w = new MockWorld(64);
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++)
                w.setBlock(x, 3, z, Blocks.stone);
        return w;
    }

    public static MockWorld randomObstacles() {
        MockWorld w = new MockWorld(64);
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++)
                w.setBlock(x, 3, z, Blocks.stone);
        java.util.Random rng = new java.util.Random(42);
        for (int i = 0; i < 80; i++) {
            int bx = rng.nextInt(80) - 40;
            int bz = rng.nextInt(80) - 40;
            int bh = rng.nextInt(3) + 1;
            for (int y = 4; y < 4 + bh; y++)
                w.setBlock(bx, y, bz, Blocks.cobblestone);
        }
        return w;
    }

    public static MockWorld narrowCorridor() {
        MockWorld w = new MockWorld(64);
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++)
                w.setBlock(x, 3, z, Blocks.stone);
        for (int x = -30; x <= 30; x++)
            for (int z = -30; z <= 30; z++)
                if (Math.abs(z) > 1) {
                    w.setBlock(x, 4, z, Blocks.cobblestone);
                    w.setBlock(x, 5, z, Blocks.cobblestone);
                }
        return w;
    }

    public static MockWorld dropCliff() {
        MockWorld w = new MockWorld(64);
        for (int x = -15; x <= 15; x++)
            for (int z = -15; z <= 15; z++)
                w.setBlock(x, 10, z, Blocks.stone);
        for (int x = 5; x <= 30; x++)
            for (int z = -15; z <= 15; z++)
                w.setBlock(x, 4, z, Blocks.stone);
        return w;
    }

    public static MockWorld mixedTerrain() {
        MockWorld w = new MockWorld(64);
        for (int x = -40; x <= 40; x++)
            for (int z = -40; z <= 40; z++)
                w.setBlock(x, 3, z, Blocks.stone);
        java.util.Random rng = new java.util.Random(123);
        for (int i = 0; i < 60; i++) {
            int bx = rng.nextInt(80) - 40;
            int bz = rng.nextInt(80) - 40;
            int bh = rng.nextInt(4) + 1;
            for (int y = 4; y < 4 + bh; y++)
                w.setBlock(bx, y, bz, rng.nextBoolean() ? Blocks.cobblestone : Blocks.stone);
        }
        for (int x = -20; x <= -15; x++)
            for (int z = -5; z <= 5; z++) {
                w.setBlock(x, 4, z, Blocks.stone);
                w.setBlock(x, 5, z, Blocks.stone);
            }
        return w;
    }
}
