package com.onenote.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.RecursiveParserWrapperHandler;
import org.apache.tika.sax.ToXMLContentHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Microsoft OneNote .one section files using Apache Tika.
 * Extracts individual pages with their titles and content, preserving document order.
 * Supports Hebrew and English content (UTF-8).
 *
 * Two strategies are tried in order:
 *   1. RecursiveParserWrapper — works if Tika treats pages as embedded documents
 *   2. XHTML structure analysis — parses Tika's XHTML output to detect page boundaries
 *
 * Usage:
 * <pre>
 *   OneNoteFileParser parser = new OneNoteFileParser();
 *   List&lt;OneNotePage&gt; pages = parser.parse(new File("section.one"));
 *   for (OneNotePage page : pages) {
 *       System.out.println(page.getTitle());
 *       System.out.println(page.getContent());
 *   }
 * </pre>
 */
public class OneNoteFileParser {

    /**
     * Parse a .one file and extract all pages with titles and content.
     *
     * @param file the .one file to parse
     * @return list of pages in document order
     */
    public List<OneNotePage> parse(File file) throws Exception {
        if (!file.exists()) {
            throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
        }

        // Strategy 1: RecursiveParserWrapper (pages as embedded documents)
        List<OneNotePage> pages = tryRecursiveParser(file);
        if (pages != null && !pages.isEmpty()) {
            return pages;
        }

        // Strategy 2: XHTML structure analysis
        return parseFromXhtml(file);
    }

    /**
     * Returns the raw XHTML output from Tika — useful for debugging
     * to see exactly how Tika structures the OneNote content.
     */
    public String getRawXhtml(File file) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        ToXMLContentHandler xmlHandler = new ToXMLContentHandler();
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());
        ParseContext context = new ParseContext();

        try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            parser.parse(is, xmlHandler, metadata, context);
        }

        return xmlHandler.toString();
    }

    /**
     * Returns the plain text content of the entire .one file (all pages concatenated).
     */
    public String getPlainText(File file) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler bodyHandler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());
        ParseContext context = new ParseContext();

        try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            parser.parse(is, bodyHandler, metadata, context);
        }

        return bodyHandler.toString();
    }

    /**
     * Returns document-level metadata from the .one file.
     */
    public Metadata getMetadata(File file) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler bodyHandler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());
        ParseContext context = new ParseContext();

        try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
            parser.parse(is, bodyHandler, metadata, context);
        }

        return metadata;
    }

    // ----------------------------------------------------------------
    // Strategy 1: RecursiveParserWrapper
    // ----------------------------------------------------------------

    private List<OneNotePage> tryRecursiveParser(File file) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            RecursiveParserWrapper wrapper = new RecursiveParserWrapper(parser);

            RecursiveParserWrapperHandler handler = new RecursiveParserWrapperHandler(
                new BasicContentHandlerFactory(
                    BasicContentHandlerFactory.HANDLER_TYPE.BODY, -1),
                -1);

            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getName());
            ParseContext context = new ParseContext();
            context.set(Parser.class, parser);

            try (InputStream is = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
                wrapper.parse(is, handler, metadata, context);
            }

            List<Metadata> metadataList = handler.getMetadataList();

            // First entry = container document. If only 1 entry, no embedded pages found.
            if (metadataList.size() <= 1) {
                return null;
            }

            List<OneNotePage> pages = new ArrayList<>();
            for (int i = 1; i < metadataList.size(); i++) {
                Metadata pageMeta = metadataList.get(i);
                String title = pageMeta.get(TikaCoreProperties.TITLE);
                String content = pageMeta.get("X-TIKA:content");

                if (title == null || title.isEmpty()) {
                    title = "Untitled Page " + i;
                }
                if (content == null) {
                    content = "";
                }

                pages.add(new OneNotePage(i, title.trim(), content.trim()));
            }

            return pages;
        } catch (Exception e) {
            // RecursiveParserWrapper didn't work; fall through to strategy 2
            return null;
        }
    }

    // ----------------------------------------------------------------
    // Strategy 2: XHTML structure analysis via SAX
    // ----------------------------------------------------------------

    private List<OneNotePage> parseFromXhtml(File file) throws Exception {
        String xhtml = getRawXhtml(file);
        return extractPagesFromXhtml(xhtml);
    }

    private List<OneNotePage> extractPagesFromXhtml(String xhtml) throws Exception {
        // Step 1: Collect all paragraphs from the XHTML
        OneNoteParagraphCollector collector = new OneNoteParagraphCollector();

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        // Security: prevent XXE
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(
            "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        SAXParser saxParser = factory.newSAXParser();
        org.xml.sax.XMLReader reader = saxParser.getXMLReader();
        reader.setContentHandler(collector);
        reader.setEntityResolver((publicId, systemId) ->
            new org.xml.sax.InputSource(new StringReader("")));

        reader.parse(new org.xml.sax.InputSource(new StringReader(xhtml)));

        List<String> allParagraphs = collector.getParagraphs();

        // Step 2: Deduplicate revision data — Tika outputs all revisions, we want only the latest
        List<List<String>> pageBlocks = OneNoteRevisionDeduplicator.deduplicate(allParagraphs);

        // Step 3: Convert each page block to a OneNotePage (first paragraph = title, rest = content)
        List<OneNotePage> pages = new ArrayList<>();
        for (int i = 0; i < pageBlocks.size(); i++) {
            List<String> block = pageBlocks.get(i);
            if (block.isEmpty()) continue;

            String title = block.get(0);
            StringBuilder content = new StringBuilder();
            for (int j = 1; j < block.size(); j++) {
                if (j > 1) content.append("\n");
                content.append(block.get(j));
            }

            pages.add(new OneNotePage(i + 1, title, content.toString()));
        }

        return pages;
    }
}
