package seraphina.seraphina_mod.client.image;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * Client-side image helpers shared by dynamic image renderers.
 */
public final class ClientImageUtil {
    private ClientImageUtil() {
    }

    /**
     * Draws a full textured quad using the supplied texture and tint.
     *
     * @param graphics current GUI graphics context
     * @param textureLocation texture to bind
     * @param x left coordinate
     * @param y top coordinate
     * @param width quad width
     * @param height quad height
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     * @param tint optional tint color; {@code null} uses white
     */
    public static void drawTexturedQuad(GuiGraphics graphics, ResourceLocation textureLocation,
                                        float x, float y, float width, float height, float alpha, Color tint) {
        Matrix4f matrix = graphics.pose().last().pose();
        float x1 = x + width;
        float y1 = y + height;
        Color safeTint = tint != null ? tint : Color.WHITE;
        float red = safeTint.getRed() / 255.0F;
        float green = safeTint.getGreen() / 255.0F;
        float blue = safeTint.getBlue() / 255.0F;
        float finalAlpha = alpha * (safeTint.getAlpha() / 255.0F);

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, textureLocation);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
        texturedVertex(buffer, matrix, x, y1, 0.0F, 1.0F, red, green, blue, finalAlpha);
        texturedVertex(buffer, matrix, x1, y1, 1.0F, 1.0F, red, green, blue, finalAlpha);
        texturedVertex(buffer, matrix, x1, y, 1.0F, 0.0F, red, green, blue, finalAlpha);
        texturedVertex(buffer, matrix, x1, y, 1.0F, 0.0F, red, green, blue, finalAlpha);
        texturedVertex(buffer, matrix, x, y, 0.0F, 0.0F, red, green, blue, finalAlpha);
        texturedVertex(buffer, matrix, x, y1, 0.0F, 1.0F, red, green, blue, finalAlpha);
        Tesselator.getInstance().end();
    }

    /**
     * Converts an AWT buffered image to Minecraft's RGBA {@link NativeImage} format.
     *
     * @param image source image
     * @return converted native image
     */
    public static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                int red = (argb >>> 16) & 0xFF;
                int green = (argb >>> 8) & 0xFF;
                int blue = argb & 0xFF;
                nativeImage.setPixelRGBA(x, y, alpha << 24 | blue << 16 | green << 8 | red);
            }
        }

        return nativeImage;
    }

    /**
     * Creates, uploads, and registers a dynamic texture with Minecraft's texture manager.
     *
     * @param location texture resource location
     * @param nativeImage pixel data to upload
     * @return registered dynamic texture
     */
    public static DynamicTexture createAndRegisterTexture(ResourceLocation location, NativeImage nativeImage) {
        DynamicTexture texture = new DynamicTexture(nativeImage);
        texture.setFilter(true, false);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(location, texture);
        return texture;
    }

    /**
     * Replaces the pixel data of an existing dynamic texture and uploads it.
     *
     * @param texture texture to update
     * @param nativeImage replacement pixels
     */
    public static void updateTexture(DynamicTexture texture, NativeImage nativeImage) {
        texture.setPixels(nativeImage);
        texture.setFilter(true, false);
        texture.upload();
    }

    /**
     * Runs a task immediately on the render thread or schedules it as a render call.
     *
     * @param runnable render-thread task
     */
    public static void executeRenderCall(Runnable runnable) {
        if (RenderSystem.isOnRenderThread()) {
            runnable.run();
        } else {
            RenderSystem.recordRenderCall(runnable::run);
        }
    }

    /**
     * Sanitizes a path so it can be reused in generated resource locations.
     *
     * @param path source path
     * @return lowercase path with unsupported characters replaced by underscores
     */
    public static String sanitizePath(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    /**
     * Clamps a float to the inclusive {@code [0, 1]} range.
     *
     * @param value value to clamp
     * @return clamped value
     */
    public static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return Math.min(value, 1.0F);
    }

    private static void texturedVertex(BufferBuilder buffer, Matrix4f matrix, float x, float y,
                                       float u, float v, float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, x, y, 0.0F).uv(u, v).color(red, green, blue, alpha).endVertex();
    }
}
