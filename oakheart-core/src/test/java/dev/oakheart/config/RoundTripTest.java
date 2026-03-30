package dev.oakheart.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip test: parse every real config.yml, serialize back, assert byte-identical.
 */
class RoundTripTest {

    static Stream<String> configFiles() {
        return Stream.of(
                "OakMobDrops-config.yml",
                "OakTools-config.yml",
                "RaidCooldown-config.yml",
                "RegionMusic-config.yml",
                "TogglePhantoms-config.yml",
                "OakPets-config.yml",
                "OakTags-config.yml",
                "PlayerWarpsPlus-config.yml",
                "ShopkeepersStockControl-config.yml",
                "OakOverflow-config.yml",
                "OakRewind-config.yml",
                "OakheartWeb-config.yml"
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configFiles")
    void roundTrip(String filename) throws IOException {
        String original = loadResource(filename);
        assertNotNull(original, "Test resource not found: " + filename);
        assertFalse(original.isEmpty(), "Test resource is empty: " + filename);

        // Normalize Windows line endings before parsing
        String normalized = original.replace("\r\n", "\n");

        YamlDocument doc = YamlParser.parse(normalized);
        String serialized = doc.serialize();

        // Strip trailing newlines for comparison
        String normalizedOriginal = stripTrailingNewlines(normalized);
        String normalizedSerialized = stripTrailingNewlines(serialized);

        assertEquals(normalizedOriginal, normalizedSerialized,
                "Round-trip failed for " + filename + ". Diff at first mismatch:\n" +
                        findFirstDiff(normalizedOriginal, normalizedSerialized));
    }

    private String loadResource(String name) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(name)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String stripTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private String findFirstDiff(String a, String b) {
        String[] linesA = a.split("\n", -1);
        String[] linesB = b.split("\n", -1);

        int maxLines = Math.max(linesA.length, linesB.length);
        for (int i = 0; i < maxLines; i++) {
            String lineA = i < linesA.length ? linesA[i] : "<EOF>";
            String lineB = i < linesB.length ? linesB[i] : "<EOF>";
            if (!lineA.equals(lineB)) {
                return String.format("Line %d:\n  Expected: [%s]\n  Actual:   [%s]", i + 1, lineA, lineB);
            }
        }
        return "No diff found (line count: A=" + linesA.length + " B=" + linesB.length + ")";
    }
}
