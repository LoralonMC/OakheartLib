package dev.oakheart.menu;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

import java.util.Set;

/**
 * The click and drag rules for a top-inventory menu with optional input slots.
 *
 * <p>Pure functions with no Bukkit state, so the table is unit-tested. Rows are checked in
 * order and the first match wins:</p>
 *
 * <ol>
 *   <li>{@code COLLECT_TO_CURSOR} or a {@code DOUBLE_CLICK}, on any slot: CANCEL. A double
 *       click gathers matching stacks from the top inventory too, display items included
 *       (OakSmith's preview dupe, G1-01).</li>
 *   <li>Raw slot -999 (outside the window) or -1 (the border): ALLOW. A display item carried
 *       on the cursor is caught by {@link DisplayGuards}.</li>
 *   <li>A top slot that is an input slot, while not locked: ALLOW.</li>
 *   <li>Any other top slot (a display slot, or a locked input): CANCEL. The menu queues a
 *       display slot's action itself.</li>
 *   <li>A bottom slot with {@code MOVE_TO_OTHER_INVENTORY}: SHIFT_INSERT when the menu has
 *       input slots and is not locked, otherwise CANCEL.</li>
 *   <li>A bottom slot with {@code UNKNOWN} or {@code NOTHING}: CANCEL.</li>
 *   <li>Any other bottom-inventory click: ALLOW.</li>
 * </ol>
 *
 * <p>A drag is allowed only if every raw slot in the top inventory is an input slot and the
 * menu is not locked. A locked menu cancels every drag, including one that stays in the bottom
 * inventory.</p>
 */
public final class ClickPolicy {

    /** Raw slot for a click outside the window ({@code InventoryView.OUTSIDE}). */
    public static final int OUTSIDE = -999;
    /** Raw slot for a click on the window border. */
    public static final int BORDER = -1;

    public enum Decision {
        /** Let vanilla handle the click. */
        ALLOW,
        /** Cancel the click. */
        CANCEL,
        /** Cancel the click and hand the moving stack to the menu's shift-insert hook. */
        SHIFT_INSERT
    }

    private ClickPolicy() {
    }

    /**
     * Decides one click.
     *
     * @param action     the event's action
     * @param click      the event's click type
     * @param rawSlot    the event's raw slot
     * @param topSize    the size of the menu's (top) inventory
     * @param inputSlots the menu's input slots, as top-inventory raw slots
     * @param locked     whether the menu's inputs are locked
     */
    public static Decision decideClick(InventoryAction action, ClickType click, int rawSlot,
                                       int topSize, Set<Integer> inputSlots, boolean locked) {
        if (action == InventoryAction.COLLECT_TO_CURSOR || click == ClickType.DOUBLE_CLICK) {
            return Decision.CANCEL;
        }
        if (rawSlot == OUTSIDE || rawSlot == BORDER) {
            return Decision.ALLOW;
        }
        if (rawSlot < topSize) {
            return inputSlots.contains(rawSlot) && !locked ? Decision.ALLOW : Decision.CANCEL;
        }
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            return !inputSlots.isEmpty() && !locked ? Decision.SHIFT_INSERT : Decision.CANCEL;
        }
        if (action == InventoryAction.UNKNOWN || action == InventoryAction.NOTHING) {
            return Decision.CANCEL;
        }
        return Decision.ALLOW;
    }

    /**
     * Decides one drag. Never returns {@link Decision#SHIFT_INSERT}.
     *
     * @param rawSlots   the event's raw slots
     * @param topSize    the size of the menu's (top) inventory
     * @param inputSlots the menu's input slots, as top-inventory raw slots
     * @param locked     whether the menu's inputs are locked
     */
    public static Decision decideDrag(Set<Integer> rawSlots, int topSize, Set<Integer> inputSlots,
                                      boolean locked) {
        if (locked) {
            return Decision.CANCEL;
        }
        for (int slot : rawSlots) {
            if (slot < topSize && !inputSlots.contains(slot)) {
                return Decision.CANCEL;
            }
        }
        return Decision.ALLOW;
    }
}
