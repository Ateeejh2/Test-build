package com.atlasdead.wanderbot.navigation;

import com.atlasdead.wanderbot.bot.MovementController;
import com.atlasdead.wanderbot.pathfinding.PathFinder;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

public class RecoveryController {
    private int ticks;
    private int phase;

    public void begin() {
        ticks = 0;
        phase = 0;
    }

    public boolean active() {
        return ticks < 38;
    }

    public boolean tick(World world, EntityPlayerSP player, MovementController movement, double desiredX, double desiredZ) {
        ticks++;
        movement.sprint(false);
        if (phase == 0) {
            // Small alternating strafe tries to free a corner without abandoning the route.
            movement.forward(true);
            movement.strafe(ticks / 5 % 2 == 0 ? 0.45F : -0.45F);
            if (ticks > 8) phase = 1;
        } else if (phase == 1) {
            movement.forward(false);
            movement.strafe(desiredX < 0.0D ? 0.65F : -0.65F);
            if (player.onGround) movement.jump();
            if (ticks > 18) phase = 2;
        } else {
            movement.strafe(0.0F);
            movement.forward(true);
        }
        return active();
    }

    public boolean hasSafeBackstep(World world, EntityPlayerSP player) {
        float yaw = (float)Math.toRadians(player.rotationYaw);
        int dx = (int)Math.round(Math.sin(yaw));
        int dz = (int)Math.round(-Math.cos(yaw));
        BlockPos back = new BlockPos(player.posX + dx, player.posY, player.posZ + dz);
        return PathFinder.canOccupy(world, back);
    }
}
