package com.atlasdead.wanderbot.bot;

import com.atlasdead.wanderbot.humanization.Humanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;

/** Centralizes all automated movement key state changes. */
public final class MovementController {
    private static final float STRAFE_DEAD_ZONE = 0.2F;

    private final Minecraft mc;

    public MovementController(Minecraft mc) {
        this.mc = mc;
    }

    public void forward(boolean pressed) {
        setKeyState(keyForward(), pressed);
    }

    public void backward(boolean pressed) {
        setKeyState(keyBack(), pressed);
    }

    /** Strafe is negative for left, positive for right and near-zero for neutral. */
    public void strafe(float amount) {
        setKeyState(keyLeft(), amount < -STRAFE_DEAD_ZONE);
        setKeyState(keyRight(), amount > STRAFE_DEAD_ZONE);
    }

    public void sprint(boolean pressed) {
        EntityPlayerSP player = mc.thePlayer;
        boolean effectivePressed = pressed && !Humanizer.shouldToggleSprintOff();
        setKeyState(keySprint(), effectivePressed);
        if (player != null) player.setSprinting(effectivePressed);
    }

    public void jump() {
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && player.onGround) {
            setKeyState(keyJump(), true);
        }
    }

    public void releaseJump() {
        setKeyState(keyJump(), false);
    }

    public void attack(boolean pressed) {
        setKeyState(keyAttack(), pressed);
    }

    /** Releases every input this controller may own. */
    public void release() {
        if (mc.gameSettings == null) return;
        setKeyState(keyForward(), false);
        setKeyState(keyBack(), false);
        setKeyState(keyLeft(), false);
        setKeyState(keyRight(), false);
        setKeyState(keyJump(), false);
        setKeyState(keySprint(), false);
        setKeyState(keyAttack(), false);
        if (mc.thePlayer != null) mc.thePlayer.setSprinting(false);
    }

    private void setKeyState(KeyBinding binding, boolean pressed) {
        if (binding == null) return;
        KeyBinding.setKeyBindState(binding.getKeyCode(), pressed);
    }

    private KeyBinding keyForward() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindForward;
    }

    private KeyBinding keyBack() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindBack;
    }

    private KeyBinding keyLeft() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindLeft;
    }

    private KeyBinding keyRight() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindRight;
    }

    private KeyBinding keyJump() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindJump;
    }

    private KeyBinding keySprint() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindSprint;
    }

    private KeyBinding keyAttack() {
        return mc.gameSettings == null ? null : mc.gameSettings.keyBindAttack;
    }
}
