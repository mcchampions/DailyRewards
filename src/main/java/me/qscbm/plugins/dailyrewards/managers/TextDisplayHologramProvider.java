package me.qscbm.plugins.dailyrewards.managers;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Hologram provider using Paper's native TextDisplay entities.
 * No external plugin dependencies required.
 * Handles entity persistence and automatic recovery after server restarts.
 */
public class TextDisplayHologramProvider implements HologramProvider {

    private static final double LINE_SPACING = 0.28;

    private int maxEntries;
    private final Map<UUID, List<UUID>> hologramEntities = new ConcurrentHashMap<>(); // hologramId -> entity UUIDs

    @Override
    public void init(Plugin plugin, Logger logger, int maxEntries) {
        this.maxEntries = maxEntries;
    }

    @Override
    public void create(UUID id, Location location, String type) {
        World world = location.getWorld();
        if (world == null) return;

        int totalLines = 1 + maxEntries + 1;
        List<UUID> entityIds = new ArrayList<>(totalLines);
        double baseY = location.getY();

        for (int i = 0; i < totalLines; i++) {
            Location lineLoc = new Location(world, location.getX(), baseY - (i * LINE_SPACING),
                    location.getZ(), location.getYaw(), location.getPitch());
            TextDisplay td = (TextDisplay) world.spawnEntity(lineLoc, EntityType.TEXT_DISPLAY);
            td.setBillboard(Display.Billboard.CENTER);
            td.setSeeThrough(true);
            td.setShadowed(true);
            td.setAlignment(TextDisplay.TextAlignment.CENTER);
            td.setPersistent(true);
            td.text(Component.empty());
            entityIds.add(td.getUniqueId());
        }

        hologramEntities.put(id, entityIds);
    }

    @Override
    public void update(UUID id, List<Component> lines) {
        List<UUID> entityIds = hologramEntities.get(id);
        if (entityIds == null) return;

        // Find a valid world by checking any entity
        World world = null;
        for (UUID eid : entityIds) {
            Entity e = Bukkit.getEntity(eid);
            if (e != null) {
                world = e.getWorld();
                break;
            }
        }
        if (world == null) return;

        for (int i = 0; i < entityIds.size(); i++) {
            Entity entity = world.getEntity(entityIds.get(i));
            if (entity instanceof TextDisplay td && !td.isDead()) {
                td.text(i < lines.size() ? lines.get(i) : Component.empty());
            }
        }
    }

    @Override
    public void remove(UUID id) {
        List<UUID> entityIds = hologramEntities.remove(id);
        if (entityIds == null) return;

        for (World world : Bukkit.getWorlds()) {
            for (UUID eid : entityIds) {
                Entity e = world.getEntity(eid);
                if (e instanceof TextDisplay && !e.isDead()) {
                    e.remove();
                }
            }
        }
    }

    @Override
    public void removeAll() {
        for (UUID id : List.copyOf(hologramEntities.keySet())) {
            remove(id);
        }
        hologramEntities.clear();
    }

    @Override
    public void shutdown() {
        // Entities are persistent, no action needed
    }

    @Override
    public String getType() {
        return "text_display";
    }

    /**
     * Returns the entity UUIDs associated with a hologram.
     * Used by HologramManager for persistence.
     */
    public List<UUID> getEntityUuids(UUID hologramId) {
        List<UUID> uuids = hologramEntities.get(hologramId);
        return uuids != null ? List.copyOf(uuids) : List.of();
    }

    /**
     * Re-register entity UUIDs after loading from persistence.
     */
    public void registerEntityUuids(UUID hologramId, List<UUID> entityUuids) {
        hologramEntities.put(hologramId, new ArrayList<>(entityUuids));
    }

    /**
     * Checks if a hologram's entities are still valid in the world.
     * Returns false if entities need respawning.
     */
    public boolean validateEntities(UUID hologramId, World world) {
        List<UUID> entityIds = hologramEntities.get(hologramId);
        if (entityIds == null || entityIds.isEmpty()) return false;
        for (UUID eid : entityIds) {
            Entity entity = world.getEntity(eid);
            if (!(entity instanceof TextDisplay td) || td.isDead()) return false;
        }
        return true;
    }

    public void respawnEntities(UUID hologramId, Location location) {
        remove(hologramId);
        create(hologramId, location, "");
    }
}
