package dev.oakheart.config;

/**
 * One line from a YAML file. Stores the raw text exactly as it appears
 * on disk (without the line terminator).
 */
public record DocumentLine(String text) {

    /**
     * Returns the number of leading spaces.
     */
    public int indent() {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ' ') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /**
     * Returns true if this line is blank or contains only whitespace.
     */
    public boolean isBlank() {
        return text.isBlank();
    }

    /**
     * Returns true if this line is a comment (first non-space char is #).
     */
    public boolean isComment() {
        String trimmed = text.stripLeading();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '#';
    }
}
