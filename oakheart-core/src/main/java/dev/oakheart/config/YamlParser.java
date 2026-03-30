package dev.oakheart.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses YAML text into a YamlDocument while preserving exact line positions.
 *
 * <p>This is a purpose-built parser for the subset of YAML used in Minecraft configs:</p>
 * <ul>
 *   <li>Block-style maps and sequences</li>
 *   <li>Scalars: strings (quoted/unquoted), ints, longs, doubles, booleans, null</li>
 *   <li>Full-line comments, inline comments, blank lines</li>
 * </ul>
 *
 * <p><strong>Not supported (by design):</strong> anchors, aliases, flow-style collections,
 * block scalars (| and >), merge keys, tags, complex keys, tabs for indentation,
 * sequences of maps (list items are always scalars).</p>
 */
public final class YamlParser {

    private YamlParser() {}

    /**
     * Parse the given text into a YamlDocument.
     */
    public static YamlDocument parse(String text) {
        if (text == null || text.isEmpty()) {
            return new YamlDocument(new ArrayList<>(),
                    new YamlNode(null, NodeType.MAP, -1, -1));
        }

        // Split lines preserving trailing empty line if file ends with newline
        List<String> rawLines = splitLines(text);
        return parse(rawLines);
    }

    /**
     * Parse from a list of raw line strings.
     */
    public static YamlDocument parse(List<String> rawLines) {
        List<DocumentLine> lines = new ArrayList<>(rawLines.size());
        for (String raw : rawLines) {
            lines.add(new DocumentLine(raw));
        }

        YamlNode root = new YamlNode(null, NodeType.MAP, -1, -1);
        List<Integer> pendingComments = new ArrayList<>();

        // Stack of (indent, node) for tracking nesting - the parent context
        List<StackEntry> stack = new ArrayList<>();
        stack.add(new StackEntry(-1, root));

        for (int i = 0; i < lines.size(); i++) {
            DocumentLine line = lines.get(i);
            String text = line.text();

            // Blank line
            if (line.isBlank()) {
                pendingComments.add(i);
                continue;
            }

            // Comment line
            if (line.isComment()) {
                pendingComments.add(i);
                continue;
            }

            // Check for tabs in indentation
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '\t') {
                    throw new YamlParseException("Tabs are not supported for indentation", i + 1, text);
                }
                if (ch != ' ') break;
            }

            int indent = line.indent();
            String trimmed = text.stripLeading();

            // Check for unsupported constructs
            rejectUnsupported(trimmed, i + 1, text);

            // Determine if this is a sequence item or a map entry
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                parseSequenceItem(lines, i, indent, trimmed, root, stack, pendingComments);
            } else {
                parseMapEntry(lines, i, indent, trimmed, root, stack, pendingComments);
            }
        }

        // Any remaining pending comments are trailing - attach to document
        // They stay in the line list but aren't attached to any node

        return new YamlDocument(lines, root);
    }

    private static void parseMapEntry(List<DocumentLine> lines, int lineIndex,
                                       int indent, String trimmed, YamlNode root,
                                       List<StackEntry> stack, List<Integer> pendingComments) {
        // Parse key: value
        int colonIndex = findKeyColonIndex(trimmed);
        if (colonIndex < 0) {
            throw new YamlParseException("Expected 'key: value' or 'key:'",
                    lineIndex + 1, lines.get(lineIndex).text());
        }

        String key = trimmed.substring(0, colonIndex).stripTrailing();
        String afterColon = trimmed.substring(colonIndex + 1);

        // Pop stack back to find the parent at a lower indent
        while (stack.size() > 1 && stack.getLast().indent >= indent) {
            stack.removeLast();
        }
        YamlNode parent = stack.getLast().node;

        // Parse the value portion (everything after "key:")
        String valuePart = afterColon.stripLeading();

        // Check for inline comment on the value
        String inlineComment = null;
        String rawValue = valuePart;

        if (!valuePart.isEmpty()) {
            // Extract inline comment (if value is not quoted)
            if (valuePart.charAt(0) != '\'' && valuePart.charAt(0) != '"') {
                int commentIdx = findInlineCommentIndex(valuePart);
                if (commentIdx >= 0) {
                    rawValue = valuePart.substring(0, commentIdx).stripTrailing();
                    inlineComment = valuePart.substring(commentIdx);
                }
            } else {
                // For quoted strings, inline comment comes after the closing quote
                int endQuote = findClosingQuote(valuePart);
                if (endQuote >= 0 && endQuote + 1 < valuePart.length()) {
                    String afterQuote = valuePart.substring(endQuote + 1).stripLeading();
                    if (afterQuote.startsWith("#")) {
                        inlineComment = afterQuote;
                        rawValue = valuePart.substring(0, endQuote + 1);
                    }
                }
            }
        }

        if (rawValue.isEmpty()) {
            // Could be a parent map, parent sequence, or null scalar - look ahead
            ChildType childType = peekChildType(lines, lineIndex, indent);

            switch (childType) {
                case SEQUENCE -> {
                    YamlNode node = new YamlNode(key, NodeType.SEQUENCE, lineIndex, indent);
                    node.setInlineComment(inlineComment);
                    node.setLeadingCommentLines(new ArrayList<>(pendingComments));
                    pendingComments.clear();
                    parent.addChild(key, node);
                    stack.add(new StackEntry(indent, node));
                }
                case MAP -> {
                    YamlNode node = new YamlNode(key, NodeType.MAP, lineIndex, indent);
                    node.setInlineComment(inlineComment);
                    node.setLeadingCommentLines(new ArrayList<>(pendingComments));
                    pendingComments.clear();
                    parent.addChild(key, node);
                    stack.add(new StackEntry(indent, node));
                }
                case NONE -> {
                    // Null scalar
                    YamlNode node = new YamlNode(key, NodeType.SCALAR, lineIndex, indent);
                    node.setScalarValue(null, YamlNode.QuoteStyle.UNQUOTED);
                    node.setInlineComment(inlineComment);
                    node.setLeadingCommentLines(new ArrayList<>(pendingComments));
                    pendingComments.clear();
                    parent.addChild(key, node);
                }
            }
        } else {
            // Parse scalar value
            YamlNode node = new YamlNode(key, NodeType.SCALAR, lineIndex, indent);
            parseAndSetScalar(node, rawValue);
            node.setInlineComment(inlineComment);
            node.setLeadingCommentLines(new ArrayList<>(pendingComments));
            pendingComments.clear();
            parent.addChild(key, node);
        }
    }

    private static void parseSequenceItem(List<DocumentLine> lines, int lineIndex,
                                           int indent, String trimmed, YamlNode root,
                                           List<StackEntry> stack, List<Integer> pendingComments) {
        // Strip the "- " prefix
        String itemContent = trimmed.equals("-") ? "" : trimmed.substring(2);

        // Find the parent sequence node.
        // Pop stack until we find a SEQUENCE node whose indent is less than the dash indent,
        // or a MAP node that contains a SEQUENCE child.
        YamlNode parent = null;

        // Walk the stack from top to find the owning sequence
        for (int s = stack.size() - 1; s >= 0; s--) {
            StackEntry entry = stack.get(s);
            if (entry.node.getType() == NodeType.SEQUENCE && entry.indent < indent) {
                parent = entry.node;
                // Pop everything above this entry
                while (stack.size() > s + 1) stack.removeLast();
                break;
            }
            if (entry.node.getType() == NodeType.MAP) {
                // Check if the last child is the sequence we need
                YamlNode lastChild = findLastSequenceChild(entry.node, indent);
                if (lastChild != null) {
                    parent = lastChild;
                    // Pop everything above this entry and push the sequence
                    while (stack.size() > s + 1) stack.removeLast();
                    stack.add(new StackEntry(lastChild.getIndent(), lastChild));
                    break;
                }
            }
        }

        if (parent == null) {
            throw new YamlParseException("Sequence item without a parent sequence key",
                    lineIndex + 1, lines.get(lineIndex).text());
        }

        // Check for inline comment
        String inlineComment = null;
        String rawValue = itemContent;

        if (!itemContent.isEmpty() && itemContent.charAt(0) != '\'' && itemContent.charAt(0) != '"') {
            int commentIdx = findInlineCommentIndex(itemContent);
            if (commentIdx >= 0) {
                rawValue = itemContent.substring(0, commentIdx).stripTrailing();
                inlineComment = itemContent.substring(commentIdx);
            }
        } else if (!itemContent.isEmpty()) {
            int endQuote = findClosingQuote(itemContent);
            if (endQuote >= 0 && endQuote + 1 < itemContent.length()) {
                String afterQuote = itemContent.substring(endQuote + 1).stripLeading();
                if (afterQuote.startsWith("#")) {
                    inlineComment = afterQuote;
                    rawValue = itemContent.substring(0, endQuote + 1);
                }
            }
        }

        YamlNode item = new YamlNode(null, NodeType.SCALAR, lineIndex, indent);
        parseAndSetScalar(item, rawValue);
        item.setInlineComment(inlineComment);
        item.setLeadingCommentLines(new ArrayList<>(pendingComments));
        pendingComments.clear();
        parent.addItem(item);
    }

    /**
     * Find the last child of a MAP node that is a SEQUENCE with indent less than dashIndent.
     */
    private static YamlNode findLastSequenceChild(YamlNode mapNode, int dashIndent) {
        if (mapNode.getType() != NodeType.MAP) return null;

        // Find the last SEQUENCE child whose indent is less than the dash indent
        YamlNode lastSeq = null;
        for (YamlNode child : mapNode.getChildren().values()) {
            if (child.getType() == NodeType.SEQUENCE && child.getIndent() < dashIndent) {
                lastSeq = child;
            }
        }
        return lastSeq;
    }

    private enum ChildType { MAP, SEQUENCE, NONE }

    /**
     * Look ahead to determine what kind of children a key with empty value has.
     */
    private static ChildType peekChildType(List<DocumentLine> lines, int currentIndex, int currentIndent) {
        for (int i = currentIndex + 1; i < lines.size(); i++) {
            DocumentLine next = lines.get(i);
            if (next.isBlank() || next.isComment()) continue;

            int nextIndent = next.indent();
            if (nextIndent <= currentIndent) {
                return ChildType.NONE; // Same or less indent = null scalar
            }

            String nextTrimmed = next.text().stripLeading();
            if (nextTrimmed.startsWith("- ") || nextTrimmed.equals("-")) {
                return ChildType.SEQUENCE;
            }
            return ChildType.MAP;
        }
        return ChildType.NONE; // End of file
    }

    /**
     * Parse a raw value string and set it on the node.
     */
    private static void parseAndSetScalar(YamlNode node, String rawValue) {
        if (rawValue == null || rawValue.isEmpty()) {
            node.setScalarValue(null, YamlNode.QuoteStyle.UNQUOTED);
            return;
        }

        // Single-quoted string
        if (rawValue.startsWith("'") && rawValue.endsWith("'") && rawValue.length() >= 2) {
            String inner = rawValue.substring(1, rawValue.length() - 1);
            // Unescape single quotes: '' -> '
            inner = inner.replace("''", "'");
            node.setScalarValue(inner, YamlNode.QuoteStyle.SINGLE_QUOTED);
            return;
        }

        // Double-quoted string
        if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length() >= 2) {
            String inner = rawValue.substring(1, rawValue.length() - 1);
            // Basic unescape for common sequences
            inner = unescapeDoubleQuoted(inner);
            node.setScalarValue(inner, YamlNode.QuoteStyle.DOUBLE_QUOTED);
            return;
        }

        // Null
        if (rawValue.equals("null") || rawValue.equals("~")) {
            node.setScalarValue(null, YamlNode.QuoteStyle.UNQUOTED);
            return;
        }

        // Boolean
        String lower = rawValue.toLowerCase(Locale.ROOT);
        if (lower.equals("true") || lower.equals("yes") || lower.equals("on")) {
            node.setScalarValue(Boolean.TRUE, YamlNode.QuoteStyle.UNQUOTED);
            return;
        }
        if (lower.equals("false") || lower.equals("no") || lower.equals("off")) {
            node.setScalarValue(Boolean.FALSE, YamlNode.QuoteStyle.UNQUOTED);
            return;
        }

        // Integer (including negative)
        try {
            long longVal = Long.parseLong(rawValue);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                node.setScalarValue((int) longVal, YamlNode.QuoteStyle.UNQUOTED);
            } else {
                node.setScalarValue(longVal, YamlNode.QuoteStyle.UNQUOTED);
            }
            return;
        } catch (NumberFormatException ignored) {}

        // Double/Float
        try {
            double doubleVal = Double.parseDouble(rawValue);
            node.setScalarValue(doubleVal, YamlNode.QuoteStyle.UNQUOTED);
            return;
        } catch (NumberFormatException ignored) {}

        // Plain string
        node.setScalarValue(rawValue, YamlNode.QuoteStyle.UNQUOTED);
    }

    /**
     * Find the colon that separates key from value in a YAML line.
     * The colon must be followed by a space, end of line, or nothing (for "key:").
     * Must not be inside quotes.
     */
    private static int findKeyColonIndex(String trimmed) {
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == ':' && !inSingleQuote && !inDoubleQuote) {
                // Colon must be followed by space, EOL, or nothing
                if (i + 1 >= trimmed.length() || trimmed.charAt(i + 1) == ' ') {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Find an inline comment (space + #) in a value string, not inside quotes.
     */
    private static int findInlineCommentIndex(String value) {
        for (int i = 0; i < value.length() - 1; i++) {
            if (value.charAt(i) == ' ' && value.charAt(i + 1) == '#') {
                // Check if there's a space before (two spaces + #) for clearer comment detection
                if (i > 0 && value.charAt(i - 1) == ' ') {
                    return i - 1;
                }
                return i;
            }
        }
        return -1;
    }

    /**
     * Find the closing quote in a quoted string value.
     */
    private static int findClosingQuote(String value) {
        if (value.isEmpty()) return -1;
        char quoteChar = value.charAt(0);

        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == quoteChar) {
                // For single quotes, check for escaped ''
                if (quoteChar == '\'' && i + 1 < value.length() && value.charAt(i + 1) == '\'') {
                    i++; // skip escaped quote
                    continue;
                }
                // For double quotes, check for backslash escape
                if (quoteChar == '"' && i > 0 && value.charAt(i - 1) == '\\') {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    /**
     * Unescape common double-quoted string escape sequences.
     */
    private static String unescapeDoubleQuoted(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '/' -> { sb.append('/'); i++; }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Reject unsupported YAML constructs at parse time.
     */
    private static void rejectUnsupported(String trimmed, int lineNumber, String rawText) {
        // Flow-style collections
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            throw new YamlParseException("Flow-style collections are not supported", lineNumber, rawText);
        }
        // Anchors and aliases
        if (trimmed.contains("&") && !trimmed.contains("'") && !trimmed.contains("\"")) {
            // Simple heuristic: & outside quotes likely means anchor
            int ampIdx = trimmed.indexOf('&');
            if (ampIdx >= 0) {
                // Check it's not inside a value after colon
                int colonIdx = findKeyColonIndex(trimmed);
                if (ampIdx < colonIdx || colonIdx < 0) {
                    // Could be an anchor in key position - but be lenient for values
                }
            }
        }
        if (trimmed.startsWith("*") && !trimmed.startsWith("*:")) {
            // Alias reference
            // Be careful: * can appear in values, only reject standalone aliases
        }
        // Block scalars
        if (trimmed.endsWith("|") || trimmed.endsWith(">") || trimmed.endsWith("|-") || trimmed.endsWith(">-")) {
            // Check if this is a key with block scalar value
            int colonIdx = findKeyColonIndex(trimmed);
            if (colonIdx >= 0) {
                String afterColon = trimmed.substring(colonIdx + 1).strip();
                if (afterColon.equals("|") || afterColon.equals(">") ||
                        afterColon.equals("|-") || afterColon.equals(">-") ||
                        afterColon.equals("|+") || afterColon.equals(">+")) {
                    throw new YamlParseException("Block scalars (| and >) are not supported",
                            lineNumber, rawText);
                }
            }
        }
        // Merge keys
        if (trimmed.startsWith("<<:")) {
            throw new YamlParseException("Merge keys (<<) are not supported", lineNumber, rawText);
        }
        // Tags
        if (trimmed.startsWith("!!") || trimmed.startsWith("!")) {
            int colonIdx = findKeyColonIndex(trimmed);
            if (colonIdx >= 0) {
                String afterColon = trimmed.substring(colonIdx + 1).strip();
                if (afterColon.startsWith("!!") || afterColon.startsWith("!")) {
                    throw new YamlParseException("YAML tags are not supported", lineNumber, rawText);
                }
            }
        }
    }

    /**
     * Split text into lines, handling \r\n and \n.
     * Preserves a trailing empty string if the text ends with a newline.
     */
    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                int end = (i > 0 && text.charAt(i - 1) == '\r') ? i - 1 : i;
                lines.add(text.substring(start, end));
                start = i + 1;
            }
        }
        // Add the last line (even if empty, to preserve trailing newline)
        if (start <= text.length()) {
            String last = text.substring(start);
            // Only add trailing empty string if the text ended with newline
            if (start < text.length() || !last.isEmpty()) {
                lines.add(last);
            }
        }
        return lines;
    }

    private record StackEntry(int indent, YamlNode node) {}
}
