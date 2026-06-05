package seraphina.seraphina_lib.client.gif;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class GIFFrameDecoder {
    static final int DEFAULT_FRAME_DELAY_MILLIS = 100;

    private static final int MIN_FRAME_DELAY_MILLIS = 20;

    private GIFFrameDecoder() {
    }

    static GIFImageData decode(ResourceLocation location) throws Exception {
        List<GIFFrame> frames = new ArrayList<>();
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        if (!readers.hasNext()) {
            throw new IllegalStateException("No GIF ImageReader is available");
        }

        ImageReader reader = readers.next();
        GIFCanvas canvas;
        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResource(location)
                .orElseThrow(() -> new IllegalStateException("GIF resource not found: " + location))
                .open();
             ImageInputStream imageInputStream = ImageIO.createImageInputStream(stream)) {
            if (imageInputStream == null) {
                throw new IllegalStateException("Unable to create GIF image input stream: " + location);
            }

            reader.setInput(imageInputStream, false, false);
            canvas = readCanvas(reader);
            readFrames(reader, canvas, frames);
        } finally {
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new IllegalStateException("GIF has no frames: " + location);
        }

        int totalDurationMillis = frames.stream().mapToInt(GIFFrame::delayMillis).sum();
        if (totalDurationMillis <= 0) {
            totalDurationMillis = frames.size() * DEFAULT_FRAME_DELAY_MILLIS;
        }

        return new GIFImageData(canvas.width(), canvas.height(), List.copyOf(frames), totalDurationMillis);
    }

    private static void readFrames(ImageReader reader, GIFCanvas canvas, List<GIFFrame> frames) throws Exception {
        BufferedImage composed = new BufferedImage(canvas.width(), canvas.height(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = composed.createGraphics();
        try {
            int frameCount = reader.getNumImages(true);
            for (int index = 0; index < frameCount; index++) {
                BufferedImage rawFrame = toArgb(reader.read(index));
                GIFFrameMetadata metadata = readFrameMetadata(reader.getImageMetadata(index));
                BufferedImage previousComposed = "restoreToPrevious".equals(metadata.disposalMethod())
                        ? deepCopy(composed)
                        : null;

                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(rawFrame, metadata.left(), metadata.top(), null);
                frames.add(new GIFFrame(deepCopy(composed), metadata.delayMillis()));

                applyDisposal(graphics, previousComposed, metadata, rawFrame.getWidth(), rawFrame.getHeight());
            }
        } finally {
            graphics.dispose();
        }
    }

    private static void applyDisposal(Graphics2D graphics, BufferedImage previousComposed,
                                      GIFFrameMetadata metadata, int frameWidth, int frameHeight) {
        switch (metadata.disposalMethod()) {
            case "none", "doNotDispose" -> {
            }
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
            default -> throw new IllegalStateException("Unknown disposalMethod: " + metadata.disposalMethod());
        }
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
}
