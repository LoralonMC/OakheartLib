# OakheartLib

A shared library for [Oakheart SMP](https://github.com/LoralonMC) Paper plugins. Zero external dependencies, Java 21.

## Modules

### Config (`dev.oakheart.config`)

A preservation-first YAML configuration engine for Minecraft plugin configs. Replaces Bukkit's `YamlConfiguration` / SnakeYAML with a custom parser that treats comments and formatting as first-class citizens.

**The problem:** SnakeYAML destroys comments, blank lines, quoting style, and key ordering every time you call `save()`.

**The solution:** A dual representation — a raw line list for byte-perfect file output and a structured tree for programmatic access. Both stay in sync at all times.

- `save()` preserves everything — comments, blank lines, quoting, indentation
- `set()` rewrites just the targeted line, nothing else
- `mergeDefaults()` inserts missing keys at the correct position with their comments, without touching existing content

#### Supported YAML Subset

Purpose-built for Minecraft plugin configs, not general YAML:

- Block-style maps and sequences (2-space indent)
- Scalars: strings (quoted and unquoted), integers, longs, doubles, booleans, null
- Full-line comments, inline comments, blank lines
- Single-quoted and double-quoted strings with proper escaping

**Not supported (by design):** flow-style collections (`[...]`, `{...}`), anchors/aliases, block scalars (`|`, `>`), merge keys (`<<`), tags, tabs for indentation. Unsupported constructs fail loudly at parse time — never silently corrupted.

#### Usage

```java
// Load from file
ConfigManager config = ConfigManager.load(configFile.toPath());

// Read values (Bukkit-familiar API)
String name = config.getString("display.name", "Default");
int cooldown = config.getInt("settings.cooldown", 60);
boolean debug = config.getBoolean("settings.debug");
List<String> worlds = config.getStringList("disabled-worlds");

// Sections and keys
ConfigManager settings = config.getSection("settings");
Set<String> keys = config.getKeys("settings", false);

// Set values (updates just that line, preserves everything else)
config.set("settings.debug", true);
config.set("api-key", generatedKey);
config.set("tags", List.of("survival", "adventure"));

// Save (byte-perfect, atomic write)
config.save();

// Merge defaults from JAR resource (adds missing keys with comments)
ConfigManager defaults = ConfigManager.fromStream(plugin.getResource("config.yml"));
if (config.mergeDefaults(defaults)) {
    config.save();
}

// Reload from disk
config.reload();
```

#### API

**Factory Methods**

| Method | Description |
|--------|-------------|
| `ConfigManager.load(Path)` | Load from file |
| `ConfigManager.fromString(String)` | Parse from string |
| `ConfigManager.fromStream(InputStream)` | Parse from stream (e.g. JAR resource) |

**Getters**

| Method | Description |
|--------|-------------|
| `getString(path, default)` | String value |
| `getInt(path, default)` | Integer value |
| `getLong(path, default)` | Long value |
| `getDouble(path, default)` | Double value |
| `getBoolean(path, default)` | Boolean value |
| `getStringList(path)` | List of strings |
| `getIntList(path)` | List of integers |
| `getSection(path)` | Sub-section as ConfigManager view |
| `getKeys(path, deep)` | Child keys (shallow or deep) |
| `contains(path)` | Check if path exists |
| `isSection(path)` | Check if path is a map section |

**Mutation**

| Method | Description |
|--------|-------------|
| `set(path, value)` | Set a scalar, list, or null — updates tree and line list |
| `remove(path)` | Remove a key and its descendants |

**Persistence**

| Method | Description |
|--------|-------------|
| `save()` | Save to original file (atomic write) |
| `save(Path)` | Save to arbitrary path |
| `reload()` | Re-read from disk |
| `mergeDefaults(ConfigManager)` | Add missing keys from defaults |
| `hasNewKeys(ConfigManager)` | Check if defaults have new keys |

#### Write Invariants

- **Unmodified lines are preserved exactly** — byte-for-byte identical
- **Modified scalar lines** preserve indent, inline comments, and quote style
- **Inserted keys** follow formatting rules (2-space indent, comments from defaults)
- **`save()` never reparses or rewrites** the entire file — it writes the line list verbatim

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
    implementation 'dev.oakheart:oakheart-lib:1.0.0'
}

shadowJar {
    relocate 'dev.oakheart.config', 'dev.oakheart.yourplugin.libs.config'
}
```

## Requirements

- Java 21
- No external dependencies
