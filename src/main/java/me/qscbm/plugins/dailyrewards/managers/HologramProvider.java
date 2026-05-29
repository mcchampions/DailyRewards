package me.qscbm.plugins.dailyrewards.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Abstraction for hologram display backends.
 * Supports both native Paper TextDisplay entities and DecentHolograms plugin.
 */
public interface HologramProvider {

    /**
     * Called once during plugin enable to initialize the provider.
     */
    void init(Plugin plugin, Logger logger, int maxEntries);

    /**
     * Create a hologram at the given location.
     *
     * @param id         unique hologram ID
     * @param location   spawn location
     * @param type       leaderboard type (total_time, daily_time, loginDays)
     */
    void create(UUID id, Location location, String type);

    /**
     * Update the text content of a hologram.
     *
     * @param id     hologram ID
     * @param lines  list of components to display (header + entries + footer)
     */
    void update(UUID id, List<Component> lines);

    /**
     * Remove a hologram and clean up its resources.
     */
    void remove(UUID id);

    /**
     * Remove all holograms created by this provider.
     */
    void removeAll();

    /**
     * Called during plugin shutdown.
     */
    void shutdown();

    /**
     * Returns the provider type identifier.
     */
    String getType();
}
