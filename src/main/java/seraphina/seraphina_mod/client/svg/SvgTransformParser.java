package seraphina.seraphina_mod.client.svg;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SvgTransformParser {
    private static final Pattern TRANSFORM_PATTERN = Pattern.compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)");

    private SvgTransformParser() {
    }

    /**
     * Applies SVG transform commands in source order, matching how nested SVG
     * groups compose their local coordinate systems.
     */
    static AffineTransform parseTransform(String value) {
        AffineTransform transform = new AffineTransform();
        if (value == null || value.isBlank()) {
            return transform;
        }

        Matcher matcher = TRANSFORM_PATTERN.matcher(value);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            List<Float> numbers = SvgParsing.parseNumberList(matcher.group(2));
            applyCommand(transform, type, numbers);
        }

        return transform;
    }

    private static void applyCommand(AffineTransform transform, String type, List<Float> numbers) {
        switch (type) {
            case "matrix" -> applyMatrix(transform, numbers);
            case "translate" -> applyTranslate(transform, numbers);
            case "scale" -> applyScale(transform, numbers);
            case "rotate" -> applyRotate(transform, numbers);
            case "skewx" -> applySkewX(transform, numbers);
            case "skewy" -> applySkewY(transform, numbers);
            default -> {
            }
        }
    }

    private static void applyMatrix(AffineTransform transform, List<Float> numbers) {
        if (numbers.size() < 6) {
            return;
        }
        float scaleX = numbers.get(0);
        float shearY = numbers.get(1);
        float shearX = numbers.get(2);
        float scaleY = numbers.get(3);
        float translateX = numbers.get(4);
        float translateY = numbers.get(5);
        transform.concatenate(new AffineTransform(scaleX, shearY, shearX, scaleY, translateX, translateY));
    }

    private static void applyTranslate(AffineTransform transform, List<Float> numbers) {
        if (numbers.isEmpty()) {
            return;
        }
        float translateX = numbers.get(0);
        float translateY = numbers.size() >= 2 ? numbers.get(1) : 0.0F;
        transform.translate(translateX, translateY);
    }

    private static void applyScale(AffineTransform transform, List<Float> numbers) {
        if (numbers.isEmpty()) {
            return;
        }
        float scaleX = numbers.get(0);
        float scaleY = numbers.size() >= 2 ? numbers.get(1) : scaleX;
        transform.scale(scaleX, scaleY);
    }

    private static void applyRotate(AffineTransform transform, List<Float> numbers) {
        if (numbers.isEmpty()) {
            return;
        }
        double radians = Math.toRadians(numbers.get(0));
        if (numbers.size() < 3) {
            transform.rotate(radians);
            return;
        }
        float centerX = numbers.get(1);
        float centerY = numbers.get(2);
        transform.rotate(radians, centerX, centerY);
    }

    private static void applySkewX(AffineTransform transform, List<Float> numbers) {
        if (numbers.isEmpty()) {
            return;
        }
        float degrees = numbers.get(0);
        transform.shear(Math.tan(Math.toRadians(degrees)), 0.0D);
    }

    private static void applySkewY(AffineTransform transform, List<Float> numbers) {
        if (numbers.isEmpty()) {
            return;
        }
        float degrees = numbers.get(0);
        transform.shear(0.0D, Math.tan(Math.toRadians(degrees)));
    }
}
