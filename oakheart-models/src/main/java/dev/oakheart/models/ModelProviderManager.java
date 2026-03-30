package dev.oakheart.models;

import dev.oakheart.models.provider.ItemModelProvider;
import dev.oakheart.models.provider.ItemsAdderProvider;
import dev.oakheart.models.provider.NexoProvider;
import dev.oakheart.models.provider.VanillaProvider;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

/**
 * Resolves model ID strings and applies custom models to ItemStacks.
 *
 * <p>Supported model ID formats:</p>
 * <ul>
 *   <li>{@code nexo:<id>} — Nexo item</li>
 *   <li>{@code itemsadder:<id>} — ItemsAdder item</li>
 *   <li>{@code model:<namespace:key>} — Modern Item Model (Paper 1.21.4+)</li>
 *   <li>{@code 1001} (integer) — Vanilla CustomModelData</li>
 * </ul>
 */
public class ModelProviderManager {

    private final Logger logger;
    private final ItemModelProvider itemModelProvider;
    private final NexoProvider nexoProvider;
    private final ItemsAdderProvider itemsAdderProvider;

    public ModelProviderManager(Logger logger) {
        this.logger = logger;
        this.itemModelProvider = new ItemModelProvider();
        this.nexoProvider = new NexoProvider(logger);
        this.itemsAdderProvider = new ItemsAdderProvider(logger);
    }

    /**
     * Apply a model to an item stack using the appropriate provider.
     *
     * @param item    the item stack to modify
     * @param modelId the model ID string (e.g. "nexo:my_item", "model:myplugin:hat", "1001")
     * @return true if the model was applied successfully
     */
    public boolean applyModel(ItemStack item, String modelId) {
        if (modelId == null || modelId.isEmpty()) return false;

        // Check if it's a plain integer (vanilla CustomModelData)
        try {
            int customModelData = Integer.parseInt(modelId);
            return new VanillaProvider(customModelData).applyModel(item, "");
        } catch (NumberFormatException ignored) {
        }

        ModelProvider provider;
        String actualModelId = modelId;

        if (modelId.toLowerCase().startsWith("model:")) {
            provider = itemModelProvider;
            actualModelId = modelId.substring(6);

            if (!provider.isAvailable()) {
                logger.warning("Item Model provider requested but not available (requires Paper 1.21.4+)");
                logger.warning("Falling back to no custom model. Consider using CustomModelData instead.");
                return false;
            }
        } else if (modelId.toLowerCase().startsWith("nexo:")) {
            actualModelId = modelId.substring(5);
            provider = nexoProvider;

            if (!provider.isAvailable()) {
                logger.warning("Nexo provider requested but Nexo plugin is not available");
                return false;
            }
        } else if (modelId.toLowerCase().startsWith("itemsadder:")) {
            actualModelId = modelId.substring(11);
            provider = itemsAdderProvider;

            if (!provider.isAvailable()) {
                logger.warning("ItemsAdder provider requested but ItemsAdder plugin is not available");
                return false;
            }
        } else {
            logger.warning("Unrecognized model-id format '" + modelId + "'");
            logger.warning("Use an integer for vanilla CustomModelData, 'model:namespace:key' for Item Model, " +
                    "'nexo:id' for Nexo, or 'itemsadder:id' for ItemsAdder");
            return false;
        }

        boolean success = provider.applyModel(item, actualModelId);

        if (!success) {
            logger.warning("Failed to apply " + provider.getName() + " model '" + actualModelId + "'");
            logger.warning("Item will be created without custom model. Check your " +
                    provider.getName() + " configuration.");
        }

        return success;
    }

    /**
     * Get the provider name for a model ID string (for logging/diagnostics).
     */
    public String getProviderName(String modelId) {
        if (modelId == null || modelId.isEmpty()) return "Unknown";

        try {
            Integer.parseInt(modelId);
            return "Vanilla CustomModelData";
        } catch (NumberFormatException ignored) {
        }

        if (modelId.toLowerCase().startsWith("model:")) return "Item Model";
        if (modelId.toLowerCase().startsWith("nexo:")) return "Nexo";
        if (modelId.toLowerCase().startsWith("itemsadder:")) return "ItemsAdder";
        return "Unknown";
    }
}
