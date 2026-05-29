package me.qscbm.plugins.dailyrewards.managers;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;

/**
 * Detects config-version mismatches and auto-merges missing keys from the JAR default
 * into the existing config file, preserving user customizations.
 */
public final class ConfigUpdater {

    private ConfigUpdater() {}

    /**
     * Checks config-version in the given file, and if it differs from expectedVersion,
     * merges any missing keys from the JAR default into the file.
     *
     * @param plugin          the plugin instance
     * @param fileName        file name (e.g. "config.yml")
     * @param expectedVersion the version the plugin expects
     */
    public static void update(Plugin plugin, String fileName, int expectedVersion) {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) return;

        YamlConfiguration existing = YamlConfiguration.loadConfiguration(file);
        int currentVersion = existing.getInt("config-version", 0);

        if (currentVersion >= expectedVersion) return;

        // Load default from JAR
        InputStream jarStream = plugin.getResource(fileName);
        if (jarStream == null) return;
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(jarStream));

        // Merge missing keys
        mergeSection(defaults, existing, "");

        // Bump version and save
        existing.set("config-version", expectedVersion);
        try {
            existing.save(file);
            plugin.getLogger().info("Updated " + fileName + " from v" + currentVersion + " to v" + expectedVersion);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to save updated " + fileName + ": " + e.getMessage());
        }
    }

    private static void mergeSection(YamlConfiguration defaults, YamlConfiguration target, String path) {
        ConfigurationSection defaultSection = path.isEmpty() ? defaults : defaults.getConfigurationSection(path);
        if (defaultSection == null) return;

        Set<String> keys = defaultSection.getKeys(false);
        for (String key : keys) {
            String fullPath = path.isEmpty() ? key : path + "." + key;
            if (defaultSection.isConfigurationSection(key)) {
                // Recurse into sub-section
                if (!target.isConfigurationSection(fullPath)) {
                    target.createSection(fullPath);
                }
                mergeSection(defaults, target, fullPath);
            } else {
                // Only add if key doesn't exist in target
                if (!target.contains(fullPath)) {
                    target.set(fullPath, defaults.get(fullPath));
                }
            }
        }
    }
}
