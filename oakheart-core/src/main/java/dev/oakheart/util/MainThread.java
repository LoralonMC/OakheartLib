package dev.oakheart.util;

import org.bukkit.Bukkit;

/**
 * Main-thread assertion for library methods that touch Bukkit state.
 */
public final class MainThread {

    private MainThread() {
    }

    /**
     * Throws {@link IllegalStateException} unless called on the server's main thread.
     *
     * @param what the operation, for the message (e.g. {@code "VoucherRedeemer.complete"})
     */
    public static void require(String what) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(what + " must be called on the main thread (was "
                    + Thread.currentThread().getName() + ")");
        }
    }
}
