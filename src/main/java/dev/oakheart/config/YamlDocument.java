package dev.oakheart.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A parsed YAML document maintaining both a line-based representation
 * (for byte-perfect file preservation) and a tree representation
 * (for programmatic access).
 *
 * <p>The two representations are kept in sync: modifications through the
 * tree API update the corresponding lines, and the line list is what
 * gets written to disk.</p>
 */
public final class YamlDocument {

    private final List<DocumentLine> lines;
    private final YamlNode root;

    YamlDocument(List<DocumentLine> lines, YamlNode root) {
        this.lines = new ArrayList<>(lines);
        this.root = root;
    }

    /**
     * Returns an unmodifiable view of the raw lines.
     */
    public List<DocumentLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /**
     * Returns the root map node.
     */
    public YamlNode getRoot() {
        return root;
    }

    /**
     * Returns the total number of lines.
     */
    public int lineCount() {
        return lines.size();
    }

    // --- Package-private mutation methods ---

    /**
     * Replace the text of a single line in-place.
     */
    void replaceLine(int lineIndex, String newText) {
        lines.set(lineIndex, new DocumentLine(newText));
    }

    /**
     * Insert lines at the given position, shifting all subsequent content.
     */
    void insertLines(int atIndex, List<DocumentLine> newLines) {
        lines.addAll(atIndex, newLines);
        root.shiftLineIndices(atIndex, newLines.size());
    }

    /**
     * Remove lines in range [fromIndex, toIndex), shifting content down.
     */
    void removeLines(int fromIndex, int toIndex) {
        int count = toIndex - fromIndex;
        for (int i = 0; i < count; i++) {
            lines.remove(fromIndex);
        }
        root.shiftLineIndices(fromIndex, -count);
    }

    /**
     * Serialize the document back to a string by joining all lines.
     */
    public String serialize() {
        if (lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i).text());
        }
        return sb.toString();
    }
}
