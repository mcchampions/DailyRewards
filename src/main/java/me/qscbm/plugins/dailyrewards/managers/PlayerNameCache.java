package me.qscbm.plugins.dailyrewards.managers;

import org.bukkit.Bukkit;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerNameCache {

    private final Map<UUID, String> cache = new ConcurrentHashMap<>();

    public String getName(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            String name = Bukkit.getOfflinePlayer(id).getName();
            return name != null ? name : id.toString().substring(0, 8);
        });
    }

    public void clear() {
        cache.clear();
    }
}
