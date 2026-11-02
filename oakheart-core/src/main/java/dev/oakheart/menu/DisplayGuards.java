package dev.oakheart.menu;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Removes display-marked stacks ({@link DisplayItems}) wherever they escape a menu.
 *
 * <p>Every plugin that draws menus registers one. The mark is shared data, so a stack drawn by
 * any plugin is caught by any plugin's guards; the first copy to act removes it and the later
 * copies see nothing. Each removal is logged at WARNING, since a display item outside a menu
 * means some menu leaked one.</p>
 *
 * <table>
 *   <caption>Guarded events</caption>
 *   <tr><th>Event</th><th>Action</th></tr>
 *   <tr><td>A {@code DROP_*} click on a marked item in the player's inventory or a real
 *       container (not a menu's own slots), or any click outside the window with a marked
 *       cursor</td><td>Cancel; empty that slot or the cursor</td></tr>
 *   <tr><td>{@code PlayerDropItemEvent} with a marked stack</td><td>Cancel, and empty the item
 *       entity's stack: after a cancel the server puts that stack back into the inventory, so
 *       emptying it is what deletes the copy</td></tr>
 *   <tr><td>Entity or hopper pickup of a marked stack</td><td>Cancel; remove the item
 *       entity</td></tr>
 *   <tr><td>{@code ItemSpawnEvent} with a marked stack</td><td>Cancel</td></tr>
 *   <tr><td>Closing one of this plugin's menus, and joining</td><td>Remove marked stacks from
 *       every player-inventory slot and the cursor (and, on join, the ender chest)</td></tr>
 * </table>
 */
public final class DisplayGuards implements Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Predicate<InventoryHolder> ownMenu;

    private DisplayGuards(Plugin plugin, Predicate<InventoryHolder> ownMenu) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.ownMenu = ownMenu;
    }

    /**
     * Registers the guards for {@code plugin}. Call once, in {@code onEnable}.
     *
     * @param ownMenu true for the holders of this plugin's menus, whose closes trigger a sweep of
     *                the viewer's inventory. It receives null for holder-less inventories.
     * @throws IllegalStateException if this copy of the library was not relocated
     */
    public static DisplayGuards register(Plugin plugin, Predicate<InventoryHolder> ownMenu) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(ownMenu, "ownMenu");
        requireRelocated();
        DisplayGuards guards = new DisplayGuards(plugin, ownMenu);
        plugin.getServer().getPluginManager().registerEvents(guards, plugin);
        return guards;
    }

    /** Unregisters the guards. Not needed on disable; Bukkit drops a disabled plugin's listeners. */
    public void unregister() {
        HandlerList.unregisterAll(this);
    }

    /**
     * Fails if the menu package still has its original name. Every plugin shades and relocates
     * the library; an unrelocated copy can be shadowed by another plugin's copy through a joined
     * classpath. The name is assembled at runtime because Shadow rewrites matching string
     * literals during relocation.
     */
    static void requireRelocated() {
        String original = String.join(".", "dev", "oakheart", "menu");
        if (DisplayGuards.class.getPackageName().equals(original)) {
            throw new IllegalStateException("OakheartLib's " + original + " package is not "
                    + "relocated in this plugin. Add relocate lines for dev.oakheart.menu, "
                    + "dev.oakheart.item and dev.oakheart.storage to shadowJar.");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        InventoryAction action = event.getAction();
        if ((action == InventoryAction.DROP_ALL_SLOT || action == InventoryAction.DROP_ONE_SLOT)
                && escapedSlot(event)
                && DisplayItems.isDisplay(event.getCurrentItem())) {
            event.setCancelled(true);
            warn(event.getWhoClicked(), event.getCurrentItem(), "a drop click");
            event.setCurrentItem(null);
            return;
        }
        ItemStack cursor = event.getView().getCursor();
        if ((event.getRawSlot() == ClickPolicy.OUTSIDE
                || action == InventoryAction.DROP_ALL_CURSOR
                || action == InventoryAction.DROP_ONE_CURSOR)
                && DisplayItems.isDisplay(cursor)) {
            event.setCancelled(true);
            warn(event.getWhoClicked(), cursor, "a cursor drop");
            event.getView().setCursor(null);
        }
    }

    /**
     * Whether the clicked slot is somewhere a display item should never be: the player's own
     * inventory, or a real container (one with a location). A menu's own slots hold display items by
     * design; the menu cancels clicks on them, and pressing Q on a button must not delete it.
     */
    private static boolean escapedSlot(InventoryClickEvent event) {
        Inventory clicked = event.getClickedInventory();
        return clicked != null
                && (clicked == event.getView().getBottomInventory() || clicked.getLocation() != null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        Item drop = event.getItemDrop();
        ItemStack stack = drop.getItemStack();
        if (DisplayItems.isDisplay(stack)) {
            event.setCancelled(true);
            warn(event.getPlayer(), stack, "PlayerDropItemEvent");
            drop.setItemStack(ItemStack.empty());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        if (DisplayItems.isDisplay(item.getItemStack())) {
            event.setCancelled(true);
            warn(null, item.getItemStack(), "EntityPickupItemEvent by " + event.getEntity().getType());
            item.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHopperPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        if (DisplayItems.isDisplay(item.getItemStack())) {
            event.setCancelled(true);
            warn(null, item.getItemStack(), "InventoryPickupItemEvent");
            item.remove();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (DisplayItems.isDisplay(stack)) {
            event.setCancelled(true);
            warn(null, stack, "ItemSpawnEvent at " + event.getLocation().toVector());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder holder = event.getInventory().getHolder(false);
        if (holder != null && ownMenu.test(holder) && event.getPlayer() instanceof Player player) {
            sweep(player, holder.getClass().getSimpleName() + " close", false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sweep(event.getPlayer(), "join", true);
    }

    /**
     * Removes marked stacks from the player's inventory and cursor, and optionally the ender
     * chest. Returns the number of stacks removed.
     */
    public int sweep(Player player, String context, boolean enderChest) {
        int removed = 0;
        ItemStack cursor = player.getItemOnCursor();
        if (DisplayItems.isDisplay(cursor)) {
            warn(player, cursor, context + " (cursor)");
            player.setItemOnCursor(null);
            removed++;
        }
        removed += sweep(player, player.getInventory(), context);
        if (enderChest) {
            removed += sweep(player, player.getEnderChest(), context + " (ender chest)");
        }
        return removed;
    }

    private int sweep(Player player, Inventory inventory, String context) {
        int removed = 0;
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (DisplayItems.isDisplay(contents[slot])) {
                warn(player, contents[slot], context + " (slot " + slot + ")");
                inventory.setItem(slot, null);
                removed++;
            }
        }
        return removed;
    }

    private void warn(HumanEntity player, ItemStack stack, String context) {
        logger.warning("Removed a display item that escaped a menu: " + stack.getAmount() + "x "
                + stack.getType().getKey() + (player != null ? " from " + player.getName() : "")
                + " (" + context + ", guard of " + plugin.getName() + ")");
    }
}
