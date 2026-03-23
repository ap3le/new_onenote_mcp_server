package com.onenote.parser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simple SAX handler that collects all paragraph texts from Tika's XHTML output.
 * The deduplication of OneNote revision data is done later in OneNoteRevisionDeduplicator.
 */
class OneNoteParagraphCollector extends DefaultHandler {

    private final List<String> paragraphs = new ArrayList<>();
    private boolean inBody = false;
    private boolean inParagraph = false;
    private StringBuilder currentText = new StringBuilder();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
        String tag = normalizeTag(localName, qName);

        if ("body".equals(tag)) {
            inBody = true;
            return;
        }
        if (!inBody) return;

        if ("p".equals(tag) || isHeadingTag(tag)) {
            inParagraph = true;
            currentText.setLength(0);
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (!inBody) return;
        if (inParagraph) {
            currentText.append(ch, start, length);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String tag = normalizeTag(localName, qName);

        if ("body".equals(tag)) {
            inBody = false;
            return;
        }
        if (!inBody) return;

        if ("p".equals(tag) || isHeadingTag(tag)) {
            inParagraph = false;
            String text = currentText.toString().trim();
            if (!text.isEmpty()) {
                paragraphs.add(text);
            }
        }
    }

    private String normalizeTag(String localName, String qName) {
        String tag = (localName != null && !localName.isEmpty()) ? localName : qName;
        if (tag == null) return "";
        int colon = tag.lastIndexOf(':');
        if (colon >= 0) tag = tag.substring(colon + 1);
        return tag.toLowerCase();
    }

    private boolean isHeadingTag(String tag) {
        return tag.length() == 2 && tag.charAt(0) == 'h'
                && tag.charAt(1) >= '1' && tag.charAt(1) <= '6';
    }

    public List<String> getParagraphs() {
        return Collections.unmodifiableList(paragraphs);
    }
}
