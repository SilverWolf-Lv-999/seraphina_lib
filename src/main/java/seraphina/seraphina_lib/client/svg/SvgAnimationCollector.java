package seraphina.seraphina_lib.client.svg;

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
            if (child instanceof Element childElement) {
                String tagName = SvgParsing.tagName(childElement);
                if ("animate".equals(tagName)) {
                    SvgAnimation.fromAnimate(element, childElement).ifPresent(animation -> {
                        if (!paintAnimationsEnabled && SvgStyleResolver.isPaintAnimationAttribute(animation.attributeName())) {
                            if (disabledPaintAnimationColor != null) {
                                animation.target().setAttribute(animation.attributeName(), SvgStyleResolver.toSvgColor(disabledPaintAnimationColor));
                            }
                            return;
                        }
                        result.add(animation);
                    });
                } else if ("animatetransform".equals(tagName)) {
                    SvgAnimation.fromAnimateTransform(element, childElement).ifPresent(result::add);
                } else {
                    collect(childElement, result, paintAnimationsEnabled, disabledPaintAnimationColor);
                }
            }
        }
    }
}
