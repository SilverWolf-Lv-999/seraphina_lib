package seraphina.seraphina_mod.client.svg;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

final class SvgDocumentLoader {
    private SvgDocumentLoader() {
    }

    static Document load(ResourceLocation svgLocation) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "https://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "https://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "https://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "https://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setAttributeIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        try (InputStream stream = Minecraft.getInstance().getResourceManager()
                .getResource(svgLocation)
                .orElseThrow(() -> new IllegalStateException("SVG resource not found: " + svgLocation))
                .open();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return factory.newDocumentBuilder().parse(new InputSource(reader));
        }
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (ParserConfigurationException | IllegalArgumentException | UnsupportedOperationException ignored) {
        }
    }

    private static void setAttributeIfSupported(DocumentBuilderFactory factory, String name, String value) {
        try {
            factory.setAttribute(name, value);
        } catch (IllegalArgumentException ignored) {
        }
    }
}
