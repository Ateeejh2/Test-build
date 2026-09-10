package com.atlasdead.wanderbot.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.KeyBinding;

/**
 * Centralizes automated keyboard state.
 *
 * <p>The controller only emits ordinary Minecraft key states. Opposing movement
 * keys are never held together, jump is momentary, and sprint is kept stable.
 * Attack input is deliberately not owned here because Myau is the sole attack
 * controller during combat.</p>
 */
public final class MovementController {
    private static final float STRAFE_DEAD_ZONE = 0.2F;

    private final Minecraft mc;
    private boolean movingBackward;

    public MovementController(Minecraft mc) {
        this.mc = mc;
    }

    /** Releases the one-tick jump action while preserving locomotion. */
    public void beginTick() {
        releaseJump();
    }

    public void forward(boolean pressed) {
        setKeyState(keyForward(), pressed);
        if (pressed) {
            setKeyState(keyBack(), false);
            movingBackward = false;
        }
    }

    public void backward(boolean pressed) {
        setKeyState(keyBack(), pressed);
        movingBackward = pressed;
        if (pressed) setKeyState(keyForward(), false);
    }

    /** Strafe is negative for left, positive for right and near-zero for neutral. */
    public void strafe(float amount) {
        boolean left = amount < -STRAFE_DEAD_ZONE;
        boolean right = amount > STRAFE_DEAD_ZONE;
        setKeyState(keyLeft(), left);
        setKeyState(keyRight(), right);
    }

    /** Applies one coherent WASD intent and rejects opposing forward/backward input. */
    public void locomotion(boolean forward, boolean backward, float strafe) {
        if (forward && backward) {
            forward = false;
            backward = false;
        }
        setKeyState(keyForward(), forward);
        setKeyState(keyBack(), backward);
        movingBackward = backward;
        this.strafe(strafe);
    }

    public void sprint(boolean pressed) {
        EntityPlayerSP player = mc.thePlayer;
        boolean effectivePressed = pressed && !movingBackward;
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

    /** Releases every input this controller may own. */
    public void release() {
        if (mc.gameSettings == null) return;
        setKeyState(keyForward(), false);
        setKeyState(keyBack(), false);
        setKeyState(keyLeft(), false);
        setKeyState(keyRight(), false);
        setKeyState(keyJump(), false);
        setKeyState(keySprint(), false);
        movingBackward = false;
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

}
