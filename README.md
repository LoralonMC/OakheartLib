# OakheartLib

A shared library for [Oakheart SMP](https://github.com/LoralonMC) Paper plugins. Java 21, Paper 1.21.10.

## Structure

| Module | Artifact | Description |
|--------|----------|-------------|
| **oakheart-core** | `dev.oakheart:oakheart-core` | Config engine, message helpers, command registration, debug logging |
| **oakheart-models** | `dev.oakheart:oakheart-models` | Model provider system (Nexo, ItemsAdder, vanilla) — coming soon |

## oakheart-core

### Config (`dev.oakheart.config`)

A preservation-first YAML configuration engine. Replaces Bukkit's `YamlConfiguration` / SnakeYAML with a custom parser that treats comments and formatting as first-class citizens.

- `save()` preserves everything — comments, blank lines, quoting, indentation
- `set()` rewrites just the targeted line, nothing else
- `mergeDefaults()` inserts missing keys at the correct position with their comments

```java
ConfigManager config = ConfigManager.load(configFile.toPath());

String name = config.getString("display.name", "Default");
int cooldown = config.getInt("settings.cooldown", 60);
List<String> worlds = config.getStringList("disabled-worlds");

config.set("settings.debug", true);
config.save();

ConfigManager defaults = ConfigManager.fromStream(plugin.getResource("config.yml"));
if (config.mergeDefaults(defaults)) {
    config.save();
}
```

Supported YAML: block-style maps/sequences, scalars (strings, numbers, booleans, null), comments, blank lines, quoted strings. Not supported (by design): flow-style, anchors, block scalars, merge keys, tags, tabs.

### Message Helper (`dev.oakheart.message`)

Stateless MiniMessage parsing and delivery utilities. Plugins compose this into their own MessageManager.

```java
// Parse a MiniMessage template (empty/null = disabled, returns Optional.empty())
Optional<Component> component = MessageHelper.parse(template,
    Placeholder.unparsed("player", playerName));

// Send with display mode routing (chat, action_bar, title)
MessageHelper.send(sender, component.get(), "action_bar");

// Parse + send in one call
MessageHelper.send(sender, template, "chat",
    Placeholder.unparsed("count", String.valueOf(count)));
```

### Command Registrar (`dev.oakheart.command`)

One-liner Brigadier command registration via Paper's LifecycleEventManager.

```java
// Before (6 lines of boilerplate)
LifecycleEventManager<Plugin> manager = plugin.getLifecycleManager();
manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
    Commands commands = event.registrar();
    commands.register(buildCommand(), "Manage raids", List.of("rc"));
});

// After (1 line)
CommandRegistrar.register(plugin, buildCommand(), "Manage raids", List.of("rc"));
```

### Debug Logger (`dev.oakheart.util`)

Conditional debug logging gated behind a config boolean.

```java
DebugLogger debug = new DebugLogger(logger, configManager::isDebugMode);
debug.log("Processing %d items for %s", items.size(), playerName);
```

## Gradle Setup

Publish to local Maven:

```shell
./gradlew publishToMavenLocal
```

Add to a plugin's `build.gradle`:

```groovy
repositories {
    mavenLocal()
}

dependencies {
    implementation 'dev.oakheart:oakheart-core:1.1.0'
    // Only if the plugin uses custom item models:
    // implementation 'dev.oakheart:oakheart-models:1.1.0'
}

shadowJar {
    relocate 'dev.oakheart.config', 'dev.oakheart.yourplugin.libs.config'
    relocate 'dev.oakheart.message', 'dev.oakheart.yourplugin.libs.message'
    relocate 'dev.oakheart.command', 'dev.oakheart.yourplugin.libs.command'
    relocate 'dev.oakheart.util', 'dev.oakheart.yourplugin.libs.util'
}
```

## Requirements

- Java 21
- Paper 1.21.10
