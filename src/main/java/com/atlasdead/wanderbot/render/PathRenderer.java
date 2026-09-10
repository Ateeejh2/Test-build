package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import com.atlasdead.wanderbot.pit.CombatExecutionController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Minimal world-space visualization for navigation and combat paths.
 *
 * Paths are rendered as a soft glow plus a thin core line. Only the current,
 * next and goal nodes are emphasized so the overlay stays readable instead of
 * filling the world with wireframe cubes.
 */
public final class PathRenderer {
    private static final double PATH_HEIGHT = 0.14D;
    private static final float GLOW_WIDTH = 7.0F;
    private static final float CORE_WIDTH = 2.0F;
    private static final double RENDER_DISTANCE_SQ = 96.0D * 96.0D;
    private static final int COMPLETED_NODE_TAIL = 4;
    private static final int MAX_RENDER_AHEAD_NODES = 160;
    private static final int RING_SEGMENTS = 20;
    private static final double[] RING_COS = new double[RING_SEGMENTS];
    private static final double[] RING_SIN = new double[RING_SEGMENTS];

    static {
        for (int i = 0; i < RING_SEGMENTS; i++) {
            double angle = Math.PI * 2.0D * i / RING_SEGMENTS;
            RING_COS[i] = Math.cos(angle);
            RING_SIN[i] = Math.sin(angle);
        }
    }

    private static final float NAV_R = 0.36F;
    private static final float NAV_G = 0.58F;
    private static final float NAV_B = 1.00F;

    private static final float COMBAT_R = 1.00F;
    private static final float COMBAT_G = 0.35F;
    private static final float COMBAT_B = 0.42F;

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return;
        if (!WanderBotSettings.showPath
                && !WanderBotSettings.showTarget
                && !WanderBotSettings.showTargetGuide) return;

        double px = mc.getRenderManager().viewerPosX;
        double py = mc.getRenderManager().viewerPosY;
        double pz = mc.getRenderManager().viewerPosZ;

        beginOverlay();
        try {
            Path navigationPath = bot.getNavigationPath();
            if (WanderBotSettings.showPath && hasNodes(navigationPath)) {
                renderStyledPath(navigationPath, px, py, pz, false);
            }

            CombatExecutionController combatExec = bot.getCombatExecutor();
            Path combatPath = combatExec != null ? combatExec.getLastCombatPath() : null;
            if (WanderBotSettings.showPath && hasNodes(combatPath)) {
                renderStyledPath(combatPath, px, py, pz, true);
            }

            if (WanderBotSettings.showTarget) {
                renderTarget(bot, event.partialTicks, px, py, pz);
            }
            if (WanderBotSettings.showTargetGuide) {
                renderTargetGuide(bot, mc, event.partialTicks, px, py, pz);
            }
        } finally {
            endOverlay();
        }
    }

    private boolean hasNodes(Path path) {
        return path != null && path.size() > 0;
    }

    private void beginOverlay() {
        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
    }

    private void endOverlay() {
        GL11.glLineWidth(1.0F);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDepthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void renderStyledPath(Path path, double px, double py, double pz, boolean combat) {
        List<PathNode> nodes = path.getNodes();
        if (nodes.size() == 1) {
            renderMarkers(path, px, py, pz, combat);
            return;
        }

        float r = combat ? COMBAT_R : NAV_R;
        float g = combat ? COMBAT_G : NAV_G;
        float b = combat ? COMBAT_B : NAV_B;

        GL11.glLineWidth(GLOW_WIDTH);
        drawPathSegments(path, px, py, pz, r, g, b, 0.16F, true);

        GL11.glLineWidth(CORE_WIDTH);
        drawPathSegments(path, px, py, pz, r, g, b, 0.96F, false);

        renderMarkers(path, px, py, pz, combat);
    }

    /**
     * Draws only the nearby active corridor. Collinear nodes are merged into a
     * single segment, and very old/far nodes are omitted before vertex upload.
     */
    private void drawPathSegments(Path path, double px, double py, double pz,
                                  float r, float g, float b, float alpha, boolean glow) {
        List<PathNode> nodes = path.getNodes();
        int current = clampIndex(path.getIndex(), nodes.size());
        int start = Math.max(0, current - COMPLETED_NODE_TAIL);
        int end = Math.min(nodes.size() - 1, current + MAX_RENDER_AHEAD_NODES);
        if (start >= end) return;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        int anchorIndex = start;
        for (int i = start + 1; i <= end; i++) {
            if (i < end && isRedundantStraightNode(nodes.get(anchorIndex), nodes.get(i), nodes.get(i + 1))) {
                continue;
            }

            PathNode a = nodes.get(anchorIndex);
            PathNode bNode = nodes.get(i);
            if (segmentVisible(a, bNode, px, py, pz)) {
                boolean completed = i < current;
                boolean nearCurrent = anchorIndex <= current + 1 && i >= current;
                float fade = completed ? 0.35F : 1.0F;
                if (!glow && nearCurrent) fade = 1.0F;
                float cr = completed ? r * 0.65F : r;
                float cg = completed ? g * 0.65F : g;
                float cb = completed ? b * 0.70F : b;
                float ca = alpha * fade;

                wr.pos(a.x + 0.5D - px, a.y + PATH_HEIGHT - py, a.z + 0.5D - pz)
                        .color(cr, cg, cb, ca).endVertex();
                wr.pos(bNode.x + 0.5D - px, bNode.y + PATH_HEIGHT - py, bNode.z + 0.5D - pz)
                        .color(cr, cg, cb, ca).endVertex();
            }
            anchorIndex = i;
        }
        tess.draw();
    }

    private boolean isRedundantStraightNode(PathNode a, PathNode b, PathNode c) {
        if (a.y != b.y || b.y != c.y) return false;
        int abx = Integer.compare(b.x, a.x);
        int abz = Integer.compare(b.z, a.z);
        int bcx = Integer.compare(c.x, b.x);
        int bcz = Integer.compare(c.z, b.z);
        return abx == bcx && abz == bcz;
    }

    private boolean segmentVisible(PathNode a, PathNode b, double px, double py, double pz) {
        return nodeDistanceSq(a, px, py, pz) <= RENDER_DISTANCE_SQ
                || nodeDistanceSq(b, px, py, pz) <= RENDER_DISTANCE_SQ;
    }

    private double nodeDistanceSq(PathNode node, double px, double py, double pz) {
        double dx = node.x + 0.5D - px;
        double dy = node.y + PATH_HEIGHT - py;
        double dz = node.z + 0.5D - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    private void renderMarkers(Path path, double px, double py, double pz, boolean combat) {
        List<PathNode> nodes = path.getNodes();
        if (nodes.isEmpty()) return;

        int current = clampIndex(path.getIndex(), nodes.size());
        float r = combat ? COMBAT_R : NAV_R;
        float g = combat ? COMBAT_G : NAV_G;
        float b = combat ? COMBAT_B : NAV_B;

        PathNode currentNode = nodes.get(current);
        if (nodeDistanceSq(currentNode, px, py, pz) <= RENDER_DISTANCE_SQ) {
            drawGlowRing(currentNode.x + 0.5D - px, currentNode.y + PATH_HEIGHT - py,
                    currentNode.z + 0.5D - pz, 0.34D, r, g, b);
        }

        int next = Math.min(nodes.size() - 1, current + 1);
        if (next != current) {
            PathNode nextNode = nodes.get(next);
            if (nodeDistanceSq(nextNode, px, py, pz) <= RENDER_DISTANCE_SQ) {
                drawRing(nextNode.x + 0.5D - px, nextNode.y + PATH_HEIGHT - py,
                        nextNode.z + 0.5D - pz, 0.13D, r, g, b, 0.72F, 1.4F);
            }
        }

        PathNode goal = nodes.get(nodes.size() - 1);
        if (nodes.size() > 2 && goal != currentNode
                && nodeDistanceSq(goal, px, py, pz) <= RENDER_DISTANCE_SQ) {
            drawRing(goal.x + 0.5D - px, goal.y + PATH_HEIGHT - py,
                    goal.z + 0.5D - pz, 0.26D, r, g, b, 0.84F, 1.8F);
            drawVerticalTick(goal.x + 0.5D - px, goal.y + PATH_HEIGHT - py,
                    goal.z + 0.5D - pz, r, g, b);
        }
    }

    private int clampIndex(int index, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(index, size - 1));
    }

    private void drawGlowRing(double x, double y, double z, double radius, float r, float g, float b) {
        drawRing(x, y, z, radius + 0.05D, r, g, b, 0.18F, 6.0F);
        drawRing(x, y, z, radius, r, g, b, 0.98F, 2.0F);
    }

    private void drawRing(double x, double y, double z, double radius,
                          float r, float g, float b, float alpha, float width) {
        GL11.glLineWidth(width);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < RING_SEGMENTS; i++) {
            wr.pos(x + RING_COS[i] * radius, y, z + RING_SIN[i] * radius)
                    .color(r, g, b, alpha).endVertex();
        }
        tess.draw();
    }

    private void drawVerticalTick(double x, double y, double z, float r, float g, float b) {
        GL11.glLineWidth(1.5F);
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x, y, z).color(r, g, b, 0.88F).endVertex();
        wr.pos(x, y + 0.45D, z).color(r, g, b, 0.18F).endVertex();
        tess.draw();
    }

    private void renderTarget(BotController bot, float partialTicks, double px, double py, double pz) {
        EntityPlayer target = bot.getPit().getTargets().getTarget();
        if (!isLiveTarget(target)) return;

        double x = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks - px;
        double y = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks - py;
        double z = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks - pz;

        AxisAlignedBB bb = target.getEntityBoundingBox();
        double minX = bb.minX - target.posX + x;
        double maxX = bb.maxX - target.posX + x;
        double minY = bb.minY - target.posY + y;
        double maxY = bb.maxY - target.posY + y;
        double minZ = bb.minZ - target.posZ + z;
        double maxZ = bb.maxZ - target.posZ + z;

        // Subtle fill + crisp outline instead of a heavy wireframe marker.
        drawFilledBox(minX, minY, minZ, maxX, maxY, maxZ,
                COMBAT_R, COMBAT_G, COMBAT_B, 0.055F);
        GL11.glLineWidth(1.7F);
        drawEntityBox(minX, minY, minZ, maxX, maxY, maxZ,
                COMBAT_R, COMBAT_G, COMBAT_B, 0.92F);

        drawRing(x, y + 0.04D, z, 0.34D,
                COMBAT_R, COMBAT_G, COMBAT_B, 0.78F, 1.5F);
    }

    private boolean isLiveTarget(EntityPlayer target) {
        return target != null && !target.isDead && target.getHealth() > 0.0F;
    }

    private void renderTargetGuide(BotController bot, Minecraft mc, float partialTicks,
                                   double px, double py, double pz) {
        EntityPlayer target = bot.getPit().getTargets().getTarget();
        EntityPlayerSP self = mc.thePlayer;
        if (!isLiveTarget(target) || self == null) return;

        double tx = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks;
        double ty = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks
                + target.getEyeHeight() * 0.72D;
        double tz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks;

        double sx = self.lastTickPosX + (self.posX - self.lastTickPosX) * partialTicks;
        double sy = self.lastTickPosY + (self.posY - self.lastTickPosY) * partialTicks
                + self.getEyeHeight() * 0.78D;
        double sz = self.lastTickPosZ + (self.posZ - self.lastTickPosZ) * partialTicks;

        GL11.glLineWidth(5.0F);
        drawGuideLine(sx - px, sy - py, sz - pz, tx - px, ty - py, tz - pz, 0.12F);
        GL11.glLineWidth(1.4F);
        drawGuideLine(sx - px, sy - py, sz - pz, tx - px, ty - py, tz - pz, 0.84F);
    }

    private void drawGuideLine(double sx, double sy, double sz,
                               double tx, double ty, double tz, float alpha) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(sx, sy, sz).color(COMBAT_R, COMBAT_G, COMBAT_B, alpha * 0.45F).endVertex();
        wr.pos(tx, ty, tz).color(COMBAT_R, COMBAT_G, COMBAT_B, alpha).endVertex();
        tess.draw();
    }

    private void drawFilledBox(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               float r, float g, float b, float a) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        quad(wr, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        quad(wr, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
        quad(wr, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
        quad(wr, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, minX, minY, maxZ, r, g, b, a);
        quad(wr, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, minX, minY, minZ, r, g, b, a);
        quad(wr, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);

        tess.draw();
    }

    private void quad(WorldRenderer wr,
                      double x1, double y1, double z1,
                      double x2, double y2, double z2,
                      double x3, double y3, double z3,
                      double x4, double y4, double z4,
                      float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        wr.pos(x3, y3, z3).color(r, g, b, a).endVertex();
        wr.pos(x4, y4, z4).color(r, g, b, a).endVertex();
    }

    private void drawEntityBox(double minX, double minY, double minZ,
                               double maxX, double maxY, double maxZ,
                               float r, float g, float b, float a) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);

        edge(wr, minX, minY, minZ, maxX, minY, minZ, r, g, b, a);
        edge(wr, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, a);
        edge(wr, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        edge(wr, minX, minY, maxZ, minX, minY, minZ, r, g, b, a);

        edge(wr, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, a);
        edge(wr, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, a);
        edge(wr, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        edge(wr, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);

        edge(wr, minX, minY, minZ, minX, maxY, minZ, r, g, b, a);
        edge(wr, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, a);
        edge(wr, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, a);
        edge(wr, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, a);

        tess.draw();
    }

    private void edge(WorldRenderer wr,
                      double x1, double y1, double z1,
                      double x2, double y2, double z2,
                      float r, float g, float b, float a) {
        wr.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        wr.pos(x2, y2, z2).color(r, g, b, a).endVertex();
    }
}
