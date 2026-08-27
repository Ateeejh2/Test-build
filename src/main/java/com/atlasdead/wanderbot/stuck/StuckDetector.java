package com.atlasdead.wanderbot.stuck;

import net.minecraft.client.entity.EntityPlayerSP;

public class StuckDetector {
    private double lastX;
    private double lastY;
    private double lastZ;
    private int ticks;
    private boolean initialized;

    public void reset(EntityPlayerSP player) {
        lastX = player.posX;
        lastY = player.posY;
        lastZ = player.posZ;
        ticks = 0;
        initialized = true;
    }

    public boolean tick(EntityPlayerSP player) {
        if (!initialized) {
            reset(player);
            return false;
        }
        double dx = player.posX - lastX;
        double dy = player.posY - lastY;
        double dz = player.posZ - lastZ;
        double movedSq = dx * dx + dy * dy + dz * dz;
        lastX = player.posX;
        lastY = player.posY;
        lastZ = player.posZ;

        if (movedSq < 0.0008D) ticks++; else ticks = Math.max(0, ticks - 3);
        return ticks >= 25;
    }
}
