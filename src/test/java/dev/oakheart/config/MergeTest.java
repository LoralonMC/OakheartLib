package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for YamlMerger — merging defaults into user configs.
 */
class MergeTest {

    @Test
    void mergeAddsMissingScalar() {
        ConfigManager user = ConfigManager.fromString("""
                name: MyPlugin
                debug: false
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                name: MyPlugin
                debug: false
                version: 1
                """);

        boolean changed = user.mergeDefaults(defaults);

        assertTrue(changed);
        assertEquals(1, user.getInt("version"));
        // Existing values unchanged
        assertEquals("MyPlugin", user.getString("name"));
        assertFalse(user.getBoolean("debug"));
    }

    @Test
    void mergePreservesUserValues() {
        ConfigManager user = ConfigManager.fromString("""
                cooldown: 30
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                cooldown: 60
                """);

        boolean changed = user.mergeDefaults(defaults);

        assertFalse(changed);
        assertEquals(30, user.getInt("cooldown")); // User value preserved
    }

    @Test
    void mergeAddsNestedKey() {
        ConfigManager user = ConfigManager.fromString("""
                settings:
                  debug: false
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                settings:
                  debug: false
                  verbose: true
                """);

        boolean changed = user.mergeDefaults(defaults);

        assertTrue(changed);
        assertTrue(user.getBoolean("settings.verbose"));
        assertFalse(user.getBoolean("settings.debug")); // User value preserved
    }

    @Test
    void mergeAddsEntireSection() {
        ConfigManager user = ConfigManager.fromString("""
                name: Test
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                name: Test
                database:
                  type: sqlite
                  path: data.db
                """);

        boolean changed = user.mergeDefaults(defaults);

        assertTrue(changed);
        assertTrue(user.isSection("database"));
        assertEquals("sqlite", user.getString("database.type"));
        assertEquals("data.db", user.getString("database.path"));
    }

    @Test
    void mergePreservesComments() {
        ConfigManager user = ConfigManager.fromString("""
                # User's custom comment
                name: MyServer
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                name: Default
                # Port setting
                port: 8080
                """);

        user.mergeDefaults(defaults);

        String output = user.getDocument().serialize();
        assertTrue(output.contains("# User's custom comment"));
        assertTrue(output.contains("name: MyServer")); // User value preserved
        assertTrue(output.contains("# Port setting"));
        assertTrue(output.contains("port: 8080"));
    }

    @Test
    void mergeInsertsAtCorrectPosition() {
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

        assertEquals(2, user.getInt("b"));

        // Verify b appears between a and c
        String output = user.getDocument().serialize();
        int posA = output.indexOf("a: 1");
        int posB = output.indexOf("b: 2");
        int posC = output.indexOf("c: 3");
        assertTrue(posA < posB, "b should come after a");
        assertTrue(posB < posC, "b should come before c");
    }

    @Test
    void mergeAddsMissingList() {
        ConfigManager user = ConfigManager.fromString("""
                name: Test
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                name: Test
                items:
                  - apple
                  - banana
                """);

        user.mergeDefaults(defaults);

        List<String> items = user.getStringList("items");
        assertTrue(items.contains("apple"));
        assertTrue(items.contains("banana"));
    }

    @Test
    void hasNewKeysDetectsMissing() {
        ConfigManager user = ConfigManager.fromString("""
                a: 1
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                a: 1
                b: 2
                """);

        assertTrue(user.hasNewKeys(defaults));
    }

    @Test
    void hasNewKeysReturnsFalseWhenComplete() {
        ConfigManager user = ConfigManager.fromString("""
                a: 1
                b: 2
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                a: 1
                b: 2
                """);

        assertFalse(user.hasNewKeys(defaults));
    }

    @Test
    void mergeSkipsUserCustomizedSections() {
        // User has replaced default pet entries with their own
        ConfigManager user = ConfigManager.fromString("""
                pets:
                  my_custom_pet:
                    display: CustomPet
                  another_pet:
                    display: Another
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                pets:
                  zombie_pet:
                    display: ZombiePet
                  skeleton_pet:
                    display: SkeletonPet
                """);

        boolean changed = user.mergeDefaults(defaults);

        // Should NOT inject zombie_pet or skeleton_pet — user replaced the section
        assertFalse(changed);
        assertFalse(user.contains("pets.zombie_pet"));
        assertFalse(user.contains("pets.skeleton_pet"));
        assertTrue(user.contains("pets.my_custom_pet"));
    }

    @Test
    void mergeHandlesMultipleMissingKeys() {
        ConfigManager user = ConfigManager.fromString("""
                a: 1
                """);
        ConfigManager defaults = ConfigManager.fromString("""
                a: 1
                b: 2
                c: 3
                d: 4
                """);

        user.mergeDefaults(defaults);

        assertEquals(2, user.getInt("b"));
        assertEquals(3, user.getInt("c"));
        assertEquals(4, user.getInt("d"));
    }

    @Test
    void mergePreservesOriginalFormatting() {
        String userYaml = """
                # ============================================================
                # MY PLUGIN CONFIG
                # ============================================================

                name: MyPlugin

                # ============================================================
                # SETTINGS
                # ============================================================
                settings:
                  debug: false""";

        ConfigManager user = ConfigManager.fromString(userYaml);
        ConfigManager defaults = ConfigManager.fromString("""
                name: DefaultName
                settings:
                  debug: false
                  verbose: true
                """);

        user.mergeDefaults(defaults);

        String output = user.getDocument().serialize();
        // Original comments preserved
        assertTrue(output.contains("# ============================================================"));
        assertTrue(output.contains("# MY PLUGIN CONFIG"));
        assertTrue(output.contains("# SETTINGS"));
        // User values preserved
        assertTrue(output.contains("name: MyPlugin"));
        assertFalse(output.contains("name: DefaultName"));
        // New key added
        assertTrue(output.contains("verbose: true"));
    }
}
