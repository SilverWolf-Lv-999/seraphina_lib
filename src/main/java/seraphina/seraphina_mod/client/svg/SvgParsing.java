package seraphina.seraphina_mod.client.svg;

import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SvgParsing {
    static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?");

    private SvgParsing() {
    }

    static List<Float> parseNumberList(String value) {
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

    static float readLength(Element element, String attribute, float defaultValue) {
        if (!element.hasAttribute(attribute)) {
            return defaultValue;
        }
        return parseFloat(element.getAttribute(attribute), defaultValue);
    }

    static float parseFloat(String value, float defaultValue) {
        if (value == null) {
            return defaultValue;
        }

        Matcher matcher = NUMBER_PATTERN.matcher(value.trim());
        if (!matcher.find()) {
            return defaultValue;
        }

        return Float.parseFloat(matcher.group());
    }

    static float[] parseDashArray(String value, float[] fallback) {
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

    static String tagName(Element element) {
        String localName = element.getLocalName();
        return (localName != null ? localName : element.getTagName()).toLowerCase(Locale.ROOT);
    }

    static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
