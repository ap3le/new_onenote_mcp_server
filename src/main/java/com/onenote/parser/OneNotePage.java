package com.onenote.parser;

/**
 * Represents a single page extracted from a OneNote .one section file.
 */
public class OneNotePage {

    private final int pageIndex;
    private final String title;
    private final String content;

    public OneNotePage(int pageIndex, String title, String content) {
        this.pageIndex = pageIndex;
        this.title = title != null ? title : "";
        this.content = content != null ? content : "";
    }

    public int getPageIndex() {
        return pageIndex;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return String.format(
            "=== Page %d ===\nTitle: %s\nContent:\n%s\n",
            pageIndex, title, content
        );
    }
}
