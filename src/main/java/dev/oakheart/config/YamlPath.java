package dev.oakheart.config;

/**
 * Utility for working with dot-separated YAML paths.
 */
public final class YamlPath {

    private YamlPath() {}

    /**
     * Split "a.b.c" into ["a", "b", "c"].
     * Returns a single empty-string element for empty/null input.
     */
    public static String[] segments(String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{""};
        }
        return path.split("\\.");
    }

    /**
     * Get parent path: "a.b.c" -> "a.b". Returns "" for top-level keys.
     */
    public static String parent(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        return path.substring(0, lastDot);
    }

    /**
     * Get the last segment: "a.b.c" -> "c".
     */
    public static String lastSegment(String path) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot < 0) {
            return path;
        }
        return path.substring(lastDot + 1);
    }

    /**
     * Join segments: ["a", "b"] -> "a.b".
     */
    public static String join(String... segments) {
        return String.join(".", segments);
    }
}
