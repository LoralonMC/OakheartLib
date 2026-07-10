package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for set() and remove() operations.
 */
class WriteApiTest {

    @Test
    void setExistingScalar() {
        ConfigManager config = ConfigManager.fromString("""
                name: TestPlugin
                debug: false
                max-count: 100
                """);

        config.set("debug", true);
        assertEquals(true, config.getBoolean("debug"));

        config.set("max-count", 200);
        assertEquals(200, config.getInt("max-count"));

        config.set("name", "NewName");
        assertEquals("NewName", config.getString("name"));

        // Verify the serialized output has only those lines changed
        String output = config.getDocument().serialize();
        assertTrue(output.contains("debug: true"));
        assertTrue(output.contains("max-count: 200"));
        assertTrue(output.contains("name: NewName"));
    }

    @Test
    void setExistingPreservesInlineComment() {
        ConfigManager config = ConfigManager.fromString(
                "repair_amount: 63  # 25% of max durability");

        config.set("repair_amount", 127);

        String output = config.getDocument().serialize();
        assertTrue(output.contains("127"), "Value should be updated");
        assertTrue(output.contains("# 25%"), "Inline comment should be preserved");
    }

    @Test
    void setExistingPreservesQuoteStyle() {
        ConfigManager config = ConfigManager.fromString("""
                single-quoted: 'hello world'
                double-quoted: "hello world"
                unquoted: hello
                """);

        config.set("single-quoted", "goodbye world");
        config.set("double-quoted", "goodbye world");
        config.set("unquoted", "goodbye");

        String output = config.getDocument().serialize();
        assertTrue(output.contains("'goodbye world'"), "Single quote style preserved");
        assertTrue(output.contains("\"goodbye world\""), "Double quote style preserved");
        assertTrue(output.contains("unquoted: goodbye"), "Unquoted style preserved");
    }

    @Test
    void setNewTopLevelKey() {
        ConfigManager config = ConfigManager.fromString("""
                existing: value
                """);

        config.set("new-key", "new-value");

        assertTrue(config.contains("new-key"));
        assertEquals("new-value", config.getString("new-key"));

        // Original key still there
        assertEquals("value", config.getString("existing"));
    }

    @Test
    void setNewNestedKey() {
        ConfigManager config = ConfigManager.fromString("""
                parent:
                  child1: value1
                """);

        config.set("parent.child2", "value2");

        assertEquals("value2", config.getString("parent.child2"));
        assertEquals("value1", config.getString("parent.child1"));
    }

    @Test
    void setCreatesIntermediateSections() {
        ConfigManager config = ConfigManager.fromString("""
                existing: true
                """);

        config.set("a.b.c", "deep-value");

        assertEquals("deep-value", config.getString("a.b.c"));
        assertTrue(config.isSection("a"));
        assertTrue(config.isSection("a.b"));
    }

    @Test
    void setListValue() {
        ConfigManager config = ConfigManager.fromString("""
                items:
                  - apple
                  - banana
                """);

        config.set("items", List.of("cherry", "date", "elderberry"));

        List<String> result = config.getStringList("items");
        assertEquals(List.of("cherry", "date", "elderberry"), result);
    }

    @Test
    void setNewListKey() {
        ConfigManager config = ConfigManager.fromString("""
                name: test
                """);

        config.set("tags", List.of("fun", "adventure", "survival"));

        List<String> result = config.getStringList("tags");
        assertEquals(List.of("fun", "adventure", "survival"), result);
    }

    @Test
    void removeKey() {
        ConfigManager config = ConfigManager.fromString("""
                keep: yes
                remove-me: true
                also-keep: yes
                """);

        config.remove("remove-me");

        assertFalse(config.contains("remove-me"));
        assertTrue(config.contains("keep"));
        assertTrue(config.contains("also-keep"));
    }

    @Test
    void removeSection() {
        ConfigManager config = ConfigManager.fromString("""
                keep: yes
                section:
                  child1: a
                  child2: b
                after: yes
                """);

        config.remove("section");

        assertFalse(config.contains("section"));
        assertFalse(config.contains("section.child1"));
        assertTrue(config.contains("keep"));
        assertTrue(config.contains("after"));
    }

    @Test
    void setNullValue() {
        ConfigManager config = ConfigManager.fromString("""
                key: value
                """);

        config.set("key", null);

        String output = config.getDocument().serialize();
        assertTrue(output.contains("key:") || output.contains("key: "));
    }

    @Test
    void setPreservesOtherLines() {
        String original = """
                # Header comment
                name: Test
                # Another comment
                settings:
                  debug: false
                  count: 10
                # Footer""";
        ConfigManager config = ConfigManager.fromString(original);

        config.set("settings.debug", true);

        String output = config.getDocument().serialize();
        assertTrue(output.contains("# Header comment"));
        assertTrue(output.contains("# Another comment"));
        assertTrue(output.contains("# Footer"));
        assertTrue(output.contains("name: Test"));
        assertTrue(output.contains("count: 10"));
        assertTrue(output.contains("debug: true"));
    }

    @Test
    void setStringThatNeedsQuoting() {
        ConfigManager config = ConfigManager.fromString("""
                key: simple
                """);

        config.set("key", "value: with colon");

        String output = config.getDocument().serialize();
        assertTrue(output.contains("'value: with colon'") || output.contains("\"value: with colon\""),
                "String with colon should be quoted: " + output);
    }

    // ── Regression tests for the 2026-07-09 review findings ──

    @Test
    void setFlowStyleListRewritesAsParseableBlock() {
        // Bug: removing a flow sequence's "item lines" removed the KEY line
        // itself, leaving orphaned block items and an unparseable file.
        ConfigManager config = ConfigManager.fromString("""
                before: 1
                key: [a, b, c]
                after: 2
                """);

        config.set("key", List.of("x", "y"));

        String output = config.getDocument().serialize();
        ConfigManager reparsed = ConfigManager.fromString(output);
        assertEquals(List.of("x", "y"), reparsed.getStringList("key"),
                "Flow list should round-trip through set(): " + output);
        assertEquals(1, reparsed.getInt("before"));
        assertEquals(2, reparsed.getInt("after"));
    }

    @Test
    void setFlowStyleListPreservesInlineComment() {
        ConfigManager config = ConfigManager.fromString(
                "key: [a, b] # keep me");

        config.set("key", List.of("x"));

        String output = config.getDocument().serialize();
        assertTrue(output.contains("# keep me"), "Inline comment should survive: " + output);
        assertEquals(List.of("x"), ConfigManager.fromString(output).getStringList("key"));
    }

    @Test
    void sectionViewNewKeyStaysInsideSection() {
        // Bug: a NEW key created through getSection(...) was written at indent 0,
        // silently relocating it to the document root.
        ConfigManager config = ConfigManager.fromString("""
                database:
                  host: localhost
                  port: 3306
                other: true
                """);

        config.getSection("database").set("username", "admin");

        String output = config.getDocument().serialize();
        ConfigManager reparsed = ConfigManager.fromString(output);
        assertEquals("admin", reparsed.getString("database.username"),
                "New key must live under the section: " + output);
        assertNull(reparsed.getString("username"),
                "New key must not leak to the root: " + output);
        assertEquals(true, reparsed.getBoolean("other"));
    }

    @Test
    void setStringWithNewlineRoundTrips() {
        // Bug: a raw newline was written unescaped, splitting the value across
        // physical lines; the remainder became a junk line silently dropped on
        // the next parse.
        ConfigManager config = ConfigManager.fromString("""
                greeting: hello
                after: 1
                """);

        config.set("greeting", "line1\nline2");

        String output = config.getDocument().serialize();
        ConfigManager reparsed = ConfigManager.fromString(output);
        assertEquals("line1\nline2", reparsed.getString("greeting"),
                "Newline strings must round-trip: " + output);
        assertEquals(1, reparsed.getInt("after"));
    }

    @Test
    void setStringWithLeadingTrailingWhitespaceRoundTrips() {
        ConfigManager config = ConfigManager.fromString("""
                key: plain
                """);

        config.set("key", "  padded  ");

        String output = config.getDocument().serialize();
        assertEquals("  padded  ", ConfigManager.fromString(output).getString("key"),
                "Padded strings must round-trip: " + output);
    }
}
