package seraphina.seraphina_lib.client.gif;

import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import net.minecraft.CrashReport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import seraphina.seraphina_lib.client.image.ClientImageUtil;

import java.awt.Color;
import java.util.List;
import java.util.Objects;

/**
 * @author Seraphina
 * @version 1.0
 *
 * GIF renderer that decodes a GIF into composited frames and renders the current frame as a dynamic texture.
 */
@OnlyIn(Dist.CLIENT)
public class GIFImage {
    @Getter
    private final ResourceLocation location;
    @Getter
    private int width = 1;
    @Getter
    private int height = 1;
    @Getter
    private float animationSpeed = 1.0F;

    private List<GIFFrame> frames = List.of();
    private List<ResourceLocation> frameLocations = List.of();
    @SuppressWarnings("FieldCanBeLocal")
    private List<DynamicTexture> frameTextures = List.of();
    private int totalDurationMillis = GIFFrameDecoder.DEFAULT_FRAME_DELAY_MILLIS;
    private long animationStartMillis = -1L;
    private int currentFrameIndex;
    private boolean loaded;
    private boolean failed;

    public GIFImage(ResourceLocation location) {
        this.location = Objects.requireNonNull(location, "location");
    }

    public GIFImage setAnimationSpeed(float animationSpeed) {
        this.animationSpeed = Float.isFinite(animationSpeed) && animationSpeed > 0.0F ? animationSpeed : 1.0F;
        return this;
    }

    public void restartAnimation() {
        animationStartMillis = System.currentTimeMillis();
        currentFrameIndex = 0;
    }

    public void update() {
        ensureLoaded();
        if (!loaded || failed || frames.isEmpty()) {
            return;
        }

        currentFrameIndex = frameIndexAt(currentAnimationMillis());
    }

    public ResourceLocation getTextureLocation() {
        update();
        if (!loaded || failed || frameLocations.isEmpty()) {
            return location;
        }
        return frameLocations.get(currentFrameIndex);
    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height) {
        draw(graphics, x, y, width, height, 1.0F);
    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        draw(graphics, x, y, width, height, alpha, Color.WHITE);
    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, true, -1);
    }

    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height) {
        drawStatic(graphics, x, y, width, height, 1.0F);
    }

    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        drawInternal(graphics, x, y, width, height, alpha, Color.WHITE, false, 0);
    }

    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height, float timeSeconds) {
        drawAtTime(graphics, x, y, width, height, timeSeconds, 1.0F);
    }

    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height, float timeSeconds, float alpha) {
        int timeMillis = Math.max(0, (int) (timeSeconds * 1000.0F));
        drawInternal(graphics, x, y, width, height, alpha, Color.WHITE, false, timeMillis);
    }

    private void drawInternal(GuiGraphics graphics, float x, float y, float width, float height, float alpha,
                              Color tint, boolean updateAnimated, int explicitTimeMillis) {
        float clampedAlpha = ClientImageUtil.clamp01(alpha);
        if (graphics == null || width <= 0.0F || height <= 0.0F || clampedAlpha <= 0.0F) {
            return;
        }

        ensureLoaded();
        if (!loaded || failed || frameLocations.isEmpty()) {
            return;
        }

        if (explicitTimeMillis >= 0) {
            currentFrameIndex = frameIndexAt(explicitTimeMillis);
        } else if (updateAnimated) {
            update();
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ClientImageUtil.drawTexturedQuad(graphics, frameLocations.get(currentFrameIndex), x, y,
                Math.max(1.0F, width), Math.max(1.0F, height), clampedAlpha, tint);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private void ensureLoaded() {
        if (loaded || failed) {
            return;
        }

        try {
            GIFImageData imageData = GIFFrameDecoder.decode(location);
            width = imageData.width();
            height = imageData.height();
            frames = imageData.frames();
            totalDurationMillis = imageData.totalDurationMillis();

            GIFFrameTextures textures = GIFFrameUploader.upload(location, frames);
            frameLocations = textures.locations();
            frameTextures = textures.textures();

            if (animationStartMillis < 0L) {
                animationStartMillis = System.currentTimeMillis();
            }
            loaded = true;
        } catch (Exception exception) {
            failed = true;
            CrashReport.forThrowable(exception, "failed to load image");
        }
    }

    private long currentAnimationMillis() {
        if (animationStartMillis < 0L) {
            animationStartMillis = System.currentTimeMillis();
        }
        float elapsed = (System.currentTimeMillis() - animationStartMillis) * animationSpeed;
        return Math.max(0L, (long) elapsed);
    }

    private int frameIndexAt(long animationMillis) {
        if (frames.size() <= 1 || totalDurationMillis <= 0) {
            return 0;
        }

        int time = (int) (animationMillis % totalDurationMillis);
        int elapsed = 0;
        for (int i = 0; i < frames.size(); i++) {
            elapsed += frames.get(i).delayMillis();
            if (time < elapsed) {
                return i;
            }
        }
        return frames.size() - 1;
    }
}
