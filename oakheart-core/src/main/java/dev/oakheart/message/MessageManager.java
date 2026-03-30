package dev.oakheart.message;

import dev.oakheart.config.ConfigManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared message manager for Oakheart plugins. Owns a {@code messages.yml} file
 * and handles loading, merging defaults, parsing MiniMessage templates, caching,
 * and delivery routing (chat / action_bar / title).
 *
 * <h2>messages.yml structure:</h2>
 * <pre>
 * # Gameplay messages (text + display mode)
 * raid-blocked:
 *   text: "&lt;#6C757D&gt;[&lt;#6B7A5E&gt;ʀᴀɪᴅ&lt;#6C757D&gt;] &lt;#D89B6A&gt;On cooldown."
 *   display: action_bar
 *
 * # Command messages (flat strings, always chat)
 * commands:
 *   reload-success: "&lt;#8FAA87&gt;Config reloaded."
 *   player-not-found: "&lt;#C27B6B&gt;Player not found."
 * </pre>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * MessageManager messages = new MessageManager(plugin, logger);
 * messages.load();
 *
 * // Gameplay message with display mode
 * messages.send(sender, "raid-blocked", Placeholder.unparsed("time", formatted));
 *
 * // Command message (always chat)
 * messages.sendCommand(sender, "reload-success");
 *
 * // Parse without sending (for GUI lore, hover text, etc.)
 * Optional<Component> component = messages.parse("greeting", Placeholder.unparsed("player", name));
 * }</pre>
 */
public class MessageManager {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private final Logger logger;
    private final Path messagesFile;
    private ConfigManager config;

    // Cache for messages without placeholders (key -> component)
    private final Map<String, Component> cache = new ConcurrentHashMap<>();

    public MessageManager(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.messagesFile = plugin.getDataFolder().toPath().resolve("messages.yml");
    }

    /**
     * Load messages.yml from disk. Extracts the default from the JAR if it doesn't exist,
     * then merges any new keys from the JAR defaults.
     */
    public void load() {
        if (!messagesFile.toFile().exists()) {
            plugin.saveResource("messages.yml", false);
        }

        try {
            config = ConfigManager.load(messagesFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load messages.yml", e);
        }

        mergeDefaults();
        cache.clear();
    }

    /**
     * Reload messages.yml from disk. Clears the cache.
     */
    public void reload() {
        try {
            config.reload();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to reload messages.yml", e);
            return;
        }
        mergeDefaults();
        cache.clear();
    }

    private void mergeDefaults() {
        try (var stream = plugin.getResource("messages.yml")) {
            if (stream != null) {
                var defaults = ConfigManager.fromStream(stream);
                if (config.mergeDefaults(defaults)) {
                    config.save();
                    logger.info("Updated messages.yml with new default messages.");
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed to merge message defaults", e);
        }
    }

    // ========================================
    //          Sending
    // ========================================

    /**
     * Send a gameplay message to a sender. The message key is looked up in messages.yml
     * under {@code <key>.text} with display mode from {@code <key>.display}.
     * Does nothing if the message text is empty (disabled).
     */
    public void send(CommandSender sender, String key, TagResolver... resolvers) {
        String text = getMessageText(key);
        if (text == null || text.isEmpty()) return;

        String display = getMessageDisplay(key);

        if (resolvers.length == 0) {
            // Use cache for messages without placeholders
            Component component = cache.computeIfAbsent(key, k -> MINI_MESSAGE.deserialize(text));
            deliver(sender, component, display);
        } else {
            Component component = MINI_MESSAGE.deserialize(text, resolvers);
            deliver(sender, component, display);
        }
    }

    /**
     * Send a command message to a sender. Looked up under {@code commands.<key>}
     * as a flat string, always delivered via chat.
     * Does nothing if the message is empty (disabled).
     */
    public void sendCommand(CommandSender sender, String key, TagResolver... resolvers) {
        String text = config.getString("commands." + key, "");
        if (text.isEmpty()) return;

        if (resolvers.length == 0) {
            String cacheKey = "commands." + key;
            Component component = cache.computeIfAbsent(cacheKey, k -> MINI_MESSAGE.deserialize(text));
            sender.sendMessage(component);
        } else {
            sender.sendMessage(MINI_MESSAGE.deserialize(text, resolvers));
        }
    }

    // ========================================
    //          Parsing (for GUI lore, hover text, etc.)
    // ========================================

    /**
     * Parse a gameplay message template and return as a Component.
     * Returns {@code Optional.empty()} if the message is null or empty (disabled).
     *
     * <p>This method does NOT suppress italic inheritance. Use {@link #deserialize(String, TagResolver...)}
     * for item names and lore where Minecraft's default italic needs to be removed.</p>
     */
    public Optional<Component> parse(String key, TagResolver... resolvers) {
        String text = getMessageText(key);
        if (text == null || text.isEmpty()) return Optional.empty();
        return Optional.of(MINI_MESSAGE.deserialize(text, resolvers));
    }

    /**
     * Parse a raw MiniMessage string (not from config). Use this for item display names
     * and lore where Minecraft's default italic inheritance needs to be suppressed.
     *
     * <p>Unlike {@link #parse(String, TagResolver...)}, this method applies
     * {@code decorationIfAbsent(ITALIC, FALSE)} to prevent inherited italic on items.</p>
     */
    public Component deserialize(String text, TagResolver... resolvers) {
        return MINI_MESSAGE.deserialize(text, resolvers)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    // ========================================
    //          Raw Access
    // ========================================

    /**
     * Get the raw text template for a gameplay message key.
     * Returns null if not found.
     */
    public String getMessageText(String key) {
        // Try text/display structure first
        String text = config.getString(key + ".text");
        if (text != null) return text;
        // Fall back to flat string (for keys that don't use text/display structure)
        return config.getString(key);
    }

    /**
     * Get the display mode for a gameplay message key.
     * Defaults to "chat" if not specified.
     */
    public String getMessageDisplay(String key) {
        return config.getString(key + ".display", "chat");
    }

    /**
     * Get the underlying config for direct access (e.g., reading non-message values
     * like time-format suffixes stored in the same file).
     */
    public ConfigManager getConfig() {
        return config;
    }

    // ========================================
    //          Delivery
    // ========================================

    private void deliver(CommandSender sender, Component component, String displayMode) {
        switch (displayMode != null ? displayMode : "chat") {
            case "action_bar" -> {
                if (sender instanceof Player player) {
                    player.sendActionBar(component);
                } else {
                    sender.sendMessage(component);
                }
            }
            case "title" -> {
                if (sender instanceof Player player) {
                    player.showTitle(Title.title(
                            Component.empty(), component,
                            Title.Times.times(
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(3),
                                    Duration.ofMillis(500))));
                } else {
                    sender.sendMessage(component);
                }
            }
            default -> sender.sendMessage(component);
        }
    }
}
