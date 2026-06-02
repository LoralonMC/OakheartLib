# Changelog

All notable changes to this library will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

OakheartLib is the shared library (`oakheart-core`, `oakheart-models`) consumed by
the Oakheart Paper plugins. Entries are written from the perspective of a plugin
developer building against the library.

## [Unreleased]

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
