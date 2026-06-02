package dev.oakheart.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Three-way leading-comment sync. Updates a user document's leading comment
 * blocks to match new defaults — but only for comments the user has not
 * customised, using a baseline (the previously-shipped default) as the merge base.
 *
 * <p>For each key present in all three documents (user, new default, baseline):</p>
 * <ul>
 *   <li>If the user's leading comment equals the baseline's, the user never
 *       touched it → adopt the new default's comment (covering changed, added,
 *       and removed comments).</li>
 *   <li>If the user's leading comment differs from the baseline's, the admin
 *       customised it → leave it alone.</li>
 * </ul>
 *
 * <p>Comparison ignores indentation and blank lines (only {@code #} lines are
 * compared), so reformatting alone never counts as customisation. Only leading
 * comment blocks are handled; inline comments are left untouched.</p>
 *
 * <p>Edits are applied to the line list bottom-up; the caller re-parses afterward
 * to rebuild a consistent tree.</p>
 */
final class CommentSync {

    private CommentSync() {}

    /** A pending replacement of a contiguous line range with new lines. */
    private record Edit(int start, int removeCount, List<String> insertLines) {}

    /** A node's leading comment region: its line range, raw text, and a normalised key for comparison. */
    private record Block(int start, int count, List<String> rawLines, String normalized) {}

    static boolean sync(YamlDocument userDoc, YamlDocument newDoc, YamlDocument baseDoc) {
        List<Edit> edits = new ArrayList<>();
        walk(userDoc.getRoot(), newDoc.getRoot(), baseDoc.getRoot(), userDoc, newDoc, baseDoc, edits);
        if (edits.isEmpty()) return false;

        // Apply bottom-up so earlier (lower) line indices stay valid as we edit.
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        for (Edit edit : edits) {
            if (edit.removeCount() > 0) {
                userDoc.removeLines(edit.start(), edit.start() + edit.removeCount());
            }
            if (!edit.insertLines().isEmpty()) {
                List<DocumentLine> docLines = new ArrayList<>(edit.insertLines().size());
                for (String text : edit.insertLines()) {
                    docLines.add(new DocumentLine(text));
                }
                userDoc.insertLines(edit.start(), docLines);
            }
        }
        return true;
    }

    private static void walk(YamlNode user, YamlNode def, YamlNode base,
                             YamlDocument userDoc, YamlDocument defDoc, YamlDocument baseDoc,
                             List<Edit> edits) {
        if (def.getType() != NodeType.MAP || user.getType() != NodeType.MAP || base.getType() != NodeType.MAP) {
            return;
        }
        for (var entry : def.getChildren().entrySet()) {
            String key = entry.getKey();
            YamlNode defChild = entry.getValue();
            YamlNode userChild = user.getChild(key);
            YamlNode baseChild = base.getChild(key);
            if (userChild == null || baseChild == null) {
                // Key isn't in all three — that's mergeDefaults/migration territory, not comment sync.
                continue;
            }

            evaluate(userChild, defChild, baseChild, userDoc, defDoc, baseDoc, edits);

            if (userChild.getType() == NodeType.MAP
                    && defChild.getType() == NodeType.MAP
                    && baseChild.getType() == NodeType.MAP) {
                walk(userChild, defChild, baseChild, userDoc, defDoc, baseDoc, edits);
            }
        }
    }

    private static void evaluate(YamlNode userChild, YamlNode defChild, YamlNode baseChild,
                                 YamlDocument userDoc, YamlDocument defDoc, YamlDocument baseDoc,
                                 List<Edit> edits) {
        Block userBlock = block(userChild, userDoc);
        Block baseBlock = block(baseChild, baseDoc);
        Block defBlock = block(defChild, defDoc);
        if (userBlock == null || baseBlock == null || defBlock == null) {
            return; // non-contiguous block somewhere — skip this key for safety
        }

        // Admin customised the comment — never clobber it.
        if (!userBlock.normalized().equals(baseBlock.normalized())) return;
        // Default's comment is unchanged from the baseline — nothing to do.
        if (defBlock.normalized().equals(baseBlock.normalized())) return;

        // Re-indent the new default's block to the user key's indentation.
        int delta = userChild.getIndent() - defChild.getIndent();
        List<String> insert = new ArrayList<>(defBlock.rawLines().size());
        for (String line : defBlock.rawLines()) {
            insert.add(adjustIndent(line, delta));
        }

        int start = userBlock.count() > 0 ? userBlock.start() : userChild.getKeyLineIndex();
        edits.add(new Edit(start, userBlock.count(), insert));
    }

    private static Block block(YamlNode node, YamlDocument doc) {
        List<Integer> idx = node.getLeadingCommentLines();
        if (idx.isEmpty()) {
            return new Block(node.getKeyLineIndex(), 0, List.of(), "");
        }
        int start = idx.getFirst();
        int end = idx.getLast();
        if (end - start + 1 != idx.size()) {
            return null; // non-contiguous leading block — bail rather than risk a bad edit
        }

        List<DocumentLine> lines = doc.getLines();
        List<String> raw = new ArrayList<>(idx.size());
        StringBuilder normalized = new StringBuilder();
        for (int i = start; i <= end; i++) {
            if (i < 0 || i >= lines.size()) return null;
            String text = lines.get(i).text();
            raw.add(text);
            String stripped = text.strip();
            if (stripped.startsWith("#")) {
                if (normalized.length() > 0) normalized.append('\n');
                normalized.append(stripped);
            }
        }
        return new Block(start, idx.size(), raw, normalized.toString());
    }

    private static String adjustIndent(String line, int delta) {
        if (delta == 0 || line.isBlank()) return line;
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        int newIndent = Math.max(0, indent + delta);
        return " ".repeat(newIndent) + line.stripLeading();
    }
}
