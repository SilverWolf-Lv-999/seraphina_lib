package seraphina.seraphina_lib.client.svg;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Map;

final class SvgRenderer {
    private SvgRenderer() {
    }

    static BufferedImage render(Element root, Rectangle2D.Float viewBox,
                                Map<String, Map<String, String>> classStyles,
                                int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();

        try {
            applyQualityRenderingHints(graphics);
            graphics.scale(width / viewBox.width, height / viewBox.height);
            graphics.translate(-viewBox.x, -viewBox.y);
            renderElement(graphics, root, SvgStyle.root(), classStyles);
        } finally {
            graphics.dispose();
        }

        return image;
    }

    private static void renderElement(Graphics2D graphics, Element element, SvgStyle inheritedStyle,
                                      Map<String, Map<String, String>> classStyles) {
        SvgStyle style = inheritedStyle.resolve(element, classStyles);
        AffineTransform oldTransform = graphics.getTransform();
        graphics.transform(SvgTransformParser.parse(element.getAttribute("transform")));

        try {
            Shape shape = SvgShapeParser.parseShape(element);
            if (shape != null) {
                paintShape(graphics, shape, style);
            }

            NodeList children = element.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child instanceof Element childElement) {
                    renderElement(graphics, childElement, style, classStyles);
                }
            }
        } finally {
            graphics.setTransform(oldTransform);
        }
    }

    private static void paintShape(Graphics2D graphics, Shape shape, SvgStyle style) {
        if (style.fill() != null) {
            graphics.setColor(SvgStyleResolver.applyOpacity(style.fill(), style.opacity()));
            graphics.fill(shape);
        }

        if (style.stroke() != null && style.strokeWidth() > 0.0F) {
            graphics.setColor(SvgStyleResolver.applyOpacity(style.stroke(), style.opacity()));
            float[] dashArray = style.strokeDashArray();
            graphics.setStroke(dashArray == null
                    ? new BasicStroke(style.strokeWidth(), style.lineCap(), style.lineJoin())
                    : new BasicStroke(style.strokeWidth(), style.lineCap(), style.lineJoin(), 10.0F, dashArray, style.strokeDashOffset()));
            graphics.draw(shape);
        }
    }

    private static void applyQualityRenderingHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }
}
