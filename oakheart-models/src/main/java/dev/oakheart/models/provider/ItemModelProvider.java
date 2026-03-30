package dev.oakheart.models.provider;

import dev.oakheart.models.ModelProvider;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Modern item model provider using ItemMeta.setItemModel() (Paper 1.21.4+).
 */
public class ItemModelProvider implements ModelProvider {

    @Override
    public String getName() {
        return "Item Model";
    }

    @Override
    public boolean isAvailable() {
        try {
            ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Override
    public boolean applyModel(ItemStack item, String modelId) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        NamespacedKey key = parseNamespacedKey(modelId);
        if (key == null) return false;

        try {
            meta.setItemModel(key);
            item.setItemMeta(meta);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private NamespacedKey parseNamespacedKey(String modelId) {
        if (modelId == null || modelId.isEmpty()) return null;

        int colonIndex = modelId.indexOf(':');
        if (colonIndex > 0) {
            String namespace = modelId.substring(0, colonIndex).toLowerCase();
            String key = modelId.substring(colonIndex + 1).toLowerCase();
            if (isValidNamespace(namespace) && isValidKey(key)) {
                return new NamespacedKey(namespace, key);
            }
        } else {
            String key = modelId.toLowerCase();
            if (isValidKey(key)) {
                return NamespacedKey.minecraft(key);
            }
        }
        return null;
    }

    private boolean isValidNamespace(String namespace) {
        if (namespace.isEmpty()) return false;
        for (char c : namespace.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private boolean isValidKey(String key) {
        if (key.isEmpty()) return false;
        for (char c : key.toCharArray()) {
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '_' && c != '-' && c != '.' && c != '/') {
                return false;
            }
        }
        return true;
    }
}
