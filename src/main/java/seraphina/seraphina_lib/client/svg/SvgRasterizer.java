package seraphina.seraphina_lib.client.svg;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Map;
import java.util.regex.Matcher;

final class SvgRasterizer {
    private static final int RASTERIZATION_SUPERSAMPLE_SCALE = 4;
    private static final int MAX_RASTERIZATION_SUPERSAMPLE_SCALE = 5;
    private static final int TRANSPARENT_COLOR_BLEED_PASSES = 3;
    private static final long MAX_SUPERSAMPLED_PIXELS = 12_000_000L;

    private SvgRasterizer() {
    }

    static BufferedImage rasterize(Document document, int width, int height, float timeSeconds, SvgAnimationTimeline timeline) {
        timeline.apply(timeSeconds);

        Element root = document.getDocumentElement();
        Rectangle2D.Float viewBox = readViewBox(root, width, height);
        Map<String, Map<String, String>> classStyles = SvgStyleResolver.collectClassStyles(document);
        int supersampleScale = supersampleScale(width, height);
        int rasterWidth = width * supersampleScale;
        int rasterHeight = height * supersampleScale;
        BufferedImage supersampled = SvgRenderer.render(root, viewBox, classStyles, rasterWidth, rasterHeight);
        BufferedImage rasterized = supersampleScale == 1
                ? supersampled
                : downsampleSupersampled(supersampled, width, height, supersampleScale);
        bleedTransparentPixelColors(rasterized);

        return rasterized;
    }

    private static Rectangle2D.Float readViewBox(Element root, int fallbackWidth, int fallbackHeight) {
        Matcher matcher = SvgParsing.NUMBER_PATTERN.matcher(root.getAttribute("viewBox"));
        float[] values = new float[4];
        int count = 0;

        while (matcher.find() && count < values.length) {
            values[count++] = Float.parseFloat(matcher.group());
        }

        if (count == values.length && values[2] > 0.0F && values[3] > 0.0F) {
            return new Rectangle2D.Float(values[0], values[1], values[2], values[3]);
        }

        float width = SvgParsing.readLength(root, "width", fallbackWidth);
        float height = SvgParsing.readLength(root, "height", fallbackHeight);
        return new Rectangle2D.Float(0.0F, 0.0F, Math.max(1.0F, width), Math.max(1.0F, height));
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
}
