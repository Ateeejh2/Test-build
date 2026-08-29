package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.humanization.Humanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;

public class MovementController {
    private final Minecraft mc;

    public MovementController(Minecraft mc) {
        this.mc = mc;
    }

    public void forward(boolean pressed) {
        if (mc.gameSettings == null) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), pressed);
    }

    /** Strafe is -1 for left, +1 for right, 0 for neutral. */
    public void backward(boolean pressed) {
        if (mc.gameSettings == null) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), pressed);
    }

    public void strafe(float amount) {
        if (mc.gameSettings == null) return;
        boolean left = amount < -0.2F;
        boolean right = amount > 0.2F;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), left);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), right);
    }

    public void sprint(boolean pressed) {
        EntityPlayerSP player = mc.thePlayer;
        if (mc.gameSettings == null) return;
        // Humanization: sprint-reset pattern for combat
        // Real players toggle sprint briefly before attacking
        if (pressed && Humanizer.shouldToggleSprintOff()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            if (player != null) player.setSprinting(false);
            return;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), pressed);
        if (player != null) player.setSprinting(pressed);
    }

    public void jump() {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && player.onGround && mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), true);
        }
    }

    public void releaseJump() {
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
    }

    public void attack(boolean pressed) {
        if (mc.gameSettings == null) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), pressed);
    }

    /**
     * Performs the normal Minecraft 1.8.9 client-side attack action against
     * the given entity. Uses the same client-side pipeline as a real player
     * pressing left-click: attackEntity() + swingItem().
     *
     * @param target The entity to attack. Must not be null.
     */
    public void clickAttack(Entity target) {
        if (mc == null || mc.thePlayer == null || mc.playerController == null) return;
        if (target == null) return;

        // Send arm swing FIRST, then attack.
        // Anti-cheat (Vulcan Type 7) checks that swing packet arrives
        // before or with the attack packet. Queue order = send order.
        mc.thePlayer.swingItem();
        mc.playerController.attackEntity(mc.thePlayer, target);
    }

    public void release() {
        if (mc.gameSettings == null) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
    }
}
