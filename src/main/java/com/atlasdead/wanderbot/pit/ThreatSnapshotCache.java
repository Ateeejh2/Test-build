package com.atlasdead.wanderbot.pit;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

/** Caches the local threat scan briefly; all accesses happen on the client thread. */
public final class ThreatSnapshotCache {
    private World world;
    private int selfId = Integer.MIN_VALUE;
    private int targetId = Integer.MIN_VALUE;
    private long timestamp;
    private double radius = -1.0D;
    private CombatThreatField.Snapshot snapshot = CombatThreatField.Snapshot.empty();

    public CombatThreatField.Snapshot get(World world, EntityPlayerSP self, EntityPlayer target, double radius, long nowMs, long minIntervalMs) {
        int t = target == null ? Integer.MIN_VALUE : target.getEntityId();
        if (world == this.world && self != null && self.getEntityId() == selfId
                && t == targetId && Double.compare(radius, this.radius) == 0
                && nowMs - timestamp < minIntervalMs) return snapshot;
        return null;
    }

    public void put(World world, EntityPlayerSP self, EntityPlayer target, double radius, long nowMs, CombatThreatField.Snapshot value) {
        this.world = world;
        this.selfId = self == null ? Integer.MIN_VALUE : self.getEntityId();
        this.targetId = target == null ? Integer.MIN_VALUE : target.getEntityId();
        this.radius = radius;
        this.timestamp = nowMs;
        this.snapshot = value == null ? CombatThreatField.Snapshot.empty() : value;
    }

    public void invalidate() { world = null; selfId = Integer.MIN_VALUE; targetId = Integer.MIN_VALUE; timestamp = 0L; radius = -1.0D; snapshot = CombatThreatField.Snapshot.empty(); }
}
