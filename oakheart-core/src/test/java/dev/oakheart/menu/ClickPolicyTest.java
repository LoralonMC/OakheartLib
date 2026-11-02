package dev.oakheart.menu;

import dev.oakheart.menu.ClickPolicy.Decision;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static dev.oakheart.menu.ClickPolicy.decideClick;
import static dev.oakheart.menu.ClickPolicy.decideDrag;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every row of the click table, in order, plus the drag rule. A 27-slot menu, inputs 10 and 12. */
class ClickPolicyTest {

    private static final int TOP = 27;
    private static final Set<Integer> INPUTS = Set.of(10, 12);
    private static final Set<Integer> NONE = Set.of();

    private static Decision click(InventoryAction action, ClickType type, int raw, boolean locked) {
        return decideClick(action, type, raw, TOP, INPUTS, locked);
    }

    @Test
    void row1CollectToCursorAndDoubleClickCancelEverywhere() {
        for (int raw : new int[] {10, 3, 40, ClickPolicy.OUTSIDE}) {
            assertEquals(Decision.CANCEL, click(InventoryAction.COLLECT_TO_CURSOR, ClickType.LEFT, raw, false), "raw " + raw);
            assertEquals(Decision.CANCEL, click(InventoryAction.PICKUP_ALL, ClickType.DOUBLE_CLICK, raw, false), "raw " + raw);
        }
        // Row 1 wins over row 3 (an unlocked input) and row 7 (the bottom inventory).
        assertEquals(Decision.CANCEL, click(InventoryAction.NOTHING, ClickType.DOUBLE_CLICK, 12, false));
    }

    @Test
    void row2OutsideAndBorderAllow() {
        assertEquals(Decision.ALLOW, click(InventoryAction.DROP_ALL_CURSOR, ClickType.LEFT, ClickPolicy.OUTSIDE, true));
        assertEquals(Decision.ALLOW, click(InventoryAction.NOTHING, ClickType.LEFT, ClickPolicy.BORDER, false));
    }

    @Test
    void row3UnlockedInputAllows() {
        assertEquals(Decision.ALLOW, click(InventoryAction.PLACE_ALL, ClickType.LEFT, 10, false));
        assertEquals(Decision.ALLOW, click(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, 12, false));
        assertEquals(Decision.ALLOW, click(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, 10, false));
    }

    @Test
    void row4DisplaySlotsAndLockedInputsCancel() {
        assertEquals(Decision.CANCEL, click(InventoryAction.PICKUP_ALL, ClickType.LEFT, 0, false));
        assertEquals(Decision.CANCEL, click(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, 26, false));
        assertEquals(Decision.CANCEL, click(InventoryAction.PLACE_ALL, ClickType.LEFT, 10, true));
        assertEquals(Decision.CANCEL, click(InventoryAction.SWAP_WITH_CURSOR, ClickType.RIGHT, 12, true));
    }

    @Test
    void row5ShiftFromBottom() {
        assertEquals(Decision.SHIFT_INSERT, click(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, 40, false));
        assertEquals(Decision.CANCEL, click(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, 40, true));
        assertEquals(Decision.CANCEL, decideClick(InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT, 40, TOP, NONE, false));
    }

    @Test
    void row6UnknownAndNothingInBottomCancel() {
        assertEquals(Decision.CANCEL, click(InventoryAction.UNKNOWN, ClickType.LEFT, 40, false));
        assertEquals(Decision.CANCEL, click(InventoryAction.NOTHING, ClickType.LEFT, 40, false));
    }

    @Test
    void row7OtherBottomClicksAllow() {
        assertEquals(Decision.ALLOW, click(InventoryAction.PICKUP_ALL, ClickType.LEFT, 40, false));
        assertEquals(Decision.ALLOW, click(InventoryAction.HOTBAR_SWAP, ClickType.NUMBER_KEY, 30, true));
        assertEquals(Decision.ALLOW, click(InventoryAction.DROP_ONE_SLOT, ClickType.DROP, 50, false));
    }

    @Test
    void firstTopSlotAfterTheMenuIsBottom() {
        assertEquals(Decision.ALLOW, click(InventoryAction.PICKUP_ALL, ClickType.LEFT, TOP, false));
        assertEquals(Decision.CANCEL, click(InventoryAction.PICKUP_ALL, ClickType.LEFT, TOP - 1, false));
    }

    @Test
    void drags() {
        assertEquals(Decision.ALLOW, decideDrag(Set.of(10, 12), TOP, INPUTS, false));
        assertEquals(Decision.ALLOW, decideDrag(Set.of(10, 30, 40), TOP, INPUTS, false));
        assertEquals(Decision.ALLOW, decideDrag(Set.of(30, 40), TOP, INPUTS, false));
        assertEquals(Decision.CANCEL, decideDrag(Set.of(10, 11), TOP, INPUTS, false));
        assertEquals(Decision.CANCEL, decideDrag(Set.of(0), TOP, NONE, false));
        assertEquals(Decision.CANCEL, decideDrag(Set.of(10), TOP, INPUTS, true));
        // A locked menu cancels even a drag that stays in the bottom inventory.
        assertEquals(Decision.CANCEL, decideDrag(Set.of(30, 40), TOP, INPUTS, true));
    }
}
