package com.onenote.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deduplicates OneNote revision data from Tika's output.
 *
 * Tika's OneNote parser outputs ALL revisions of each page. Each revision is a
 * complete snapshot, so the output is cumulative. The paragraph order within a
 * single revision does NOT match the visual order in OneNote.
 *
 * Key insight: the ORDER in which paragraphs are ADDED across revisions corresponds
 * to visual (top-to-bottom) order. Items that appear BEFORE surviving anchors in
 * XHTML represent items added at the BOTTOM of the page (stack-like storage).
 *
 * Algorithm per page:
 * 1. Split revisions by time-marker end boundaries
 * 2. Detect title (paragraph between date and time in rev 1)
 * 3. For each revision transition, track surviving/removed/added items
 * 4. Detect replacements via prefix matching + position-based matching
 * 5. Position new items: before-first-surviving → end; after-surviving → relative position
 */
class OneNoteRevisionDeduplicator {

    static List<List<String>> deduplicate(List<String> paragraphs) {
        if (paragraphs.isEmpty()) {
            return new ArrayList<>();
        }

        List<PageRevisions> pageGroups = splitIntoPages(paragraphs);

        List<List<String>> pages = new ArrayList<>();
        for (PageRevisions pr : pageGroups) {
            List<String> pageContent = extractPageContent(pr);
            if (!pageContent.isEmpty()) {
                pages.add(pageContent);
            }
        }

        return pages;
    }

    // ---- Internal data structure ----

    private static class PageRevisions {
        Set<String> metadata;
        String dateParagraph;
        String timeParagraph;
        List<List<String>> revisions;
    }

    // ---- Step 1: Split into per-page revision groups ----

    private static List<PageRevisions> splitIntoPages(List<String> paragraphs) {
        List<PageRevisions> result = new ArrayList<>();
        int pos = 0;

        while (pos < paragraphs.size() - 1) {
            String date = paragraphs.get(pos);
            String time = paragraphs.get(pos + 1);
            Set<String> metadata = new HashSet<>(Arrays.asList(date, time));

            List<Integer> endPositions = new ArrayList<>();
            for (int i = pos + 1; i < paragraphs.size(); i++) {
                if (paragraphs.get(i).equals(time)) {
                    endPositions.add(i);
                }
            }

            List<List<String>> revisions = new ArrayList<>();
            int blockStart = pos;

            for (int endPos : endPositions) {
                List<String> block = new ArrayList<>();
                for (int j = blockStart; j <= endPos; j++) {
                    block.add(paragraphs.get(j));
                }

                if (!revisions.isEmpty()) {
                    List<String> prev = revisions.get(revisions.size() - 1);
                    if (!isCumulativeRevision(block, prev)) {
                        break;
                    }
                }

                revisions.add(block);
                blockStart = endPos + 1;
            }

            PageRevisions pr = new PageRevisions();
            pr.metadata = metadata;
            pr.dateParagraph = date;
            pr.timeParagraph = time;
            pr.revisions = revisions;
            result.add(pr);

            pos = blockStart;
        }

        return result;
    }

    // ---- Step 2: Extract title + ordered content ----

    private static List<String> extractPageContent(PageRevisions pr) {
        if (pr.revisions.size() <= 1) {
            return new ArrayList<>();
        }

        String title = detectTitle(pr.revisions.get(1), pr.dateParagraph, pr.timeParagraph);

        // Process revisions sequentially, building ordered content via diffs
        List<String> orderedContent = new ArrayList<>();
        List<String> prevFiltered = filterContent(pr.revisions.get(0), pr.metadata, title);

        for (int i = 1; i < pr.revisions.size(); i++) {
            List<String> currFiltered = filterContent(pr.revisions.get(i), pr.metadata, title);
            orderedContent = processTransition(orderedContent, prevFiltered, currFiltered);
            prevFiltered = currFiltered;
        }

        // Verify against final revision — remove stale, add missed
        List<String> finalFiltered = filterContent(
            pr.revisions.get(pr.revisions.size() - 1), pr.metadata, title);
        Set<String> finalSet = new HashSet<>(finalFiltered);

        orderedContent.removeIf(item -> !finalSet.contains(item));

        for (String item : finalFiltered) {
            if (!orderedContent.contains(item)) {
                orderedContent.add(item);
            }
        }

        List<String> result = new ArrayList<>();
        result.add(title);
        result.addAll(orderedContent);
        return result;
    }

    // ---- Filter content: remove metadata and title from a revision ----

    private static List<String> filterContent(List<String> revision, Set<String> metadata, String title) {
        List<String> result = new ArrayList<>();
        for (String item : revision) {
            if (!metadata.contains(item) && !item.equals(title)) {
                if (!result.contains(item)) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    // ---- Process a single revision transition ----

    private static List<String> processTransition(
            List<String> orderedContent,
            List<String> prevFiltered,
            List<String> currFiltered) {

        Set<String> prevSet = new HashSet<>(prevFiltered);
        Set<String> currSet = new HashSet<>(currFiltered);

        // Classify items
        Set<String> survivingSet = new HashSet<>();
        for (String s : prevFiltered) {
            if (currSet.contains(s)) survivingSet.add(s);
        }

        List<String> removed = new ArrayList<>();
        for (String s : prevFiltered) {
            if (!currSet.contains(s) && !removed.contains(s)) removed.add(s);
        }

        List<String> added = new ArrayList<>();
        for (String s : currFiltered) {
            if (!prevSet.contains(s) && !added.contains(s)) added.add(s);
        }

        // ---- Find replacements ----
        Map<String, String> replacements = new LinkedHashMap<>();
        Set<String> usedAsReplacement = new HashSet<>();

        // Strategy 1: prefix/suffix string match
        for (String rem : removed) {
            for (String add : added) {
                if (!usedAsReplacement.contains(add)) {
                    if (add.startsWith(rem) || rem.startsWith(add)) {
                        replacements.put(rem, add);
                        usedAsReplacement.add(add);
                        break;
                    }
                }
            }
        }

        // Strategy 2: position-based match (same segment relative to surviving anchors)
        if (!survivingSet.isEmpty()) {
            for (String rem : removed) {
                if (replacements.containsKey(rem)) continue;

                String remPreceding = findPrecedingSurviving(rem, prevFiltered, survivingSet);
                String remFollowing = findFollowingSurviving(rem, prevFiltered, survivingSet);

                for (String add : added) {
                    if (usedAsReplacement.contains(add)) continue;

                    String addPreceding = findPrecedingSurviving(add, currFiltered, survivingSet);
                    String addFollowing = findFollowingSurviving(add, currFiltered, survivingSet);

                    if (Objects.equals(remPreceding, addPreceding)
                            && Objects.equals(remFollowing, addFollowing)) {
                        replacements.put(rem, add);
                        usedAsReplacement.add(add);
                        break;
                    }
                }
            }
        }

        // ---- Apply replacements and keep surviving items ----
        List<String> updated = new ArrayList<>();
        for (String item : orderedContent) {
            if (replacements.containsKey(item)) {
                updated.add(replacements.get(item));
            } else if (currSet.contains(item)) {
                updated.add(item);
            }
            // else: removed without replacement → drop
        }

        // ---- Position truly new items ----
        List<String> trulyNew = new ArrayList<>();
        for (String add : added) {
            if (!usedAsReplacement.contains(add) && !updated.contains(add)) {
                trulyNew.add(add);
            }
        }

        if (!trulyNew.isEmpty()) {
            if (survivingSet.isEmpty()) {
                // No surviving anchors — add in XHTML content-list order
                for (String item : trulyNew) {
                    if (!updated.contains(item)) {
                        updated.add(item);
                    }
                }
            } else {
                // Find position of first surviving item in currFiltered
                int firstSurvivingPos = Integer.MAX_VALUE;
                for (int i = 0; i < currFiltered.size(); i++) {
                    if (survivingSet.contains(currFiltered.get(i))) {
                        firstSurvivingPos = i;
                        break;
                    }
                }

                List<String> beforeAnchors = new ArrayList<>();
                List<String> afterAnchors = new ArrayList<>();

                for (String item : trulyNew) {
                    int itemPos = currFiltered.indexOf(item);
                    if (itemPos < firstSurvivingPos) {
                        beforeAnchors.add(item);
                    } else {
                        afterAnchors.add(item);
                    }
                }

                // After-anchor items: insert at position relative to preceding anchor
                for (String item : afterAnchors) {
                    int itemPos = currFiltered.indexOf(item);
                    String precedingInUpdated = null;
                    for (int i = itemPos - 1; i >= 0; i--) {
                        if (updated.contains(currFiltered.get(i))) {
                            precedingInUpdated = currFiltered.get(i);
                            break;
                        }
                    }
                    if (precedingInUpdated != null) {
                        int insertAt = updated.indexOf(precedingInUpdated) + 1;
                        updated.add(insertAt, item);
                    } else {
                        updated.add(item);
                    }
                }

                // Before-all-anchors items → added at bottom of page visually → append at end
                updated.addAll(beforeAnchors);
            }
        }

        return updated;
    }

    // ---- Helpers for position-based replacement detection ----

    private static String findPrecedingSurviving(String item, List<String> contentList, Set<String> surviving) {
        int idx = contentList.indexOf(item);
        if (idx < 0) return null;
        for (int i = idx - 1; i >= 0; i--) {
            if (surviving.contains(contentList.get(i))) {
                return contentList.get(i);
            }
        }
        return null;
    }

    private static String findFollowingSurviving(String item, List<String> contentList, Set<String> surviving) {
        int idx = contentList.indexOf(item);
        if (idx < 0) return null;
        for (int i = idx + 1; i < contentList.size(); i++) {
            if (surviving.contains(contentList.get(i))) {
                return contentList.get(i);
            }
        }
        return null;
    }

    // ---- Title detection ----

    private static String detectTitle(List<String> rev1, String date, String time) {
        int dateIdx = -1;
        int timeIdx = -1;

        for (int i = 0; i < rev1.size(); i++) {
            if (rev1.get(i).equals(date) && dateIdx == -1) {
                dateIdx = i;
            }
            if (rev1.get(i).equals(time)) {
                timeIdx = i;
            }
        }

        if (dateIdx >= 0 && timeIdx > dateIdx) {
            for (int i = dateIdx + 1; i < timeIdx; i++) {
                String candidate = rev1.get(i);
                if (!candidate.equals(date) && !candidate.equals(time)) {
                    return candidate;
                }
            }
        }

        for (String text : rev1) {
            if (!text.equals(date) && !text.equals(time)) {
                return text;
            }
        }

        return "Untitled";
    }

    // ---- Utilities ----

    private static boolean isCumulativeRevision(List<String> current, List<String> previous) {
        if (previous.isEmpty()) return true;

        Set<String> currentSet = new HashSet<>(current);
        int matchCount = 0;
        for (String s : previous) {
            if (currentSet.contains(s)) {
                matchCount++;
            }
        }

        return matchCount >= previous.size() * 0.5;
    }
}
