package dev.oakheart.item;

import dev.oakheart.util.MainThread;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.Chest;
import org.bukkit.block.Furnace;
import io.papermc.paper.block.TileStateInventoryHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Removes blocks without dropping their contents, and restores them from a snapshot.
 *
 * <p>For plugins that take blocks away and put them back (OakRewind's explosion rebuild). The
 * snapshot from {@link Block#getState()} carries the block entity's data, contents included, so
 * restoring it brings the contents back; the live block must therefore lose its contents
 * without dropping them, or every remove-and-restore duplicates them.</p>
 *
 * <p>For a multi-block removal, snapshot every block before removing any: removing one block can
 * change a neighbour (a double chest's other half, a door's other half) before its snapshot is
 * taken. {@link #removeAllWithoutDrops} does both steps in that order.</p>
 *
 * <p>Accepted limit: snapshots live in memory, so a crash between removal and restore loses the
 * blocks and their contents.</p>
 */
public final class BlockCustody {

    private BlockCustody() {
    }

    /** Snapshots one block. The returned state is never modified by this class. */
    public static BlockState snapshot(Block block) {
        MainThread.require("BlockCustody.snapshot");
        return block.getState();
    }

    /** Snapshots every block, in order, before anything is removed. */
    public static List<BlockState> snapshot(Collection<Block> blocks) {
        MainThread.require("BlockCustody.snapshot");
        List<BlockState> states = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            states.add(block.getState());
        }
        return states;
    }

    /**
     * Snapshots every block, then removes each without drops. Returns the snapshots in the order
     * given, for {@link #restore}.
     */
    public static List<BlockState> removeAllWithoutDrops(Collection<Block> blocks) {
        List<BlockState> states = snapshot(blocks);
        for (Block block : blocks) {
            removeWithoutDrops(block);
        }
        return states;
    }

    /**
     * Empties the block's live contents, then sets it to air without physics. Take the block's
     * snapshot first.
     *
     * <ol>
     *   <li>A chest is emptied through {@code getBlockInventory()}, so only this half of a double
     *       chest is touched. Any other {@link TileStateInventoryHolder} (containers, and the
     *       decorated pot, chiseled bookshelf, shelf, jukebox and lectern) through
     *       {@code getInventory()}.</li>
     *   <li>A fresh state is taken and written back with {@code update(true, false)}: a furnace's
     *       used recipes are cleared, since removing a furnace pays out their stored XP and a
     *       restore would bring the store back (an XP farm); a campfire's slots are emptied,
     *       since a campfire is a plain tile state, not an inventory holder.</li>
     *   <li>{@code setType(AIR, false)}.</li>
     * </ol>
     */
    public static void removeWithoutDrops(Block block) {
        MainThread.require("BlockCustody.removeWithoutDrops");
        BlockState state = block.getState();
        if (state instanceof Chest chest) {
            chest.getBlockInventory().clear();
        } else if (state instanceof TileStateInventoryHolder holder) {
            holder.getInventory().clear();
            // A fresh state: the live inventory is already empty, so update() writes nothing back.
            if (block.getState() instanceof Furnace furnace && !furnace.getRecipesUsed().isEmpty()) {
                furnace.setRecipesUsed(Map.of());
                furnace.update(true, false);
            }
        } else if (state instanceof Campfire campfire) {
            for (int slot = 0; slot < campfire.getSize(); slot++) {
                campfire.setItem(slot, null);
            }
            campfire.update(true, false);
        }
        block.setType(Material.AIR, false);
    }

    /** Puts a snapshot back, contents included, without physics. */
    public static boolean restore(BlockState snapshot) {
        MainThread.require("BlockCustody.restore");
        return snapshot.update(true, false);
    }
}
