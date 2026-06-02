package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for renameKey / removeKey and the ConfigMigrator version runner.
 */
class MigrationTest {

    @Test
    void renameKeyMovesScalarAndPreservesValue() {
        ConfigManager c = ConfigManager.fromString("old-name: 42\nother: keep\n");

        assertTrue(c.renameKey("old-name", "new-name"));
        assertFalse(c.contains("old-name"));
        assertEquals(42, c.getInt("new-name"));
        assertEquals("keep", c.getString("other"));
    }

    @Test
    void renameKeyMovesListValue() {
        ConfigManager c = ConfigManager.fromString("worlds:\n  - world\n  - world_nether\n");

        assertTrue(c.renameKey("worlds", "enabled-worlds"));
        assertFalse(c.contains("worlds"));
        assertEquals(List.of("world", "world_nether"), c.getStringList("enabled-worlds"));
    }

    @Test
    void renameKeyIsNoOpWhenOldMissingOrNewExists() {
        ConfigManager c = ConfigManager.fromString("a: 1\nb: 2\n");

        assertFalse(c.renameKey("missing", "x"), "absent old key");
        assertFalse(c.renameKey("a", "b"), "new key already exists");
        // Re-running a completed rename is safe (old already gone).
        assertTrue(c.renameKey("a", "c"));
        assertFalse(c.renameKey("a", "c"));
        assertEquals(1, c.getInt("c"));
        assertEquals(2, c.getInt("b"));
    }

    @Test
    void renameKeyRejectsSections() {
        ConfigManager c = ConfigManager.fromString("section:\n  inner: 1\n");

        assertThrows(UnsupportedOperationException.class, () -> c.renameKey("section", "moved"));
    }

    @Test
    void removeKeyReportsWhetherItExisted() {
        ConfigManager c = ConfigManager.fromString("keep: 1\ndrop: 2\n");

        assertTrue(c.removeKey("drop"));
        assertFalse(c.removeKey("drop"));
        assertFalse(c.removeKey("never-there"));
        assertTrue(c.contains("keep"));
    }

    @Test
    void migratorRunsOnlyStepsAboveCurrentVersionAndStamps() {
        ConfigManager c = ConfigManager.fromString(
                "config-version: 1\nold-name: hi\ndeprecated: 5\n");

        boolean changed = ConfigMigrator.forConfig(c)
                .step(2, cm -> cm.renameKey("old-name", "new-name"))
                .step(3, cm -> cm.removeKey("deprecated"))
                .run();

        assertTrue(changed);
        assertEquals("hi", c.getString("new-name"));
        assertFalse(c.contains("old-name"));
        assertFalse(c.contains("deprecated"));
        assertEquals(3, c.getInt("config-version"));
    }

    @Test
    void migratorSkipsAlreadyAppliedSteps() {
        // File already at the latest version (fresh install) — nothing should run.
        ConfigManager c = ConfigManager.fromString("config-version: 3\nnew-name: hi\n");

        boolean changed = ConfigMigrator.forConfig(c)
                .step(2, cm -> cm.renameKey("old-name", "new-name"))
                .step(3, cm -> cm.removeKey("deprecated"))
                .run();

        assertFalse(changed);
        assertEquals("hi", c.getString("new-name"));
        assertEquals(3, c.getInt("config-version"));
    }

    @Test
    void migratorIsIdempotentAcrossRuns() {
        ConfigManager c = ConfigManager.fromString("config-version: 1\nold-name: hi\n");

        // A fresh migrator each run, as a plugin would build on every startup.
        assertTrue(ConfigMigrator.forConfig(c)
                .step(2, cm -> cm.renameKey("old-name", "new-name"))
                .run());
        assertEquals(2, c.getInt("config-version"));

        // Second run: version is already 2, step is skipped, no change.
        assertFalse(ConfigMigrator.forConfig(c)
                .step(2, cm -> cm.renameKey("old-name", "new-name"))
                .run());
        assertEquals("hi", c.getString("new-name"));
    }

    @Test
    void migratorRunsMultiStepUpgradeInOrder() {
        // Version 0 (no config-version key) → applies every step ascending.
        ConfigManager c = ConfigManager.fromString("a: 1\n");

        boolean changed = ConfigMigrator.forConfig(c)
                .step(3, cm -> cm.renameKey("b", "c"))   // registered out of order on purpose
                .step(2, cm -> cm.renameKey("a", "b"))   // runs first (ascending): a -> b, then b -> c
                .run();

        assertTrue(changed);
        assertEquals("1", c.getString("c"));   // a -> b (step 2) -> c (step 3)
        assertEquals(3, c.getInt("config-version"));
    }
}
