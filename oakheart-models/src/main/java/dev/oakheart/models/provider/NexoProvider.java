package dev.oakheart.models.provider;

import com.nexomc.nexo.api.NexoItems;
import dev.oakheart.models.ModelProvider;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;
import java.util.logging.Logger;

/**
 * Nexo model provider.
 * Detects whether the Nexo item uses Item Model or CustomModelData and applies accordingly.
 */
public class NexoProvider implements ModelProvider {

    private final Logger logger;

    public NexoProvider(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "Nexo";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.nexomc.nexo.api.NexoItems");
            return Bukkit.getPluginManager().getPlugin("Nexo") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean applyModel(ItemStack item, String modelId) {
        try {
            var nexoItemBuilder = NexoItems.itemFromId(modelId);

            if (nexoItemBuilder == null) {
                logger.warning("Nexo item '" + modelId + "' not found. " +
                        "Make sure you have defined this item in your Nexo config.");
                return false;
            }

            ItemStack nexoItem = nexoItemBuilder.build();
            if (nexoItem == null || !nexoItem.hasItemMeta()) {
                logger.warning("Nexo item '" + modelId + "' has no metadata.");
                return false;
            }

            ItemMeta sourceMeta = nexoItem.getItemMeta();
            ItemMeta targetMeta = item.getItemMeta();
            if (targetMeta == null) return false;

            // Prefer modern Item Model system
            if (sourceMeta.hasItemModel()) {
                NamespacedKey itemModel = sourceMeta.getItemModel();
                targetMeta.setItemModel(itemModel);
                item.setItemMeta(targetMeta);
                return true;
            }

            // Fall back to CustomModelData
            CustomModelDataComponent sourceCmd = sourceMeta.getCustomModelDataComponent();
            List<Float> floats = sourceCmd.getFloats();
            if (floats.isEmpty()) {
                logger.warning("Nexo item '" + modelId + "' has no Item Model or CustomModelData. " +
                        "Make sure it's properly configured in your resource pack.");
                return false;
            }

            CustomModelDataComponent targetCmd = targetMeta.getCustomModelDataComponent();
            targetCmd.setFloats(floats);
            targetMeta.setCustomModelDataComponent(targetCmd);
            item.setItemMeta(targetMeta);
            return true;

        } catch (Exception e) {
            logger.warning("Failed to apply Nexo model '" + modelId + "': " + e.getMessage());
            return false;
        }
    }
}
