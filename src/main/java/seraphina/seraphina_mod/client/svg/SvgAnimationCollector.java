package seraphina.seraphina_mod.client.svg;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class SvgAnimationCollector {
    private SvgAnimationCollector() {
    }

    /**
     * Finds supported SVG animation tags and optionally freezes paint
     * animations to a fixed color for callers that only want geometry motion.
     */
    static List<SvgAnimation> collect(Document document, boolean paintAnimationsEnabled, Color disabledPaintAnimationColor) {
        List<SvgAnimation> result = new ArrayList<>();
        collect(document.getDocumentElement(), result, paintAnimationsEnabled, disabledPaintAnimationColor);
        return result;
    }

    private static void collect(Element element, List<SvgAnimation> result, boolean paintAnimationsEnabled,
                                Color disabledPaintAnimationColor) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element childElement)) {
                continue;
            }
            collectChild(element, childElement, result, paintAnimationsEnabled, disabledPaintAnimationColor);
        }
    }

    private static void collectChild(Element parent, Element child, List<SvgAnimation> result,
                                     boolean paintAnimationsEnabled, Color disabledPaintAnimationColor) {
        String tagName = SvgParsing.tagName(child);
        if ("animate".equals(tagName)) {
            collectAttributeAnimation(parent, child, result, paintAnimationsEnabled, disabledPaintAnimationColor);
            return;
        }
        if ("animatetransform".equals(tagName)) {
            SvgAnimation.fromAnimateTransform(parent, child).ifPresent(result::add);
            return;
        }
        collect(child, result, paintAnimationsEnabled, disabledPaintAnimationColor);
    }

    private static void collectAttributeAnimation(Element parent, Element animationElement, List<SvgAnimation> result,
                                                  boolean paintAnimationsEnabled, Color disabledPaintAnimationColor) {
        SvgAnimation.fromAnimate(parent, animationElement)
                .ifPresent(animation -> addAttributeAnimation(animation, result, paintAnimationsEnabled, disabledPaintAnimationColor));
    }

    private static void addAttributeAnimation(SvgAnimation animation, List<SvgAnimation> result,
                                              boolean paintAnimationsEnabled, Color disabledPaintAnimationColor) {
        if (!paintAnimationsEnabled && SvgStyleResolver.isPaintAnimationAttribute(animation.attributeName())) {
            applyDisabledPaintColor(animation, disabledPaintAnimationColor);
            return;
        }
        result.add(animation);
    }

    private static void applyDisabledPaintColor(SvgAnimation animation, Color disabledPaintAnimationColor) {
        if (disabledPaintAnimationColor != null) {
            animation.target().setAttribute(animation.attributeName(), SvgStyleResolver.toSvgColor(disabledPaintAnimationColor));
        }
    }
}
