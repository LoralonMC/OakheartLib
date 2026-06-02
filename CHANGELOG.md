# Changelog

All notable changes to this library will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

OakheartLib is the shared library (`oakheart-core`, `oakheart-models`) consumed by
the Oakheart Paper plugins. Entries are written from the perspective of a plugin
developer building against the library.

## [Unreleased]

### Added

- `ConfigManager.syncComments(...)` — three-way sync of leading comment blocks from new defaults onto an existing config, so improved/added/removed comments reach live files (the case `mergeDefaults` won't touch). Uses a persisted baseline (the previously-shipped default) as the merge base: a comment is only updated when the admin hasn't customised it — admin-written comments are always preserved. A `syncComments(defaults, baselineFile)` overload manages the baseline file itself. Inline comments are not synced yet.
- `ConfigMigrator` — a version-gated migration runner for the structural config changes `mergeDefaults` deliberately won't make (renamed / removed / restructured keys). Steps declare the version they upgrade *to* and run only on older files; the config is stamped with the highest version reached. Steps are idempotent, so a missed save just re-runs harmlessly.
- `ConfigManager.renameKey(oldPath, newPath)` (scalar and list values; sections throw for now) and `ConfigManager.removeKey(path)` (returns whether the key existed) as building blocks for migration steps.

## [1.3.0] - 2026-06-02

### Added

- `MessageManager.parseLines(key, resolvers...)` returns a `List<Component>` for multi-line item lore — reads the key as a YAML string list (one MiniMessage line per entry) and falls back to a single scalar string for legacy one-line keys. A blank `""` entry renders as a spacer line, and italic inheritance is suppressed per line.
- `ConfigManager.getLongList`, `getDoubleList`, and `getBooleanList` (with backing `YamlNode.asLongList` / `asDoubleList` / `asBooleanList`), completing the typed list-accessor family alongside `getStringList` / `getIntList`.
- `DebugLogger.log(Supplier<String>)` — a lazily-evaluated overload so expensive debug-message construction is skipped entirely when debug mode is off.
- Published a `-sources` jar for both modules so IDEs can navigate into library source from consuming plugins.

### Changed

- `ConfigManager.getMapList` now recurses into nested maps and nested map-lists instead of silently dropping them. Nested maps surface as nested `Map<String, Object>` values and nested sequences-of-maps as `List<Map<String, Object>>`; sequences of scalars remain `List<String>`. Existing flat configs are unaffected.

### Removed

- The legacy one-shot migration that moved a `messages:` block out of `config.yml` into `messages.yml` on first load. Every consuming plugin has already migrated, so the path was dead code; removing it drops ~180 lines of fragile line-scanning. Fresh installs extract the JAR's `messages.yml` as before.

## [1.2.0] - 2026-05-21

### Changed

- Targets Paper 26.1.2 and Java 25.
