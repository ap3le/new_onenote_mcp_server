package com.onenote.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

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
        return deduplicate(paragraphs, null);
    }

    static List<List<String>> deduplicate(List<String> paragraphs, List<String> binaryTitles) {
        if (paragraphs.isEmpty()) {
            return new ArrayList<>();
        }

        List<PageRevisions> pageGroups = splitIntoPages(paragraphs);

        List<List<String>> pages = new ArrayList<>();
        for (int i = 0; i < pageGroups.size(); i++) {
            PageRevisions pr = pageGroups.get(i);
            // Use binary-extracted title if available
            String forcedTitle = (binaryTitles != null && i < binaryTitles.size())
                ? binaryTitles.get(i) : null;
            List<String> pageContent = extractPageContent(pr, forcedTitle);
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

        // Find all time-marker positions (end of each revision block)
        List<Integer> timePositions = new ArrayList<>();
        for (int i = 0; i < paragraphs.size(); i++) {
            if (isTimePattern(paragraphs.get(i))) {
                timePositions.add(i);
            }
        }

        if (timePositions.isEmpty()) {
            return result;
        }

        // Split into blocks ending at each time marker
        List<List<String>> blocks = new ArrayList<>();
        int blockStart = 0;
        for (int timePos : timePositions) {
            List<String> block = new ArrayList<>();
            for (int j = blockStart; j <= timePos; j++) {
                block.add(paragraphs.get(j));
            }
            blocks.add(block);
            blockStart = timePos + 1;
        }

        // Group consecutive blocks that are cumulative revisions of each other
        int i = 0;
        while (i < blocks.size()) {
            List<List<String>> pageBlocks = new ArrayList<>();
            pageBlocks.add(blocks.get(i));

            int j = i + 1;
            while (j < blocks.size()) {
                List<String> curr = blocks.get(j);
                List<String> prev = blocks.get(j - 1);
                if (isCumulativeRevision(curr, prev) || isCumulativeRevision(prev, curr)) {
                    pageBlocks.add(curr);
                    j++;
                } else {
                    break;
                }
            }

            // Sort revisions by size (ascending) — smallest = oldest
            pageBlocks.sort(new java.util.Comparator<List<String>>() {
                public int compare(List<String> a, List<String> b) {
                    return a.size() - b.size();
                }
            });

            // Find date and time from the blocks
            String date = null;
            String time = null;
            for (List<String> block : pageBlocks) {
                for (String p : block) {
                    if (date == null && isDatePattern(p)) date = p;
                    if (time == null && isTimePattern(p)) time = p;
                }
            }

            PageRevisions pr = new PageRevisions();
            pr.dateParagraph = date;
            pr.timeParagraph = time;
            pr.metadata = new HashSet<>();
            if (date != null) pr.metadata.add(date);
            if (time != null) pr.metadata.add(time);
            pr.revisions = pageBlocks;
            result.add(pr);

            i = j;
        }

        return result;
    }

    // ---- Step 2: Extract title + ordered content ----

    private static List<String> extractPageContent(PageRevisions pr, String forcedTitle) {
        if (pr.revisions.isEmpty()) {
            return new ArrayList<>();
        }

        // Use binary-extracted title if provided, otherwise detect from revision data
        String title = forcedTitle;
        if (title == null || title.isEmpty()) {
            for (List<String> rev : pr.revisions) {
                title = detectTitle(rev, pr.dateParagraph, pr.timeParagraph);
                if (title != null && !title.equals("Untitled")) break;
            }
            if (title == null) title = "Untitled";
        }

        if (pr.revisions.size() <= 1) {
            // Single revision (compacted) — use XHTML order heuristic
            return extractSingleRevisionContent(pr.revisions.get(0), pr.metadata, title);
        }

        // Process revisions sequentially, building ordered content via diffs
        List<String> orderedContent = new ArrayList<>();
        List<String> prevFiltered = filterContent(pr.revisions.get(0), pr.metadata, title);

        for (int i = 1; i < pr.revisions.size(); i++) {
            // Initialize orderedContent from prevFiltered if starting with non-empty content
            if (orderedContent.isEmpty() && !prevFiltered.isEmpty()) {
                orderedContent = new ArrayList<>(prevFiltered);
            }
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

    // ---- Single-revision page content extraction ----

    private static List<String> extractSingleRevisionContent(
            List<String> revision, Set<String> metadata, String title) {
        List<String> result = new ArrayList<>();
        result.add(title);

        int dateIdx = -1;
        int timeIdx = -1;
        for (int i = 0; i < revision.size(); i++) {
            if (dateIdx == -1 && isDatePattern(revision.get(i))) dateIdx = i;
            if (isTimePattern(revision.get(i))) timeIdx = i;
        }

        // Content between date and time (excluding metadata and title)
        if (dateIdx >= 0 && timeIdx > dateIdx) {
            for (int i = dateIdx + 1; i < timeIdx; i++) {
                String item = revision.get(i);
                if (!metadata.contains(item) && !item.equals(title)) {
                    result.add(item);
                }
            }
        }

        // Content before date (added last in OneNote → bottom of page)
        if (dateIdx > 0) {
            for (int i = 0; i < dateIdx; i++) {
                String item = revision.get(i);
                if (!metadata.contains(item) && !item.equals(title)) {
                    result.add(item);
                }
            }
        }

        return result;
    }

    // ---- Date/Time pattern detection ----

    private static final Pattern DATE_PATTERN = Pattern.compile(
        "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), " +
        "(January|February|March|April|May|June|July|August|September|October|November|December) " +
        "\\d{1,2}, \\d{4}$");

    private static final Pattern TIME_PATTERN = Pattern.compile(
        "^\\d{1,2}:\\d{2} (AM|PM)$");

    private static boolean isDatePattern(String s) {
        return DATE_PATTERN.matcher(s).matches();
    }

    private static boolean isTimePattern(String s) {
        return TIME_PATTERN.matcher(s).matches();
    }
}
