package org.sunbird.common;

import javax.xml.parsers.SAXParserFactory;

/**
 * XXE-hardened XML parser factory builder. Disables external entity loading and DTD processing
 * to prevent XML External Entity (XXE) attacks and billion-laughs DOS.
 */
public class SecureXmlParser {

    /**
     * Creates a namespace-aware SAXParserFactory with XXE protections enabled.
     * Disables external general entities, external parameter entities, DTD declarations, and external DTD loading.
     *
     * @return hardened SAXParserFactory
     * @throws Exception if parser factory cannot be configured or XXE features cannot be set
     */
    public static SAXParserFactory newSecureFactory() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory;
    }
}
