package seraphina.seraphina_mod.client.svg;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SvgStyleResolver {
    private static final Pattern STYLE_CLASS_PATTERN = Pattern.compile("\\.([a-zA-Z0-9_-]+)\\s*\\{([^}]*)}", Pattern.DOTALL);

    private SvgStyleResolver() {
    }

    static SvgStyle resolve(SvgStyle inherited, Element element, Map<String, Map<String, String>> classStyles) {
        Map<String, String> style = resolveStyleMap(element, classStyles);
        Color nextFill = parseColor(attribute(element, style, "fill"), inherited.fill());
        Color nextStroke = parseColor(attribute(element, style, "stroke"), inherited.stroke());
        float nextStrokeWidth = SvgParsing.parseFloat(attribute(element, style, "stroke-width"), inherited.strokeWidth());
        int nextLineCap = element.hasAttribute("stroke-linecap") || style.containsKey("stroke-linecap")
                ? parseLineCap(attribute(element, style, "stroke-linecap"))
                : inherited.lineCap();
        int nextLineJoin = element.hasAttribute("stroke-linejoin") || style.containsKey("stroke-linejoin")
                ? parseLineJoin(attribute(element, style, "stroke-linejoin"))
                : inherited.lineJoin();
        float nextOpacity = SvgParsing.parseFloat(attribute(element, style, "opacity"), inherited.opacity());
        float[] nextStrokeDashArray = SvgParsing.parseDashArray(attribute(element, style, "stroke-dasharray"), inherited.strokeDashArray());
        float nextStrokeDashOffset = SvgParsing.parseFloat(attribute(element, style, "stroke-dashoffset"), inherited.strokeDashOffset());

        return new SvgStyle(nextFill, nextStroke, nextStrokeWidth, nextLineCap, nextLineJoin, nextOpacity,
                nextStrokeDashArray, nextStrokeDashOffset);
    }

    static Map<String, Map<String, String>> collectClassStyles(Document document) {
        Map<String, Map<String, String>> result = new HashMap<>();
        collectClassStyles(document.getDocumentElement(), result);
        return result;
    }

    static Color applyOpacity(Color color, float opacity) {
        int alpha = SvgParsing.clamp(Math.round(color.getAlpha() * Math.max(0.0F, Math.min(1.0F, opacity))));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    static boolean isPaintAnimationAttribute(String attributeName) {
        if (attributeName == null) {
            return false;
        }

        return switch (attributeName.trim().toLowerCase(Locale.ROOT)) {
            case "fill", "stroke", "opacity", "fill-opacity", "stroke-opacity", "stop-color", "stop-opacity" -> true;
            default -> false;
        };
    }

    static String toSvgColor(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static void collectClassStyles(Element element, Map<String, Map<String, String>> result) {
        if ("style".equals(SvgParsing.tagName(element))) {
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
            Matcher matcher = SvgParsing.NUMBER_PATTERN.matcher(color);
            int[] components = new int[3];
            int count = 0;
            while (matcher.find() && count < components.length) {
                components[count++] = SvgParsing.clamp(Math.round(Float.parseFloat(matcher.group())));
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
}
