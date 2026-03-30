package dev.oakheart.models;

import org.bukkit.inventory.ItemStack;

/**
 * Interface for custom model providers (Nexo, ItemsAdder, vanilla, etc.).
 */
public interface ModelProvider {

    /**
     * Get the display name of this provider.
     */
    String getName();

    /**
     * Check if this provider is available on the server.
     */
    boolean isAvailable();

    /**
     * Apply a model to an item stack.
     *
     * @param item    the item stack to modify
     * @param modelId the provider-specific model identifier
     * @return true if the model was applied successfully
     */
    boolean applyModel(ItemStack item, String modelId);
}
