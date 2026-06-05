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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.*;
import java.util.List;

/**
 * @author Seraphina
 * @version 1.0
 *
 * GIF renderer that decodes a GIF into composited frames and renders the current frame as a dynamic texture.
 * <p>
 * The GIF format stores frames as patches plus disposal metadata, so this class composites every frame into a
 * full-canvas {@link BufferedImage} before uploading the frames to Minecraft textures.
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public class GIFImage {
    private static final int DEFAULT_FRAME_DELAY_MILLIS = 100;
    private static final int MIN_FRAME_DELAY_MILLIS = 20;

    @Getter
    private final ResourceLocation location;
    @Getter
    private int width = 1;
    @Getter
    private int height = 1;
    @Getter
    private float animationSpeed = 1.0F;

    private final List<GIFFrame> frames = new ArrayList<>();
    private final List<ResourceLocation> frameLocations = new ArrayList<>();
    private final List<DynamicTexture> frameTextures = new ArrayList<>();
    private int totalDurationMillis = DEFAULT_FRAME_DELAY_MILLIS;
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
        float clampedAlpha = clamp01(alpha);
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
        drawTexturedQuad(graphics, frameLocations.get(currentFrameIndex), x, y, Math.max(1.0F, width), Math.max(1.0F, height), clampedAlpha, tint);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private void ensureLoaded() {
        if (loaded || failed) {
            return;
        }

        try {
            loadFrames();
            uploadFrames();
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

    private void loadFrames() throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IllegalStateException("No GIF ImageReader is available");
        }

        ImageReader reader = readers.next();
        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResource(location)
                .orElseThrow(() -> new IllegalStateException("GIF resource not found: " + location))
                .open();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(stream)) {
            if (imageInputStream == null) {
                throw new IllegalStateException("Unable to create GIF image input stream: " + location);
            }

            reader.setInput(imageInputStream, false, false);
            GIFCanvas canvas = readCanvas(reader);
            width = canvas.width();
            height = canvas.height();

            BufferedImage composed = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            BufferedImage previousComposed;
            Graphics2D graphics = composed.createGraphics();
            try {
                int frameCount = reader.getNumImages(true);
                for (int index = 0; index < frameCount; index++) {
                    BufferedImage rawFrame = toArgb(reader.read(index));
                    GIFFrameMetadata metadata = readFrameMetadata(reader.getImageMetadata(index));

                    if ("restoreToPrevious".equals(metadata.disposalMethod())) {
                        previousComposed = deepCopy(composed);
                    } else {
                        previousComposed = null;
                    }

                    graphics.setComposite(AlphaComposite.SrcOver);
                    graphics.drawImage(rawFrame, metadata.left(), metadata.top(), null);
                    frames.add(new GIFFrame(deepCopy(composed), metadata.delayMillis()));

                    applyDisposal(graphics, previousComposed, metadata, rawFrame.getWidth(), rawFrame.getHeight());
                }
            } finally {
                graphics.dispose();
            }
        } finally {
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new IllegalStateException("GIF has no frames: " + location);
        }

        totalDurationMillis = frames.stream().mapToInt(GIFFrame::delayMillis).sum();
        if (totalDurationMillis <= 0) {
            totalDurationMillis = frames.size() * DEFAULT_FRAME_DELAY_MILLIS;
        }
    }

    private void applyDisposal(Graphics2D graphics, BufferedImage previousComposed,
                               GIFFrameMetadata metadata, int frameWidth, int frameHeight) {
        switch (metadata.disposalMethod()) {
            case "restoreToBackgroundColor" -> {
                graphics.setComposite(AlphaComposite.Clear);
                graphics.fillRect(metadata.left(), metadata.top(), frameWidth, frameHeight);
                graphics.setComposite(AlphaComposite.SrcOver);
            }
            case "restoreToPrevious" -> {
                if (previousComposed != null) {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.drawImage(previousComposed, 0, 0, null);
                    graphics.setComposite(AlphaComposite.SrcOver);
                }
            }
            default -> {
                throw new IllegalStateException("Unknown disposalMethod: " + metadata.disposalMethod());
            }
        }
    }

    private void uploadFrames() {
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            ResourceLocation frameLocation = frameTextureLocation(frameIndex);
            NativeImage nativeImage = toNativeImage(frames.get(frameIndex).image());
            Runnable upload = () -> {
                DynamicTexture texture = new DynamicTexture(nativeImage);
                texture.setFilter(true, false);
                texture.upload();
                frameTextures.add(texture);
                Minecraft.getInstance().getTextureManager().register(frameLocation, texture);
            };

            if (RenderSystem.isOnRenderThread()) {
                upload.run();
            } else {
                RenderSystem.recordRenderCall(upload::run);
            }

            frameLocations.add(frameLocation);
        }
    }

    private ResourceLocation frameTextureLocation(int frameIndex) {
        return ResourceLocation.fromNamespaceAndPath(
                location.getNamespace(),
                "gif/generated/" + sanitizePath(location.getPath()) + "/frame_" + frameIndex
        );
    }

    private static GIFCanvas readCanvas(ImageReader reader) throws Exception {
        IIOMetadata streamMetadata = reader.getStreamMetadata();
        if (streamMetadata != null) {
            Node root = streamMetadata.getAsTree(streamMetadata.getNativeMetadataFormatName());
            Node screen = firstChild(root, "LogicalScreenDescriptor");
            if (screen != null) {
                int width = intAttribute(screen, "logicalScreenWidth", 1);
                int height = intAttribute(screen, "logicalScreenHeight", 1);
                return new GIFCanvas(Math.max(1, width), Math.max(1, height));
            }
        }

        BufferedImage first = reader.read(0);
        return new GIFCanvas(Math.max(1, first.getWidth()), Math.max(1, first.getHeight()));
    }

    private static GIFFrameMetadata readFrameMetadata(IIOMetadata metadata) {
        Node root = metadata.getAsTree(metadata.getNativeMetadataFormatName());
        Node descriptor = firstChild(root, "ImageDescriptor");
        Node control = firstChild(root, "GraphicControlExtension");

        int left = descriptor != null ? intAttribute(descriptor, "imageLeftPosition", 0) : 0;
        int top = descriptor != null ? intAttribute(descriptor, "imageTopPosition", 0) : 0;
        int delay = control != null ? intAttribute(control, "delayTime", 10) * 10 : DEFAULT_FRAME_DELAY_MILLIS;
        String disposal = control != null ? stringAttribute(control, "disposalMethod", "none") : "none";
        return new GIFFrameMetadata(left, top, Math.max(MIN_FRAME_DELAY_MILLIS, delay), disposal);
    }

    private static Node firstChild(Node root, String name) {
        if (root == null) {
            return null;
        }

        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (name.equals(child.getNodeName())) {
                return child;
            }
        }
        return null;
    }

    private static int intAttribute(Node node, String name, int defaultValue) {
        String value = stringAttribute(node, name, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String stringAttribute(Node node, String name, String defaultValue) {
        NamedNodeMap attributes = node.getAttributes();
        if (attributes == null) {
            return defaultValue;
        }

        Node attribute = attributes.getNamedItem(name);
        return attribute != null ? attribute.getNodeValue() : defaultValue;
    }

    private static BufferedImage toArgb(BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_ARGB) {
            return image;
        }

        BufferedImage converted = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = converted.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return converted;
    }

    private static BufferedImage deepCopy(BufferedImage source) {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = copy.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
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

    private static void drawTexturedQuad(GuiGraphics graphics, ResourceLocation textureLocation,
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

    private static void texturedVertex(BufferBuilder buffer, Matrix4f matrix, float x, float y,
                                       float u, float v, float red, float green, float blue, float alpha) {
        buffer.vertex(matrix, x, y, 0.0F).uv(u, v).color(red, green, blue, alpha).endVertex();
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

    private record GIFCanvas(int width, int height) {
    }

    private record GIFFrame(BufferedImage image, int delayMillis) {
    }

    private record GIFFrameMetadata(int left, int top, int delayMillis, String disposalMethod) {
    }
}