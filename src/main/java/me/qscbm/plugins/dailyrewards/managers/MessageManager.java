package me.qscbm.plugins.dailyrewards.managers;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Loads messages.yml once and caches deserialized Component templates in memory.
 * Variable replacement is applied to the cached templates without reparsing message syntax.
 */
public class MessageManager {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern MINI_TAG = Pattern.compile("</?[a-z][a-z0-9_]*(:[^>]+)?>");

    private final Map<String, Component> cache = new HashMap<>();
    private final Map<String, List<Component>> listCache = new HashMap<>();
    private Component prefix;

    public MessageManager() {}

    /**
     * Loads messages.yml and flattens all paths into the in-memory cache.
     */
    public void load(File dataFolder) {
        cache.clear();
        listCache.clear();

        File file = new File(dataFolder, "messages.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        prefix = deserialize(cfg.getString("prefix", "<dark_aqua>[<aqua>每日奖励</aqua>]</dark_aqua> "));

        // Flatten all keys into the cache
        flatten(cfg, "");
    }

    private void flatten(org.bukkit.configuration.ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (section.isConfigurationSection(key)) {
                flatten(section.getConfigurationSection(key), path);
            } else if (section.isList(key)) {
                List<String> list = section.getStringList(key);
                if (!list.isEmpty()) {
                    listCache.put(path, list.stream().map(MessageManager::deserialize).toList());
                }
            } else {
                cache.put(path, deserialize(section.getString(key, "")));
            }
        }
    }

    /**
     * Returns a cached message with variable replacement applied.
     */
    public Component get(String path, Map<String, String> vars) {
        Component template = cache.get(path);
        if (template == null) {
            // Try list cache
            List<Component> list = listCache.get(path);
            if (list != null && !list.isEmpty()) {
                template = list.get(0);
            }
        }
        if (template == null) return Component.text(path);
        return vars == null || vars.isEmpty() ? template : replaceVars(template, vars);
    }

    /**
     * Returns a cached message without variables.
     */
    public Component get(String path) {
        return get(path, null);
    }

    /**
     * Returns a pre-cached list of Components with variable replacement.
     */
    public List<Component> getList(String path, Map<String, String> vars) {
        List<Component> templates = listCache.get(path);
        if (templates == null) return Collections.emptyList();
        if (vars == null || vars.isEmpty()) return templates;

        List<Component> result = new ArrayList<>();
        for (Component line : templates) {
            result.add(replaceVars(line, vars));
        }
        return result;
    }

    public List<Component> getList(String path) {
        return getList(path, null);
    }

    /**
     * Returns the prefix as a Component.
     */
    public Component prefix() {
        return prefix;
    }

    // --- Deserialization ---

    private static Component deserialize(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (MINI_TAG.matcher(raw).find()) {
            try {
                return MINI.deserialize(raw);
            } catch (Exception ignored) {}
        }
        return LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', raw));
    }

    private static Component replaceVars(Component template, Map<String, String> vars) {
        Component result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + entry.getKey() + "%")
                    .replacement(Component.text(entry.getValue()))
                    .build());
        }
        return result;
    }
}
