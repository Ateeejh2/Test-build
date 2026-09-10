package com.atlasdead.wanderbot.pit;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;

/**
 * Snapshot of a combat target at a specific point in time.
 * Inspired by Myau's AttackData — keeps entity reference, expanded bounding box,
 * and acquisition timestamp so the combat layer can reason about the target
 * without being surprised by per-tick position jitter.
 */
public final class CombatTarget {
    private final EntityPlayer entity;
    private final AxisAlignedBB box;
    private final double posX;
    private final double posY;
    private final double posZ;
    private final long acquiredTick;
    private final float entityHealth;

    public CombatTarget(EntityPlayer entity, long currentTick) {
        this.entity = entity;
        double border = entity.getCollisionBorderSize();
        this.box = entity.getEntityBoundingBox().expand(border, border, border);
        this.posX = entity.posX;
        this.posY = entity.posY;
        this.posZ = entity.posZ;
        this.acquiredTick = currentTick;
        this.entityHealth = entity.getHealth();
    }

    /** Refresh the snapshot from the live entity (call each tick while target is held). */
    public CombatTarget refresh(long currentTick) {
        return new CombatTarget(this.entity, currentTick);
    }

    public EntityPlayer getEntity() { return entity; }
    public AxisAlignedBB getBox() { return box; }
    public double getPosX() { return posX; }
    public double getPosY() { return posY; }
    public double getPosZ() { return posZ; }
    public long getAcquiredTick() { return acquiredTick; }
    public float getHealth() { return entityHealth; }

    /** Is the underlying entity still alive and in the world? */
    public boolean isAlive() {
        return entity != null && !entity.isDead && entity.getHealth() > 0F;
    }

    /** Convenience: live distance from self. */
    public double distanceTo(net.minecraft.client.entity.EntityPlayerSP self) {
        return self.getDistanceToEntity(entity);
    }

    /** Convenience: live bounding box (not snapshot). */
    public AxisAlignedBB liveBox() {
        double b = entity.getCollisionBorderSize();
        return entity.getEntityBoundingBox().expand(b, b, b);
    }

    @Override
    public String toString() {
        return "CombatTarget{" + (entity != null ? entity.getName() : "null")
                + " hp=" + String.format("%.1f", entityHealth)
                + " tick=" + acquiredTick + "}";
    }
}
