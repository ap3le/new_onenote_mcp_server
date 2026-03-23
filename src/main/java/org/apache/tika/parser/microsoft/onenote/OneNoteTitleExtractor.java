package org.apache.tika.parser.microsoft.onenote;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.tika.exception.TikaException;

/**
 * Helper to extract page titles directly from the OneNote binary format
 * by accessing Tika's package-private OneNote classes.
 */
public class OneNoteTitleExtractor {

    /**
     * Extract all unique page titles from a .one file by walking the internal
     * file node tree and looking for CachedTitleString properties.
     */
    public static List<String> extractTitles(File file) throws IOException, TikaException {
        Set<String> titleSet = new LinkedHashSet<>();

        OneNoteDirectFileResource dif = new OneNoteDirectFileResource(file);
        try {
            OneNoteParser parser = new OneNoteParser();
            OneNoteDocument doc = parser.createOneNoteDocumentFromDirectFileResource(dif);

            // Walk the root file node list recursively
            if (doc.root != null) {
                walkFileNodeList(doc, dif, doc.root, titleSet);
            }
        } finally {
            dif.close();
        }

        return new ArrayList<>(titleSet);
    }

    private static void walkFileNodeList(OneNoteDocument doc, OneNoteDirectFileResource dif,
                                          FileNodeList nodeList, Set<String> titles)
            throws IOException, TikaException {
        if (nodeList == null || nodeList.getChildren() == null) return;

        for (FileNode node : nodeList.getChildren()) {
            if (node == null) continue;

            // Check property set on this node
            if (node.propertySet != null) {
                checkPropertySet(doc, dif, node.propertySet, titles);
            }

            // Recurse into child file node list
            if (node.childFileNodeList != null) {
                walkFileNodeList(doc, dif, node.childFileNodeList, titles);
            }

            // Also dereference sub-nodes via subType if possible
            if (node.subType != null) {
                // subType references other parts of the tree; skip for now
            }
        }
    }

    private static void checkPropertySet(OneNoteDocument doc, OneNoteDirectFileResource dif,
                                           PropertySet ps, Set<String> titles)
            throws IOException, TikaException {
        if (ps.getRgPridsData() == null) return;

        for (PropertyValue pv : ps.getRgPridsData()) {
            OneNotePropertyId propId = pv.getPropertyId();
            if (propId == null) continue;

            OneNotePropertyEnum propEnum = propId.getPropertyEnum();
            if (propEnum == null) continue;

            if (propEnum == OneNotePropertyEnum.CachedTitleString ||
                propEnum == OneNotePropertyEnum.CachedTitleStringFromPage) {
                String title = readStringProperty(dif, pv);
                if (title != null && !title.isEmpty()) {
                    titles.add(title);
                }
            }

            // Recurse into nested property sets
            if (pv.getPropertySet() != null) {
                checkPropertySet(doc, dif, pv.getPropertySet(), titles);
            }
        }
    }

    private static String readStringProperty(OneNoteDirectFileResource dif,
                                              PropertyValue pv) throws IOException {
        FileChunkReference rawData = pv.getRawData();
        if (rawData == null) return null;

        try {
            long pos = rawData.getStp();
            long len = rawData.getCb();
            if (pos >= 0 && len > 0 && len < 10000) {
                long savedPos = dif.position();
                dif.position(pos);
                ByteBuffer buf = ByteBuffer.allocate((int) len);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                dif.read(buf);
                buf.flip();
                dif.position(savedPos);

                // OneNote strings are UTF-16LE
                return StandardCharsets.UTF_16LE.decode(buf).toString().trim();
            }
        } catch (Exception e) {
            // Fall through
        }

        return null;
    }
}
