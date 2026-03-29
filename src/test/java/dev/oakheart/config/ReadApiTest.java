package dev.oakheart.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OakheartConfig read API against real config files.
 */
class ReadApiTest {

    @Test
    void readScalarValues() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        assertEquals(86400, config.getInt("raid-cooldown-seconds"));
        assertTrue(config.getBoolean("settings.auto-cleanup"));
        assertEquals(10, config.getInt("settings.cleanup-interval-minutes"));
        assertTrue(config.getBoolean("synchronized-reset.enabled"));
        assertEquals("00:00", config.getString("synchronized-reset.reset-time"));
        assertEquals("Ready", config.getString("placeholderapi.ready-message"));
    }

    @Test
    void readDefaults() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        assertEquals("fallback", config.getString("nonexistent.path", "fallback"));
        assertEquals(42, config.getInt("missing", 42));
        assertFalse(config.getBoolean("missing", false));
        assertEquals(3.14, config.getDouble("missing", 3.14));
    }

    @Test
    void readStringList() {
        OakheartConfig config = loadConfig("OakRewind-config.yml");

        List<String> types = config.getStringList("enabled-explosion-types");
        assertNotNull(types);
        assertFalse(types.isEmpty());
        assertTrue(types.contains("CREEPER"));
        assertTrue(types.contains("TNT"));
    }

    @Test
    void readSection() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        OakheartConfig settings = config.getSection("settings");
        assertNotNull(settings);
        assertTrue(settings.getBoolean("auto-cleanup"));
        assertEquals(10, settings.getInt("cleanup-interval-minutes"));
    }

    @Test
    void getKeysShallow() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        OakheartConfig settings = config.getSection("settings");
        assertNotNull(settings);
        Set<String> keys = settings.getKeys(false);
        assertTrue(keys.contains("auto-cleanup"));
        assertTrue(keys.contains("cleanup-interval-minutes"));
        assertTrue(keys.contains("log-cooldown-actions"));
    }

    @Test
    void getKeysDeep() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        Set<String> deepKeys = config.getKeys(true);
        assertTrue(deepKeys.contains("raid-cooldown-seconds"));
        assertTrue(deepKeys.contains("settings.auto-cleanup"));
        assertTrue(deepKeys.contains("synchronized-reset.enabled"));
        assertTrue(deepKeys.contains("synchronized-reset.reset-time"));
    }

    @Test
    void contains() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        assertTrue(config.contains("settings"));
        assertTrue(config.contains("settings.auto-cleanup"));
        assertFalse(config.contains("nonexistent"));
        assertFalse(config.contains("settings.nonexistent"));
    }

    @Test
    void isSection() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");

        assertTrue(config.isSection("settings"));
        assertTrue(config.isSection("synchronized-reset"));
        assertFalse(config.isSection("raid-cooldown-seconds"));
        assertFalse(config.isSection("nonexistent"));
    }

    @Test
    void readFromString() {
        String yaml = """
                name: TestPlugin
                version: '1.0.0'
                settings:
                  debug: false
                  max-count: 100
                  ratio: 0.75
                items:
                  - apple
                  - banana
                  - cherry
                """;
        OakheartConfig config = OakheartConfig.fromString(yaml);

        assertEquals("TestPlugin", config.getString("name"));
        assertEquals("1.0.0", config.getString("version"));
        assertFalse(config.getBoolean("settings.debug"));
        assertEquals(100, config.getInt("settings.max-count"));
        assertEquals(0.75, config.getDouble("settings.ratio"));
        assertEquals(List.of("apple", "banana", "cherry"), config.getStringList("items"));
    }

    @Test
    void readNestedSections() {
        OakheartConfig config = loadConfig("OakTools-config.yml");

        // OakTools has deep nesting like tools.wand.undo.enabled
        assertTrue(config.isSection("tools"));

        OakheartConfig tools = config.getSection("tools");
        assertNotNull(tools);
        Set<String> toolKeys = tools.getKeys(false);
        assertFalse(toolKeys.isEmpty());
    }

    @Test
    void emptyListReturned() {
        OakheartConfig config = loadConfig("RaidCooldown-config.yml");
        List<String> empty = config.getStringList("nonexistent");
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    private OakheartConfig loadConfig(String resource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(is, "Resource not found: " + resource);
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return OakheartConfig.fromString(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
