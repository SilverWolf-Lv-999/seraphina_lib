package seraphina.seraphina_lib.client.svg;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

record SvgAnimation(Element target, String attributeName, String baseValue, String fromValue,
                    String toValue, List<String> values, float begin, float duration,
                    boolean repeatForever, boolean additiveSum, boolean transformAnimation,
                    String transformType) {
    static Optional<SvgAnimation> fromAnimate(Element target, Element animation) {
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

    static Optional<SvgAnimation> fromAnimateTransform(Element target, Element animation) {
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

    void restoreBaseValue() {
        if (baseValue == null || baseValue.isBlank()) {
            target.removeAttribute(attributeName);
        } else {
            target.setAttribute(attributeName, baseValue);
        }
    }

    void apply(float timeSeconds) {
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
        List<Float> fromNumbers = SvgParsing.parseNumberList(from);
        List<Float> toNumbers = SvgParsing.parseNumberList(to);
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

        return SvgParsing.parseFloat(trimmed, defaultValue) * multiplier;
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
