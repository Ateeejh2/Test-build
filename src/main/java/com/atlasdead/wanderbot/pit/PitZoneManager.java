package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/**
 * Tracks the private Pit recreation's non-combat spawn area.
 *
 * The real Pit has a preparation/spawn area where PvP is disabled; combat starts
 * after leaving the area via the center drop or launch-pad routes. This class
 * intentionally learns an anchor from the player's own Waiting/Warmup position
 * instead of hard-coding one map's coordinates.
 */
public class PitZoneManager {
    private static final double DEFAULT_SPAWN_RADIUS = 14.0D;
    private static final double DEFAULT_SPAWN_Y_TOLERANCE = 9.0D;
    private static final double OUTSIDE_MARGIN = 1.75D;

    private double spawnX;
    private double spawnY;
    private double spawnZ;
    private boolean anchorKnown;
    private double spawnRadius = DEFAULT_SPAWN_RADIUS;
    private final SpawnRegionDetector detector = new SpawnRegionDetector();
    private double spawnYTolerance = DEFAULT_SPAWN_Y_TOLERANCE;

    public void reset() {
        anchorKnown = false;
        spawnX = spawnY = spawnZ = 0.0D;
        detector.reset();
    }

    /** Learn/refresh the spawn anchor while the player is definitely protected. */
    public void learnFromSafeState(EntityPlayerSP player, boolean waiting, boolean warmup) {
        if (player == null || !(waiting || warmup)) return;
        if (!anchorKnown) {
            spawnY = player.posY;
            spawnX = 0.0D;
            spawnZ = 0.0D;
            anchorKnown = true;
        }
    }

    /**
     * Uses the automatically calibrated origin-centered spawn region. A small hysteresis
     * margin is reserved for future boundary transitions; the current public checks use the
     * calibrated core region.
     */
    public void updateAutoDetection(Minecraft mc, EntityPlayerSP player, boolean waiting, boolean warmup) {
        detector.update(mc, player, waiting, warmup);
        if (detector.isCalibrated()) {
            spawnX = detector.getCenterX();
            spawnZ = detector.getCenterZ();
            spawnRadius = detector.getRadius();
        }
    }

    public boolean isSelfProtected(EntityPlayerSP player) {
        return player != null && isProtected(player.posX, player.posY, player.posZ, false);
    }

    public boolean isPlayerProtected(EntityPlayer player) {
        return player != null && isProtected(player.posX, player.posY, player.posZ, false);
    }

    private boolean isProtected(double x, double y, double z, boolean outside) {
        if (!anchorKnown) return false;
        double dx = x - spawnX;
        double dz = z - spawnZ;
        double horizontalSq = dx * dx + dz * dz;
        double limit = spawnRadius + (outside ? OUTSIDE_MARGIN : 0.0D);
        if (horizontalSq > limit * limit) return false;
        return Math.abs(y - spawnY) <= spawnYTolerance;
    }

    public boolean hasAnchor() { return anchorKnown; }
    public double getSpawnX() { return spawnX; }
    public double getSpawnY() { return spawnY; }
    public double getSpawnZ() { return spawnZ; }
    public double getSpawnRadius() { return spawnRadius; }
    public double getDetectionConfidence() { return detector.getConfidence(); }
    public boolean isAutoCalibrated() { return detector.isCalibrated(); }

    public void setSpawnRadius(double radius) {
        if (radius >= 4.0D && radius <= 64.0D) this.spawnRadius = radius;
    }

    public void setSpawnYTolerance(double tolerance) {
        if (tolerance >= 2.0D && tolerance <= 32.0D) this.spawnYTolerance = tolerance;
    }

    public String describe(EntityPlayerSP player) {
        if (!anchorKnown) return "UNKNOWN";
        return isSelfProtected(player) ? "SPAWN" : "COMBAT";
    }
}
