package dev.oakheart.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Merges default configuration into a user's existing configuration.
 *
 * <p>Strategy:</p>
 * <ol>
 *   <li>Walk the defaults tree.</li>
 *   <li>For each key in defaults that is missing from the user tree:
 *       find the correct insertion point, copy the lines from defaults
 *       (key line + preceding comments + descendants), and insert.</li>
 *   <li>Keys that exist in the user's config are NEVER touched,
 *       even if the value differs from the default.</li>
 * </ol>
 *
 * <p>The "correct insertion point" is determined by finding the nearest
 * sibling key that exists in both documents and inserting after it.
 * If no sibling exists, insert at the end of the parent section.</p>
 */
public final class YamlMerger {

    private YamlMerger() {}

    /**
     * Merge missing keys from defaults into userDoc, mutating userDoc in place.
     *
     * @param userDoc    the user's document (will be modified)
     * @param defaultDoc the defaults document (read-only)
     * @return true if any keys were added (caller should save)
     */
    public static boolean merge(YamlDocument userDoc, YamlDocument defaultDoc) {
        return mergeNode(userDoc, userDoc.getRoot(), defaultDoc, defaultDoc.getRoot());
    }

    /**
     * Check if the user's document is missing any leaf keys from defaults.
     * Lightweight check that does not modify anything.
     */
    public static boolean hasNewKeys(YamlDocument userDoc, YamlDocument defaultDoc) {
        return checkMissing(userDoc.getRoot(), defaultDoc.getRoot());
    }

    private static boolean checkMissing(YamlNode userNode, YamlNode defaultNode) {
        if (defaultNode.getType() != NodeType.MAP) return false;

        for (var entry : defaultNode.getChildren().entrySet()) {
            String key = entry.getKey();
            YamlNode defaultChild = entry.getValue();
            YamlNode userChild = userNode.getChild(key);

            if (userChild == null) {
                // Check if this is a user-customized section (skip it)
                if (isUserCustomizedSection(userNode, defaultNode, key)) {
                    continue;
                }
                return true;
            }

            // Recurse into map sections
            if (defaultChild.getType() == NodeType.MAP && userChild.getType() == NodeType.MAP) {
                if (checkMissing(userChild, defaultChild)) return true;
            }
        }
        return false;
    }

    private static boolean mergeNode(YamlDocument userDoc, YamlNode userNode,
                                      YamlDocument defaultDoc, YamlNode defaultNode) {
        if (defaultNode.getType() != NodeType.MAP) return false;

        boolean changed = false;
        List<String> defaultKeyOrder = new ArrayList<>(defaultNode.getChildKeys());

        for (String key : defaultKeyOrder) {
            YamlNode defaultChild = defaultNode.getChild(key);
            YamlNode userChild = userNode.getChild(key);

            if (userChild == null) {
                // Check if this is a user-customized section — skip if so
                if (isUserCustomizedSection(userNode, defaultNode, key)) {
                    continue;
                }

                // Insert this missing key (and all its descendants) into the user doc
                insertMissingKey(userDoc, userNode, defaultDoc, defaultNode, key, defaultKeyOrder);
                changed = true;
            } else if (defaultChild.getType() == NodeType.MAP && userChild.getType() == NodeType.MAP) {
                // Recurse into matching sections
                if (mergeNode(userDoc, userChild, defaultDoc, defaultChild)) {
                    changed = true;
                }
            }
            // If both exist but types differ, or both are scalars/sequences, leave user's value alone
        }
        return changed;
    }

    /**
     * Insert a missing key from defaults into the user document at the correct position.
     */
    private static void insertMissingKey(YamlDocument userDoc, YamlNode userParent,
                                          YamlDocument defaultDoc, YamlNode defaultParent,
                                          String missingKey, List<String> defaultKeyOrder) {
        YamlNode defaultChild = defaultParent.getChild(missingKey);

        // Find insertion point and the sibling to insert after/before in the tree
        InsertionInfo insertion = findInsertionPoint(userDoc, userParent, defaultKeyOrder, missingKey);

        // Extract lines from defaults for this key (leading comments + key + descendants)
        List<String> extractedLines = extractNodeLines(defaultDoc, defaultChild);

        // Adjust indentation to match user document's context
        int targetIndent = calculateTargetIndent(userParent);
        int sourceIndent = defaultChild.getIndent();
        int indentDelta = targetIndent - sourceIndent;

        List<DocumentLine> newLines = new ArrayList<>();
        for (String line : extractedLines) {
            if (indentDelta != 0 && !line.isBlank()) {
                line = adjustIndent(line, indentDelta);
            }
            newLines.add(new DocumentLine(line));
        }

        // Insert lines into user document
        if (!newLines.isEmpty()) {
            userDoc.insertLines(insertion.lineIndex, newLines);
        }

        // Re-parse the inserted section to create proper tree nodes
        // (simpler and safer than manually constructing nodes)
        StringBuilder insertedText = new StringBuilder();
        for (int i = 0; i < newLines.size(); i++) {
            if (i > 0) insertedText.append('\n');
            insertedText.append(newLines.get(i).text());
        }

        // Parse the inserted text to get a node tree
        YamlDocument tempDoc = YamlParser.parse(insertedText.toString());
        YamlNode tempRoot = tempDoc.getRoot();

        // There should be exactly one top-level key in the temp doc
        if (!tempRoot.getChildren().isEmpty()) {
            String insertedKey = tempRoot.getChildKeys().iterator().next();
            YamlNode insertedNode = tempRoot.getChild(insertedKey);

            // Shift the temp node's line indices to match actual positions in user doc
            shiftNodeIndices(insertedNode, insertion.lineIndex);
            insertedNode.setLeadingCommentLines(
                    shiftIndices(insertedNode.getLeadingCommentLines(), insertion.lineIndex));

            // Insert into tree at the correct position (matching line order)
            if (insertion.afterKey != null) {
                userParent.addChildAfter(insertion.afterKey, missingKey, insertedNode);
            } else if (insertion.beforeKey != null) {
                userParent.addChildBefore(insertion.beforeKey, missingKey, insertedNode);
            } else {
                userParent.addChild(missingKey, insertedNode);
            }
        }
    }

    private record InsertionInfo(int lineIndex, String afterKey, String beforeKey) {}

    /**
     * Find where to insert a missing key in the user document.
     * Returns the line index and which sibling key to insert after/before in the tree.
     */
    private static InsertionInfo findInsertionPoint(YamlDocument userDoc, YamlNode userParent,
                                                     List<String> defaultKeyOrder, String missingKey) {
        int missingIndex = defaultKeyOrder.indexOf(missingKey);

        // Look backwards through default key order for a sibling that exists in user
        for (int i = missingIndex - 1; i >= 0; i--) {
            String siblingKey = defaultKeyOrder.get(i);
            YamlNode sibling = userParent.getChild(siblingKey);
            if (sibling != null) {
                return new InsertionInfo(sibling.getLastLine() + 1, siblingKey, null);
            }
        }

        // No preceding sibling found — look forward for a sibling that exists
        for (int i = missingIndex + 1; i < defaultKeyOrder.size(); i++) {
            String siblingKey = defaultKeyOrder.get(i);
            YamlNode sibling = userParent.getChild(siblingKey);
            if (sibling != null) {
                return new InsertionInfo(sibling.getFirstLine(), null, siblingKey);
            }
        }

        // No siblings at all — insert after parent's key line or at end
        if (userParent.getKeyLineIndex() >= 0) {
            return new InsertionInfo(userParent.getKeyLineIndex() + 1, null, null);
        }
        return new InsertionInfo(userDoc.lineCount(), null, null);
    }

    /**
     * Extract all lines for a node from the document, including leading comments and descendants.
     */
    private static List<String> extractNodeLines(YamlDocument doc, YamlNode node) {
        int firstLine = node.getFirstLine();
        int lastLine = node.getLastContentLine();

        List<String> lines = new ArrayList<>();
        List<DocumentLine> docLines = doc.getLines();

        for (int i = firstLine; i <= lastLine && i < docLines.size(); i++) {
            lines.add(docLines.get(i).text());
        }
        return lines;
    }

    /**
     * Calculate the target indentation for a child of the given parent.
     */
    private static int calculateTargetIndent(YamlNode parent) {
        if (parent.getKeyLineIndex() < 0) {
            // Root node — children are at indent 0
            return 0;
        }
        return parent.getIndent() + 2;
    }

    /**
     * Adjust indentation of a line by the given delta.
     */
    private static String adjustIndent(String line, int delta) {
        if (delta == 0) return line;

        int currentIndent = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == ' ') currentIndent++;
            else break;
        }

        int newIndent = Math.max(0, currentIndent + delta);
        return " ".repeat(newIndent) + line.stripLeading();
    }

    /**
     * Shift all line indices in a node tree by the given base offset.
     * The temp doc has indices starting at 0; we need to shift them to actual positions.
     */
    private static void shiftNodeIndices(YamlNode node, int base) {
        node.setKeyLineIndex(node.getKeyLineIndex() + base);
        node.setLeadingCommentLines(shiftIndices(node.getLeadingCommentLines(), base));
        node.setTrailingBlankLines(shiftIndices(node.getTrailingBlankLines(), base));

        if (node.getType() == NodeType.MAP) {
            for (YamlNode child : node.getChildren().values()) {
                shiftNodeIndices(child, base);
            }
        }
        if (node.getType() == NodeType.SEQUENCE) {
            for (YamlNode item : node.getItems()) {
                shiftNodeIndices(item, base);
            }
        }
    }

    private static List<Integer> shiftIndices(List<Integer> indices, int base) {
        List<Integer> shifted = new ArrayList<>(indices.size());
        for (int idx : indices) {
            shifted.add(idx + base);
        }
        return shifted;
    }

    /**
     * Determines if a missing key is absent because the user has customized the parent section
     * (replaced default entries with their own). In that case, we should NOT re-inject defaults.
     *
     * <p>Heuristic: if the parent section exists in the user doc with at least one child,
     * but NONE of the default's children are present, the user has replaced the entire section.</p>
     */
    private static boolean isUserCustomizedSection(YamlNode userParent, YamlNode defaultParent, String key) {
        YamlNode defaultChild = defaultParent.getChild(key);
        if (defaultChild == null || defaultChild.getType() != NodeType.MAP) {
            return false;
        }

        // Only applies to map sections (e.g., pet definitions, tag configs)
        // The user section must exist and have children, but they should be different
        // from the defaults — indicating the user replaced the defaults with their own entries
        Set<String> defaultSiblingKeys = defaultParent.getChildKeys();
        Set<String> userSiblingKeys = userParent.getChildKeys();

        // If the user has ANY of the default's sibling keys (other than this missing one),
        // this is a normal partial config — merge normally
        for (String siblingKey : defaultSiblingKeys) {
            if (!siblingKey.equals(key) && userSiblingKeys.contains(siblingKey)) {
                return false; // User has other default keys — not a full customization
            }
        }

        // If the user has children that DON'T overlap with defaults at all,
        // and the parent section exists, the user likely customized this section
        if (!userSiblingKeys.isEmpty()) {
            boolean anyOverlap = false;
            for (String userKey : userSiblingKeys) {
                if (defaultSiblingKeys.contains(userKey)) {
                    anyOverlap = true;
                    break;
                }
            }
            if (!anyOverlap) {
                return true; // User replaced all default entries — skip
            }
        }

        return false;
    }
}
