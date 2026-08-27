package com.atlasdead.wanderbot.render;

import com.atlasdead.wanderbot.WanderBotMod;
import com.atlasdead.wanderbot.config.WanderBotSettings;
import com.atlasdead.wanderbot.bot.BotController;
import com.atlasdead.wanderbot.pathfinding.Path;
import com.atlasdead.wanderbot.pathfinding.PathNode;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

public class PathRenderer {
    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        BotController bot = WanderBotMod.BOT;
        if (bot == null || !bot.isEnabled()) return;
        Path path = bot.getPath();

        Minecraft mc = Minecraft.getMinecraft();
        double px = mc.getRenderManager().viewerPosX;
        double py = mc.getRenderManager().viewerPosY;
        double pz = mc.getRenderManager().viewerPosZ;

        GlStateManager.pushMatrix();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(2.5F);

        if (WanderBotSettings.showPath && path != null && !path.getNodes().isEmpty()) {
            renderPath(path, px, py, pz);
            renderNodes(path, px, py, pz);
        }
        if (WanderBotSettings.showTarget) renderTarget(bot, mc, event.partialTicks, px, py, pz);
        if (WanderBotSettings.showTargetGuide) renderTargetGuide(bot, mc, event.partialTicks, px, py, pz);

        GL11.glLineWidth(1.0F);
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();
    }

    private void renderPath(Path path, double px, double py, double pz) {
        java.util.List<PathNode> nodes = path.getNodes();
        if (nodes.size() < 2) return;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        int currentIndex = Math.max(0, Math.min(path.getIndex(), nodes.size() - 1));
        for (int i = 0; i < nodes.size() - 1; i++) {
            PathNode a = nodes.get(i);
            PathNode b = nodes.get(i + 1);
            boolean completed = i < currentIndex;
            boolean next = i == currentIndex || i + 1 == currentIndex;
            float r = completed ? 0.42F : (next ? 0.15F : 0.2F);
            float g = completed ? 0.62F : (next ? 1.0F : 0.92F);
            float bl = completed ? 1.0F : (next ? 0.55F : 1.0F);
            float alpha = completed ? 0.45F : 1.0F;
            wr.pos(a.x + 0.5D - px, a.y + 1.08D - py, a.z + 0.5D - pz)
                    .color(r, g, bl, alpha).endVertex();
            wr.pos(b.x + 0.5D - px, b.y + 1.08D - py, b.z + 0.5D - pz)
                    .color(r, g, bl, alpha).endVertex();
        }
        tess.draw();
    }

    private void renderNodes(Path path, double px, double py, double pz) {
        for (int i = 0; i < path.getNodes().size(); i++) {
            PathNode n = path.getNodes().get(i);
            boolean current = i == path.getIndex();
            boolean upcoming = i > path.getIndex();
            float size = current ? 0.95F : (upcoming ? 0.55F : 0.38F);
            drawBox(n.x + 0.5D - px, n.y + 1.01D - py, n.z + 0.5D - pz, size,
                    current ? 0.15F : (upcoming ? 1.0F : 0.5F),
                    current ? 1.0F : (upcoming ? 0.75F : 0.5F),
                    current ? 1.0F : 0.2F,
                    current ? 1.0F : 0.82F);
        }
    }

    private void renderTarget(BotController bot, Minecraft mc, float partialTicks, double px, double py, double pz) {
        EntityPlayer target = bot.getPit().getTargetTracker().getTarget();
        if (target == null || target.isDead || target.getHealth() <= 0.0F) return;

        double x = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks - px;
        double y = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks - py;
        double z = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks - pz;

        AxisAlignedBB bb = target.getEntityBoundingBox();
        double dx = target.posX - target.lastTickPosX;
        double dy = target.posY - target.lastTickPosY;
        double dz = target.posZ - target.lastTickPosZ;
        double minX = bb.minX - target.posX + x;
        double maxX = bb.maxX - target.posX + x;
        double minY = bb.minY - target.posY + y;
        double maxY = bb.maxY - target.posY + y;
        double minZ = bb.minZ - target.posZ + z;
        double maxZ = bb.maxZ - target.posZ + z;

        drawEntityBox(minX, minY, minZ, maxX, maxY, maxZ, 1.0F, 0.25F, 0.25F, 1.0F);
        drawBox(target.posX + dx * 0.05D - px, target.posY + 0.05D - py, target.posZ + dz * 0.05D - pz, 0.45F, 1.0F, 0.85F, 0.2F, 0.95F);
    }

    private void renderTargetGuide(BotController bot, Minecraft mc, float partialTicks, double px, double py, double pz) {
        EntityPlayer target = bot.getPit().getTargetTracker().getTarget();
        if (target == null) return;
        EntityPlayerSP self = mc.thePlayer;
        if (self == null) return;

        double tx = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks;
        double ty = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks + target.getEyeHeight() * 0.82D;
        double tz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(self.posX - px, self.posY + self.getEyeHeight() * 0.86D - py, self.posZ - pz)
                .color(1.0F, 0.25F, 0.25F, 0.8F).endVertex();
        wr.pos(tx - px, ty - py, tz - pz)
                .color(1.0F, 0.55F, 0.15F, 0.95F).endVertex();
        tess.draw();
    }

    private void drawBox(double x, double y, double z, double size) {
        double h = size * 0.5D;
        double minX = x - h, maxX = x + h;
        double minY = y, maxY = y + size;
        double minZ = z - h, maxZ = z + h;

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINE_STRIP, DefaultVertexFormats.POSITION_COLOR);
        vertex(wr, minX, minY, minZ); vertex(wr, maxX, minY, minZ); vertex(wr, maxX, minY, maxZ); vertex(wr, minX, minY, maxZ); vertex(wr, minX, minY, minZ);
        vertex(wr, minX, maxY, minZ); vertex(wr, maxX, maxY, minZ); vertex(wr, maxX, maxY, maxZ); vertex(wr, minX, maxY, maxZ); vertex(wr, minX, maxY, minZ);
        vertex(wr, maxX, minY, minZ); vertex(wr, maxX, maxY, minZ);
        vertex(wr, maxX, minY, maxZ); vertex(wr, maxX, maxY, maxZ);
        vertex(wr, minX, minY, maxZ); vertex(wr, minX, maxY, maxZ);
        tess.draw();
    }

    private void vertex(WorldRenderer wr, double x, double y, double z) {
        wr.pos(x, y, z).color(1.0F, 0.85F, 0.2F, 1.0F).endVertex();
    }
    private void drawBox(double x, double y, double z, double size, float r, float g, float b, float a) {
        double h = size * 0.5D;
        drawEntityBox(x - h, y, z - h, x + h, y + size, z + h, r, g, b, a);
    }

    private void drawEntityBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float r, float g, float b, float a) {
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        boxVertex(wr, minX, minY, minZ, r, g, b, a); boxVertex(wr, maxX, minY, minZ, r, g, b, a);
        boxVertex(wr, maxX, minY, minZ, r, g, b, a); boxVertex(wr, maxX, minY, maxZ, r, g, b, a);
        boxVertex(wr, maxX, minY, maxZ, r, g, b, a); boxVertex(wr, minX, minY, maxZ, r, g, b, a);
        boxVertex(wr, minX, minY, maxZ, r, g, b, a); boxVertex(wr, minX, minY, minZ, r, g, b, a);
        boxVertex(wr, minX, maxY, minZ, r, g, b, a); boxVertex(wr, maxX, maxY, minZ, r, g, b, a);
        boxVertex(wr, maxX, maxY, minZ, r, g, b, a); boxVertex(wr, maxX, maxY, maxZ, r, g, b, a);
        boxVertex(wr, maxX, maxY, maxZ, r, g, b, a); boxVertex(wr, minX, maxY, maxZ, r, g, b, a);
        boxVertex(wr, minX, maxY, maxZ, r, g, b, a); boxVertex(wr, minX, maxY, minZ, r, g, b, a);
        boxVertex(wr, minX, minY, minZ, r, g, b, a); boxVertex(wr, minX, maxY, minZ, r, g, b, a);
        boxVertex(wr, maxX, minY, minZ, r, g, b, a); boxVertex(wr, maxX, maxY, minZ, r, g, b, a);
        boxVertex(wr, maxX, minY, maxZ, r, g, b, a); boxVertex(wr, maxX, maxY, maxZ, r, g, b, a);
        boxVertex(wr, minX, minY, maxZ, r, g, b, a); boxVertex(wr, minX, maxY, maxZ, r, g, b, a);
        tess.draw();
    }

    private void boxVertex(WorldRenderer wr, double x, double y, double z, float r, float g, float b, float a) {
        wr.pos(x, y, z).color(r, g, b, a).endVertex();
    }

}
