package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;
import me.qscbm.plugins.dailyrewards.DailyRewards;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HologramManager {

    private final Plugin plugin;
    private final Logger logger;
    private final File hologramFile;
    private final Map<UUID, HologramData> holograms = new ConcurrentHashMap<>();
    private HologramProvider provider;
    private String providerType;
    private int maxEntries;
    private int updateInterval;
    private boolean running;
    private boolean updateTaskStarted;
    private ScheduledTask updateTask;

    @Getter
    public boolean decentHologramsEnabled;

    private static final TextColor GOLD = TextColor.color(0xFFAA00);
    private static final TextColor GRAY = TextColor.color(0xAAAAAA);
    private static final TextColor WHITE = TextColor.color(0xFFFFFF);
    private static final TextColor AQUA = TextColor.color(0x55FFFF);

    public HologramManager(Plugin plugin, Logger logger, File dataFolder) {
        this.plugin = plugin;
        this.logger = logger;
        this.hologramFile = new File(dataFolder, "holograms.yml");
    }

    // --- Initialization ---

    public void load() {
        running = true;
        loadCachedConfig();

        initProvider();

        if (hologramFile.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(hologramFile);
            ConfigurationSection sec = cfg.getConfigurationSection("holograms");
            if (sec != null) {
                for (String key : sec.getKeys(false)) {
                    try {
                        UUID id = UUID.fromString(key);
                        String type = sec.getString(key + ".type", "total_time");
                        String worldName = sec.getString(key + ".world");
                        double x = sec.getDouble(key + ".x");
                        double y = sec.getDouble(key + ".y");
                        double z = sec.getDouble(key + ".z");

                        HologramData hd = new HologramData(id, type, worldName, x, y, z);
                        holograms.put(id, hd);

                        // Recover provider-specific state
                        if (provider instanceof TextDisplayHologramProvider td) {
                            List<String> rawUuids = sec.getStringList(key + ".entity-uuids");
                            List<UUID> euuids = rawUuids.stream().map(UUID::fromString).toList();
                            td.registerEntityUuids(id, euuids);
                        } else if (provider instanceof DecentHologramsProvider dh) {
                            String dhId = sec.getString(key + ".dh-id");
                            if (dhId != null) dh.registerHologram(id, dhId);
                        }
                    } catch (Exception e) {
                        logger.log(Level.WARNING, "Failed to load hologram " + key, e);
                    }
                }
            }
        }
        logger.info("Loaded " + holograms.size() + " hologram(s) via " + provider.getType());

        // Deferred init — TextDisplay needs chunks loaded, DH is ready immediately
        long delay = provider instanceof TextDisplayHologramProvider ? 20L : 1L;
        plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
            recoverOrphanedHolograms();
            refreshAll();
            startUpdateTask();
        }, delay);
    }

    public void reloadConfig() {
        int oldInterval = updateInterval;
        String oldProviderType = providerType;
        loadCachedConfig();

        // Provider switch: migrate all holograms to new backend
        if (!Objects.equals(oldProviderType, providerType)) {
            logger.info("Switching hologram provider: " + oldProviderType + " -> " + providerType);
            // Remove all holograms from old provider
            provider.removeAll();
            // Switch to new provider
            initProvider();
            // Re-create all holograms with new provider
            for (HologramData hd : holograms.values()) {
                Location loc = hd.getLocation();
                if (loc != null) {
                    provider.create(hd.id, loc, hd.type);
                }
            }
            save();
        }

        if (oldInterval != updateInterval) {
            restartUpdateTask();
        }
        refreshAll();
    }

    private void loadCachedConfig() {
        var cm = DailyRewards.getInstance().getConfigManager();
        maxEntries = cm.leaderboardMaxEntries();
        updateInterval = Math.max(1, cm.hologramUpdateInterval());
        providerType = cm.hologramProvider().toLowerCase(Locale.ROOT);
    }

    private void initProvider() {
        if ("decent_holograms".equals(providerType)
            && Bukkit.getPluginManager().isPluginEnabled("DecentHolograms")) {
            decentHologramsEnabled = true;
            provider = new DecentHologramsProvider();
        } else {
            if ("decent_holograms".equals(providerType)) {
                logger.warning("DecentHolograms not found, falling back to text_display");
            }
            provider = new TextDisplayHologramProvider();
        }
        provider.init(plugin, logger, maxEntries);
    }

    /**
     * Recover holograms whose entities were lost (e.g., chunk was unloaded and cleaned).
     */
    private void recoverOrphanedHolograms() {
        for (HologramData hd : holograms.values()) {
            Location loc = hd.getLocation();
            if (loc == null) continue;

            if (provider instanceof TextDisplayHologramProvider td) {
                if (!td.validateEntities(hd.id, loc.getWorld())) {
                    td.respawnEntities(hd.id, loc);
                }
            } else if (provider instanceof DecentHologramsProvider dh && dh.isAvailable()) {
                // Verify DH hologram still exists; recreate if missing
                try {
                    var hologram = eu.decentsoftware.holograms.api.DHAPI.getHologram("dr_" + hd.id.toString().substring(0, 8));
                    if (hologram == null) {
                        provider.create(hd.id, loc, hd.type);
                    }
                } catch (Exception e) {
                    provider.create(hd.id, loc, hd.type);
                }
            }
        }
    }

    private void startUpdateTask() {
        if (updateTaskStarted) return;
        updateTaskStarted = true;
        // initial delay = updateInterval (first auto-refresh), then every updateInterval
        updateTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!running) return;
            refreshAll();
        }, updateInterval, updateInterval, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void restartUpdateTask() {
        if (updateTask != null && !updateTask.isCancelled()) {
            updateTask.cancel();
        }
        updateTaskStarted = false;
        startUpdateTask();
    }

    // --- Public API ---

    public UUID create(Location location, String type) {
        UUID id = UUID.randomUUID();
        HologramData hd = new HologramData(id, type, location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
        holograms.put(id, hd);

        provider.create(id, location, type);
        save();
        refreshSingle(hd);
        return id;
    }

    public boolean remove(UUID id) {
        HologramData hd = holograms.remove(id);
        if (hd == null) return false;
        provider.remove(id);
        save();
        return true;
    }

    public Collection<HologramData> getHolograms() {
        return List.copyOf(holograms.values());
    }

    public HologramData get(UUID id) {
        return holograms.get(id);
    }

    public void refreshAll() {
        var ptm = DailyRewards.getInstance().getPlayerTimeManager();
        Map<String, List<HologramData>> byType = new HashMap<>();
        for (HologramData hd : holograms.values()) {
            byType.computeIfAbsent(hd.type, ignored -> new ArrayList<>()).add(hd);
        }
        // Read from cache — no DB query, always up-to-date
        for (Map.Entry<String, List<HologramData>> entry : byType.entrySet()) {
            var tops = ptm.getTopPlayers(entry.getKey(), maxEntries);
            List<Component> lines = buildLines(entry.getKey(), tops);
            if ("text_display".equals(providerType)) {
                // Refresh holograms after player leaves
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    for (HologramData hd : entry.getValue()) {
                        provider.update(hd.id, lines);
                    }
                });
                return;
            }
            for (HologramData hd : entry.getValue()) {
                provider.update(hd.id, lines);
            }
        }
    }

    public void refreshSingle(HologramData hd) {
        var tops = DailyRewards.getInstance().getPlayerTimeManager().getTopPlayers(hd.type, maxEntries);
        List<Component> lines = buildLines(hd.type, tops);
        provider.update(hd.id, lines);
    }

    private List<Component> buildLines(String type, List<PlayerTimeManager.TopEntry> tops) {
        List<Component> lines = new ArrayList<>(maxEntries + 2);
        lines.add(buildHeader(type));

        for (int rank = 1; rank <= maxEntries; rank++) {
            if (tops != null && rank <= tops.size()) {
                var top = tops.get(rank - 1);
                String name = resolveName(top.uuid());
                int value = switch (type) {
                    case "daily_time" -> top.dailyTime() / 60;
                    case "login" -> top.loginDays();
                    default -> top.totalTime() / 60;
                };
                String unit = "login".equals(type) ? " 天" : " 分钟";
                lines.add(buildEntry(rank, name, value, unit));
            } else {
                lines.add(Component.empty());
            }
        }
        lines.add(buildFooter());
        return lines;
    }

    public void shutdown() {
        running = false;
        if (updateTask != null && !updateTask.isCancelled()) {
            updateTask.cancel();
        }
        save();
        if (provider != null) provider.shutdown();
    }

    // --- Persistence ---

    public void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, HologramData> entry : holograms.entrySet()) {
            String key = entry.getKey().toString();
            HologramData hd = entry.getValue();
            cfg.set("holograms." + key + ".type", hd.type);
            cfg.set("holograms." + key + ".world", hd.worldName);
            cfg.set("holograms." + key + ".x", hd.x);
            cfg.set("holograms." + key + ".y", hd.y);
            cfg.set("holograms." + key + ".z", hd.z);
            cfg.set("holograms." + key + ".provider", provider.getType());

            if (provider instanceof TextDisplayHologramProvider td) {
                cfg.set("holograms." + key + ".entity-uuids",
                        td.getEntityUuids(hd.id).stream().map(UUID::toString).toList());
            } else if (provider instanceof DecentHologramsProvider) {
                cfg.set("holograms." + key + ".dh-id", "dr_" + hd.id.toString().substring(0, 8));
            }
        }
        try {
            cfg.save(hologramFile);
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Failed to save holograms.yml", e);
        }
    }

    // --- Helpers ---

    private static String resolveName(UUID uuid) {
        return me.qscbm.plugins.dailyrewards.DailyRewards.getInstance().getPlayerNameCache().getName(uuid);
    }

    // --- Component builders ---

    private static Component buildHeader(String type) {
        String title = switch (type) {
            case "daily_time" -> " 今日在线排行榜 ";
            case "login" -> " 连续登录排行榜 ";
            default -> " 总在线时长排行榜 ";
        };
        return Component.text()
                .append(Component.text("◤", GOLD))
                .append(Component.text(title, AQUA, TextDecoration.BOLD))
                .append(Component.text("◢", GOLD))
                .build();
    }

    private static Component buildEntry(int rank, String name, int value, String unit) {
        TextColor rankColor = switch (rank) {
            case 1 -> TextColor.color(0xFFD700);
            case 2 -> WHITE;
            case 3 -> TextColor.color(0xCD7F32);
            default -> GRAY;
        };
        return Component.text()
                .append(Component.text("No." + rank + " ", rankColor))
                .append(Component.text(name, GOLD))
                .append(Component.text(" - ", GRAY))
                .append(Component.text(value + unit, WHITE))
                .build();
    }

    private static Component buildFooter() {
        return Component.text()
                .append(Component.text("◣", GOLD))
                .append(Component.text(" DailyRewards ", GRAY, TextDecoration.ITALIC))
                .append(Component.text("◢", GOLD))
                .build();
    }

    @Getter
    public static class HologramData {
        private final UUID id;
        private final String type, worldName;
        private final double x, y, z;
        private final long createdAt = System.currentTimeMillis();

        public HologramData(UUID id, String type, String worldName, double x, double y, double z) {
            this.id = id;
            this.type = type;
            this.worldName = worldName;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public Location getLocation() {
            World w = Bukkit.getWorld(worldName);
            return w != null ? new Location(w, x, y, z) : null;
        }
    }
}
