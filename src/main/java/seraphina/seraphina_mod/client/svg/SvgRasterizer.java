package seraphina.seraphina_mod.client.svg;

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

    /**
     * Renders SVG content with bounded supersampling, then prepares transparent
     * pixels for texture filtering by copying nearby visible colors into them.
     */
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
                targetPixels[y * width + x] = downsamplePixel(sourcePixels, sourceStride, sourceX, sourceY, scale, sampleCount);
            }
        }

        return target;
    }

    private static int downsamplePixel(int[] sourcePixels, int sourceStride, int sourceX, int sourceY,
                                       int scale, int sampleCount) {
        long alphaSum = 0L;
        long redSum = 0L;
        long greenSum = 0L;
        long blueSum = 0L;

        for (int sy = 0; sy < scale; sy++) {
            int sourceIndex = (sourceY + sy) * sourceStride + sourceX;
            for (int sx = 0; sx < scale; sx++) {
                int argb = sourcePixels[sourceIndex + sx];
                int alpha = alpha(argb);
                alphaSum += alpha;
                redSum += (long) red(argb) * alpha;
                greenSum += (long) green(argb) * alpha;
                blueSum += (long) blue(argb) * alpha;
            }
        }

        int alpha = (int) ((alphaSum + sampleCount / 2L) / sampleCount);
        int red = alphaSum == 0L ? 0 : (int) ((redSum + alphaSum / 2L) / alphaSum);
        int green = alphaSum == 0L ? 0 : (int) ((greenSum + alphaSum / 2L) / alphaSum);
        int blue = alphaSum == 0L ? 0 : (int) ((blueSum + alphaSum / 2L) / alphaSum);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static void bleedTransparentPixelColors(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] current = pixels.clone();

        for (int pass = 0; pass < TRANSPARENT_COLOR_BLEED_PASSES; pass++) {
            int[] next = current.clone();
            boolean changed = bleedTransparentPixelPass(current, next, width, height);

            current = next;
            if (!changed) {
                break;
            }
        }

        System.arraycopy(current, 0, pixels, 0, pixels.length);
    }

    private static boolean bleedTransparentPixelPass(int[] current, int[] next, int width, int height) {
        boolean changed = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                changed |= bleedTransparentPixel(current, next, width, height, x, y);
            }
        }
        return changed;
    }

    private static boolean bleedTransparentPixel(int[] current, int[] next, int width, int height, int x, int y) {
        int index = y * width + x;
        if (alpha(current[index]) != 0) {
            return false;
        }

        ColorSample sample = sampleNeighborColors(current, width, height, x, y);
        if (sample.weightSum == 0L) {
            return false;
        }

        int red = (int) ((sample.redSum + sample.weightSum / 2L) / sample.weightSum);
        int green = (int) ((sample.greenSum + sample.weightSum / 2L) / sample.weightSum);
        int blue = (int) ((sample.blueSum + sample.weightSum / 2L) / sample.weightSum);
        next[index] = red << 16 | green << 8 | blue;
        return true;
    }

    private static ColorSample sampleNeighborColors(int[] current, int width, int height, int x, int y) {
        ColorSample sample = new ColorSample();
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            int neighborY = y + offsetY;
            if (!isInside(neighborY, height)) {
                continue;
            }
            sampleNeighborRow(current, width, x, offsetY, neighborY, sample);
        }
        return sample;
    }

    private static void sampleNeighborRow(int[] current, int width, int x, int offsetY,
                                          int neighborY, ColorSample sample) {
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            if (offsetX == 0 && offsetY == 0) {
                continue;
            }

            int neighborX = x + offsetX;
            if (!isInside(neighborX, width)) {
                continue;
            }
            sample.add(current[neighborY * width + neighborX]);
        }
    }

    private static boolean isInside(int value, int limit) {
        return value >= 0 && value < limit;
    }

    private static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    private static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    private static int blue(int argb) {
        return argb & 0xFF;
    }

    private static final class ColorSample {
        private long redSum;
        private long greenSum;
        private long blueSum;
        private long weightSum;

        private void add(int argb) {
            int neighborAlpha = alpha(argb);
            int neighborColor = argb & 0x00FFFFFF;
            if (neighborAlpha == 0 && neighborColor == 0) {
                return;
            }

            int weight = Math.max(1, neighborAlpha);
            this.redSum += (long) red(argb) * weight;
            this.greenSum += (long) green(argb) * weight;
            this.blueSum += (long) blue(argb) * weight;
            this.weightSum += weight;
        }
    }
}
