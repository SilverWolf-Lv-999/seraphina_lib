package seraphina.seraphina_lib.client.svg;

import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SvgTransformParser {
    private static final Pattern TRANSFORM_PATTERN = Pattern.compile("([a-zA-Z]+)\\s*\\(([^)]*)\\)");

    private SvgTransformParser() {
    }

    static AffineTransform parse(String value) {
        AffineTransform transform = new AffineTransform();
        if (value == null || value.isBlank()) {
            return transform;
        }

        Matcher matcher = TRANSFORM_PATTERN.matcher(value);
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            List<Float> numbers = SvgParsing.parseNumberList(matcher.group(2));
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
}
