package dev.oakheart.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a YamlDocument to disk. Since the document maintains a line list
 * that is the exact file contents, writing is trivial: join lines with
 * newlines and write atomically.
 */
public final class YamlWriter {

    private YamlWriter() {}

    /**
     * Save the document to the given path using atomic write
     * (write to temp file, then rename).
     */
    public static void save(YamlDocument document, Path path) throws IOException {
        String content = document.serialize();

        // Ensure content ends with a newline (standard for text files)
        if (!content.isEmpty() && !content.endsWith("\n")) {
            content += "\n";
        }

        // Write to temp file in the same directory, then atomic move
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tempFile = Files.createTempFile(
                parent != null ? parent : Path.of("."),
                ".oakheart-", ".tmp");
        try {
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);
            try {
                Files.move(tempFile, path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (UnsupportedOperationException | IOException e) {
                // Fallback: non-atomic move if ATOMIC_MOVE not supported
                Files.move(tempFile, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Clean up temp file on failure
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {}
            throw e;
        }
    }
}
