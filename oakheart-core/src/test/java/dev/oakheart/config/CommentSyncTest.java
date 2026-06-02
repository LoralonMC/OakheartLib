package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for three-way leading-comment sync (ConfigManager.syncComments).
 * Assertions read the serialized document directly (same package).
 */
class CommentSyncTest {

    private static String serialized(ConfigManager c) {
        return c.getDocument().serialize();
    }

    @Test
    void updatesCommentTheUserNeverTouched() {
        ConfigManager user = ConfigManager.fromString("# old comment\nkey: 1\n");
        ConfigManager base = ConfigManager.fromString("# old comment\nkey: 9\n");
        ConfigManager def = ConfigManager.fromString("# new comment\nkey: 9\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# new comment"), out);
        assertFalse(out.contains("# old comment"), out);
        assertEquals(1, user.getInt("key"), "value must be preserved, only the comment changes");
    }

    @Test
    void preservesCommentTheAdminCustomised() {
        ConfigManager user = ConfigManager.fromString("# my own note\nkey: 1\n");
        ConfigManager base = ConfigManager.fromString("# old comment\nkey: 1\n");
        ConfigManager def = ConfigManager.fromString("# new comment\nkey: 1\n");

        assertFalse(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# my own note"), out);
        assertFalse(out.contains("# new comment"), out);
    }

    @Test
    void addsCommentWhenDefaultGainedOne() {
        ConfigManager user = ConfigManager.fromString("key: 1\n");
        ConfigManager base = ConfigManager.fromString("key: 1\n");
        ConfigManager def = ConfigManager.fromString("# freshly documented\nkey: 1\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# freshly documented"), out);
        assertEquals(1, user.getInt("key"));
    }

    @Test
    void removesCommentWhenDefaultDroppedIt() {
        ConfigManager user = ConfigManager.fromString("# going away\nkey: 1\n");
        ConfigManager base = ConfigManager.fromString("# going away\nkey: 1\n");
        ConfigManager def = ConfigManager.fromString("key: 1\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertFalse(out.contains("# going away"), out);
        assertEquals(1, user.getInt("key"));
    }

    @Test
    void noChangeWhenDefaultCommentUnchanged() {
        ConfigManager user = ConfigManager.fromString("# same\nkey: 1\n");
        ConfigManager base = ConfigManager.fromString("# same\nkey: 5\n");
        ConfigManager def = ConfigManager.fromString("# same\nkey: 5\n");

        assertFalse(user.syncComments(def, base));
        assertTrue(serialized(user).contains("# same"));
    }

    @Test
    void syncsNestedKeyComments() {
        ConfigManager user = ConfigManager.fromString("section:\n  # old inner\n  inner: 1\n");
        ConfigManager base = ConfigManager.fromString("section:\n  # old inner\n  inner: 1\n");
        ConfigManager def = ConfigManager.fromString("section:\n  # new inner\n  inner: 1\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# new inner"), out);
        assertFalse(out.contains("# old inner"), out);
        assertEquals(1, user.getInt("section.inner"));
    }

    @Test
    void multiLineCommentBlockUpdatesAsAUnit() {
        ConfigManager user = ConfigManager.fromString("# line a\n# line b\nkey: 1\n");
        ConfigManager base = ConfigManager.fromString("# line a\n# line b\nkey: 1\n");
        ConfigManager def = ConfigManager.fromString("# line a\n# line b improved\n# line c\nkey: 1\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# line b improved"), out);
        assertTrue(out.contains("# line c"), out);
        assertEquals(1, user.getInt("key"));
    }

    @Test
    void onlyTheChangedKeysCommentMovesOthersUntouched() {
        ConfigManager user = ConfigManager.fromString("# keep me\nfirst: 1\n# change me\nsecond: 2\n");
        ConfigManager base = ConfigManager.fromString("# keep me\nfirst: 1\n# change me\nsecond: 2\n");
        ConfigManager def = ConfigManager.fromString("# keep me\nfirst: 1\n# changed\nsecond: 2\n");

        assertTrue(user.syncComments(def, base));

        String out = serialized(user);
        assertTrue(out.contains("# keep me"), out);
        assertTrue(out.contains("# changed"), out);
        assertFalse(out.contains("# change me"), out);
        assertEquals(1, user.getInt("first"));
        assertEquals(2, user.getInt("second"));
    }
}
