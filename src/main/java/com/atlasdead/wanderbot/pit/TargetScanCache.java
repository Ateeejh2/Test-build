package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Small client-thread cache for expensive target scans. */
public final class TargetScanCache {
    private World world;
    private int selfEntityId = Integer.MIN_VALUE;
    private long lastTimeMs;
    private double lastRange = -1.0D;
    private EntityPlayer cachedTarget;

    public EntityPlayer get(World world, EntityPlayerSP self, double range, long nowMs, long minIntervalMs) {
        if (world == this.world && self != null && self.getEntityId() == selfEntityId
                && Double.compare(range, lastRange) == 0 && cachedTarget != null
                && nowMs - lastTimeMs < minIntervalMs) {
            return cachedTarget;
        }
        return null;
    }

    public void put(World world, EntityPlayerSP self, double range, long nowMs, EntityPlayer target) {
        this.world = world;
        this.selfEntityId = self == null ? Integer.MIN_VALUE : self.getEntityId();
        this.lastRange = range;
        this.lastTimeMs = nowMs;
        this.cachedTarget = target;
    }

    public void invalidate() {
        world = null;
        selfEntityId = Integer.MIN_VALUE;
        lastTimeMs = 0L;
        lastRange = -1.0D;
        cachedTarget = null;
    }
}
