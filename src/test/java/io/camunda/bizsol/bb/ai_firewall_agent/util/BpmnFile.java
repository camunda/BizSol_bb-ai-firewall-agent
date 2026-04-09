package io.camunda.bizsol.bb.ai_firewall_agent.util;

import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Test utility for loading a BPMN file and performing XPath-driven modifications before deployment.
 * Supports a fluent API for changing element attributes, removing elements, and appending child
 * elements. Namespace prefixes are resolved automatically from the root element's namespace
 * declarations.
 *
 * <p>Example:
 *
 * <pre>{@code
 * BpmnModelInstance model =
 *     new BpmnFile(BPMN_SOURCE)
 *         .changeProperties(
 *             "//zeebe:input[@target='provider.type']", "source", "bedrock")
 *         .removeElement(
 *             "//zeebe:input[@target='provider.openaiCompatible.timeouts.timeout']")
 *         .asBpmnModel();
 * }</pre>
 */
public class BpmnFile {

    private final Document document;
    private final XPath xpath;

    /**
     * Load and parse a BPMN file. Namespace declarations from the root element are registered
     * automatically so that XPath expressions can use their prefixes (e.g. {@code bpmn:}, {@code
     * zeebe:}).
     *
     * @param path path to the BPMN file
     */
    public BpmnFile(Path path) {
        try {
            var dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            this.document = dbf.newDocumentBuilder().parse(path.toFile());
            this.xpath = buildXPath(document.getDocumentElement());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load BPMN from " + path, e);
        }
    }

    private static XPath buildXPath(Element root) {
        Map<String, String> ns = new LinkedHashMap<>();
        NamedNodeMap attrs = root.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node attr = attrs.item(i);
            String name = attr.getNodeName();
            if (name.startsWith("xmlns:")) {
                ns.put(name.substring(6), attr.getNodeValue());
            }
        }
        XPath xp = XPathFactory.newInstance().newXPath();
        xp.setNamespaceContext(
                new NamespaceContext() {
                    @Override
                    public String getNamespaceURI(String prefix) {
                        return ns.getOrDefault(prefix, XMLConstants.NULL_NS_URI);
                    }

                    @Override
                    public String getPrefix(String namespaceURI) {
                        return ns.entrySet().stream()
                                .filter(e -> e.getValue().equals(namespaceURI))
                                .map(Map.Entry::getKey)
                                .findFirst()
                                .orElse(null);
                    }

                    @Override
                    public Iterator<String> getPrefixes(String namespaceURI) {
                        return ns.entrySet().stream()
                                .filter(e -> e.getValue().equals(namespaceURI))
                                .map(Map.Entry::getKey)
                                .iterator();
                    }
                });
        return xp;
    }

    /**
     * Set one or more attributes on every element matched by the given XPath expression.
     *
     * @param xpathExpr XPath expression selecting the target element(s)
     * @param keyValues alternating attribute name / value pairs (e.g. {@code "source", "bedrock"})
     * @return this instance for chaining
     */
    public BpmnFile changeProperties(String xpathExpr, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "keyValues must contain an even number of entries (name/value pairs)");
        }
        try {
            NodeList nodes =
                    (NodeList) xpath.compile(xpathExpr).evaluate(document, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element el = (Element) nodes.item(i);
                for (int j = 0; j < keyValues.length; j += 2) {
                    el.setAttribute(keyValues[j], keyValues[j + 1]);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("XPath error in changeProperties: " + xpathExpr, e);
        }
        return this;
    }

    /**
     * Remove every element matched by the given XPath expression from the document.
     *
     * @param xpathExpr XPath expression selecting the element(s) to remove
     * @return this instance for chaining
     */
    public BpmnFile removeElement(String xpathExpr) {
        try {
            NodeList nodes =
                    (NodeList) xpath.compile(xpathExpr).evaluate(document, XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                node.getParentNode().removeChild(node);
            }
        } catch (Exception e) {
            throw new RuntimeException("XPath error in removeElement: " + xpathExpr, e);
        }
        return this;
    }

    /**
     * Append a new child element to every parent element matched by the given XPath expression. The
     * element's namespace URI is resolved from the prefix declared in the document root.
     *
     * @param parentXpathExpr XPath expression selecting the parent element(s)
     * @param qualifiedName qualified element name including namespace prefix (e.g. {@code
     *     "zeebe:input"})
     * @param keyValues alternating attribute name / value pairs for the new element
     * @return this instance for chaining
     */
    public BpmnFile appendElement(
            String parentXpathExpr, String qualifiedName, String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "keyValues must contain an even number of entries (name/value pairs)");
        }
        String nsURI = null;
        int colon = qualifiedName.indexOf(':');
        if (colon > 0) {
            nsURI = xpath.getNamespaceContext().getNamespaceURI(qualifiedName.substring(0, colon));
        }
        final String resolvedNsURI = nsURI;
        try {
            NodeList parents =
                    (NodeList)
                            xpath.compile(parentXpathExpr)
                                    .evaluate(document, XPathConstants.NODESET);
            for (int i = 0; i < parents.getLength(); i++) {
                Element parent = (Element) parents.item(i);
                Element newEl =
                        (resolvedNsURI != null && !resolvedNsURI.isEmpty())
                                ? document.createElementNS(resolvedNsURI, qualifiedName)
                                : document.createElement(qualifiedName);
                for (int j = 0; j < keyValues.length; j += 2) {
                    newEl.setAttribute(keyValues[j], keyValues[j + 1]);
                }
                parent.appendChild(newEl);
            }
        } catch (Exception e) {
            throw new RuntimeException("XPath error in appendElement: " + parentXpathExpr, e);
        }
        return this;
    }

    /**
     * Serialize the modified document and parse it as a {@link BpmnModelInstance}.
     *
     * @return a fully parsed Camunda BPMN model instance ready for deployment
     */
    public BpmnModelInstance asBpmnModel() {
        try {
            var tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            var out = new ByteArrayOutputStream();
            tf.newTransformer().transform(new DOMSource(document), new StreamResult(out));
            return Bpmn.readModelFromStream(new ByteArrayInputStream(out.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize BPMN model", e);
        }
    }
}
