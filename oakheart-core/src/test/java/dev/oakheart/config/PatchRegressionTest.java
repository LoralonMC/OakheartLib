package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regressions for the 1.3.2 fixes (Plugin Review 2026-11-01, LIB-01/02/05/07/08/10/13).
 * Each test reproduces the failure the review proved with a harness.
 */
class PatchRegressionTest {

    private static String text(ConfigManager config) {
        return config.getDocument().serialize();
    }

    private static ConfigManager reload(ConfigManager config) {
        return ConfigManager.fromString(text(config));
    }

    // ---------------------------------------------------------------- LIB-01

    @Test
    void mergeThenSyncKeepsAdminValues() {
        ConfigManager user = ConfigManager.fromString("a: 1\nb: 2\nc: 3\nd: 4\ne: 55\nf: 66\ng: 77\n");
        ConfigManager baseline = ConfigManager.fromString("a: 1\nb: 2\nc: 3\nnewish: 0\nd: 4\ne: 5\nf: 6\ng: 7\n");
        ConfigManager defaults = ConfigManager.fromString(
                "a: 1\nb: 2\nc: 3\n# now documented\nnewish: 0\nd: 4\ne: 5\nf: 6\ng: 7\n");

        user.mergeDefaults(defaults);
        user.syncComments(defaults, baseline);

        assertEquals(55, user.getInt("e", -1), "admin value must survive merge + sync");
        assertEquals(66, user.getInt("f", -1));
        assertTrue(user.contains("newish"));
        ConfigManager again = reload(user);
        assertEquals(55, again.getInt("e", -1));
        assertEquals(1, text(user).split("# now documented", -1).length - 1, "comment written once");
    }

    @Test
    void mergeThenSyncKeepsSections() {
        ConfigManager user = ConfigManager.fromString("a: 1\nb: 2\nc: 3\nd: 4\nsec:\n  x: 10\n  y: 20\ng: 7\n");
        ConfigManager baseline = ConfigManager.fromString("a: 1\nb: 2\nc: 3\nnewish: 0\nd: 4\nsec:\n  x: 1\n  y: 2\ng: 7\n");
        ConfigManager defaults = ConfigManager.fromString(
                "a: 1\nb: 2\nc: 3\n# now documented\nnewish: 0\nd: 4\nsec:\n  x: 1\n  y: 2\ng: 7\n");

        user.mergeDefaults(defaults);
        user.syncComments(defaults, baseline);

        ConfigManager again = reload(user);
        assertTrue(again.isSection("sec"));
        assertEquals(10, again.getInt("sec.x", -1));
        assertEquals("4", again.getString("d"));
    }

    @Test
    void removeOfJustMergedKeyRemovesItsLines() {
        ConfigManager user = ConfigManager.fromString("a: 1\nb: 2\nc: 3\n");
        ConfigManager defaults = ConfigManager.fromString("a: 1\nb: 2\n# about new\nnewkey: 5\nc: 3\n");
        user.mergeDefaults(defaults);
        user.remove("newkey");

        String out = text(user);
        assertFalse(out.contains("newkey"));
        assertFalse(out.contains("# about new"));
        assertEquals(3, reload(user).getInt("c", -1));
    }

    // ---------------------------------------------------------------- LIB-02

    @Test
    void emptiedSectionStaysExtensibleAfterReload() {
        ConfigManager config = ConfigManager.fromString("homes:\n  a: 1\nother: 2\n");
        config.remove("homes.a");
        assertTrue(text(config).contains("homes: {}"));

        ConfigManager again = reload(config);
        assertTrue(again.isSection("homes"));
        assertTrue(again.getKeys("homes", false).isEmpty());
        again.set("homes.b", 2);
        assertEquals(2, again.getInt("homes.b", -1));

        ConfigManager third = reload(again);
        assertEquals(2, third.getInt("homes.b", -1));
        assertEquals(2, third.getInt("other", -1));
        assertFalse(text(third).contains("{}"));
    }

    @Test
    void emptiedNestedSectionCanBeRefilled() {
        // The OakShelves shape: placed.<set>.<pool>.<index>.uuid
        ConfigManager config = ConfigManager.fromString("placed:\n  s:\n    pool:\n      0:\n        uuid: x\n");
        config.remove("placed.s.pool.0");
        ConfigManager again = reload(config);
        again.set("placed.s.pool.0.uuid", "y");
        assertEquals("y", reload(again).getString("placed.s.pool.0.uuid"));
    }

    @Test
    void emptiedListStaysExtensibleAfterReload() {
        ConfigManager config = ConfigManager.fromString("blocked:\n  - a\n");
        config.set("blocked", List.of());
        assertTrue(text(config).contains("blocked: []"));

        ConfigManager again = reload(config);
        assertTrue(again.getStringList("blocked").isEmpty());
        again.set("blocked", List.of("b", "c"));
        assertEquals(List.of("b", "c"), reload(again).getStringList("blocked"));
        assertFalse(text(again).contains("[]"));
    }

    @Test
    void newEmptyListIsWrittenAsPlaceholder() {
        ConfigManager config = ConfigManager.fromString("a: 1\n");
        config.set("list", List.of());
        ConfigManager again = reload(config);
        again.set("list", List.of("x"));
        assertEquals(List.of("x"), reload(again).getStringList("list"));
    }

    @Test
    void bareKeyLeftByEarlierVersionsCanBeWrittenInto() {
        // What 1.3.1 and older saved for an emptied section / list.
        ConfigManager config = ConfigManager.fromString("pool:\nlist:\nz: 1\n");
        config.set("pool.0.uuid", "u");
        config.set("list", List.of("a"));
        ConfigManager again = reload(config);
        assertEquals("u", again.getString("pool.0.uuid"));
        assertEquals(List.of("a"), again.getStringList("list"));
        assertEquals(1, again.getInt("z", -1));
    }

    @Test
    void emptyFlowMapParsesAsSectionAndRoundTrips() {
        String source = "sets: {}  # filled by /shelf bind\nnext: 1\n";
        ConfigManager config = ConfigManager.fromString(source);
        assertTrue(config.isSection("sets"));
        assertEquals(source.stripTrailing(), text(config).stripTrailing(), "untouched placeholder round-trips");

        config.set("sets.a.x", 1);
        ConfigManager again = reload(config);
        assertEquals(1, again.getInt("sets.a.x", -1));
        assertTrue(text(again).contains("# filled by /shelf bind"));
    }

    @Test
    void plainListRewriteKeepsKeyLineFormatting() {
        String source = "items: # keep me\n  - a\n";
        ConfigManager config = ConfigManager.fromString(source);
        config.set("items", List.of("b"));
        assertTrue(text(config).startsWith("items: # keep me\n"), text(config));
    }

    // ---------------------------------------------------------------- LIB-05

    @Test
    void flowListOnFirstKeyOfSequenceItemIsAList() {
        ConfigManager config = ConfigManager.fromString("""
                rules:
                  - types: [VILLAGER, PIG]
                    other: [c, d]
                    name: calm
                """);
        Map<?, ?> rule = config.getMapList("rules").getFirst();
        assertEquals(List.of("VILLAGER", "PIG"), rule.get("types"));
        assertEquals(List.of("c", "d"), rule.get("other"));
        assertEquals("calm", rule.get("name"));
    }

    // ---------------------------------------------------------------- LIB-07

    @Test
    void byteOrderMarkIsStripped() {
        ConfigManager config = ConfigManager.fromString("\uFEFFenabled: false\nother: 1\n");
        assertFalse(config.getBoolean("enabled", true));
        assertFalse(text(config).startsWith("\uFEFF"));
    }

    // ---------------------------------------------------------------- LIB-08

    @Test
    void renameKeyKeepsTypedListValues() {
        ConfigManager config = ConfigManager.fromString("old:\n  - 1\n  - 2\nflags:\n  - true\n");
        assertTrue(config.renameKey("old", "nw"));
        assertTrue(config.renameKey("flags", "fl"));
        ConfigManager again = reload(config);
        assertEquals(List.of(1, 2), again.getIntList("nw"));
        assertEquals(List.of(true), again.getBooleanList("fl"));
    }

    @Test
    void renameKeyRefusesListOfSections() {
        ConfigManager config = ConfigManager.fromString("old:\n  - id: a\n    n: 1\n  - id: b\n");
        assertThrows(UnsupportedOperationException.class, () -> config.renameKey("old", "new"));
        assertEquals(2, config.getMapList("old").size(), "nothing touched");
    }

    // ---------------------------------------------------------------- LIB-10

    @Test
    void doubleQuotedUnicodeEscapesAreDecoded() {
        ConfigManager config = ConfigManager.fromString("""
                a: "dots\\u2026end"
                b: "\\U0001F600"
                c: "\\x41"
                d: "kept \\\\u2026 literal"
                e: "bad \\uZZZZ"
                """);
        assertEquals("dots\u2026end", config.getString("a"));
        assertEquals(new String(Character.toChars(0x1F600)), config.getString("b"));
        assertEquals("A", config.getString("c"));
        assertEquals("kept \\u2026 literal", config.getString("d"), "escaped backslash stays literal");
        assertEquals("bad \\uZZZZ", config.getString("e"), "malformed escape left as written");
    }

    // ---------------------------------------------------------------- LIB-13

    @Test
    void escapedTrailingBackslashDoesNotSwallowComment() {
        ConfigManager config = ConfigManager.fromString("p: \"C:\\\\\"  # c\nq: 1\n");
        assertEquals("C:\\", config.getString("p"));
    }
}
