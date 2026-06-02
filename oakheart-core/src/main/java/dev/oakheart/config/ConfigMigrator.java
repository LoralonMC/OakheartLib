package dev.oakheart.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs version-gated migration steps against a {@link ConfigManager}, for the
 * structural config changes that {@code mergeDefaults} deliberately won't touch:
 * renamed, removed, or restructured keys.
 *
 * <p>{@code mergeDefaults} handles the additive case (new keys/sections) without
 * ever modifying existing values. It cannot, however, know that {@code old-name}
 * became {@code new-name} — so an admin who upgrades ends up with both keys and a
 * silently-ignored customization. This runner closes that gap: each step declares
 * the version it upgrades <em>to</em>, and only runs when the file is older.</p>
 *
 * <h2>Usage:</h2>
 * <pre>{@code
 * boolean changed = ConfigMigrator.forConfig(config)        // versionKey defaults to "config-version"
 *     .step(2, c -> c.renameKey("old-name", "new-name"))
 *     .step(3, c -> {
 *         c.removeKey("deprecated-option");
 *         c.renameKey("sounds.enter", "sounds.region-enter");
 *     })
 *     .run();
 * if (changed) config.save();
 * }</pre>
 *
 * <p>Steps run in ascending version order; after the last applicable step the
 * config is stamped with the highest version reached. Steps should be
 * <strong>idempotent</strong> (safe to re-run) — {@link ConfigManager#renameKey}
 * and {@link ConfigManager#removeKey} already are, so a re-run after a missed save
 * is harmless. The default config should carry the current {@code config-version}
 * so fresh installs start at the latest version and skip every step.</p>
 */
public final class ConfigMigrator {

    private record Step(int targetVersion, Consumer<ConfigManager> action) {}

    private final ConfigManager config;
    private String versionKey = "config-version";
    private final List<Step> steps = new ArrayList<>();

    private ConfigMigrator(ConfigManager config) {
        this.config = config;
    }

    public static ConfigMigrator forConfig(ConfigManager config) {
        return new ConfigMigrator(config);
    }

    /** Override the key that stores the schema version (default {@code "config-version"}). */
    public ConfigMigrator versionKey(String key) {
        this.versionKey = key;
        return this;
    }

    /**
     * Register a step that upgrades the config <em>to</em> {@code targetVersion}.
     * It runs only when the file's current version is below {@code targetVersion}.
     */
    public ConfigMigrator step(int targetVersion, Consumer<ConfigManager> action) {
        steps.add(new Step(targetVersion, action));
        return this;
    }

    /**
     * Run all applicable steps in ascending version order, then stamp the config
     * with the highest version reached.
     *
     * @return true if any step ran (caller should {@code save()})
     */
    public boolean run() {
        int current = config.getInt(versionKey, 0);
        int highest = current;
        boolean changed = false;

        steps.sort(Comparator.comparingInt(Step::targetVersion));
        for (Step step : steps) {
            if (step.targetVersion() > current) {
                step.action().accept(config);
                highest = Math.max(highest, step.targetVersion());
                changed = true;
            }
        }

        if (changed) {
            config.set(versionKey, highest);
        }
        return changed;
    }
}
