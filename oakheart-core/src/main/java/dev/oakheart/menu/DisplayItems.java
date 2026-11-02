package dev.oakheart.menu;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * The cross-plugin display-item mark.
 *
 * <p>Every icon a menu draws carries the PDC boolean {@code oakheart:display}. The key is data on
 * the item rather than state in the library, so every relocated copy of the library, in every
 * plugin, recognises every other plugin's icons. {@link DisplayGuards} removes marked stacks
 * wherever they surface outside a menu, and anything that accepts items by identity (a recipe
 * match, a delivery, OakOverflow's add API) rejects them with {@link #isDisplay}.</p>
 */
public final class DisplayItems {

    /** {@code oakheart:display}. */
    public static final NamespacedKey KEY = new NamespacedKey("oakheart", "display");

    private DisplayItems() {
    }

    /**
     * Returns a marked clone of {@code icon}. The argument is not changed.
     *
     * @throws IllegalArgumentException if {@code icon} is null or empty
     */
    public static ItemStack mark(ItemStack icon) {
        if (icon == null || icon.isEmpty()) {
            throw new IllegalArgumentException("A display icon must be a non-empty item");
        }
        ItemStack copy = icon.clone();
        copy.editPersistentDataContainer(pdc -> pdc.set(KEY, PersistentDataType.BOOLEAN, true));
        return copy;
    }

    /** Whether {@code stack} carries the display mark. Null and empty stacks are not. */
    public static boolean isDisplay(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getPersistentDataContainer().has(KEY);
    }
}
