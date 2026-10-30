package com.stelody.collector.push;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public final class YouTubePushFeed {
  public static final int MAX_BYTES = 256 * 1024;
  private static final String ATOM = "http://www.w3.org/2005/Atom";
  private static final String YT = "http://www.youtube.com/xml/schemas/2015";

  private YouTubePushFeed() {}

  public static Map<String, Instant> parse(byte[] body, String channel) {
    if (body.length == 0 || body.length > MAX_BYTES) throw invalid();
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new DefaultHandler() {
            @Override
            public void error(org.xml.sax.SAXParseException e) throws SAXException {
              throw e;
            }

            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws SAXException {
              throw e;
            }
          });
      var root = builder.parse(new ByteArrayInputStream(body)).getDocumentElement();
      if (!ATOM.equals(root.getNamespaceURI()) || !"feed".equals(root.getLocalName()))
        throw invalid();
      var entries = root.getElementsByTagNameNS(ATOM, "entry");
      if (entries.getLength() > 50) throw invalid();
      var result = new LinkedHashMap<String, Instant>();
      for (int n = 0; n < entries.getLength(); n++) {
        var entry = (Element) entries.item(n);
        if (entry.getParentNode() != root) throw invalid();
        String id = text(entry, YT, "videoId");
        if (!id.matches("[A-Za-z0-9_-]{11}") || !channel.equals(text(entry, YT, "channelId")))
          throw invalid();
        var updated = Instant.parse(text(entry, ATOM, "updated"));
        result.merge(id, updated, (a, b) -> a.isAfter(b) ? a : b);
      }
      return result;
    } catch (Exception e) {
      throw invalid();
    }
  }

  private static String text(Element entry, String namespace, String local) {
    var values = entry.getElementsByTagNameNS(namespace, local);
    if (values.getLength() != 1 || values.item(0).getParentNode() != entry) throw invalid();
    return values.item(0).getTextContent().strip();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("INVALID_YOUTUBE_PUSH_FEED");
  }
}
