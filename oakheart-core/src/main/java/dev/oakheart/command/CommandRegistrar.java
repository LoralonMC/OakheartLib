package dev.oakheart.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Registers Brigadier commands via Paper's LifecycleEventManager.
 *
 * <p>Replaces the repeated boilerplate:</p>
 * <pre>{@code
 * // Before (6 lines)
 * LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
 * manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
 *     Commands commands = event.registrar();
 *     commands.register(command, "description", List.of("alias"));
 * });
 *
 * // After (1 line)
 * CommandRegistrar.register(plugin, command, "description", List.of("alias"));
 * }</pre>
 */
@SuppressWarnings("UnstableApiUsage")
public final class CommandRegistrar {

    private CommandRegistrar() {}

    /**
     * Register a Brigadier command with description and aliases.
     */
    public static void register(Plugin plugin, LiteralCommandNode<CommandSourceStack> command,
                                 String description, List<String> aliases) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(command, description, aliases);
        });
    }

    /**
     * Register a Brigadier command with description and no aliases.
     */
    public static void register(Plugin plugin, LiteralCommandNode<CommandSourceStack> command,
                                 String description) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(command, description);
        });
    }
}
