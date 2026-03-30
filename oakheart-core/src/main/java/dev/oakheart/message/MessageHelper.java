package dev.oakheart.message;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.Optional;

/**
 * Stateless utility for MiniMessage parsing and message delivery.
 *
 * <p>Plugins compose this into their own MessageManager alongside
 * plugin-specific message keys and convenience methods.</p>
 */
public final class MessageHelper {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private MessageHelper() {}

    /**
     * Parse a MiniMessage template with optional tag resolvers.
     * Returns {@code Optional.empty()} if the template is null or blank
     * (treating empty messages as disabled).
     */
    public static Optional<Component> parse(String template, TagResolver... resolvers) {
        if (template == null || template.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(MINI_MESSAGE.deserialize(template, resolvers));
    }

    /**
     * Send a component to a sender using the specified display mode.
     *
     * @param sender      the recipient
     * @param component   the message
     * @param displayMode "chat", "action_bar", or "title"
     */
    public static void send(CommandSender sender, Component component, String displayMode) {
        if (component == null) return;

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

    /**
     * Parse a MiniMessage template and send it in one call.
     * Does nothing if the template is null or blank (disabled message).
     */
    public static void send(CommandSender sender, String template, String displayMode,
                             TagResolver... resolvers) {
        parse(template, resolvers).ifPresent(component -> send(sender, component, displayMode));
    }
}
