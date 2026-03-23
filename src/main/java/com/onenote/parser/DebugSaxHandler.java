package com.onenote.parser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Debug SAX handler that prints ALL events to see Tika's full XHTML structure.
 */
class DebugSaxHandler extends DefaultHandler {
    private boolean inBody = false;
    private int depth = 0;

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
        String tag = localName.isEmpty() ? qName : localName;
        if ("body".equals(tag)) inBody = true;
        if (!inBody) return;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("<").append(tag);
        for (int i = 0; i < attrs.getLength(); i++) {
            String attrName = attrs.getLocalName(i).isEmpty() ? attrs.getQName(i) : attrs.getLocalName(i);
            sb.append(" ").append(attrName).append("=\"").append(attrs.getValue(i)).append("\"");
        }
        sb.append(">");
        System.out.println(sb.toString());
        depth++;
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String tag = localName.isEmpty() ? qName : localName;
        if (!inBody) return;
        depth--;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append("</").append(tag).append(">");
        System.out.println(sb.toString());
        if ("body".equals(tag)) inBody = false;
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (!inBody) return;
        String text = new String(ch, start, length).trim();
        if (!text.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append("TEXT: \"").append(text).append("\"");
            System.out.println(sb.toString());
        }
    }
}
