package dev.oakheart.util;

import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * A lightweight debug logger that gates messages behind a config boolean.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * DebugLogger debug = new DebugLogger(logger, configManager::isDebugMode);
 * debug.log("Processing %d items", items.size());
 * }</pre>
 */
public final class DebugLogger {

    private final Logger logger;
    private final BooleanSupplier enabled;

    public DebugLogger(Logger logger, BooleanSupplier enabled) {
        this.logger = logger;
        this.enabled = enabled;
    }

    /**
     * Log a debug message at INFO level if debug mode is enabled.
     */
    public void log(String message) {
        if (enabled.getAsBoolean()) {
            logger.info("[DEBUG] " + message);
        }
    }

    /**
     * Log a formatted debug message at INFO level if debug mode is enabled.
     * Uses {@link String#format} for formatting.
     */
    public void log(String format, Object... args) {
        if (enabled.getAsBoolean()) {
            logger.info("[DEBUG] " + String.format(format, args));
        }
    }
}
