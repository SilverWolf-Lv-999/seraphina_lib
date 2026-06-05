package seraphina.seraphina_lib.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import lombok.Getter;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Seraphina
 * @version 1.0
 * */
public class SvgImage {
    private static final Pattern STYLE_CLASS_PATTERN = Pattern.compile("\\.([a-zA-Z0-9_-]+)\\s*\\{([^}]*)}", Pattern.DOTALL);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?");
    private static final Pattern PATH_TOKEN_PATTERN = Pattern.compile("[AaCcHhLlMmQqSsTtVvZz]|" + NUMBER_PATTERN.pattern());
    private static final Pattern TRANSFORM_PATTERN = Pattern.compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)");
    private static final int RASTERIZATION_SUPERSAMPLE_SCALE = 4;
    private static final int MAX_RASTERIZATION_SUPERSAMPLE_SCALE = 5;
    private static final int TRANSPARENT_COLOR_BLEED_PASSES = 3;
    private static final long MAX_SUPERSAMPLED_PIXELS = 12_000_000L;
    private static final long ANIMATION_FRAME_INTERVAL_MILLIS = 50L;
    private static final long FINAL_ANIMATION_FRAME_KEY = Long.MAX_VALUE;

    @Getter
    private final ResourceLocation svgLocation;
    private final ResourceLocation textureLocation;
    @Getter
    private final int textureWidth;
    @Getter
    private final int textureHeight;
    @Getter
    private DynamicTexture texture;
    private ResourceLocation currentTextureLocation;
    private final Map<Long, ResourceLocation> animationFrameLocations = new HashMap<>();
    private Document document;
    private List<SvgAnimation> animations = List.of();
    private long animationStartMillis = -1L;
    private long lastRasterizedAnimationFrameKey = Long.MIN_VALUE;
    private float animationEndSeconds;
    private boolean repeatAnimation;
    private boolean animationComplete;
    private boolean paintAnimationsEnabled = true;
    private Color disabledPaintAnimationColor;
    @Getter
    private float animationSpeed = 1.0F;
    private boolean animated;
    private boolean loaded;
    private boolean failed;

    public SvgImage(ResourceLocation svgLocation) {
        this(svgLocation, 256, 256);
    }

    public SvgImage(ResourceLocation svgLocation, int textureWidth, int textureHeight) {
        this.svgLocation = Objects.requireNonNull(svgLocation, "svgLocation");
        this.textureWidth = Math.max(1, textureWidth);
        this.textureHeight = Math.max(1, textureHeight);
        this.textureLocation = ResourceLocation.fromNamespaceAndPath(
                svgLocation.getNamespace(),
                "svg/generated/" + sanitizePath(svgLocation.getPath()) + "_" + this.textureWidth + "x" + this.textureHeight
        );
        this.currentTextureLocation = textureLocation;
    }

    public ResourceLocation getTextureLocation() {
        ensureLoaded();
        return currentTextureLocation;
    }

    public SvgImage setAnimationSpeed(float animationSpeed) {
        this.animationSpeed = Float.isFinite(animationSpeed) && animationSpeed > 0.0F ? animationSpeed : 1.0F;
        return this;
    }

    public SvgImage setPaintAnimationsEnabled(boolean paintAnimationsEnabled) {
        this.paintAnimationsEnabled = paintAnimationsEnabled;
        return this;
    }

    public SvgImage setDisabledPaintAnimationColor(Color color) {
        this.disabledPaintAnimationColor = color;
        return this;
    }

    public void update() {
        if (!loaded || failed || !animated || animationComplete) {
            return;
        }

        try {
            updateAnimationFrame(currentAnimationTimeSeconds());
        } catch (Exception exception) {
            failed = true;
            CrashReport.forThrowable(exception, "Updating animation failed");
        }
    }

//    public void restartAnimation() {
//        animationStartMillis = System.currentTimeMillis();
//        lastRasterizedAnimationFrameKey = Long.MIN_VALUE;
//        animationComplete = false;
//        if (loaded && !failed) {
//            try {
//                updateAnimationFrame(0.0F);
//                lastRasterizedAnimationFrameKey = frameKeyFor(0.0F, false);
//            } catch (Exception exception) {
//                failed = true;
//                CrashReport.forThrowable(exception, "");
//            }
//        }
//    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height) {
        draw(graphics, x, y, width, height, 1.0F);
    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        draw(graphics, x, y, width, height, alpha, Color.WHITE);
    }

    public void draw(GuiGraphics graphics, float x, float y, float width, float height, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, Float.NaN);
    }

    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height, float timeSeconds, float alpha) {
        drawAtTime(graphics, x, y, width, height, timeSeconds, alpha, Color.WHITE);
    }

    public void drawAtTime(GuiGraphics graphics, float x, float y, float width, float height,
                           float timeSeconds, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, Math.max(0.0F, timeSeconds));
    }

    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height, float alpha) {
        drawStatic(graphics, x, y, width, height, alpha, Color.WHITE);
    }

    public void drawStatic(GuiGraphics graphics, float x, float y, float width, float height, float alpha, Color tint) {
        drawInternal(graphics, x, y, width, height, alpha, tint, 0.0F);
    }

    private void drawInternal(GuiGraphics graphics, float x, float y, float width, float height, float alpha,
                              Color tint, float explicitAnimationTimeSeconds) {
        float clampedAlpha = clamp01(alpha);
        if (clampedAlpha <= 0.0F) {
            return;
        }

        ensureLoaded();
        if (!loaded || failed) {
            return;
        }

        if (animated) {
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
        drawTexturedQuad(graphics, currentTextureLocation, x, y, Math.max(1.0F, width), Math.max(1.0F, height),
                clampedAlpha, tint);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static void drawTexturedQuad(GuiGraphics graphics, ResourceLocation textureLocation,
                                         float x, float y, float width, float height, float alpha, Color tint) {
        Matrix4f matrix = graphics.pose().last().pose();
        float x1 = x + width;
        float y1 = y + height;
        Color safeTint = tint != null ? tint : Color.WHITE;
        float red = safeTint.getRed() / 255.0F;
        float green = safeTint.getGreen() / 255.0F;
        float blue = safeTint.getBlue() / 255.0F;
        float tintAlpha = safeTint.getAlpha() / 255.0F;
        float finalAlpha = alpha * tintAlpha;

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

    private static void texturedVertex(BufferBuilder buffer, Matrix4f matrix, float x, float y,
                                       float u, float v, float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, x, y, 0.0F).uv(u, v).color(red, green, blue, alpha).endVertex();
    }

    private void ensureLoaded() {
        if (loaded || failed) {
            return;
        }

        try {
            document = loadDocument();
            animations = collectAnimations(document, paintAnimationsEnabled, disabledPaintAnimationColor);
            animated = !animations.isEmpty();
            configureAnimationMetadata();
            if (animationStartMillis < 0L) {
                animationStartMillis = System.currentTimeMillis();
            }
            if (animated) {
                float initialTimeSeconds = currentAnimationTimeSeconds();
                boolean finalFrame = !repeatAnimation && initialTimeSeconds >= animationEndSeconds;
                if (finalFrame) {
                    initialTimeSeconds = animationEndSeconds;
                }
                currentTextureLocation = textureLocationForFrame(frameKeyFor(initialTimeSeconds, finalFrame), initialTimeSeconds);
                lastRasterizedAnimationFrameKey = frameKeyFor(initialTimeSeconds, finalFrame);
                animationComplete = finalFrame;
            } else {
                uploadRasterizedFrame(0.0F);
                currentTextureLocation = textureLocation;
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
        boolean finalFrame = !repeatAnimation && timeSeconds >= animationEndSeconds;
        if (finalFrame) {
            timeSeconds = animationEndSeconds;
        }

        long frameKey = frameKeyFor(timeSeconds, finalFrame);
        if (frameKey == lastRasterizedAnimationFrameKey) {
            return;
        }

        currentTextureLocation = textureLocationForFrame(frameKey, timeSeconds);
        lastRasterizedAnimationFrameKey = frameKey;
        animationComplete = finalFrame;
    }

    private void configureAnimationMetadata() {
        repeatAnimation = animations.stream().anyMatch(SvgAnimation::repeatForever);
        animationEndSeconds = 0.0F;
        for (SvgAnimation animation : animations) {
            animationEndSeconds = Math.max(animationEndSeconds, animation.begin() + animation.duration());
        }
    }

    private static long frameKeyFor(float timeSeconds, boolean finalFrame) {
        if (finalFrame) {
            return FINAL_ANIMATION_FRAME_KEY;
        }
        return Math.max(0L, (long) Math.floor(timeSeconds * 1000.0F / ANIMATION_FRAME_INTERVAL_MILLIS));
    }

    private void uploadRasterizedFrame(float timeSeconds) {
        BufferedImage image = rasterize(document, textureWidth, textureHeight, timeSeconds, animations);
        NativeImage nativeImage = toNativeImage(image);
        Runnable upload = () -> {
            if (texture == null) {
                DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
                dynamicTexture.setFilter(true, false);
                dynamicTexture.upload();
                texture = dynamicTexture;
                Minecraft.getInstance().getTextureManager().register(textureLocation, dynamicTexture);
            } else {
                texture.setPixels(nativeImage);
                texture.setFilter(true, false);
                texture.upload();
            }
        };

        if (RenderSystem.isOnRenderThread()) {
            upload.run();
        } else {
            RenderSystem.recordRenderCall(upload::run);
        }
    }

    private ResourceLocation textureLocationForFrame(long frameKey, float timeSeconds) {
        if (repeatAnimation) {
            uploadRasterizedFrame(timeSeconds);
            return textureLocation;
        }

        ResourceLocation cachedLocation = animationFrameLocations.get(frameKey);
        if (cachedLocation != null) {
            return cachedLocation;
        }

        ResourceLocation frameLocation = animationFrameTextureLocation(frameKey);
        BufferedImage image = rasterize(document, textureWidth, textureHeight, timeSeconds, animations);
        NativeImage nativeImage = toNativeImage(image);
        Runnable upload = () -> {
            DynamicTexture dynamicTexture = new DynamicTexture(nativeImage);
            dynamicTexture.setFilter(true, false);
            dynamicTexture.upload();
//            animationFrameTextures.put(frameKey, dynamicTexture);
            Minecraft.getInstance().getTextureManager().register(frameLocation, dynamicTexture);
        };

        if (RenderSystem.isOnRenderThread()) {
            upload.run();
        } else {
            RenderSystem.recordRenderCall(upload::run);
        }

        animationFrameLocations.put(frameKey, frameLocation);
        return frameLocation;
    }

    private ResourceLocation animationFrameTextureLocation(long frameKey) {
        String frameName = frameKey == FINAL_ANIMATION_FRAME_KEY ? "final" : Long.toString(frameKey);
        return ResourceLocation.fromNamespaceAndPath(
                svgLocation.getNamespace(),
                "svg/generated/" + sanitizePath(svgLocation.getPath()) + "_" + textureWidth + "x" + textureHeight + "/frame_" + frameName
        );
    }

    private Document loadDocument() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "https://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "https://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "https://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "https://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResource(svgLocation)
                .orElseThrow(() -> new IllegalStateException("SVG resource not found: " + svgLocation))
                .open();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return factory.newDocumentBuilder().parse(new InputSource(reader));
        }
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | IllegalArgumentException | UnsupportedOperationException ignored) {
        }
    }

    private static void setAttributeIfSupported(DocumentBuilderFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static BufferedImage rasterize(Document document, int width, int height, float timeSeconds, List<SvgAnimation> animations) {
        applyAnimations(timeSeconds, animations);

        Element root = document.getDocumentElement();
        Rectangle2D.Float viewBox = readViewBox(root, width, height);
        Map<String, Map<String, String>> classStyles = collectClassStyles(document);
        int supersampleScale = supersampleScale(width, height);
        int rasterWidth = width * supersampleScale;
        int rasterHeight = height * supersampleScale;
        BufferedImage supersampled = renderRaster(root, viewBox, classStyles, rasterWidth, rasterHeight);
        BufferedImage rasterized = supersampleScale == 1
                ? supersampled
                : downsampleSupersampled(supersampled, width, height, supersampleScale);
        bleedTransparentPixelColors(rasterized);

        return rasterized;
    }

    private static BufferedImage renderRaster(Element root, Rectangle2D.Float viewBox,
                                              Map<String, Map<String, String>> classStyles,
                                              int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        try {
            applyQualityRenderingHints(graphics);
            graphics.scale(width / viewBox.width, height / viewBox.height);
            graphics.translate(-viewBox.x, -viewBox.y);
            renderElement(graphics, root, SvgStyle.root(), classStyles);
        } finally {
            graphics.dispose();
        }

        return image;
    }

    private static void applyQualityRenderingHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static int supersampleScale(int width, int height) {
        int scale = Math.min(width, height) <= 128
                ? RASTERIZATION_SUPERSAMPLE_SCALE + 1
                : RASTERIZATION_SUPERSAMPLE_SCALE;
        scale = Math.min(scale, MAX_RASTERIZATION_SUPERSAMPLE_SCALE);
        long pixels = (long) width * height;
        while (scale > 1 && pixels * scale * scale > MAX_SUPERSAMPLED_PIXELS) {
            scale--;
        }
        return Math.max(1, scale);
    }

    private static BufferedImage downsampleSupersampled(BufferedImage source, int width, int height, int scale) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] sourcePixels = ((DataBufferInt) source.getRaster().getDataBuffer()).getData();
        int[] targetPixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        int sourceStride = source.getWidth();
        int sampleCount = scale * scale;

        for (int y = 0; y < height; y++) {
            int sourceY = y * scale;
            for (int x = 0; x < width; x++) {
                int sourceX = x * scale;
                long alphaSum = 0L;
                long redSum = 0L;
                long greenSum = 0L;
                long blueSum = 0L;

                for (int sy = 0; sy < scale; sy++) {
                    int sourceIndex = (sourceY + sy) * sourceStride + sourceX;
                    for (int sx = 0; sx < scale; sx++) {
                        int argb = sourcePixels[sourceIndex + sx];
                        int alpha = (argb >>> 24) & 0xFF;
                        alphaSum += alpha;
                        redSum += (long) ((argb >>> 16) & 0xFF) * alpha;
                        greenSum += (long) ((argb >>> 8) & 0xFF) * alpha;
                        blueSum += (long) (argb & 0xFF) * alpha;
                    }
                }

                int alpha = (int) ((alphaSum + sampleCount / 2L) / sampleCount);
                int red = alphaSum == 0L ? 0 : (int) ((redSum + alphaSum / 2L) / alphaSum);
                int green = alphaSum == 0L ? 0 : (int) ((greenSum + alphaSum / 2L) / alphaSum);
                int blue = alphaSum == 0L ? 0 : (int) ((blueSum + alphaSum / 2L) / alphaSum);
                targetPixels[y * width + x] = alpha << 24 | red << 16 | green << 8 | blue;
            }
        }

        return target;
    }

    private static void bleedTransparentPixelColors(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] current = pixels.clone();

        for (int pass = 0; pass < TRANSPARENT_COLOR_BLEED_PASSES; pass++) {
            int[] next = current.clone();
            boolean changed = false;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    if (((current[index] >>> 24) & 0xFF) != 0) {
                        continue;
                    }

                    long redSum = 0L;
                    long greenSum = 0L;
                    long blueSum = 0L;
                    long weightSum = 0L;

                    for (int offsetY = -1; offsetY <= 1; offsetY++) {
                        int neighborY = y + offsetY;
                        if (neighborY < 0 || neighborY >= height) {
                            continue;
                        }

                        for (int offsetX = -1; offsetX <= 1; offsetX++) {
                            if (offsetX == 0 && offsetY == 0) {
                                continue;
                            }

                            int neighborX = x + offsetX;
                            if (neighborX < 0 || neighborX >= width) {
                                continue;
                            }

                            int neighbor = current[neighborY * width + neighborX];
                            int neighborAlpha = (neighbor >>> 24) & 0xFF;
                            int neighborColor = neighbor & 0x00FFFFFF;
                            if (neighborAlpha == 0 && neighborColor == 0) {
                                continue;
                            }

                            int weight = Math.max(1, neighborAlpha);
                            redSum += (long) ((neighbor >>> 16) & 0xFF) * weight;
                            greenSum += (long) ((neighbor >>> 8) & 0xFF) * weight;
                            blueSum += (long) (neighbor & 0xFF) * weight;
                            weightSum += weight;
                        }
                    }

                    if (weightSum > 0L) {
                        int red = (int) ((redSum + weightSum / 2L) / weightSum);
                        int green = (int) ((greenSum + weightSum / 2L) / weightSum);
                        int blue = (int) ((blueSum + weightSum / 2L) / weightSum);
                        next[index] = red << 16 | green << 8 | blue;
                        changed = true;
                    }
                }
            }

            current = next;
            if (!changed) {
                break;
            }
        }

        System.arraycopy(current, 0, pixels, 0, pixels.length);
    }

    private static void applyAnimations(float timeSeconds, List<SvgAnimation> animations) {
        for (SvgAnimation animation : animations) {
            animation.restoreBaseValue();
        }

        for (SvgAnimation animation : animations) {
            animation.apply(timeSeconds);
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
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

    private static void renderElement(Graphics2D graphics, Element element, SvgStyle inheritedStyle, Map<String, Map<String, String>> classStyles) {
        SvgStyle style = inheritedStyle.resolve(element, classStyles);
        String tagName = tagName(element);
        AffineTransform oldTransform = graphics.getTransform();
        graphics.transform(parseTransform(element.getAttribute("transform")));

        try {
            Shape shape = switch (tagName) {
                case "path" -> parsePath(element.getAttribute("d"));
                case "line" -> parseLine(element);
                case "rect" -> parseRect(element);
                case "circle" -> parseCircle(element);
                case "ellipse" -> parseEllipse(element);
                case "polyline" -> parsePoly(element, false);
                case "polygon" -> parsePoly(element, true);
                default -> null;
            };

            if (shape != null) {
                paintShape(graphics, shape, style);
            }

            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element childElement) {
                    renderElement(graphics, childElement, style, classStyles);
                }
            }
        } finally {
            graphics.setTransform(oldTransform);
        }
    }

    private static void paintShape(Graphics2D graphics, Shape shape, SvgStyle style) {
        if (style.fill() != null) {
            graphics.setColor(applyOpacity(style.fill(), style.opacity()));
            graphics.fill(shape);
        }

        if (style.stroke() != null && style.strokeWidth() > 0.0F) {
            graphics.setColor(applyOpacity(style.stroke(), style.opacity()));
            float[] dashArray = style.strokeDashArray();
            graphics.setStroke(dashArray == null
                    ? new BasicStroke(style.strokeWidth(), style.lineCap(), style.lineJoin())
                    : new BasicStroke(style.strokeWidth(), style.lineCap(), style.lineJoin(), 10.0F, dashArray, style.strokeDashOffset()));
            graphics.draw(shape);
        }
    }

    private static Path2D.Float parsePath(String data) {
        if (data == null || data.isBlank()) {
            return new Path2D.Float();
        }
        return new PathParser(data).parse();
    }

    private static Shape parseLine(Element element) {
        float x1 = readLength(element, "x1", 0.0F);
        float y1 = readLength(element, "y1", 0.0F);
        float x2 = readLength(element, "x2", 0.0F);
        float y2 = readLength(element, "y2", 0.0F);
        return new Line2D.Float(x1, y1, x2, y2);
    }

    private static Shape parseRect(Element element) {
        float x = readLength(element, "x", 0.0F);
        float y = readLength(element, "y", 0.0F);
        float width = readLength(element, "width", 0.0F);
        float height = readLength(element, "height", 0.0F);
        float rx = readLength(element, "rx", 0.0F);
        float ry = readLength(element, "ry", rx);

        if (rx > 0.0F || ry > 0.0F) {
            return new RoundRectangle2D.Float(x, y, width, height, rx * 2.0F, ry * 2.0F);
        }

        return new Rectangle2D.Float(x, y, width, height);
    }

    private static Shape parseCircle(Element element) {
        float cx = readLength(element, "cx", 0.0F);
        float cy = readLength(element, "cy", 0.0F);
        float radius = readLength(element, "r", 0.0F);
        return new Ellipse2D.Float(cx - radius, cy - radius, radius * 2.0F, radius * 2.0F);
    }

    private static Shape parseEllipse(Element element) {
        float cx = readLength(element, "cx", 0.0F);
        float cy = readLength(element, "cy", 0.0F);
        float rx = readLength(element, "rx", 0.0F);
        float ry = readLength(element, "ry", 0.0F);
        return new Ellipse2D.Float(cx - rx, cy - ry, rx * 2.0F, ry * 2.0F);
    }

    private static Shape parsePoly(Element element, boolean closed) {
        Path2D.Float path = new Path2D.Float();
        Matcher matcher = NUMBER_PATTERN.matcher(element.getAttribute("points"));
        List<Float> numbers = new ArrayList<>();

        while (matcher.find()) {
            numbers.add(Float.parseFloat(matcher.group()));
        }

        for (int i = 0; i + 1 < numbers.size(); i += 2) {
            float x = numbers.get(i);
            float y = numbers.get(i + 1);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        if (closed) {
            path.closePath();
        }

        return path;
    }

    private static List<SvgAnimation> collectAnimations(Document document, boolean paintAnimationsEnabled,
                                                        Color disabledPaintAnimationColor) {
        List<SvgAnimation> result = new ArrayList<>();
        collectAnimations(document.getDocumentElement(), result, paintAnimationsEnabled, disabledPaintAnimationColor);
        return result;
    }

    private static Map<String, Map<String, String>> collectClassStyles(Document document) {
        Map<String, Map<String, String>> result = new HashMap<>();
        collectClassStyles(document.getDocumentElement(), result);
        return result;
    }

    private static void collectClassStyles(Element element, Map<String, Map<String, String>> result) {
        if ("style".equals(tagName(element))) {
            Matcher matcher = STYLE_CLASS_PATTERN.matcher(element.getTextContent());
            while (matcher.find()) {
                String className = matcher.group(1).trim();
                Map<String, String> style = parseStyle(matcher.group(2));
                result.computeIfAbsent(className, ignored -> new HashMap<>()).putAll(style);
            }
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                collectClassStyles(childElement, result);
            }
        }
    }

    private static Map<String, String> resolveStyleMap(Element element, Map<String, Map<String, String>> classStyles) {
        Map<String, String> values = new HashMap<>();
        String classAttribute = element.getAttribute("class");
        if (!classAttribute.isBlank()) {
            for (String className : classAttribute.trim().split("\\s+")) {
                Map<String, String> classStyle = classStyles.get(className);
                if (classStyle != null) {
                    values.putAll(classStyle);
                }
            }
        }
        values.putAll(parseStyle(element.getAttribute("style")));
        return values;
    }

    private static void collectAnimations(Element element, List<SvgAnimation> result, boolean paintAnimationsEnabled,
                                          Color disabledPaintAnimationColor) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                String tagName = tagName(childElement);
                if ("animate".equals(tagName)) {
                    SvgAnimation.fromAnimate(element, childElement).ifPresent(animation -> {
                        if (!paintAnimationsEnabled && isPaintAnimationAttribute(animation.attributeName())) {
                            if (disabledPaintAnimationColor != null) {
                                animation.target().setAttribute(animation.attributeName(), toSvgColor(disabledPaintAnimationColor));
                            }
                            return;
                        }
                        result.add(animation);
                    });
                } else if ("animatetransform".equals(tagName)) {
                    SvgAnimation.fromAnimateTransform(element, childElement).ifPresent(result::add);
                } else {
                    collectAnimations(childElement, result, paintAnimationsEnabled, disabledPaintAnimationColor);
                }
            }
        }
    }

    private static boolean isPaintAnimationAttribute(String attributeName) {
        if (attributeName == null) {
            return false;
        }

        return switch (attributeName.trim().toLowerCase(Locale.ROOT)) {
            case "fill", "stroke", "opacity", "fill-opacity", "stroke-opacity", "stop-color", "stop-opacity" -> true;
            default -> false;
        };
    }

    private static String toSvgColor(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static AffineTransform parseTransform(String value) {
        AffineTransform transform = new AffineTransform();
        if (value == null || value.isBlank()) {
            return transform;
        }

        Matcher matcher = TRANSFORM_PATTERN.matcher(value);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            List<Float> numbers = parseNumberList(matcher.group(2));
            switch (type) {
                case "matrix" -> {
                    if (numbers.size() >= 6) {
                        transform.concatenate(new AffineTransform(
                                numbers.get(0), numbers.get(1), numbers.get(2),
                                numbers.get(3), numbers.get(4), numbers.get(5)
                        ));
                    }
                }
                case "translate" -> {
                    if (!numbers.isEmpty()) {
                        transform.translate(numbers.get(0), numbers.size() >= 2 ? numbers.get(1) : 0.0F);
                    }
                }
                case "scale" -> {
                    if (!numbers.isEmpty()) {
                        transform.scale(numbers.get(0), numbers.size() >= 2 ? numbers.get(1) : numbers.get(0));
                    }
                }
                case "rotate" -> {
                    if (!numbers.isEmpty()) {
                        double radians = Math.toRadians(numbers.get(0));
                        if (numbers.size() >= 3) {
                            transform.rotate(radians, numbers.get(1), numbers.get(2));
                        } else {
                            transform.rotate(radians);
                        }
                    }
                }
                case "skewx" -> {
                    if (!numbers.isEmpty()) {
                        transform.shear(Math.tan(Math.toRadians(numbers.get(0))), 0.0D);
                    }
                }
                case "skewy" -> {
                    if (!numbers.isEmpty()) {
                        transform.shear(0.0D, Math.tan(Math.toRadians(numbers.get(0))));
                    }
                }
                default -> {
                }
            }
        }

        return transform;
    }

    private static List<Float> parseNumberList(String value) {
        List<Float> numbers = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return numbers;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(value);
        while (matcher.find()) {
            numbers.add(Float.parseFloat(matcher.group()));
        }
        return numbers;
    }

    private static Rectangle2D.Float readViewBox(Element root, int fallbackWidth, int fallbackHeight) {
        Matcher matcher = NUMBER_PATTERN.matcher(root.getAttribute("viewBox"));
        float[] values = new float[4];
        int count = 0;

        while (matcher.find() && count < values.length) {
            values[count++] = Float.parseFloat(matcher.group());
        }

        if (count == values.length && values[2] > 0.0F && values[3] > 0.0F) {
            return new Rectangle2D.Float(values[0], values[1], values[2], values[3]);
        }

        float width = readLength(root, "width", fallbackWidth);
        float height = readLength(root, "height", fallbackHeight);
        return new Rectangle2D.Float(0.0F, 0.0F, Math.max(1.0F, width), Math.max(1.0F, height));
    }

    private static float readLength(Element element, String attribute, float defaultValue) {
        if (!element.hasAttribute(attribute)) {
            return defaultValue;
        }
        return parseFloat(element.getAttribute(attribute), defaultValue);
    }

    private static float parseFloat(String value, float defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return defaultValue;
        }

        return Float.parseFloat(matcher.group());
    }

    private static float[] parseDashArray(String value, float[] fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(trimmed)) {
            return null;
        }

        List<Float> numbers = parseNumberList(trimmed);
        if (numbers.isEmpty()) {
            return fallback;
        }

        float[] result = new float[numbers.size()];
        float total = 0.0F;
        for (int i = 0; i < numbers.size(); i++) {
            float number = Math.max(0.0F, numbers.get(i));
            result[i] = number;
            total += number;
        }

        return total > 0.0F ? result : fallback;
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        String color = value.trim().toLowerCase(Locale.ROOT);
        if ("none".equals(color)) {
            return null;
        }

        if (color.startsWith("#")) {
            return parseHexColor(color, fallback);
        }

        if (color.startsWith("rgb")) {
            Matcher matcher = NUMBER_PATTERN.matcher(color);
            int[] components = new int[3];
            int count = 0;
            while (matcher.find() && count < components.length) {
                components[count++] = clamp(Math.round(Float.parseFloat(matcher.group())));
            }
            return count == components.length ? new Color(components[0], components[1], components[2]) : fallback;
        }

        return switch (color) {
            case "black" -> Color.BLACK;
            case "white" -> Color.WHITE;
            case "red" -> Color.RED;
            case "green" -> Color.GREEN;
            case "blue" -> Color.BLUE;
            case "transparent" -> new Color(0, 0, 0, 0);
            default -> fallback;
        };
    }

    private static Color parseHexColor(String color, Color fallback) {
        String hex = color.substring(1);

        try {
            if (hex.length() == 3) {
                int red = Integer.parseInt(hex.substring(0, 1) + hex.charAt(0), 16);
                int green = Integer.parseInt(hex.substring(1, 2) + hex.charAt(1), 16);
                int blue = Integer.parseInt(hex.substring(2, 3) + hex.charAt(2), 16);
                return new Color(red, green, blue);
            }

            if (hex.length() == 6) {
                return new Color(Integer.parseInt(hex, 16));
            }

            if (hex.length() == 8) {
                int alpha = Integer.parseInt(hex.substring(0, 2), 16);
                int red = Integer.parseInt(hex.substring(2, 4), 16);
                int green = Integer.parseInt(hex.substring(4, 6), 16);
                int blue = Integer.parseInt(hex.substring(6, 8), 16);
                return new Color(red, green, blue, alpha);
            }
        } catch (NumberFormatException ignored) {
        }

        return fallback;
    }

    private static Color applyOpacity(Color color, float opacity) {
        int alpha = clamp(Math.round(color.getAlpha() * Math.max(0.0F, Math.min(1.0F, opacity))));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private static int parseLineCap(String value) {
        if (value == null) {
            return BasicStroke.CAP_BUTT;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "round" -> BasicStroke.CAP_ROUND;
            case "square" -> BasicStroke.CAP_SQUARE;
            default -> BasicStroke.CAP_BUTT;
        };
    }

    private static int parseLineJoin(String value) {
        if (value == null) {
            return BasicStroke.JOIN_MITER;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "round" -> BasicStroke.JOIN_ROUND;
            case "bevel" -> BasicStroke.JOIN_BEVEL;
            default -> BasicStroke.JOIN_MITER;
        };
    }

    private static String attribute(Element element, Map<String, String> style, String name) {
        if (element.hasAttribute(name)) {
            return element.getAttribute(name);
        }
        return style.get(name);
    }

    private static Map<String, String> parseStyle(String style) {
        Map<String, String> values = new HashMap<>();
        if (style == null || style.isBlank()) {
            return values;
        }

        String[] parts = style.split(";");
        for (String part : parts) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }

            values.put(
                    part.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    part.substring(colon + 1).trim()
            );
        }

        return values;
    }

    private static String tagName(Element element) {
        String localName = element.getLocalName();
        return (localName != null ? localName : element.getTagName()).toLowerCase(Locale.ROOT);
    }

    private static String sanitizePath(String path) {
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_");
    }

    private static float clamp01(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        return Math.min(value, 1.0F);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record SvgStyle(Color fill, Color stroke, float strokeWidth, int lineCap, int lineJoin, float opacity,
                            float[] strokeDashArray, float strokeDashOffset) {
        private static SvgStyle root() {
            return new SvgStyle(Color.BLACK, null, 1.0F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0F, null, 0.0F);
        }

        private SvgStyle resolve(Element element, Map<String, Map<String, String>> classStyles) {
            Map<String, String> style = resolveStyleMap(element, classStyles);
            Color nextFill = parseColor(attribute(element, style, "fill"), fill);
            Color nextStroke = parseColor(attribute(element, style, "stroke"), stroke);
            float nextStrokeWidth = parseFloat(attribute(element, style, "stroke-width"), strokeWidth);
            int nextLineCap = element.hasAttribute("stroke-linecap") || style.containsKey("stroke-linecap")
                    ? parseLineCap(attribute(element, style, "stroke-linecap"))
                    : lineCap;
            int nextLineJoin = element.hasAttribute("stroke-linejoin") || style.containsKey("stroke-linejoin")
                    ? parseLineJoin(attribute(element, style, "stroke-linejoin"))
                    : lineJoin;
            float nextOpacity = parseFloat(attribute(element, style, "opacity"), opacity);
            float[] nextStrokeDashArray = parseDashArray(attribute(element, style, "stroke-dasharray"), strokeDashArray);
            float nextStrokeDashOffset = parseFloat(attribute(element, style, "stroke-dashoffset"), strokeDashOffset);

            return new SvgStyle(nextFill, nextStroke, nextStrokeWidth, nextLineCap, nextLineJoin, nextOpacity,
                    nextStrokeDashArray, nextStrokeDashOffset);
        }
    }

    private record SvgAnimation(Element target, String attributeName, String baseValue, String fromValue,
                                String toValue, List<String> values, float begin, float duration,
                                boolean repeatForever, boolean additiveSum, boolean transformAnimation,
                                String transformType) {
        private static Optional<SvgAnimation> fromAnimate(Element target, Element animation) {
            String attributeName = animation.getAttribute("attributeName");
            if (attributeName.isBlank()) {
                return Optional.empty();
            }

            float duration = parseDuration(animation.getAttribute("dur"), -1.0F);
            if (duration <= 0.0F) {
                return Optional.empty();
            }

            List<String> values = parseValueList(animation.getAttribute("values"));
            String from = animation.getAttribute("from");
            String to = animation.getAttribute("to");
            if (values.isEmpty() && (from.isBlank() || to.isBlank())) {
                return Optional.empty();
            }

            return Optional.of(new SvgAnimation(
                    target,
                    attributeName,
                    target.getAttribute(attributeName),
                    from,
                    to,
                    values,
                    parseDuration(animation.getAttribute("begin"), 0.0F),
                    duration,
                    "indefinite".equalsIgnoreCase(animation.getAttribute("repeatCount")),
                    "sum".equalsIgnoreCase(animation.getAttribute("additive")),
                    false,
                    ""
            ));
        }

        private static Optional<SvgAnimation> fromAnimateTransform(Element target, Element animation) {
            String type = animation.getAttribute("type");
            if (type.isBlank()) {
                type = "translate";
            }

            float duration = parseDuration(animation.getAttribute("dur"), -1.0F);
            if (duration <= 0.0F) {
                return Optional.empty();
            }

            List<String> values = parseValueList(animation.getAttribute("values"));
            String from = animation.getAttribute("from");
            String to = animation.getAttribute("to");
            if (values.isEmpty() && (from.isBlank() || to.isBlank())) {
                return Optional.empty();
            }

            return Optional.of(new SvgAnimation(
                    target,
                    "transform",
                    target.getAttribute("transform"),
                    from,
                    to,
                    values,
                    parseDuration(animation.getAttribute("begin"), 0.0F),
                    duration,
                    "indefinite".equalsIgnoreCase(animation.getAttribute("repeatCount")),
                    "sum".equalsIgnoreCase(animation.getAttribute("additive")),
                    true,
                    type.trim().toLowerCase(Locale.ROOT)
            ));
        }

        private void restoreBaseValue() {
            if (baseValue == null || baseValue.isBlank()) {
                target.removeAttribute(attributeName);
            } else {
                target.setAttribute(attributeName, baseValue);
            }
        }

        private void apply(float timeSeconds) {
            if (timeSeconds < begin) {
                return;
            }

            float elapsed = timeSeconds - begin;
            if (!repeatForever && elapsed > duration) {
                elapsed = duration;
            }

            float progress = repeatForever ? (elapsed % duration) / duration : Math.min(1.0F, elapsed / duration);
            String value = interpolatedValue(progress);
            if (value == null || value.isBlank()) {
                return;
            }

            if (transformAnimation) {
                String transformValue = transformType + "(" + value + ")";
                if (additiveSum && baseValue != null && !baseValue.isBlank()) {
                    transformValue = baseValue + " " + transformValue;
                }
                target.setAttribute(attributeName, transformValue);
            } else {
                target.setAttribute(attributeName, value);
            }
        }

        private String interpolatedValue(float progress) {
            if (!values.isEmpty()) {
                if (values.size() == 1) {
                    return values.get(0);
                }

                float scaled = progress * (values.size() - 1);
                int index = Math.min(values.size() - 2, Math.max(0, (int) Math.floor(scaled)));
                return interpolate(values.get(index), values.get(index + 1), scaled - index);
            }

            return interpolate(fromValue, toValue, progress);
        }

        private static String interpolate(String from, String to, float progress) {
            List<Float> fromNumbers = parseNumberList(from);
            List<Float> toNumbers = parseNumberList(to);
            if (fromNumbers.isEmpty() || fromNumbers.size() != toNumbers.size()) {
                return progress < 1.0F ? from : to;
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < fromNumbers.size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                float value = fromNumbers.get(i) + (toNumbers.get(i) - fromNumbers.get(i)) * progress;
                builder.append(trimFloat(value));
            }
            return builder.toString();
        }

        private static List<String> parseValueList(String value) {
            List<String> result = new ArrayList<>();
            if (value == null || value.isBlank()) {
                return result;
            }

            for (String part : value.split(";")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        private static float parseDuration(String value, float defaultValue) {
            if (value == null || value.isBlank() || "indefinite".equalsIgnoreCase(value.trim())) {
                return defaultValue;
            }

            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            float multiplier = 0.0F;
            if (trimmed.endsWith("ms")) {
                multiplier = 0.001F;
            } else if (trimmed.endsWith("min")) {
                multiplier = 60.0F;
            } else if (trimmed.endsWith("s")) {
                multiplier = 1.0F;
            }

            return parseFloat(trimmed, defaultValue) * multiplier;
        }

        private static String trimFloat(float value) {
            if (Math.abs(value) < 0.0001F) {
                value = 0.0F;
            }
            if (Math.abs(value - Math.round(value)) < 0.0001F) {
                return Integer.toString(Math.round(value));
            }
            return Float.toString(value);
        }
    }

    private static final class PathParser {
        private final List<String> tokens = new ArrayList<>();
        private final Path2D.Float path = new Path2D.Float();
        private int index;
        private char command;
        private float currentX;
        private float currentY;
        private float startX;
        private float startY;
        private float lastCubicControlX;
        private float lastCubicControlY;
        private float lastQuadControlX;
        private float lastQuadControlY;
        private boolean hasLastCubicControl;
        private boolean hasLastQuadControl;

        private PathParser(String data) {
            Matcher matcher = PATH_TOKEN_PATTERN.matcher(data);
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
        }

        private Path2D.Float parse() {
            while (index < tokens.size()) {
                if (isCommand(tokens.get(index))) {
                    command = tokens.get(index++).charAt(0);
                }

                if (command == 0) {
                    throw new IllegalArgumentException("SVG path data is missing command");
                }

                switch (command) {
                    case 'M', 'm' -> moveTo(Character.isLowerCase(command));
                    case 'L', 'l' -> lineTo(Character.isLowerCase(command));
                    case 'H', 'h' -> horizontalLineTo(Character.isLowerCase(command));
                    case 'V', 'v' -> verticalLineTo(Character.isLowerCase(command));
                    case 'C', 'c' -> cubicTo(Character.isLowerCase(command));
                    case 'S', 's' -> smoothCubicTo(Character.isLowerCase(command));
                    case 'Q', 'q' -> quadTo(Character.isLowerCase(command));
                    case 'T', 't' -> smoothQuadTo(Character.isLowerCase(command));
                    case 'A', 'a' -> arcTo(Character.isLowerCase(command));
                    case 'Z', 'z' -> closePath();
                    default -> throw new IllegalArgumentException("Unsupported SVG path command: " + command);
                }
            }

            return path;
        }

        private void moveTo(boolean relative) {
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x += currentX;
                y += currentY;
            }

            path.moveTo(x, y);
            currentX = x;
            currentY = y;
            startX = x;
            startY = y;
            resetControlPoints();
            command = relative ? 'l' : 'L';
        }

        private void lineTo(boolean relative) {
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x += currentX;
                y += currentY;
            }

            path.lineTo(x, y);
            currentX = x;
            currentY = y;
            resetControlPoints();
        }

        private void horizontalLineTo(boolean relative) {
            float x = readFloat();
            if (relative) {
                x += currentX;
            }

            path.lineTo(x, currentY);
            currentX = x;
            resetControlPoints();
        }

        private void verticalLineTo(boolean relative) {
            float y = readFloat();
            if (relative) {
                y += currentY;
            }

            path.lineTo(currentX, y);
            currentY = y;
            resetControlPoints();
        }

        private void cubicTo(boolean relative) {
            float x1 = readFloat();
            float y1 = readFloat();
            float x2 = readFloat();
            float y2 = readFloat();
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x1 += currentX;
                y1 += currentY;
                x2 += currentX;
                y2 += currentY;
                x += currentX;
                y += currentY;
            }

            path.curveTo(x1, y1, x2, y2, x, y);
            currentX = x;
            currentY = y;
            lastCubicControlX = x2;
            lastCubicControlY = y2;
            hasLastCubicControl = true;
            hasLastQuadControl = false;
        }

        private void smoothCubicTo(boolean relative) {
            float x1 = hasLastCubicControl ? currentX * 2.0F - lastCubicControlX : currentX;
            float y1 = hasLastCubicControl ? currentY * 2.0F - lastCubicControlY : currentY;
            float x2 = readFloat();
            float y2 = readFloat();
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x2 += currentX;
                y2 += currentY;
                x += currentX;
                y += currentY;
            }

            path.curveTo(x1, y1, x2, y2, x, y);
            currentX = x;
            currentY = y;
            lastCubicControlX = x2;
            lastCubicControlY = y2;
            hasLastCubicControl = true;
            hasLastQuadControl = false;
        }

        private void quadTo(boolean relative) {
            float x1 = readFloat();
            float y1 = readFloat();
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x1 += currentX;
                y1 += currentY;
                x += currentX;
                y += currentY;
            }

            path.quadTo(x1, y1, x, y);
            currentX = x;
            currentY = y;
            lastQuadControlX = x1;
            lastQuadControlY = y1;
            hasLastQuadControl = true;
            hasLastCubicControl = false;
        }

        private void smoothQuadTo(boolean relative) {
            float x1 = hasLastQuadControl ? currentX * 2.0F - lastQuadControlX : currentX;
            float y1 = hasLastQuadControl ? currentY * 2.0F - lastQuadControlY : currentY;
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x += currentX;
                y += currentY;
            }

            path.quadTo(x1, y1, x, y);
            currentX = x;
            currentY = y;
            lastQuadControlX = x1;
            lastQuadControlY = y1;
            hasLastQuadControl = true;
            hasLastCubicControl = false;
        }

        private void arcTo(boolean relative) {
            readFloat();
            readFloat();
            readFloat();
            readFloat();
            readFloat();
            float x = readFloat();
            float y = readFloat();
            if (relative) {
                x += currentX;
                y += currentY;
            }

            path.lineTo(x, y);
            currentX = x;
            currentY = y;
            resetControlPoints();
        }

        private void closePath() {
            path.closePath();
            currentX = startX;
            currentY = startY;
            resetControlPoints();
            command = 0;
        }

        private float readFloat() {
            if (index >= tokens.size() || isCommand(tokens.get(index))) {
                throw new IllegalArgumentException("SVG path data has too few values");
            }
            return Float.parseFloat(tokens.get(index++));
        }

        private void resetControlPoints() {
            hasLastCubicControl = false;
            hasLastQuadControl = false;
        }

        private static boolean isCommand(String token) {
            return token.length() == 1 && Character.isLetter(token.charAt(0));
        }
    }
}
