package me.qscbm.plugins.dailyrewards.managers;

import eu.decentsoftware.holograms.api.DHAPI;
import eu.decentsoftware.holograms.api.holograms.Hologram;
import me.qscbm.plugins.dailyrewards.DailyRewards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hologram provider using DecentHolograms as the display backend.
 * DH handles persistence, entity lifecycle, and rendering — we just create/update/remove via DHAPI.
 */
public class DecentHologramsProvider implements HologramProvider {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .hexCharacter('#')
            .build();

    private final Map<UUID, String> hologramIds = new ConcurrentHashMap<>();
    private Logger logger;
    private int maxEntries;

    @Override
    public void init(Plugin plugin, Logger logger, int maxEntries) {
        this.logger = logger;
        this.maxEntries = maxEntries;

        if (!Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            logger.severe("DecentHolograms not found — provider unavailable");
            return;
        }
        logger.info("DecentHolograms hologram provider initialized (v"
                + Bukkit.getPluginManager().getPlugin("DecentHolograms").getDescription().getVersion() + ")");
    }

    @Override
    public void create(UUID id, Location location, String type) {
        if (!isAvailable()) return;

        String dhId = "dr_" + id.toString().substring(0, 8);
        try {
            Hologram hologram = DHAPI.createHologram(dhId, location);
            if (hologram == null) {
                logger.warning("DHAPI.createHologram returned null for " + dhId);
                return;
            }

            // Initialize with placeholder lines
            List<String> placeholder = new ArrayList<>();
            for (int i = 0; i < 1 + maxEntries + 1; i++) {
                placeholder.add("...");
            }
            DHAPI.setHologramLines(hologram, 0, placeholder);
            hologramIds.put(id, dhId);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create DH hologram " + dhId, e);
        }
    }

    @Override
    public void update(UUID id, List<Component> lines) {
        if (!isAvailable()) return;

        String dhId = hologramIds.get(id);
        if (dhId == null) return;

        try {
            Hologram hologram = DHAPI.getHologram(dhId);
            if (hologram == null) {
                logger.warning("DH hologram not found during update: " + dhId);
                return;
            }

            List<String> textLines = new ArrayList<>();
            for (Component c : lines) {
                textLines.add(LEGACY.serialize(c));
            }
            DHAPI.setHologramLines(hologram, 0, textLines);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to update DH hologram " + dhId, e);
        }
    }

    @Override
    public void remove(UUID id) {
        if (!isAvailable()) return;

        String dhId = hologramIds.remove(id);
        if (dhId != null) {
            try {
                DHAPI.removeHologram(dhId);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to remove DH hologram " + dhId, e);
            }
        }
    }

    @Override
    public void removeAll() {
        if (!isAvailable()) return;

        for (String dhId : List.copyOf(hologramIds.values())) {
            try {
                DHAPI.removeHologram(dhId);
            } catch (Exception ignored) {}
        }
        hologramIds.clear();
    }

    @Override
    public void shutdown() {
        // DH handles persistence natively
    }

    @Override
    public String getType() {
        return "decent_holograms";
    }

    public boolean isAvailable() {
        return DailyRewards.getInstance().getHologramManager().isDecentHologramsEnabled();
    }

    /**
     * Re-register a DH hologram after loading metadata from config.
     */
    public void registerHologram(UUID id, String dhId) {
        hologramIds.put(id, dhId);
        if (isAvailable()) {
            try {
                Hologram hologram = DHAPI.getHologram(dhId);
                if (hologram == null) {
                    logger.warning("DH hologram " + dhId + " not found on register — it may need to be recreated with /dr hologram create");
                }
            } catch (Exception ignored) {}
        }
    }
}
