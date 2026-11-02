package dev.oakheart.item;

import dev.oakheart.util.MainThread;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Two-phase voucher redemption: {@code begin} on the right-click, {@code complete} when the
 * player confirms (typically from a confirm menu, a tick or more later).
 *
 * <p>The voucher is consumed only after the grant succeeds, and only if it is still in the hand
 * it was clicked from, so moving, dropping or trading it away while the confirm menu is open
 * redeems nothing, and a failed grant costs nothing.</p>
 *
 * <ul>
 *   <li>Both {@code begin} overloads debounce to one begin per player per tick: one click can fire
 *       several interact events (block then air, or entity then air).</li>
 *   <li>Listeners calling {@code begin} must not use {@code ignoreCancelled}: a right-click on air
 *       arrives already cancelled.</li>
 *   <li>Vouchers work on a block right-click too, since Bedrock clients may not send air
 *       clicks.</li>
 *   <li>By default a player in CREATIVE or SPECTATOR cannot redeem: that would turn an item from
 *       OakCreative's creative inventory into a survival entitlement.</li>
 * </ul>
 *
 * <p>Residual: the window between the grant's own persistence and the {@code saveData()} that
 * persists the consumed voucher. Grants should persist synchronously.</p>
 */
public final class VoucherRedeemer {

    private final Plugin plugin;
    private final boolean allowCreative;
    private int debounceTick = Integer.MIN_VALUE;
    private final Set<UUID> begunThisTick = new HashSet<>();

    /** A redeemer that refuses players in CREATIVE or SPECTATOR. */
    public VoucherRedeemer(Plugin plugin) {
        this(plugin, false);
    }

    /**
     * @param allowCreative whether players in CREATIVE or SPECTATOR may redeem. Only for vouchers
     *                      whose grant cannot reach survival.
     */
    public VoucherRedeemer(Plugin plugin, boolean allowCreative) {
        this.plugin = plugin;
        this.allowCreative = allowCreative;
    }

    /**
     * Begins a redemption from a block or air right-click.
     *
     * <p>When the item in the event's hand is a voucher (a PDC string under {@code idKey} that
     * {@code isVoucher} accepts), the event's block and item use are denied, so a chest or door
     * does not open over the confirm menu. That happens even when no token is returned (debounced,
     * or a refused game mode).</p>
     *
     * @return a token, or empty if this is not a voucher click, a repeat within the tick, or the
     *         player's game mode is refused ({@link #refusesGameMode})
     */
    public Optional<VoucherToken> begin(PlayerInteractEvent event, NamespacedKey idKey,
                                        Predicate<String> isVoucher) {
        MainThread.require("VoucherRedeemer.begin");
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return Optional.empty();
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return Optional.empty();
        }
        String id = voucherId(event.getItem(), idKey, isVoucher);
        if (id == null) {
            return Optional.empty();
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        return token(event.getPlayer(), hand, idKey, id);
    }

    /**
     * Begins a redemption from an entity right-click. Item frames (and glow item frames) are
     * ignored, so vouchers stay frameable. For any other entity a voucher click cancels the
     * event, so a NAME_TAG voucher does not rename the mob.
     *
     * @return a token, or empty as for the block overload
     */
    public Optional<VoucherToken> begin(PlayerInteractEntityEvent event, NamespacedKey idKey,
                                        Predicate<String> isVoucher) {
        MainThread.require("VoucherRedeemer.begin");
        if (event.getRightClicked() instanceof ItemFrame) {
            return Optional.empty();
        }
        EquipmentSlot hand = event.getHand();
        Player player = event.getPlayer();
        String id = voucherId(player.getInventory().getItem(hand), idKey, isVoucher);
        if (id == null) {
            return Optional.empty();
        }
        event.setCancelled(true);
        return token(player, hand, idKey, id);
    }

    /**
     * Completes a redemption. Single-use: a token is spent by its first call, whatever the result.
     *
     * <ol>
     *   <li>Re-finds the voucher (same PDC id, amount at least 1) in the hand recorded by the token;
     *       returns false if it is not there, the player is offline, or the game mode is refused.</li>
     *   <li>Runs {@code grant}. False means nothing was granted, and the voucher is kept.</li>
     *   <li>On true, removes one voucher from that hand and calls {@code player.saveData()}.</li>
     * </ol>
     *
     * @return whether the grant ran and succeeded
     */
    public boolean complete(VoucherToken token, BooleanSupplier grant) {
        MainThread.require("VoucherRedeemer.complete");
        if (token.used) {
            return false;
        }
        token.used = true;
        Player player = Bukkit.getPlayer(token.playerId());
        if (player == null || !player.isConnected() || refusesGameMode(player)) {
            return false;
        }
        if (!token.voucherId().equals(voucherId(player.getInventory().getItem(token.hand()),
                token.idKey(), id -> true))) {
            return false;
        }
        if (!grant.getAsBoolean()) {
            return false;
        }
        // Re-read the hand: the grant ran plugin code that may have changed the inventory.
        ItemStack inHand = player.getInventory().getItem(token.hand());
        if (token.voucherId().equals(voucherId(inHand, token.idKey(), id -> true))) {
            if (inHand.getAmount() <= 1) {
                player.getInventory().setItem(token.hand(), null);
            } else {
                inHand.setAmount(inHand.getAmount() - 1);
                player.getInventory().setItem(token.hand(), inHand);
            }
        } else {
            plugin.getLogger().severe("Voucher " + token.voucherId() + " left " + player.getName()
                    + "'s " + token.hand() + " during its own grant; the grant succeeded and no "
                    + "voucher was consumed.");
        }
        player.saveData();
        return true;
    }

    /** Whether this redeemer refuses {@code player} for their game mode. */
    public boolean refusesGameMode(Player player) {
        if (allowCreative) {
            return false;
        }
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    private Optional<VoucherToken> token(Player player, EquipmentSlot hand, NamespacedKey idKey,
                                         String id) {
        if (refusesGameMode(player) || !firstThisTick(player.getUniqueId())) {
            return Optional.empty();
        }
        return Optional.of(new VoucherToken(player.getUniqueId(), hand, idKey, id));
    }

    private boolean firstThisTick(UUID player) {
        int tick = Bukkit.getCurrentTick();
        if (tick != debounceTick) {
            debounceTick = tick;
            begunThisTick.clear();
        }
        return begunThisTick.add(player);
    }

    private static String voucherId(ItemStack item, NamespacedKey idKey, Predicate<String> isVoucher) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        String id = item.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return id != null && isVoucher.test(id) ? id : null;
    }

    /** A begun redemption. Single-use. */
    public static final class VoucherToken {
        private final UUID playerId;
        private final EquipmentSlot hand;
        private final NamespacedKey idKey;
        private final String voucherId;
        private boolean used;

        VoucherToken(UUID playerId, EquipmentSlot hand, NamespacedKey idKey, String voucherId) {
            this.playerId = playerId;
            this.hand = hand;
            this.idKey = idKey;
            this.voucherId = voucherId;
        }

        public UUID playerId() {
            return playerId;
        }

        public EquipmentSlot hand() {
            return hand;
        }

        public NamespacedKey idKey() {
            return idKey;
        }

        /** The PDC string the voucher carried under {@code idKey}. */
        public String voucherId() {
            return voucherId;
        }

        /** Whether {@code complete} has been called with this token. */
        public boolean isUsed() {
            return used;
        }
    }
}
