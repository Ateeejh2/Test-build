package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.Random;

/**
 * KillAura module — mirrors Myau's KillAura attack architecture.
 *
 * Key design points from Myau:
 * 1. ms-based attack delay (1000 / CPS) not tick-based
 * 2. swingItem() BEFORE attackEntity()
 * 3. rayTrace check: verify rotation points at target bounding box
 * 4. BoundingBox-based distance calculation (eye→box nearest point)
 * 5. Rotation uses boundingBox center with Y clamping (5%-75% height)
 * 6. moveFix: align movement direction with combat rotation
 */
public class KillAura {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private final Random random = new Random();

    // === Settings (Myau equivalents) ===
    public boolean enabled = false;

    // Attack range
    public float attackRange = 3.0F;
    public float swingRange = 3.5F;

    // CPS (attacks per second)
    public int minCPS = 12;
    public int maxCPS = 14;

    // Rotation
    public RotationMode rotationMode = RotationMode.SILENT;
    public MoveFixMode moveFixMode = MoveFixMode.SILENT;
    public float smoothing = 0.0F;
    public int angleStep = 90;

    // Target
    public boolean players = true;
    public boolean throughWalls = true;
    public int fov = 360;

    public enum RotationMode { NONE, LEGIT, SILENT, LOCK_VIEW }
    public enum MoveFixMode { NONE, SILENT, STRICT }

    // === Internal state ===
    private CombatTarget target;
    private long attackDelayMS = 0L;
    private boolean hitRegistered = false;
    private float currentYaw;
    private float currentPitch;
    private boolean rotationActive = false;

    public void reset() {
        target = null;
        attackDelayMS = 0L;
        hitRegistered = false;
        currentYaw = 0F;
        currentPitch = 0F;
        rotationActive = false;
    }

    /** Sync settings from WanderBotSettings. */
    private void syncSettings() {
        attackRange = (float)com.atlasdead.wanderbot.config.WanderBotSettings.killAuraAttackRange;
        swingRange = (float)com.atlasdead.wanderbot.config.WanderBotSettings.killAuraSwingRange;
        minCPS = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMinCPS;
        maxCPS = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMaxCPS;
        rotationMode = RotationMode.values()[com.atlasdead.wanderbot.config.WanderBotSettings.killAuraRotationMode];
        moveFixMode = MoveFixMode.values()[com.atlasdead.wanderbot.config.WanderBotSettings.killAuraMoveFixMode];
        smoothing = (float)com.atlasdead.wanderbot.config.WanderBotSettings.killAuraSmoothing;
        throughWalls = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraThroughWalls;
        fov = com.atlasdead.wanderbot.config.WanderBotSettings.killAuraFOV;
    }

    /**
     * Main tick — called every client tick when combat is active.
     * Mirrors Myau's KillAura.onUpdate(UpdateEvent) PRE handler.
     */
    public void tick(EntityPlayerSP self, EntityPlayer targetEntity, long now) {
        if (!enabled || self == null || mc.theWorld == null) {
            reset();
            return;
        }

        // Sync settings from WanderBotSettings each tick
        syncSettings();

        // Decrement attack delay (Myau pattern)
        if (attackDelayMS > 0L) {
            attackDelayMS -= 50L;
        }

        // Validate target
        if (targetEntity == null || targetEntity.isDead || targetEntity.getHealth() <= 0F) {
            this.target = null;
            rotationActive = false;
            return;
        }

        // Create/update CombatTarget snapshot (Myau AttackData pattern)
        if (this.target == null || this.target.getEntity() != targetEntity) {
            this.target = new CombatTarget(targetEntity, now / 50L);
            hitRegistered = false;
        } else {
            this.target = this.target.refresh(now / 50L);
        }

        // Check if target is in attack range
        double distance = distanceToBox(self, this.target);
        boolean inRange = distance <= attackRange;
        boolean inSwingRange = distance <= swingRange;

        if (!inSwingRange) {
            rotationActive = false;
            return;
        }

        // Calculate rotation to target bounding box (Myau getRotationsToBox pattern)
        float[] rotations = getRotationsToBox(self, this.target.getBox());
        currentYaw = rotations[0];
        currentPitch = rotations[1];
        rotationActive = true;

        // Apply rotation to player (silent or lock_view)
        if (rotationMode == RotationMode.SILENT || rotationMode == RotationMode.LOCK_VIEW) {
            self.rotationYaw = currentYaw;
            self.rotationPitch = currentPitch;
        }

        // Perform attack if conditions met (Myau performAttack pattern)
        if (inRange && attackDelayMS <= 0L) {
            performAttack(self, this.target, currentYaw, currentPitch);
        }

        // Apply moveFix (Myau onMove handler pattern)
        applyMoveFix(self, currentYaw);
    }

    /**
     * Myau's performAttack — exact sequence:
     * 1. Check attackDelayMS
     * 2. Set attackDelayMS += getAttackDelay()
     * 3. swingItem() FIRST
     * 4. rayTrace check (rotation → boundingBox)
     * 5. attackEntity()
     */
    private boolean performAttack(EntityPlayerSP self, CombatTarget target, float yaw, float pitch) {
        if (attackDelayMS > 0L) return false;

        // Set next attack delay
        attackDelayMS += getAttackDelay();

        // 1. Swing FIRST (Myau: mc.thePlayer.func_71038_i())
        mc.thePlayer.swingItem();

        // 2. RayTrace check: verify rotation points at target bounding box
        if (!rayTraceToBox(self, target.getBox(), yaw, pitch)) {
            return false;
        }

        // 3. Sync current play item (Myau: callSyncCurrentPlayItem via accessor)
        try {
            java.lang.reflect.Method syncMethod = mc.playerController.getClass()
                    .getDeclaredMethod("syncCurrentPlayItem");
            syncMethod.setAccessible(true);
            syncMethod.invoke(mc.playerController);
        } catch (Exception ignored) {}

        // 4. Send attack packet DIRECTLY via NetworkManager (Myau: PacketUtil.sendPacket)
        //    NOT through mc.playerController.attackEntity() which queues through PlayerControllerMP
        try {
            net.minecraft.entity.Entity entityTarget = target.getEntity();
            net.minecraft.network.play.client.C02PacketUseEntity attackPacket =
                    new net.minecraft.network.play.client.C02PacketUseEntity(
                            entityTarget,
                            net.minecraft.network.play.client.C02PacketUseEntity.Action.ATTACK);
            // Send directly to NetworkManager channel (bypasses packet queue)
            mc.thePlayer.sendQueue.getNetworkManager().sendPacket(attackPacket);
        } catch (Exception e) {
            // Fallback: use playerController if direct send fails
            mc.playerController.attackEntity(mc.thePlayer, target.getEntity());
        }

        hitRegistered = true;
        return true;
    }

    /**
     * Myau's getAttackDelay — ms-based with random CPS.
     */
    private long getAttackDelay() {
        int cps = minCPS + random.nextInt(Math.max(1, maxCPS - minCPS + 1));
        return 1000L / cps;
    }

    /**
     * Myau's RotationUtil.rayTrace(box, yaw, pitch, range).
     * Verify that current rotation actually intersects the target bounding box.
     */
    private boolean rayTraceToBox(EntityPlayerSP self, AxisAlignedBB box, float yaw, float pitch) {
        double eyeY = self.posY + self.getEyeHeight();
        Vec3 eyePos = new Vec3(self.posX, eyeY, self.posZ);

        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        float lookX = (float)(-Math.sin(yawRad) * Math.cos(pitchRad));
        float lookY = (float)(-Math.sin(pitchRad));
        float lookZ = (float)(Math.cos(yawRad) * Math.cos(pitchRad));

        Vec3 targetPos = eyePos.addVector(lookX * attackRange, lookY * attackRange, lookZ * attackRange);
        MovingObjectPosition mop = box.calculateIntercept(eyePos, targetPos);
        return mop != null;
    }

    /**
     * Myau's RotationUtil.getRotationsToBox(box, yaw, pitch, maxAngle, smoothFactor).
     * Calculate yaw/pitch to aim at the bounding box center with Y clamping.
     */
    private float[] getRotationsToBox(EntityPlayerSP self, AxisAlignedBB box) {
        double eyeY = self.posY + self.getEyeHeight();
        Vec3 eyePos = new Vec3(self.posX, eyeY, self.posZ);

        // Myau: target Y is clamped to 5%-75% of box height
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
        float yaw = (float)(Math.atan2(deltaZ, deltaX) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float)(-(Math.atan2(deltaY, horizontalDist) * 180.0 / Math.PI));

        // Smoothing
        if (smoothing > 0.0F) {
            float factor = 0.5F + 0.5F * (1.0F - smoothing);
            yaw = self.rotationYaw + (yaw - self.rotationYaw) * factor;
            pitch = self.rotationPitch + (pitch - self.rotationPitch) * factor;
        }

        // Clamp pitch
        pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);

        // Quantize (Myau quantizeAngle)
        yaw = (float)((double)yaw - (double)yaw % 0.0096);
        pitch = (float)((double)pitch - (double)pitch % 0.0096);

        return new float[]{ yaw, pitch };
    }

    /**
     * Myau's MoveUtil.fixStrafe — align movement direction with rotation.
     */
    private void applyMoveFix(EntityPlayerSP self, float combatYaw) {
        if (moveFixMode == MoveFixMode.NONE) return;
        if (!mc.gameSettings.keyBindForward.isKeyDown()) return;

        float yawDiff = MathHelper.wrapAngleTo180_float(combatYaw - self.rotationYaw);
        if (Math.abs(yawDiff) < 1.0F) return;

        double speed = Math.sqrt(self.motionX * self.motionX + self.motionZ * self.motionZ);
        if (speed < 0.001D) return;

        double yawRad = Math.toRadians(combatYaw);
        self.motionX = -Math.sin(yawRad) * speed;
        self.motionZ = Math.cos(yawRad) * speed;
    }

    /**
     * Myau's RotationUtil.distanceToBox — eye→boundingBox nearest point distance.
     */
    private double distanceToBox(EntityPlayerSP self, CombatTarget target) {
        AxisAlignedBB box = target.liveBox();
        double eyeY = self.posY + self.getEyeHeight();
        Vec3 eyePos = new Vec3(self.posX, eyeY, self.posZ);

        // Clamp eye position to box
        double clampedX = Math.max(box.minX, Math.min(box.maxX, eyePos.xCoord));
        double clampedY = Math.max(box.minY, Math.min(box.maxY, eyePos.yCoord));
        double clampedZ = Math.max(box.minZ, Math.min(box.maxZ, eyePos.zCoord));

        // If eye is inside box, distance is 0
        if (eyePos.xCoord >= box.minX && eyePos.xCoord <= box.maxX
                && eyePos.yCoord >= box.minY && eyePos.yCoord <= box.maxY
                && eyePos.zCoord >= box.minZ && eyePos.zCoord <= box.maxZ) {
            return 0.0;
        }

        double dx = clampedX - eyePos.xCoord;
        double dy = clampedY - eyePos.yCoord;
        double dz = clampedZ - eyePos.zCoord;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // === Getters ===
    public boolean isActive() { return enabled && target != null && target.isAlive(); }
    public boolean isRotating() { return rotationActive; }
    public float getCurrentYaw() { return currentYaw; }
    public float getCurrentPitch() { return currentPitch; }
    public CombatTarget getTarget() { return target; }
    public long getAttackDelayMS() { return attackDelayMS; }
    public boolean hasHitRegistered() { return hitRegistered; }
}
