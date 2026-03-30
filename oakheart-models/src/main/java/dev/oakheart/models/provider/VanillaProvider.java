package dev.oakheart.models.provider;

import dev.oakheart.models.ModelProvider;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * Vanilla CustomModelData provider. Always available.
 */
public class VanillaProvider implements ModelProvider {

    private final int customModelData;

    public VanillaProvider(int customModelData) {
        this.customModelData = customModelData;
    }

    @Override
    public String getName() {
        return "Vanilla CustomModelData";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean applyModel(ItemStack item, String modelId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setFloats(List.of((float) customModelData));
        meta.setCustomModelDataComponent(cmd);
        item.setItemMeta(meta);
        return true;
    }
}
