package dev.oakheart.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * The main API for ConfigManager. Wraps a YamlDocument and provides
 * Bukkit-style typed accessors with dot-path navigation.
 *
 * <p>This is a preservation-first config document model for a constrained
 * Minecraft-style YAML subset. Unmodified regions are preserved exactly.
 * Modified lines are normalized according to ConfigManager formatting rules.</p>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * ConfigManager config = ConfigManager.load(configPath);
 * String name = config.getString("display.name", "Default");
 * config.set("api-key", generatedKey);
 * config.save();
 * }</pre>
 */
public final class ConfigManager {

    private volatile YamlDocument document;
    private final Path filePath;
    private final Object lock = new Object();

    // For section views only: the base node for path resolution (null = use document root)
    private final YamlNode baseNode;
    // Reference to the owning config (for section views that share the document)
    private final ConfigManager owner;

    private ConfigManager(YamlDocument document, Path filePath) {
        this.document = document;
        this.filePath = filePath;
        this.baseNode = null;
        this.owner = null;
    }

    // Section view constructor
    private ConfigManager(YamlDocument document, Path filePath, YamlNode baseNode, ConfigManager owner) {
        this.document = document;
        this.filePath = filePath;
        this.baseNode = baseNode;
        this.owner = owner;
    }

    // ========================================
    //          Static Factory Methods
    // ========================================

    /**
     * Load a YAML file from disk, preserving all formatting.
     */
    public static ConfigManager load(Path filePath) throws IOException {
        String text = Files.readString(filePath, StandardCharsets.UTF_8);
        // Normalize Windows line endings
        text = text.replace("\r\n", "\n");
        YamlDocument doc = YamlParser.parse(text);
        return new ConfigManager(doc, filePath);
    }

    /**
     * Parse YAML from a String.
     */
    public static ConfigManager fromString(String yamlText) {
        String normalized = yamlText != null ? yamlText.replace("\r\n", "\n") : "";
        YamlDocument doc = YamlParser.parse(normalized);
        return new ConfigManager(doc, null);
    }

    /**
     * Parse YAML from an InputStream (e.g., plugin.getResource("config.yml")).
     */
    public static ConfigManager fromStream(InputStream stream) throws IOException {
        if (stream == null) {
            throw new IOException("InputStream is null");
        }
        String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return fromString(text);
    }

    // ========================================
    //          Typed Getters
    // ========================================

    public String getString(String path, String def) {
        YamlNode node = resolve(path);
        if (node == null) return def;
        return node.asString(def);
    }

    public String getString(String path) {
        return getString(path, null);
    }

    public int getInt(String path, int def) {
        YamlNode node = resolve(path);
        if (node == null) return def;
        return node.asInt(def);
    }

    public int getInt(String path) {
        return getInt(path, 0);
    }

    public double getDouble(String path, double def) {
        YamlNode node = resolve(path);
        if (node == null) return def;
        return node.asDouble(def);
    }

    public double getDouble(String path) {
        return getDouble(path, 0.0);
    }

    public long getLong(String path, long def) {
        YamlNode node = resolve(path);
        if (node == null) return def;
        return node.asLong(def);
    }

    public long getLong(String path) {
        return getLong(path, 0L);
    }

    public boolean getBoolean(String path, boolean def) {
        YamlNode node = resolve(path);
        if (node == null) return def;
        return node.asBoolean(def);
    }

    public boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    public List<String> getStringList(String path) {
        YamlNode node = resolve(path);
        if (node == null || node.getType() != NodeType.SEQUENCE) {
            return Collections.emptyList();
        }
        return node.asStringList();
    }

    public List<Integer> getIntList(String path) {
        YamlNode node = resolve(path);
        if (node == null || node.getType() != NodeType.SEQUENCE) {
            return Collections.emptyList();
        }
        return node.asIntList();
    }

    // ========================================
    //          Section / Structure Access
    // ========================================

    /**
     * Get a section (sub-map) at the given path.
     * Returns a lightweight view backed by the same document.
     * Returns null if the path does not exist or is not a map.
     */
    public ConfigManager getSection(String path) {
        YamlNode node = resolve(path);
        if (node == null || node.getType() != NodeType.MAP) return null;
        ConfigManager root = owner != null ? owner : this;
        return new ConfigManager(document, filePath, node, root);
    }

    /**
     * Get child key names at the given path.
     *
     * @param path the dot-separated path (empty string for root/current section)
     * @param deep if true, returns all descendant leaf paths; if false, immediate children only
     * @return set of key names in insertion order
     */
    public Set<String> getKeys(String path, boolean deep) {
        YamlNode node = (path == null || path.isEmpty()) ? getBaseNode() : resolve(path);
        if (node == null || node.getType() != NodeType.MAP) {
            return Collections.emptySet();
        }
        Set<String> keys = new LinkedHashSet<>();
        if (deep) {
            collectDeepKeys(node, "", keys);
        } else {
            keys.addAll(node.getChildKeys());
        }
        return keys;
    }

    public Set<String> getKeys(boolean deep) {
        return getKeys("", deep);
    }

    private void collectDeepKeys(YamlNode node, String prefix, Set<String> keys) {
        for (Map.Entry<String, YamlNode> entry : node.getChildren().entrySet()) {
            String fullKey = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            YamlNode child = entry.getValue();
            if (child.getType() == NodeType.MAP) {
                collectDeepKeys(child, fullKey, keys);
            } else {
                keys.add(fullKey);
            }
        }
    }

    /**
     * Check if a path exists (as either a value or a section).
     */
    public boolean contains(String path) {
        return resolve(path) != null;
    }

    /**
     * Check if a path is a configuration section (map node).
     */
    public boolean isSection(String path) {
        YamlNode node = resolve(path);
        return node != null && node.getType() == NodeType.MAP;
    }

    // ========================================
    //          Mutation
    // ========================================

    /**
     * Set a value at the given path. Updates both the tree and the line list.
     *
     * <p>If the key exists, rewrites just that line (preserving inline comments and quote style).
     * If the key does not exist, inserts a new line at the appropriate position.
     * If intermediate sections don't exist, they are created.</p>
     *
     * @param path  dot-separated path (e.g., "database.type")
     * @param value the new value (String, Number, Boolean, null, or List)
     */
    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        synchronized (getLock()) {
            YamlDocument doc = getDocument();

            if (value instanceof List<?> list) {
                setList(path, (List<Object>) list, doc);
                return;
            }

            YamlNode existing = resolve(path);
            if (existing != null && existing.getType() == NodeType.SCALAR) {
                // Update existing scalar in-place
                updateExistingScalar(existing, value, doc);
            } else if (existing != null && existing.getType() == NodeType.SEQUENCE && !(value instanceof List)) {
                // Converting sequence to scalar - remove sequence items first, then update
                removeSequenceItems(existing, doc);
                existing.setType(NodeType.SCALAR);
                updateExistingScalar(existing, value, doc);
            } else if (existing != null && existing.getType() == NodeType.MAP) {
                throw new IllegalArgumentException(
                        "Cannot overwrite section '" + path + "' with a scalar value. Use remove() first.");
            } else if (existing == null) {
                // Insert new key
                insertNewKey(path, value, doc);
            }
        }
    }

    /**
     * Remove a key (and all its children if it's a section) from both
     * the tree and the line list. Associated leading comments are also removed.
     */
    public void remove(String path) {
        synchronized (getLock()) {
            YamlDocument doc = getDocument();
            String[] segments = YamlPath.segments(path);

            // Find the parent and the target node
            YamlNode parent = getBaseNode();
            for (int i = 0; i < segments.length - 1; i++) {
                if (parent.getType() != NodeType.MAP) return;
                parent = parent.getChild(segments[i]);
                if (parent == null) return;
            }

            String targetKey = segments[segments.length - 1];
            YamlNode target = parent.getChild(targetKey);
            if (target == null) return;

            // Determine the line range to remove
            int firstLine = target.getFirstLine();
            int lastLine = target.getLastLine();

            // Remove lines from document
            if (firstLine >= 0 && lastLine >= firstLine && firstLine < doc.lineCount()) {
                int removeEnd = Math.min(lastLine + 1, doc.lineCount());
                doc.removeLines(firstLine, removeEnd);
            }

            // Remove from tree
            parent.removeChild(targetKey);
        }
    }

    private void updateExistingScalar(YamlNode node, Object value, YamlDocument doc) {
        // Determine quote style: preserve existing, or choose appropriate for new values
        YamlNode.QuoteStyle style = node.getQuoteStyle();
        if (value instanceof String s && style == YamlNode.QuoteStyle.UNQUOTED && needsQuoting(s)) {
            style = YamlNode.QuoteStyle.SINGLE_QUOTED;
        }

        // Update the node
        node.setScalarValue(value, style);

        // Reconstruct the line
        int lineIdx = node.getKeyLineIndex();
        if (lineIdx >= 0 && lineIdx < doc.lineCount()) {
            String indent = " ".repeat(node.getIndent());
            String formattedValue = formatScalarValue(value, style);
            String inlineComment = node.getInlineComment();
            String newLine = indent + node.getKey() + ": " + formattedValue;
            if (inlineComment != null && !inlineComment.isEmpty()) {
                newLine += "  " + inlineComment;
            }
            doc.replaceLine(lineIdx, newLine);
        }
    }

    private void setList(String path, List<Object> values, YamlDocument doc) {
        YamlNode existing = resolve(path);

        if (existing != null && existing.getType() == NodeType.SEQUENCE) {
            // Remove old items, insert new ones
            removeSequenceItems(existing, doc);
            insertSequenceItems(existing, values, doc);
        } else if (existing == null) {
            // Insert new key as a sequence
            insertNewSequence(path, values, doc);
        }
    }

    private void removeSequenceItems(YamlNode seqNode, YamlDocument doc) {
        List<YamlNode> items = seqNode.getItems();
        if (items.isEmpty()) return;

        int firstItemLine = items.getFirst().getFirstLine();
        int lastItemLine = items.getLast().getLastLine();

        if (firstItemLine >= 0 && lastItemLine >= firstItemLine) {
            doc.removeLines(firstItemLine, lastItemLine + 1);
        }
        seqNode.clearItems();
    }

    private void insertSequenceItems(YamlNode seqNode, List<Object> values, YamlDocument doc) {
        int insertAt = seqNode.getKeyLineIndex() + 1;
        int itemIndent = seqNode.getIndent() + 2;
        String indentStr = " ".repeat(itemIndent);

        List<DocumentLine> newLines = new ArrayList<>();
        for (Object val : values) {
            String formatted = formatScalarValue(val, chooseStyle(val));
            newLines.add(new DocumentLine(indentStr + "- " + formatted));
        }

        if (!newLines.isEmpty()) {
            doc.insertLines(insertAt, newLines);

            // Create item nodes
            for (int i = 0; i < values.size(); i++) {
                YamlNode item = new YamlNode(null, NodeType.SCALAR, insertAt + i, itemIndent);
                Object val = values.get(i);
                item.setScalarValue(val, chooseStyle(val));
                seqNode.addItem(item);
            }
        }
    }

    private void insertNewKey(String path, Object value, YamlDocument doc) {
        String[] segments = YamlPath.segments(path);

        // Walk down, creating intermediate MAP sections as needed
        YamlNode current = getBaseNode();
        int lastExistingDepth = -1;

        for (int i = 0; i < segments.length - 1; i++) {
            YamlNode child = current.getChild(segments[i]);
            if (child != null && child.getType() == NodeType.MAP) {
                current = child;
                lastExistingDepth = i;
            } else if (child == null) {
                // Need to create intermediate section
                int parentIndent = current == getBaseNode() ? -2 : current.getIndent();
                int newIndent = parentIndent + 2;
                int insertAt = findInsertionPoint(current, doc);

                String indentStr = newIndent >= 0 ? " ".repeat(newIndent) : "";
                DocumentLine sectionLine = new DocumentLine(indentStr + segments[i] + ":");
                doc.insertLines(insertAt, List.of(sectionLine));

                YamlNode section = new YamlNode(segments[i], NodeType.MAP, insertAt, Math.max(0, newIndent));
                current.addChild(segments[i], section);
                current = section;
                lastExistingDepth = i;
            } else {
                throw new IllegalArgumentException(
                        "Cannot create path '" + path + "': '" + segments[i] + "' exists but is not a section.");
            }
        }

        // Insert the final key
        String finalKey = segments[segments.length - 1];
        int parentIndent = current == getBaseNode() ? -2 : current.getIndent();
        int newIndent = parentIndent + 2;
        int insertAt = findInsertionPoint(current, doc);
        String indentStr = newIndent >= 0 ? " ".repeat(newIndent) : "";

        YamlNode.QuoteStyle style = chooseStyle(value);
        String formattedValue = formatScalarValue(value, style);
        DocumentLine newLine = new DocumentLine(indentStr + finalKey + ": " + formattedValue);
        doc.insertLines(insertAt, List.of(newLine));

        YamlNode newNode = new YamlNode(finalKey, NodeType.SCALAR, insertAt, Math.max(0, newIndent));
        newNode.setScalarValue(value, style);
        current.addChild(finalKey, newNode);
    }

    private void insertNewSequence(String path, List<Object> values, YamlDocument doc) {
        String[] segments = YamlPath.segments(path);

        // Walk down, creating intermediate MAP sections as needed
        YamlNode current = getBaseNode();
        for (int i = 0; i < segments.length - 1; i++) {
            YamlNode child = current.getChild(segments[i]);
            if (child != null && child.getType() == NodeType.MAP) {
                current = child;
            } else if (child == null) {
                int parentIndent = current == getBaseNode() ? -2 : current.getIndent();
                int newIndent = parentIndent + 2;
                int insertAt = findInsertionPoint(current, doc);

                String indentStr = newIndent >= 0 ? " ".repeat(newIndent) : "";
                DocumentLine sectionLine = new DocumentLine(indentStr + segments[i] + ":");
                doc.insertLines(insertAt, List.of(sectionLine));

                YamlNode section = new YamlNode(segments[i], NodeType.MAP, insertAt, Math.max(0, newIndent));
                current.addChild(segments[i], section);
                current = section;
            } else {
                throw new IllegalArgumentException(
                        "Cannot create sequence: '" + segments[i] + "' exists but is not a section.");
            }
        }

        // Insert sequence key line
        String finalKey = segments[segments.length - 1];
        int parentIndent = current == getBaseNode() ? -2 : current.getIndent();
        int newIndent = parentIndent + 2;
        int insertAt = findInsertionPoint(current, doc);
        String indentStr = newIndent >= 0 ? " ".repeat(newIndent) : "";

        DocumentLine keyLine = new DocumentLine(indentStr + finalKey + ":");
        doc.insertLines(insertAt, List.of(keyLine));

        YamlNode seqNode = new YamlNode(finalKey, NodeType.SEQUENCE, insertAt, Math.max(0, newIndent));
        current.addChild(finalKey, seqNode);

        // Insert items
        insertSequenceItems(seqNode, values, doc);
    }

    /**
     * Find where to insert a new child under the given parent node.
     * Places it after the last existing child's last line, or after the parent's key line.
     */
    private int findInsertionPoint(YamlNode parent, YamlDocument doc) {
        if (parent.getType() == NodeType.MAP && !parent.getChildren().isEmpty()) {
            // After the last child's last content line
            YamlNode lastChild = null;
            for (YamlNode child : parent.getChildren().values()) {
                lastChild = child;
            }
            return lastChild.getLastLine() + 1;
        }
        if (parent.getKeyLineIndex() >= 0) {
            return parent.getKeyLineIndex() + 1;
        }
        // Root node with no children — insert at end
        return doc.lineCount();
    }

    private static String formatScalarValue(Object value, YamlNode.QuoteStyle style) {
        if (value == null) return "";
        String str = value.toString();

        return switch (style) {
            case SINGLE_QUOTED -> "'" + str.replace("'", "''") + "'";
            case DOUBLE_QUOTED -> "\"" + escapeDoubleQuoted(str) + "\"";
            case UNQUOTED -> str;
        };
    }

    private static String escapeDoubleQuoted(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static boolean needsQuoting(String value) {
        if (value.isEmpty()) return true;
        // Check for YAML special characters that require quoting
        if (value.startsWith("#") || value.startsWith("&") || value.startsWith("*") ||
                value.startsWith("!") || value.startsWith("|") || value.startsWith(">") ||
                value.startsWith("{") || value.startsWith("[") || value.startsWith("'") ||
                value.startsWith("\"") || value.startsWith("%") || value.startsWith("@")) {
            return true;
        }
        if (value.contains(": ") || value.contains(" #")) return true;

        // Check if it looks like a boolean, null, or number
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("false") || lower.equals("yes") || lower.equals("no") ||
                lower.equals("on") || lower.equals("off") || lower.equals("null") || lower.equals("~")) {
            return true;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException ignored) {}

        return false;
    }

    private static YamlNode.QuoteStyle chooseStyle(Object value) {
        if (value instanceof String s) {
            return needsQuoting(s) ? YamlNode.QuoteStyle.SINGLE_QUOTED : YamlNode.QuoteStyle.UNQUOTED;
        }
        return YamlNode.QuoteStyle.UNQUOTED;
    }

    // ========================================
    //          Persistence
    // ========================================

    /**
     * Save the document to its file path, preserving all formatting.
     */
    public void save() throws IOException {
        if (filePath == null) {
            throw new IOException("No file path set — this config was created from a string or stream");
        }
        synchronized (getLock()) {
            YamlWriter.save(getDocument(), filePath);
        }
    }

    /**
     * Save to an arbitrary path.
     */
    public void save(Path path) throws IOException {
        synchronized (getLock()) {
            YamlWriter.save(getDocument(), path);
        }
    }

    // ========================================
    //          Reload
    // ========================================

    /**
     * Re-read the file from disk, replacing the in-memory document.
     */
    public void reload() throws IOException {
        if (filePath == null) {
            throw new IOException("No file path set — cannot reload");
        }
        String text = Files.readString(filePath, StandardCharsets.UTF_8);
        text = text.replace("\r\n", "\n");
        YamlDocument newDoc = YamlParser.parse(text);
        synchronized (getLock()) {
            this.document = newDoc;
        }
    }

    // ========================================
    //          Merging
    // ========================================

    /**
     * Merge missing keys from a defaults config into this config.
     * Keys present in defaults but missing from this config are inserted
     * at the correct position with their associated comments.
     * Existing keys are never modified.
     *
     * @param defaults the defaults config (e.g., loaded from JAR resource)
     * @return true if any keys were added (caller should save)
     */
    public boolean mergeDefaults(ConfigManager defaults) {
        synchronized (getLock()) {
            return YamlMerger.merge(getDocument(), defaults.getDocument());
        }
    }

    /**
     * Check if this config is missing any keys from the defaults.
     */
    public boolean hasNewKeys(ConfigManager defaults) {
        return YamlMerger.hasNewKeys(getDocument(), defaults.getDocument());
    }

    // ========================================
    //          Internal
    // ========================================

    /**
     * Navigate to a node by dot-separated path, relative to the base node.
     */
    private YamlNode getBaseNode() {
        return baseNode != null ? baseNode : getDocument().getRoot();
    }

    private YamlNode resolve(String path) {
        YamlNode base = getBaseNode();
        if (path == null || path.isEmpty()) return base;

        String[] segments = YamlPath.segments(path);
        YamlNode current = base;

        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (current.getType() != NodeType.MAP) return null;
            current = current.getChild(segment);
            if (current == null) return null;
        }
        return current;
    }

    YamlDocument getDocument() {
        return owner != null ? owner.document : document;
    }

    private Object getLock() {
        return owner != null ? owner.lock : lock;
    }

    /**
     * Get the file path this config was loaded from (null if from string/stream).
     */
    public Path getFilePath() {
        return filePath;
    }
}
