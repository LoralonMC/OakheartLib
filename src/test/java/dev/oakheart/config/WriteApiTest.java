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
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString(
                "repair_amount: 63  # 25% of max durability");

        config.set("repair_amount", 127);

        String output = config.getDocument().serialize();
        assertTrue(output.contains("127"), "Value should be updated");
        assertTrue(output.contains("# 25%"), "Inline comment should be preserved");
    }

    @Test
    void setExistingPreservesQuoteStyle() {
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString("""
                parent:
                  child1: value1
                """);

        config.set("parent.child2", "value2");

        assertEquals("value2", config.getString("parent.child2"));
        assertEquals("value1", config.getString("parent.child1"));
    }

    @Test
    void setCreatesIntermediateSections() {
        OakheartConfig config = OakheartConfig.fromString("""
                existing: true
                """);

        config.set("a.b.c", "deep-value");

        assertEquals("deep-value", config.getString("a.b.c"));
        assertTrue(config.isSection("a"));
        assertTrue(config.isSection("a.b"));
    }

    @Test
    void setListValue() {
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString("""
                name: test
                """);

        config.set("tags", List.of("fun", "adventure", "survival"));

        List<String> result = config.getStringList("tags");
        assertEquals(List.of("fun", "adventure", "survival"), result);
    }

    @Test
    void removeKey() {
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString("""
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
        OakheartConfig config = OakheartConfig.fromString(original);

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
        OakheartConfig config = OakheartConfig.fromString("""
                key: simple
                """);

        config.set("key", "value: with colon");

        String output = config.getDocument().serialize();
        assertTrue(output.contains("'value: with colon'") || output.contains("\"value: with colon\""),
                "String with colon should be quoted: " + output);
    }
}
