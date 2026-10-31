package com.copybot.engine.listing;

import com.copybot.engine.pipeline.GroupingConfig;
import com.copybot.engine.pipeline.GroupingRuleConfig;
import com.copybot.engine.pipeline.PipelineChecks;
import com.copybot.engine.plugin.PluginDefinition;
import com.copybot.engine.plugin.PluginEngine;
import com.copybot.logger.CopybotLogger;
import com.copybot.plugin.api.definition.IPlugin;
import com.copybot.plugin.api.grouping.GroupingPreset;
import com.copybot.plugin.api.grouping.GroupingRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The grouping presets a run can cite (spec plugin grouping presets §4): those of the core ({@link GroupingPresets})
 * and those of the loaded plugins, keyed by the plugin module name and the preset code. A preset of a plugin whose
 * rules are invalid is discarded when the registry is built, with a warning in the log; a plugin whose presets
 * cannot be read has none (a citation is refused with its own message, the cause in the log).
 */
public final class GroupingPresetRegistry {

    private static final CopybotLogger LOG = CopybotLogger.getLogger(GroupingPresetRegistry.class);

    /** A preset of a loaded plugin, its rules in their pipeline form. */
    public record PluginPreset(String plugin, String pluginCode, String version, String code,
                               List<GroupingRuleConfig> rules) {
    }

    private record Key(String plugin, String code) {
    }

    /** The registry of a list of loaded plugins (the current one is built again only when the list changes). */
    private record Built(List<PluginDefinition> from, GroupingPresetRegistry registry) {
    }

    private static volatile Built current;

    // frozen once built: the registry is shared by the threads of a run
    private final Map<Key, PluginPreset> presets;
    private final Set<String> plugins;
    private final Set<Key> discarded;
    private final Set<String> failed;

    private GroupingPresetRegistry(Builder built) {
        this.presets = Collections.unmodifiableMap(new LinkedHashMap<>(built.presets));
        this.plugins = Set.copyOf(built.plugins);
        this.discarded = Set.copyOf(built.discarded);
        this.failed = Set.copyOf(built.failed);
    }

    /** What a registry is built from, plugin after plugin. */
    private static final class Builder {
        final Map<Key, PluginPreset> presets = new LinkedHashMap<>();
        final Set<String> plugins = new HashSet<>();
        final Set<Key> discarded = new HashSet<>();
        final Set<String> failed = new HashSet<>();
    }

    /** The presets of the core and of the plugins loaded by {@link PluginEngine} (the core only before the load). */
    public static GroupingPresetRegistry current() {
        return current(PluginEngine.getLoadedPlugins());
    }

    /**
     * The presets of the core and of this list of loaded plugins: the current registry when it was built from this
     * very list, else one built from it (kept as the current one only when the list is still the loaded one).
     */
    public static GroupingPresetRegistry current(List<PluginDefinition> loaded) {
        Built built = current;
        if (built == null || built.from() != loaded) {
            built = new Built(loaded, of(loaded));
            if (loaded == PluginEngine.getLoadedPlugins()) {
                current = built;
            }
        }
        return built.registry();
    }

    /**
     * The presets of the core and of these loaded plugins, in their order: of the versions of a plugin, the first
     * one (the most recent, as loaded) brings the presets.
     */
    public static GroupingPresetRegistry of(List<PluginDefinition> loadedPlugins) {
        Builder builder = new Builder();
        Set<String> seen = new HashSet<>();
        for (PluginDefinition plugin : loadedPlugins) {
            if (seen.add(plugin.getName())) {
                add(builder, plugin);
            }
        }
        return new GroupingPresetRegistry(builder);
    }

    private static void add(Builder builder, PluginDefinition plugin) {
        Map<Key, PluginPreset> presets = builder.presets;
        Set<Key> discarded = builder.discarded;
        IPlugin instance = plugin.getPluginInstance();
        String pluginCode;
        List<GroupingPreset> declared;
        try {
            pluginCode = instance.getPluginCode();
            declared = List.copyOf(instance.getGroupingPresets());
        } catch (LinkageError | RuntimeException e) {
            LOG.warn(e, "plugin.presets.failed", plugin.getName(), plugin.getVersion(), String.valueOf(e));
            builder.failed.add(plugin.getName());
            return;
        }
        builder.plugins.add(plugin.getName());
        for (GroupingPreset preset : declared) {
            Key key = new Key(plugin.getName(), preset.code());
            if (presets.containsKey(key) || discarded.contains(key)) {
                LOG.warn("plugin.preset.duplicate", plugin.getName(), preset.code());
                continue;
            }
            List<GroupingRuleConfig> rules = new ArrayList<>();
            Optional<String> invalid = Optional.empty();
            for (GroupingRule rule : preset.rules()) {
                GroupingRuleConfig config = GroupingRuleConfig.of(rule);
                invalid = PipelineChecks.ruleRefusal(config, rules.size() + 1);
                if (invalid.isPresent()) {
                    break;
                }
                rules.add(config);
            }
            if (invalid.isPresent()) {
                LOG.warn("plugin.preset.invalid", plugin.getName(), preset.code(), invalid.get());
                discarded.add(key);
            } else {
                presets.put(key, new PluginPreset(plugin.getName(), pluginCode, plugin.getVersion(), preset.code(),
                        List.copyOf(rules)));
            }
        }
    }

    /** Whether the rule cites a preset of the core: no plugin (or a blank one). */
    public static boolean isCore(GroupingRuleConfig rule) {
        return rule.plugin() == null || rule.plugin().isBlank();
    }

    /** Whether the plugin of this module name is loaded and its presets were read. */
    public boolean hasPlugin(String plugin) {
        return plugin != null && plugins.contains(plugin);
    }

    /** Whether the plugin is loaded but its presets could not be read (warned in the log): it brings none. */
    public boolean hasFailed(String plugin) {
        return plugin != null && failed.contains(plugin);
    }

    /** The module names of the loaded plugins whose presets were read, whether they declared valid ones or not. */
    public Set<String> plugins() {
        return plugins;
    }

    /** Whether the plugin declared this preset with invalid rules: it was discarded. */
    public boolean isDiscarded(String plugin, String code) {
        return discarded.contains(new Key(plugin, code));
    }

    /** The rules of the preset the rule cites (of the core without plugin), empty when it is not available. */
    public Optional<List<GroupingRuleConfig>> rules(GroupingRuleConfig rule) {
        if (rule.preset() == null) {
            return Optional.empty();
        }
        if (isCore(rule)) {
            return GroupingPresets.rules(rule.preset());
        }
        return Optional.ofNullable(presets.get(new Key(rule.plugin(), rule.preset()))).map(PluginPreset::rules);
    }

    /** The presets of the loaded plugins, in plugin order then their declaration order. */
    public List<PluginPreset> pluginPresets() {
        return List.copyOf(presets.values());
    }

    /**
     * The rules of the configuration with each preset replaced by its rules; the own rules pass through unchanged.
     * Idempotent. Absent configuration: no rule.
     *
     * @throws IllegalStateException a cited preset is not available (the validation refuses it before any listing)
     */
    public List<GroupingRuleConfig> expand(GroupingConfig config) {
        List<GroupingRuleConfig> expanded = new ArrayList<>();
        if (config == null || config.rules() == null) {
            return expanded;
        }
        for (GroupingRuleConfig rule : config.rules()) {
            if (rule == null) {
                continue;
            }
            if (rule.preset() == null) {
                expanded.add(rule);
            } else {
                expanded.addAll(rules(rule).orElseThrow(() -> new IllegalStateException(
                        "grouping preset not available: " + rule.preset()
                                + (isCore(rule) ? "" : " of the plugin " + rule.plugin()))));
            }
        }
        return expanded;
    }
}
