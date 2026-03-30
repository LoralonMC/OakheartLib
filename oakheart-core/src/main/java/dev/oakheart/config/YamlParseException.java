package dev.oakheart.config;

/**
 * Thrown when the parser encounters malformed or unsupported YAML.
 */
public final class YamlParseException extends RuntimeException {

    private final int lineNumber;
    private final String lineText;

    public YamlParseException(String message, int lineNumber, String lineText) {
        super("Line " + lineNumber + ": " + message + " -> " + lineText);
        this.lineNumber = lineNumber;
        this.lineText = lineText;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getLineText() {
        return lineText;
    }
}
