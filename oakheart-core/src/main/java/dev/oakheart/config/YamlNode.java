package dev.oakheart.config;

import java.util.*;

/**
 * A node in the YAML tree. Can be a scalar, a map, or a sequence.
 *
 * <p>Each node tracks its position in the document's line list, enabling
 * targeted updates without rewriting the entire file.</p>
 */
public final class YamlNode {

    /**
     * Quoting style for scalar values.
     */
    public enum QuoteStyle {
        UNQUOTED,
        SINGLE_QUOTED,
        DOUBLE_QUOTED
    }

    private final String key;
    private NodeType type;
    private int keyLineIndex;
    private int indent;

    // Scalar fields
    private Object value;
    private QuoteStyle quoteStyle;
    private String inlineComment;

    // Map fields (insertion-ordered)
    private LinkedHashMap<String, YamlNode> children;

    // Sequence fields
    private List<YamlNode> items;

    // Comment association: line indices of comments/blanks that precede this node
    private List<Integer> leadingCommentLines;

    // Trailing blank lines after this node's content (section separators)
    private List<Integer> trailingBlankLines;

    YamlNode(String key, NodeType type, int keyLineIndex, int indent) {
        this.key = key;
        this.type = type;
        this.keyLineIndex = keyLineIndex;
        this.indent = indent;
        this.quoteStyle = QuoteStyle.UNQUOTED;
        this.leadingCommentLines = new ArrayList<>();
        this.trailingBlankLines = new ArrayList<>();

        if (type == NodeType.MAP) {
            this.children = new LinkedHashMap<>();
        } else if (type == NodeType.SEQUENCE) {
            this.items = new ArrayList<>();
        }
    }

    // --- Getters ---

    public String getKey() {
        return key;
    }

    public NodeType getType() {
        return type;
    }

    public int getKeyLineIndex() {
        return keyLineIndex;
    }

    public int getIndent() {
        return indent;
    }

    public Object getRawValue() {
        return value;
    }

    public QuoteStyle getQuoteStyle() {
        return quoteStyle;
    }

    public String getInlineComment() {
        return inlineComment;
    }

    // --- Typed scalar access ---

    public String asString(String def) {
        if (value == null) return def;
        return value.toString();
    }

    public int asInt(int def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long asLong(long def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public double asDouble(double def) {
        if (value == null) return def;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean asBoolean(boolean def) {
        if (value == null) return def;
        if (value instanceof Boolean b) return b;
        String s = value.toString().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "yes", "on" -> true;
            case "false", "no", "off" -> false;
            default -> def;
        };
    }

    // --- Map access ---

    public YamlNode getChild(String key) {
        if (children == null) return null;
        return children.get(key);
    }

    public boolean hasChild(String key) {
        return children != null && children.containsKey(key);
    }

    public Set<String> getChildKeys() {
        if (children == null) return Collections.emptySet();
        return Collections.unmodifiableSet(children.keySet());
    }

    public Map<String, YamlNode> getChildren() {
        if (children == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(children);
    }

    // --- Sequence access ---

    public List<YamlNode> getItems() {
        if (items == null) return Collections.emptyList();
        return Collections.unmodifiableList(items);
    }

    public List<String> asStringList() {
        if (items == null) return Collections.emptyList();
        List<String> result = new ArrayList<>(items.size());
        for (YamlNode item : items) {
            result.add(item.asString(""));
        }
        return result;
    }

    public List<Integer> asIntList() {
        if (items == null) return Collections.emptyList();
        List<Integer> result = new ArrayList<>(items.size());
        for (YamlNode item : items) {
            result.add(item.asInt(0));
        }
        return result;
    }

    // --- Mutation (package-private) ---

    void setType(NodeType type) {
        this.type = type;
        if (type == NodeType.MAP && children == null) {
            children = new LinkedHashMap<>();
        } else if (type == NodeType.SEQUENCE && items == null) {
            items = new ArrayList<>();
        }
    }

    void setScalarValue(Object value, QuoteStyle style) {
        this.value = value;
        this.quoteStyle = style;
    }

    void setInlineComment(String comment) {
        this.inlineComment = comment;
    }

    void setKeyLineIndex(int index) {
        this.keyLineIndex = index;
    }

    void setIndent(int indent) {
        this.indent = indent;
    }

    void addChild(String key, YamlNode child) {
        if (children == null) {
            children = new LinkedHashMap<>();
        }
        children.put(key, child);
    }

    /**
     * Insert a child after a specific sibling key, preserving insertion order.
     * If afterKey is null or not found, appends at the end.
     */
    void addChildAfter(String afterKey, String key, YamlNode child) {
        if (children == null) {
            children = new LinkedHashMap<>();
            children.put(key, child);
            return;
        }

        if (afterKey == null || !children.containsKey(afterKey)) {
            children.put(key, child);
            return;
        }

        // Rebuild the map with the new entry inserted after afterKey
        LinkedHashMap<String, YamlNode> newChildren = new LinkedHashMap<>();
        for (var entry : children.entrySet()) {
            newChildren.put(entry.getKey(), entry.getValue());
            if (entry.getKey().equals(afterKey)) {
                newChildren.put(key, child);
            }
        }
        children = newChildren;
    }

    /**
     * Insert a child before a specific sibling key, preserving insertion order.
     * If beforeKey is null or not found, appends at the end.
     */
    void addChildBefore(String beforeKey, String key, YamlNode child) {
        if (children == null) {
            children = new LinkedHashMap<>();
            children.put(key, child);
            return;
        }

        if (beforeKey == null || !children.containsKey(beforeKey)) {
            children.put(key, child);
            return;
        }

        LinkedHashMap<String, YamlNode> newChildren = new LinkedHashMap<>();
        for (var entry : children.entrySet()) {
            if (entry.getKey().equals(beforeKey)) {
                newChildren.put(key, child);
            }
            newChildren.put(entry.getKey(), entry.getValue());
        }
        children = newChildren;
    }

    void removeChild(String key) {
        if (children != null) {
            children.remove(key);
        }
    }

    void addItem(YamlNode item) {
        if (items == null) {
            items = new ArrayList<>();
        }
        items.add(item);
    }

    void clearItems() {
        if (items != null) {
            items.clear();
        }
    }

    // --- Comment tracking ---

    public List<Integer> getLeadingCommentLines() {
        return Collections.unmodifiableList(leadingCommentLines);
    }

    public List<Integer> getTrailingBlankLines() {
        return Collections.unmodifiableList(trailingBlankLines);
    }

    void setLeadingCommentLines(List<Integer> lines) {
        this.leadingCommentLines = new ArrayList<>(lines);
    }

    void addLeadingCommentLine(int lineIndex) {
        this.leadingCommentLines.add(lineIndex);
    }

    void setTrailingBlankLines(List<Integer> lines) {
        this.trailingBlankLines = new ArrayList<>(lines);
    }

    void addTrailingBlankLine(int lineIndex) {
        this.trailingBlankLines.add(lineIndex);
    }

    // --- Range calculations ---

    /**
     * Returns the first line this node occupies, including leading comments.
     */
    public int getFirstLine() {
        if (!leadingCommentLines.isEmpty()) {
            return leadingCommentLines.getFirst();
        }
        return keyLineIndex;
    }

    /**
     * Returns the last line this node (and all descendants) occupies,
     * including trailing blank lines.
     */
    public int getLastLine() {
        if (!trailingBlankLines.isEmpty()) {
            return trailingBlankLines.getLast();
        }
        return getLastContentLine();
    }

    /**
     * Returns the last content line (excluding trailing blanks).
     */
    public int getLastContentLine() {
        if (type == NodeType.MAP && children != null && !children.isEmpty()) {
            // Last child's last line
            YamlNode lastChild = null;
            for (YamlNode child : children.values()) {
                lastChild = child;
            }
            return lastChild.getLastLine();
        }
        if (type == NodeType.SEQUENCE && items != null && !items.isEmpty()) {
            return items.getLast().getLastLine();
        }
        return keyLineIndex;
    }

    /**
     * Shift all line indices in this node and descendants by the given delta,
     * for indices >= fromIndex.
     */
    void shiftLineIndices(int fromIndex, int delta) {
        if (keyLineIndex >= fromIndex) {
            keyLineIndex += delta;
        }

        leadingCommentLines.replaceAll(idx -> idx >= fromIndex ? idx + delta : idx);
        trailingBlankLines.replaceAll(idx -> idx >= fromIndex ? idx + delta : idx);

        if (children != null) {
            for (YamlNode child : children.values()) {
                child.shiftLineIndices(fromIndex, delta);
            }
        }
        if (items != null) {
            for (YamlNode item : items) {
                item.shiftLineIndices(fromIndex, delta);
            }
        }
    }
}
