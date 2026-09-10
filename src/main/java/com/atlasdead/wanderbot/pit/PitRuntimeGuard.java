package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.world.World;

/**
 * Runtime safety guard for client context transitions. It never performs gameplay actions;
 * it only decides when the automation runtime must be reset or temporarily paused.
 */
public final class PitRuntimeGuard {
    public enum Status {
        SAFE,
        PAUSED_GUI,
        WAITING_PLAYER,
        PLAYER_DEAD,
        WORLD_CHANGED,
        DISCONNECTED
    }

    private World lastWorld;
    private int lastPlayerEntityId = Integer.MIN_VALUE;
    private String lastPlayerName;
    private Status status = Status.WAITING_PLAYER;
    private long lastProgressTick = -1L;
    private int staleTicks;

    public void reset() {
        lastWorld = null;
        lastPlayerEntityId = Integer.MIN_VALUE;
        lastPlayerName = null;
        status = Status.WAITING_PLAYER;
        lastProgressTick = -1L;
        staleTicks = 0;
    }

    public Status inspect(Minecraft mc, long runtimeTick) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            status = Status.WAITING_PLAYER;
            return status;
        }

        EntityPlayerSP player = mc.thePlayer;
        if (mc.currentScreen != null) {
            status = Status.PAUSED_GUI;
            return status;
        }

        if (player.isDead || player.getHealth() <= 0.0F) {
            status = Status.PLAYER_DEAD;
            return status;
        }

        if (lastWorld != null && lastWorld != mc.theWorld) {
            status = Status.WORLD_CHANGED;
            lastWorld = mc.theWorld;
            lastPlayerEntityId = player.getEntityId();
            lastPlayerName = player.getName();
            lastProgressTick = runtimeTick;
            staleTicks = 0;
            return status;
        }

        String playerName = player.getName();
        if (lastPlayerEntityId != Integer.MIN_VALUE
                && lastPlayerEntityId != player.getEntityId()) {
            status = Status.WORLD_CHANGED;
            lastPlayerEntityId = player.getEntityId();
            lastPlayerName = playerName;
            lastProgressTick = runtimeTick;
            staleTicks = 0;
            return status;
        }

        if (lastPlayerName != null && !lastPlayerName.equals(playerName)) {
            status = Status.WORLD_CHANGED;
            lastPlayerName = playerName;
            lastPlayerEntityId = player.getEntityId();
            lastProgressTick = runtimeTick;
            staleTicks = 0;
            return status;
        }

        if (lastWorld == null) lastWorld = mc.theWorld;
        lastPlayerEntityId = player.getEntityId();
        lastPlayerName = playerName;
        status = Status.SAFE;
        return status;
    }

    public void observeProgress(long runtimeTick, boolean active) {
        if (!active) {
            staleTicks = 0;
            lastProgressTick = runtimeTick;
            return;
        }
        if (lastProgressTick < 0L) {
            lastProgressTick = runtimeTick;
            staleTicks = 0;
            return;
        }
        long delta = runtimeTick - lastProgressTick;
        if (delta > 0L) {
            staleTicks = (int)Math.min(1000L, delta);
        }
    }

    public boolean stale(int threshold) {
        return staleTicks >= Math.max(1, threshold);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isHardStop() {
        return status == Status.DISCONNECTED || status == Status.WORLD_CHANGED;
    }

    public boolean isPaused() {
        return status == Status.PAUSED_GUI || status == Status.PLAYER_DEAD || status == Status.WAITING_PLAYER;
    }
}
