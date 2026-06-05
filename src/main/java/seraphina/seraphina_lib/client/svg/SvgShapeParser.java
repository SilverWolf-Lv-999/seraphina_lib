package seraphina.seraphina_lib.client.svg;

import org.w3c.dom.Element;

import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

final class SvgShapeParser {
    private SvgShapeParser() {
    }

    static Shape parseShape(Element element) {
        return switch (SvgParsing.tagName(element)) {
            case "path" -> SvgPathParser.parse(element.getAttribute("d"));
            case "line" -> parseLine(element);
            case "rect" -> parseRect(element);
            case "circle" -> parseCircle(element);
            case "ellipse" -> parseEllipse(element);
            case "polyline" -> parsePoly(element, false);
            case "polygon" -> parsePoly(element, true);
            default -> null;
        };
    }

    private static Shape parseLine(Element element) {
        float x1 = SvgParsing.readLength(element, "x1", 0.0F);
        float y1 = SvgParsing.readLength(element, "y1", 0.0F);
        float x2 = SvgParsing.readLength(element, "x2", 0.0F);
        float y2 = SvgParsing.readLength(element, "y2", 0.0F);
        return new Line2D.Float(x1, y1, x2, y2);
    }

    private static Shape parseRect(Element element) {
        float x = SvgParsing.readLength(element, "x", 0.0F);
        float y = SvgParsing.readLength(element, "y", 0.0F);
        float width = SvgParsing.readLength(element, "width", 0.0F);
        float height = SvgParsing.readLength(element, "height", 0.0F);
        float rx = SvgParsing.readLength(element, "rx", 0.0F);
        float ry = SvgParsing.readLength(element, "ry", rx);

        if (rx > 0.0F || ry > 0.0F) {
            return new RoundRectangle2D.Float(x, y, width, height, rx * 2.0F, ry * 2.0F);
        }

        return new Rectangle2D.Float(x, y, width, height);
    }

    private static Shape parseCircle(Element element) {
        float cx = SvgParsing.readLength(element, "cx", 0.0F);
        float cy = SvgParsing.readLength(element, "cy", 0.0F);
        float radius = SvgParsing.readLength(element, "r", 0.0F);
        return new Ellipse2D.Float(cx - radius, cy - radius, radius * 2.0F, radius * 2.0F);
    }

    private static Shape parseEllipse(Element element) {
        float cx = SvgParsing.readLength(element, "cx", 0.0F);
        float cy = SvgParsing.readLength(element, "cy", 0.0F);
        float rx = SvgParsing.readLength(element, "rx", 0.0F);
        float ry = SvgParsing.readLength(element, "ry", 0.0F);
        return new Ellipse2D.Float(cx - rx, cy - ry, rx * 2.0F, ry * 2.0F);
    }

    private static Shape parsePoly(Element element, boolean closed) {
        Path2D.Float path = new Path2D.Float();
        Matcher matcher = SvgParsing.NUMBER_PATTERN.matcher(element.getAttribute("points"));
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
}
