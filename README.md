# OakheartLib

A shared library for [Oakheart SMP](https://github.com/LoralonMC) Paper plugins. Java 25, Paper 26.3.

## Structure

| Module | Artifact | Description |
|--------|----------|-------------|
| **oakheart-core** | `dev.oakheart:oakheart-core` | Config engine, message helpers, command registration, debug logging, menu click rules and display guards, block custody, vouchers, async storage |
| **oakheart-models** | `dev.oakheart:oakheart-models` | Model provider system (Nexo, ItemsAdder, vanilla, Item Model) |

## oakheart-core

### Config (`dev.oakheart.config`)

A preservation-first YAML configuration engine. Replaces Bukkit's `YamlConfiguration` / SnakeYAML with a custom parser that treats comments and formatting as first-class citizens.

- `save()` preserves comments, blank lines, quoting style, and indentation for unmodified regions
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

Supported YAML: block-style maps/sequences (including sequences of maps), scalars (strings, numbers, booleans, null), comments, blank lines, quoted strings. Not supported (by design): flow-style, anchors, block scalars, merge keys, tags, tabs. Line endings are normalized to `\n` on load.

### Message Manager (`dev.oakheart.message`)

Shared message manager that owns a `messages.yml` file per plugin. Handles loading, merging defaults, caching, and delivery routing (chat / action_bar / title).

```java
MessageManager messages = new MessageManager(plugin, logger);
messages.load();

// Gameplay message with display mode from messages.yml
messages.send(sender, "raid-blocked", Placeholder.unparsed("time", formatted));

// Command message (always chat)
messages.sendCommand(sender, "reload-success");

// Parse without sending (for GUI lore, hover text)
Optional<Component> component = messages.parse("greeting", Placeholder.unparsed("player", name));

// Raw MiniMessage (for config values, not messages.yml)
Component title = messages.deserialize(configTitle);
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

### Menu safety (`dev.oakheart.menu`), 1.4

- `ClickPolicy.decideClick` / `decideDrag`: the click and drag rules for a menu with optional input slots (pure, unit-tested; see the class Javadoc for the table).
- `DisplayItems.mark(icon)` stamps an icon `oakheart:display`; `DisplayItems.isDisplay(stack)` recognises any plugin's icons.
- `DisplayGuards.register(plugin, holder -> holder instanceof MyMenu)` removes marked stacks that escape a menu (drops, pickups, spawns, and a sweep on menu close and join). Throws if the library was not relocated.

### Items (`dev.oakheart.item`), 1.4

- `BlockCustody`: `removeAllWithoutDrops(blocks)` snapshots every block, then empties and removes each without drops (furnace XP and campfire items included); `restore(state)` puts one back.
- `VoucherRedeemer`: two-phase redemption. `begin(event, idKey, isVoucher)` on a right-click returns a single-use token; `complete(token, grant)` re-finds the voucher in the same hand, runs the grant, and consumes one only if it succeeded. Refuses CREATIVE and SPECTATOR by default.

### Storage (`dev.oakheart.storage`), 1.4

- `SerialWriter`: one daemon thread, FIFO. `submit`, `supply`, `submitLatest(key, task)` (supersedes queued tasks of the same key), `drain(timeout)` for `onDisable`.
- `SqliteStore`: subclass with `schemaVersion()` and `migrate(connection, from)`. One writer thread, a synchronous query-only reader, WAL, `user_version` migrations in one transaction, and refusal to open a newer schema.

```java
public final class LogStore extends SqliteStore {
    public LogStore(Path file, Logger logger) {
        super(file, "OakLog-db-writer", logger, false);
    }
    @Override protected int schemaVersion() { return 1; }
    @Override protected void migrate(Connection c, int from) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE entries (id INTEGER PRIMARY KEY, player TEXT, at INTEGER)");
        }
    }
}

store.open();                                    // onEnable, blocking
store.write("INSERT INTO entries (player, at) VALUES (?, ?)", uuid, now);
int n = store.read(c -> count(c));               // main thread OK, small queries only
store.close();                                   // onDisable, last
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
    implementation 'dev.oakheart:oakheart-core:1.3.2'
    // Only if the plugin uses custom item models:
    // implementation 'dev.oakheart:oakheart-models:1.3.2'
}

shadowJar {
    relocate 'dev.oakheart.config', 'dev.oakheart.yourplugin.libs.config'
    relocate 'dev.oakheart.message', 'dev.oakheart.yourplugin.libs.message'
    relocate 'dev.oakheart.command', 'dev.oakheart.yourplugin.libs.command'
    relocate 'dev.oakheart.util', 'dev.oakheart.yourplugin.libs.util'
    // 1.4 packages:
    relocate 'dev.oakheart.menu', 'dev.oakheart.yourplugin.libs.menu'
    relocate 'dev.oakheart.item', 'dev.oakheart.yourplugin.libs.item'
    relocate 'dev.oakheart.storage', 'dev.oakheart.yourplugin.libs.storage'
}
```

## Requirements

- Java 25
- Paper 26.3 (oakheart-core 1.4 compiles against 26.3)
