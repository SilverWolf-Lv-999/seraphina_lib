package seraphina.seraphina_lib.client.svg;

import com.mojang.blaze3d.systems.RenderSystem;
import lombok.Getter;
import net.minecraft.CrashReport;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.w3c.dom.Document;
import seraphina.seraphina_lib.client.image.ClientImageUtil;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * @author Seraphina
 * @version 1.0
 *
 * SVG renderer that rasterizes SVG resources into dynamic textures and supports
 * simple animated SVG attributes.
 */
@OnlyIn(Dist.CLIENT)
public class SvgImage {
    @Getter
    private final ResourceLocation svgLocation;
    private final ResourceLocation textureLocation;
    @Getter
    private final int textureWidth;
    @Getter
    private final int textureHeight;
    private final SvgTextureFrames textureFrames;

    private ResourceLocation currentTextureLocation;
    private Document document;
    private SvgAnimationTimeline animationTimeline = SvgAnimationTimeline.empty();
    private long animationStartMillis = -1L;
    private long lastRasterizedAnimationFrameKey = Long.MIN_VALUE;
    private boolean animationComplete;
    private boolean paintAnimationsEnabled = true;
    private Color disabledPaintAnimationColor;
    @Getter
    private float animationSpeed = 1.0F;
    private boolean loaded;
    private boolean failed;

    /**
     * Creates a lazily loaded SVG image with a 256x256 backing texture.
     *
     * @param svgLocation resource location of the SVG asset
     */
    public SvgImage(ResourceLocation svgLocation) {
        this(svgLocation, 256, 256);
    }

    /**
     * Creates a lazily loaded SVG image with a fixed backing texture size.
     *
     * @param svgLocation resource location of the SVG asset
     * @param textureWidth backing texture width
     * @param textureHeight backing texture height
     */
    public SvgImage(ResourceLocation svgLocation, int textureWidth, int textureHeight) {
        this.svgLocation = Objects.requireNonNull(svgLocation, "svgLocation");
        this.textureWidth = Math.max(1, textureWidth);
        this.textureHeight = Math.max(1, textureHeight);
        this.textureLocation = ResourceLocation.fromNamespaceAndPath(
                svgLocation.getNamespace(),
                "svg/generated/" + ClientImageUtil.sanitizePath(svgLocation.getPath()) + "_" + this.textureWidth + "x" + this.textureHeight
        );
        this.textureFrames = new SvgTextureFrames(svgLocation, textureLocation, this.textureWidth, this.textureHeight);
        this.currentTextureLocation = textureLocation;
    }

    /**
     * Returns the texture location for the current rasterized SVG frame.
     *
     * @return current texture location
     */
    public ResourceLocation getTextureLocation() {
        ensureLoaded();
        return currentTextureLocation;
    }

    /**
     * Returns the repeating dynamic texture used by the SVG renderer.
     *
     * @return dynamic texture for the image
     */
    public DynamicTexture getTexture() {
        return textureFrames.texture();
    }

    /**
     * Sets the SVG animation speed multiplier.
     *
     * @param animationSpeed positive finite speed multiplier
     * @return this image instance for chaining
     */
    public SvgImage setAnimationSpeed(float animationSpeed) {
        this.animationSpeed = Float.isFinite(animationSpeed) && animationSpeed > 0.0F ? animationSpeed : 1.0F;
        return this;
    }

    /**
     * Enables or disables paint animation attributes when collecting the SVG timeline.
     *
     * @param paintAnimationsEnabled {@code true} to apply paint animations
     * @return this image instance for chaining
     */
    public SvgImage setPaintAnimationsEnabled(boolean paintAnimationsEnabled) {
        this.paintAnimationsEnabled = paintAnimationsEnabled;
        return this;
    }

    /**
     * Sets a fallback color used when paint animations are disabled.
     *
     * @param color replacement paint color, or {@code null} to keep original values
     * @return this image instance for chaining
     */
    public SvgImage setDisabledPaintAnimationColor(Color color) {
        this.disabledPaintAnimationColor = color;
        return this;
    }

    /**
     * Loads the SVG if necessary and updates the current rasterized animation frame.
     */
    public void update() {
        if (!loaded || failed || !animationTimeline.animated() || animationComplete) {
            return;
        }

        try {
            updateAnimationFrame(currentAnimationTimeSeconds());
        } catch (Exception exception) {
            failed = true;
            CrashReport.forThrowable(exception, "Updating animation failed");
        }
    }

    /**
     * Draws the current SVG frame at full opacity.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height) {
        draw(graphics, x, y, width, height, 1.0F);
    }

    /**
     * Draws the current SVG frame with custom opacity.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        draw(graphics, x, y, width, height, alpha, Color.WHITE);
    }

    /**
     * Draws the current SVG frame with custom opacity and tint.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     * @param tint tint color
     */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, Float.NaN);
    }

    /**
     * Draws the SVG frame for an explicit animation time.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param timeSeconds animation time in seconds
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     */
    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height, float timeSeconds, float alpha) {
        drawAtTime(graphics, x, y, width, height, timeSeconds, alpha, Color.WHITE);
    }

    /**
     * Draws the SVG frame for an explicit animation time with custom tint.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param timeSeconds animation time in seconds
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     * @param tint tint color
     */
    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height,
                           float timeSeconds, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, Math.max(0.0F, timeSeconds));
    }

    /**
     * Draws the SVG at animation time {@code 0}.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     */
    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        drawStatic(graphics, x, y, width, height, alpha, Color.WHITE);
    }

    /**
     * Draws the SVG at animation time {@code 0} with custom tint.
     *
     * @param graphics current GUI graphics context
     * @param x left coordinate
     * @param y top coordinate
     * @param width draw width
     * @param height draw height
     * @param alpha opacity in the range {@code 0.0F} to {@code 1.0F}
     * @param tint tint color
     */
    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, 0.0F);
    }

    private void drawInternal(GuiGraphics graphics, float x, float y, float width, float height, float alpha,
                              Color tint, float explicitAnimationTimeSeconds) {
        float clampedAlpha = ClientImageUtil.clamp01(alpha);
        if (clampedAlpha <= 0.0F) {
            return;
        }

        ensureLoaded();
        if (!loaded || failed) {
            return;
        }

        if (animationTimeline.animated()) {
            if (Float.isFinite(explicitAnimationTimeSeconds)) {
                updateAnimationFrame(explicitAnimationTimeSeconds);
            } else {
                update();
            }
            if (failed) {
                return;
            }
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        ClientImageUtil.drawTexturedQuad(graphics, currentTextureLocation, x, y, Math.max(1.0F, width),
                Math.max(1.0F, height), clampedAlpha, tint);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private void ensureLoaded() {
        if (loaded || failed) {
            return;
        }

        try {
            document = SvgDocumentLoader.load(svgLocation);
            animationTimeline = SvgAnimationTimeline.collect(document, paintAnimationsEnabled, disabledPaintAnimationColor);
            if (animationStartMillis < 0L) {
                animationStartMillis = System.currentTimeMillis();
            }

            if (animationTimeline.animated()) {
                float initialTimeSeconds = currentAnimationTimeSeconds();
                boolean finalFrame = isFinalFrame(initialTimeSeconds);
                if (finalFrame) {
                    initialTimeSeconds = animationTimeline.endSeconds();
                }
                long frameKey = SvgAnimationFrameKey.forTime(initialTimeSeconds, finalFrame);
                currentTextureLocation = textureLocationForFrame(frameKey, initialTimeSeconds);
                lastRasterizedAnimationFrameKey = frameKey;
                animationComplete = finalFrame;
            } else {
                currentTextureLocation = uploadRasterizedFrame(0.0F);
            }
            loaded = true;
        } catch (Exception exception) {
            failed = true;
            CrashReport.forThrowable(exception, "failed load svg image from document");
        }
    }

    private float currentAnimationTimeSeconds() {
        if (animationStartMillis < 0L) {
            animationStartMillis = System.currentTimeMillis();
        }
        float elapsedSeconds = (System.currentTimeMillis() - animationStartMillis) / 1000.0F;
        return Math.max(0.0F, elapsedSeconds * animationSpeed);
    }

    private void updateAnimationFrame(float timeSeconds) {
        boolean finalFrame = isFinalFrame(timeSeconds);
        if (finalFrame) {
            timeSeconds = animationTimeline.endSeconds();
        }

        long frameKey = SvgAnimationFrameKey.forTime(timeSeconds, finalFrame);
        if (frameKey == lastRasterizedAnimationFrameKey) {
            return;
        }

        currentTextureLocation = textureLocationForFrame(frameKey, timeSeconds);
        lastRasterizedAnimationFrameKey = frameKey;
        animationComplete = finalFrame;
    }

    private boolean isFinalFrame(float timeSeconds) {
        return !animationTimeline.repeatForever() && timeSeconds >= animationTimeline.endSeconds();
    }

    private ResourceLocation textureLocationForFrame(long frameKey, float timeSeconds) {
        if (animationTimeline.repeatForever()) {
            return uploadRasterizedFrame(timeSeconds);
        }

        ResourceLocation cachedLocation = textureFrames.cachedFrameLocation(frameKey);
        if (cachedLocation != null) {
            return cachedLocation;
        }

        BufferedImage image = SvgRasterizer.rasterize(document, textureWidth, textureHeight, timeSeconds, animationTimeline);
        return textureFrames.uploadCachedFrame(frameKey, image);
    }

    private ResourceLocation uploadRasterizedFrame(float timeSeconds) {
        BufferedImage image = SvgRasterizer.rasterize(document, textureWidth, textureHeight, timeSeconds, animationTimeline);
        return textureFrames.uploadRepeatingFrame(image);
    }
}
