package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge case tests identified during code review.
 */
class EdgeCaseTest {

    // --- Merge order vs getKeys() order ---

    @Test
    void mergePreservesKeyOrderInTree() {
        ConfigManager user = ConfigManager.fromString("""
                a: 1
                c: 3
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                a: 1
                b: 2
                c: 3
                """);

        user.mergeDefaults(defaults);

        // getKeys should return a, b, c — matching the default/document order
        List<String> keys = new ArrayList<>(user.getKeys(false));
        assertEquals(List.of("a", "b", "c"), keys);
    }

    @Test
    void mergePreservesKeyOrderMultipleMissing() {
        ConfigManager user = ConfigManager.fromString("""
                a: 1
                e: 5
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                a: 1
                b: 2
                c: 3
                d: 4
                e: 5
                """);

        user.mergeDefaults(defaults);

        List<String> keys = new ArrayList<>(user.getKeys(false));
        assertEquals(List.of("a", "b", "c", "d", "e"), keys);
    }

    // --- Tab handling ---

    @Test
    void tabInIndentationThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("key:\n\t  value: true"));
    }

    @Test
    void tabAfterSpacesThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("key:\n  \tvalue: true"));
    }

    @Test
    void tabInValueIsAllowed() {
        // Tabs in values (not indentation) should be fine
        YamlDocument doc = YamlParser.parse("key: hello\tworld");
        assertNotNull(doc);
    }

    // --- CRLF handling ---

    @Test
    void crlfNormalizedOnLoad() {
        String crlf = "key: value\r\nother: stuff\r\n";
        ConfigManager config = ConfigManager.fromString(crlf);
        assertEquals("value", config.getString("key"));
        assertEquals("stuff", config.getString("other"));
    }

    // --- Trailing newline ---

    @Test
    void fileWithTrailingNewline() {
        YamlDocument doc = YamlParser.parse("key: value\n");
        assertEquals("key: value", doc.serialize());
    }

    @Test
    void fileWithoutTrailingNewline() {
        YamlDocument doc = YamlParser.parse("key: value");
        assertEquals("key: value", doc.serialize());
    }

    // --- Empty/null values ---

    @Test
    void emptyStringValue() {
        ConfigManager config = ConfigManager.fromString("key: ''");
        assertEquals("", config.getString("key"));
    }

    @Test
    void nullValue() {
        ConfigManager config = ConfigManager.fromString("key:");
        assertNull(config.getString("key"));
    }

    @Test
    void tildeNull() {
        ConfigManager config = ConfigManager.fromString("key: ~");
        assertNull(config.getString("key"));
    }

    @Test
    void explicitNull() {
        ConfigManager config = ConfigManager.fromString("key: null");
        assertNull(config.getString("key"));
    }

    // --- Invalid set() operations should throw ---

    @Test
    void setOnSectionThrows() {
        ConfigManager config = ConfigManager.fromString("""
                section:
                  child: value
                """);

        assertThrows(IllegalArgumentException.class, () ->
                config.set("section", "scalar"));
    }

    @Test
    void setUnderNonMapThrows() {
        ConfigManager config = ConfigManager.fromString("""
                scalar: value
                """);

        assertThrows(IllegalArgumentException.class, () ->
                config.set("scalar.child", "nested"));
    }

    // --- Unsupported constructs fail loudly ---

    @Test
    void flowStyleMapThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("{key: value}"));
    }

    @Test
    void flowStyleListThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("[item1, item2]"));
    }

    @Test
    void blockScalarThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("key: |\n  line1\n  line2"));
    }

    @Test
    void mergeKeyThrows() {
        assertThrows(YamlParseException.class, () ->
                YamlParser.parse("<<: *other"));
    }

    // --- Deeply nested sections ---

    @Test
    void deepNestedGetAndSet() {
        ConfigManager config = ConfigManager.fromString("""
                a:
                  b:
                    c:
                      d: deep
                """);

        assertEquals("deep", config.getString("a.b.c.d"));

        config.set("a.b.c.e", "new");
        assertEquals("new", config.getString("a.b.c.e"));
    }

    // --- Section with no children ---

    @Test
    void emptySectionReturnsEmptyKeys() {
        ConfigManager config = ConfigManager.fromString("""
                section:
                other: value
                """);

        // section: with nothing under it is a null scalar, not an empty map
        assertFalse(config.isSection("section"));
    }

    // --- getKeys deep includes nested paths ---

    @Test
    void getKeysDeepReturnsFullPaths() {
        ConfigManager config = ConfigManager.fromString("""
                a:
                  x: 1
                  y: 2
                b: 3
                """);

        Set<String> keys = config.getKeys(true);
        assertTrue(keys.contains("a.x"));
        assertTrue(keys.contains("a.y"));
        assertTrue(keys.contains("b"));
        assertFalse(keys.contains("a")); // sections excluded from deep keys
    }
}
