package seraphina.seraphina_mod.client.svg;

import org.w3c.dom.Element;

import java.awt.BasicStroke;
import java.awt.Color;
import java.util.Map;

record SvgStyle(Color fill, Color stroke, float strokeWidth, int lineCap, int lineJoin, float opacity,
                float[] strokeDashArray, float strokeDashOffset) {
    static SvgStyle root() {
        return new SvgStyle(Color.BLACK, null, 1.0F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0F, null, 0.0F);
    }

    SvgStyle resolve(Element element, Map<String, Map<String, String>> classStyles) {
        return SvgStyleResolver.resolve(this, element, classStyles);
    }
}
