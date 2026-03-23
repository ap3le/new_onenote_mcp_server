package com.onenote.parser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SAX ContentHandler that extracts page structures from Tika's XHTML output
 * of OneNote .one files.
 *
 * Handles two output patterns:
 *   1. Div-based: each page wrapped in a top-level div, headings for titles
 *   2. Heading-based: no wrapping divs, each heading starts a new page
 *
 * If neither pattern is detected, all body content becomes a single page
 * with the first line used as the title.
 */
class OneNotePageContentHandler extends DefaultHandler {

    private final List<OneNotePage> pages = new ArrayList<>();
    private boolean inBody = false;
    private int divDepth = 0;
    private boolean isHeading = false;
    private boolean hasTopLevelDivs = false;
    private boolean titleCaptured = false;
    private int pageIndex = 0;

    private StringBuilder currentTitle = new StringBuilder();
    private StringBuilder currentContent = new StringBuilder();

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attrs) {
        String tag = normalizeTag(localName, qName);

        if ("body".equals(tag)) {
            inBody = true;
            return;
        }
        if (!inBody) return;

        // Top-level <div> marks a page boundary
        if ("div".equals(tag)) {
            divDepth++;
            if (divDepth == 1) {
                hasTopLevelDivs = true;
                saveCurrentPage();
                titleCaptured = false;
            }
        }

        // Headings serve as page titles (first heading per page)
        if (isHeadingTag(tag)) {
            isHeading = true;
            // In heading-only mode (no divs), a new heading = new page
            if (!hasTopLevelDivs && titleCaptured) {
                saveCurrentPage();
                titleCaptured = false;
            }
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) {
        if (!inBody) return;

        String text = new String(ch, start, length);

        if (isHeading && !titleCaptured) {
            currentTitle.append(text);
        } else {
            currentContent.append(text);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        String tag = normalizeTag(localName, qName);

        if ("body".equals(tag)) {
            saveCurrentPage();
            inBody = false;
            return;
        }
        if (!inBody) return;

        // End of top-level div = end of page
        if ("div".equals(tag)) {
            if (divDepth == 1) {
                saveCurrentPage();
            }
            divDepth = Math.max(0, divDepth - 1);
        }

        if (isHeadingTag(tag)) {
            isHeading = false;
            titleCaptured = true;
        }

        // Newline after block-level elements (but not inside a heading being captured)
        if (isBlockTag(tag) && !isHeading) {
            if (titleCaptured || !isHeadingTag(tag)) {
                currentContent.append("\n");
            }
        }
    }

    private void saveCurrentPage() {
        String title = currentTitle.toString().trim();
        String content = currentContent.toString().trim();

        if (title.isEmpty() && content.isEmpty()) return;

        pageIndex++;

        // If no explicit heading was found, use the first line as the title
        if (title.isEmpty() && !content.isEmpty()) {
            int nlIdx = content.indexOf('\n');
            if (nlIdx > 0) {
                title = content.substring(0, nlIdx).trim();
                content = content.substring(nlIdx + 1).trim();
            } else {
                title = content;
                content = "";
            }
        }

        pages.add(new OneNotePage(pageIndex, title, content));

        currentTitle = new StringBuilder();
        currentContent = new StringBuilder();
        titleCaptured = false;
    }

    private String normalizeTag(String localName, String qName) {
        String tag = (localName != null && !localName.isEmpty()) ? localName : qName;
        if (tag == null) return "";
        // Strip namespace prefix (e.g. "xhtml:div" -> "div")
        int colon = tag.lastIndexOf(':');
        if (colon >= 0) tag = tag.substring(colon + 1);
        return tag.toLowerCase();
    }

    private boolean isHeadingTag(String tag) {
        return tag.length() == 2 && tag.charAt(0) == 'h'
                && tag.charAt(1) >= '1' && tag.charAt(1) <= '6';
    }

    private boolean isBlockTag(String tag) {
        return "p".equals(tag) || "br".equals(tag) || isHeadingTag(tag);
    }

    public List<OneNotePage> getPages() {
        return Collections.unmodifiableList(pages);
    }
}
