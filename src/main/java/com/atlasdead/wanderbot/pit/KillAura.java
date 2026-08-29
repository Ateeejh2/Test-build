package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.Random;

/**
 * KillAura module — matches vanilla 1.8.9 left-click packet flow exactly.
 *
 * Vanilla left-click packet sequence:
 *   1. C02PacketUseEntity (attack) — queued by playerControllerMP.attackEntity()
 *   2. C03PacketPlayer (position)  — sent by onUpdateWalkingPlayer()
 *   3. C0APacketAnimation (swing)  — sent by onUpdateWalkingPlayer() when isSwinging
 *
 * This class does NOT manually send C0APacketAnimation.
 * Instead it calls playerController.attackEntity() which:
 *   - Sends C02PacketUseEntity (attack)
 *   - Calls swingItem() which sets isSwinging = true
 *   - Vanilla's onUpdateWalkingPlayer() then sends C0A naturally
 *
 * This produces the exact same packet order as a real player:
 *   C02 → C03 → C0A
 *
 * Sending a manual C0A BEFORE attackEntity() would create a duplicate
 * swing packet (C0A → C02 → C03 → C0A) which triggers:
 *   - Vulcan Killaura A: Post UseEntity packets
 *   - Vulcan BadPacket X: Post ArmAnimation packets
 *
 * Rotation fields rotationYaw, rotationPitch, rotationYawHead, and
 * renderYawOffset are all set together so the server sees a consistent
 * entity model state — preventing Vulcan Aim I.
 */
public class KillAura {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();

    // === Settings ===
    public boolean enabled = false;
    public float attackRange = 3.0F;
    public float swingRange = 3.5F;
    public int minCPS = 12;
    public int maxCPS = 14;
    public RotationMode rotationMode = RotationMode.SILENT;
    public MoveFixMode moveFixMode = MoveFixMode.SILENT;
    public float smoothing = 0.0F;
    public boolean throughWalls = true;
    public int fov = 360;

    public enum RotationMode { NONE, LEGIT, SILENT, LOCK_VIEW }
    public enum MoveFixMode { NONE, SILENT, STRICT }

    // === Internal state ===
    private CombatTarget target;
    private long attackDelayMS = 0L;

    // Rotation state — computed in tickPre, applied before C03
    private float desiredYaw;
    private float desiredPitch;
    private boolean rotationReady = false;

    /**
     * Minimum ticks that rotation must be aligned before an attack is sent.
     * Ensures the server has seen our rotation before the attack packet.
     */
    private int rotationAlignedTicks;

    public void reset() {
        target = null;
        attackDelayMS = 0L;
        rotationReady = false;
        rotationAlignedTicks = 0;
    }

    private void syncSettings() {
        attackRange = (float) com.atlasdead.wanderbot.config.WanderBotSettings.killAuraAttackRange;
        swingRange = (float) com.atlasdead.wanderbot.config.WanderBotSettings.killAuraSwingRange;
        minCPS = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMinCPS;
        maxCPS = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMaxCPS;
        rotationMode = RotationMode.values()[com.atlasdead.wanderbot.config.WanderBotSettings.killAuraRotationMode];
        moveFixMode = MoveFixMode.values()[com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMoveFixMode];
        smoothing = (float) com.atlasdead.wanderbot.config.WanderBotSettings.killAuraSmoothing;
        throughWalls = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraThroughWalls;
        fov = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraFOV;
    }

    // ===================================================================
    // PHASE 1: PRE — Called BEFORE vanilla tick sends position packets
    //
    // This sets rotationYaw/pitch before vanilla's onUpdateWalkingPlayer()
    // sends C03/C06, so the server sees consistent rotation.
    //
    // Attack packets are sent via playerController.attackEntity() which:
    //   - Queues C02PacketUseEntity (attack) to the send buffer
    //   - Calls swingItem() setting isSwinging = true
    //   - Vanilla's onUpdateWalkingPlayer() then sends C0A + C03 naturally
    //
    // Packet order on the wire: C02 → C03 → C0A (matches vanilla exactly)
    // ===================================================================
    public void tickPre(EntityPlayerSP self, EntityPlayer targetEntity) {
        if (!enabled || self == null || mc.theWorld == null) {
            rotationReady = false;
            return;
        }

        syncSettings();

        // Validate target
        if (targetEntity == null || targetEntity.isDead || targetEntity.getHealth() <= 0F) {
            this.target = null;
            rotationReady = false;
            return;
        }

        // Create/update CombatTarget snapshot
        if (this.target == null || this.target.getEntity() != targetEntity) {
            this.target = new CombatTarget(targetEntity, self.ticksExisted);
            rotationAlignedTicks = 0;
        } else {
            this.target = this.target.refresh(self.ticksExisted);
        }

        // Check swing range
        double distance = distanceToBox(self, this.target);
        if (distance > swingRange) {
            rotationReady = false;
            return;
        }

        // Calculate rotation to target bounding box
        float[] rotations = getRotationsToBox(self, this.target.getBox());
        desiredYaw = rotations[0];
        desiredPitch = rotations[1];
        rotationReady = true;

        // Apply rotation and sync ALL rotation fields for entity model consistency
        if (rotationMode == RotationMode.SILENT || rotationMode == RotationMode.LOCK_VIEW) {
            self.rotationYaw = desiredYaw;
            self.rotationPitch = desiredPitch;
            self.rotationYawHead = desiredYaw;
            self.renderYawOffset = desiredYaw;
        }

        // Track alignment duration
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(desiredYaw - self.rotationYaw));
        float pitchDiff = Math.abs(MathHelper.wrapAngleTo180_float(desiredPitch - self.rotationPitch));
        if (yawDiff < 5.0F && pitchDiff < 15.0F) {
            rotationAlignedTicks++;
        } else {
            rotationAlignedTicks = 0;
        }

        // Check attack range and timing
        boolean inRange = distance <= attackRange;
        boolean canAttack = inRange && attackDelayMS <= 0L && rotationAlignedTicks >= 2;

        if (canAttack) {
            // ============================================================
            // MACRO-STYLE ATTACK — Simulate mouse click, not manual packets.
            //
            // We manually set mc.objectMouseOver to point at the target,
            // then call mc.clickMouse() which is vanilla's left-click handler.
            // This triggers the EXACT same code path as a real player clicking:
            //
            //   mc.clickMouse()
            //     → checks objectMouseOver.entityHit
            //     → swingItem()          (sets isSwinging = true)
            //     → attackEntity()       (sends C02 + client-side effects)
            //     → onUpdateWalkingPlayer() (sends C03 + C0A naturally)
            //
            // We must set objectMouseOver because tickPre() runs BEFORE
            // vanilla's onUpdate(), so objectMouseOver still has last tick's value.
            // ============================================================

            // Set objectMouseOver to point at the target entity
            updateObjectMouseOver(targetEntity);

            // Trigger vanilla's clickMouse() — handles swing + attack + packets
            clickMouse();

            // Set next attack delay (ms-based like Myau)
            attackDelayMS += getAttackDelay();
        }
    }

    // ===================================================================
    // PHASE 2: POST — Called AFTER vanilla tick
    //
    // Only handles client-side visual effects.
    // All network packets were already sent in tickPre().
    // ===================================================================
    public void tickPost(EntityPlayerSP self, EntityPlayer targetEntity) {
        if (!enabled || self == null || mc.theWorld == null) return;

        // Decrement attack delay
        if (attackDelayMS > 0L) {
            attackDelayMS -= 50L;
        }
    }

    // ===================================================================
    // PACKET HELPERS
    // ===================================================================
    // No manual packet sending needed — attackEntity() handles C02,
    // and vanilla's onUpdateWalkingPlayer() handles C0A + C03.
    // This avoids duplicate swing packets that trigger Vulcan detections.

    // ===================================================================
    // MACRO HELPERS — ObjectMouseOver + clickMouse via reflection
    // ===================================================================

    /**
     * Update mc.objectMouseOver to point at the target entity.
     * This is necessary because tickPre() runs BEFORE vanilla's onUpdate(),
     * so objectMouseOver still has last tick's value (might be null or wrong entity).
     * We create a MovingObjectPosition that points at the target's bounding box center.
     */
    private void updateObjectMouseOver(EntityPlayer targetEntity) {
        // Create a MovingObjectPosition pointing at the target entity.
        // clickMouse() checks objectMouseOver.entityHit to decide what to attack.
        Vec3 targetVec = new Vec3(targetEntity.posX, targetEntity.posY + targetEntity.getEyeHeight(), targetEntity.posZ);
        net.minecraft.util.BlockPos targetPos = new net.minecraft.util.BlockPos(targetEntity);
        mc.objectMouseOver = new net.minecraft.util.MovingObjectPosition(
                targetVec,
                net.minecraft.util.EnumFacing.UP,
                targetPos
        );
        mc.objectMouseOver.entityHit = targetEntity;
        mc.objectMouseOver.typeOfHit = net.minecraft.util.MovingObjectPosition.MovingObjectType.ENTITY;
    }

    /**
     * Simulate a left mouse click by calling mc.clickMouse() via reflection.
     * This is the same method vanilla calls when you press the attack button.
     * It handles swingItem(), attackEntity(), and all client-side effects
     * exactly as a real player click would.
     *
     * Using reflection because clickMouse() is private in Minecraft 1.8.9.
     * The Method object is cached after the first call for performance.
     */
    private static java.lang.reflect.Method clickMouseMethod;
    private static boolean clickMouseFailed = false;

    private void clickMouse() {
        try {
            if (clickMouseMethod == null && !clickMouseFailed) {
                clickMouseMethod = Minecraft.class.getDeclaredMethod("clickMouse");
                clickMouseMethod.setAccessible(true);
            }
            if (clickMouseMethod != null) {
                clickMouseMethod.invoke(mc);
            }
        } catch (Exception e) {
            clickMouseFailed = true;
            // Fallback: use playerController.attackEntity() directly
            // This is less ideal but still works
            mc.playerController.attackEntity(
                    mc.thePlayer,
                    mc.objectMouseOver.entityHit);
        }
    }

    // ===================================================================
    // UTILITY — Matches Myau's RotationUtil methods
    // ===================================================================

    private long getAttackDelay() {
        int cps = minCPS + random.nextInt(Math.max(1, maxCPS - minCPS + 1));
        return 1000L / cps;
    }

    /**
     * Distance from player eye to nearest point on bounding box.
     * Matches Myau's RotationUtil.distanceToBox.
     */
    private double distanceToBox(EntityPlayerSP self, CombatTarget target) {
        AxisAlignedBB box = target.liveBox();
        double eyeY = self.posY + self.getEyeHeight();
        Vec3 eyePos = new Vec3(self.posX, eyeY, self.posZ);

        if (eyePos.xCoord >= box.minX && eyePos.xCoord <= box.maxX
                && eyePos.yCoord >= box.minY && eyePos.yCoord <= box.maxY
                && eyePos.zCoord >= box.minZ && eyePos.zCoord <= box.maxZ) {
            return 0.0;
        }

        double clampedX = Math.max(box.minX, Math.min(box.maxX, eyePos.xCoord));
        double clampedY = Math.max(box.minY, Math.min(box.maxY, eyePos.yCoord));
        double clampedZ = Math.max(box.minZ, Math.min(box.maxZ, eyePos.zCoord));

        double dx = clampedX - eyePos.xCoord;
        double dy = clampedY - eyePos.yCoord;
        double dz = clampedZ - eyePos.zCoord;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate rotation to aim at bounding box center within Y 5%-75%.
     * Matches Myau's RotationUtil.getRotationsToBox.
     */
    private float[] getRotationsToBox(EntityPlayerSP self, AxisAlignedBB box) {
        double eyeY = self.posY + self.getEyeHeight();
        Vec3 eyePos = new Vec3(self.posX, eyeY, self.posZ);

        double minY = box.minY + 0.05 * (box.maxY - box.minY);
        double maxY = box.minY + 0.75 * (box.maxY - box.minY);

        double centerX = (box.minX + box.maxX) / 2.0;
        double centerZ = (box.minZ + box.maxZ) / 2.0;

        double deltaX = centerX - eyePos.xCoord;
        double deltaY;
        if (eyePos.yCoord >= maxY) {
            deltaY = maxY - eyePos.yCoord;
        } else if (eyePos.yCoord <= minY) {
            deltaY = minY - eyePos.yCoord;
        } else {
            deltaY = 0.0;
        }
        double deltaZ = centerZ - eyePos.zCoord;

        double horizontalDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
        float yaw = (float) (Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) (-(Math.atan2(deltaY, horizontalDist) * 180.0 / Math.PI));

        if (smoothing > 0.0F) {
            float factor = 0.5F + 0.5F * (1.0F - smoothing);
            yaw = self.rotationYaw + (yaw - self.rotationYaw) * factor;
            pitch = self.rotationPitch + (pitch - self.rotationPitch) * factor;
        }

        pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);

        // Quantize to reduce micro-rotation patterns (Myau quantizeAngle)
        yaw = (float) ((double) yaw - (double) yaw % 0.0096);
        pitch = (float) ((double) pitch - (double) pitch % 0.0096);

        return new float[]{yaw, pitch};
    }

    // === Getters ===
    public boolean isActive() { return enabled && target != null && target.isAlive(); }
    public boolean isRotating() { return rotationReady; }
    public float getCurrentYaw() { return desiredYaw; }
    public float getCurrentPitch() { return desiredPitch; }
    public CombatTarget getTarget() { return target; }
    public long getAttackDelayMS() { return attackDelayMS; }
}
