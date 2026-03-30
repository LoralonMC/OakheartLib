package dev.oakheart.models.provider;

import dev.lone.itemsadder.api.CustomStack;
import dev.oakheart.models.ModelProvider;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;
import java.util.logging.Logger;

/**
 * ItemsAdder model provider.
 * Detects whether the ItemsAdder item uses Item Model or CustomModelData and applies accordingly.
 */
public class ItemsAdderProvider implements ModelProvider {

    private final Logger logger;

    public ItemsAdderProvider(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getName() {
        return "ItemsAdder";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("dev.lone.itemsadder.api.CustomStack");
            return Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean applyModel(ItemStack item, String modelId) {
        try {
            CustomStack customStack = CustomStack.getInstance(modelId);

            if (customStack == null) {
                logger.warning("ItemsAdder item '" + modelId + "' not found. " +
                        "Make sure you have defined this item in your ItemsAdder config.");
                return false;
            }

            ItemStack iaItem = customStack.getItemStack();
            if (iaItem == null || !iaItem.hasItemMeta()) return false;

            ItemMeta sourceMeta = iaItem.getItemMeta();
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
                logger.warning("ItemsAdder item '" + modelId + "' has no Item Model or CustomModelData. " +
                        "Make sure it's properly configured in your resource pack.");
                return false;
            }

            CustomModelDataComponent targetCmd = targetMeta.getCustomModelDataComponent();
            targetCmd.setFloats(floats);
            targetMeta.setCustomModelDataComponent(targetCmd);
            item.setItemMeta(targetMeta);
            return true;

        } catch (Exception e) {
            logger.warning("Failed to apply ItemsAdder model '" + modelId + "': " + e.getMessage());
            return false;
        }
    }
}
