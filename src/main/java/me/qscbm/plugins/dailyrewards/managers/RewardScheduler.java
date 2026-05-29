package me.qscbm.plugins.dailyrewards.managers;

import me.qscbm.plugins.dailyrewards.DailyRewards;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Handles periodic tasks: time tracking, auto-grant, reminders, daily reset, and data saves.
 * Uses cached config values from DailyRewards to avoid repeated YAML parsing.
 */
public class RewardScheduler {

    private final DailyRewards plugin;
    private LocalDate lastResetDate = LocalDate.MIN;
    private volatile boolean running;
    private volatile CompletableFuture<Void> saveInProgress = CompletableFuture.completedFuture(null);
    private ScheduledTask trackingTask;
    private ScheduledTask saveTask;
    private ScheduledTask resetTask;

    public RewardScheduler(DailyRewards plugin) {
        this.plugin = plugin;
    }

    public void start() {
        running = true;
        int interval = plugin.getConfigManager().trackingInterval();
        int saveInterval = plugin.getConfigManager().storageSaveInterval();

        // Time tracking + auto-grant + reminders
        trackingTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!running) return;
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getPlayerTimeManager().addTime(player.getUniqueId(), interval);

                if (plugin.getConfigManager().modeAutoGrant()) {
                    checkAndGrant(player);
                }

                if (plugin.getConfigManager().modeAutoReminder() && plugin.getConfigManager().modeGuiEnabled()) {
                    checkReminder(player);
                }
            }
        }, 20L, interval * 20L);

        // Auto-save — only flushes dirty entries
        saveTask = plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!running) return;
            if (!saveInProgress.isDone()) return;
            saveInProgress = plugin.getPlayerTimeManager().saveDirty().exceptionally(ex -> {
                plugin.getLogger().warning("Auto-save failed: " + ex.getMessage());
                return null;
            });
        }, saveInterval, saveInterval, java.util.concurrent.TimeUnit.SECONDS);

        // Daily reset check — runs every 30s
        resetTask = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            if (!running) return;
            LocalTime now = LocalTime.now();
            LocalDate today = LocalDate.now();

            if (!lastResetDate.equals(today)
                    && !now.isBefore(LocalTime.of(plugin.getConfigManager().resetHour(), plugin.getConfigManager().resetMinute()))) {
                lastResetDate = today;
                String todayString = today.toString();
                plugin.getPlayerTimeManager().resetAllForNewDay(todayString);
                plugin.getDatabaseManager().resetDailyClaimFields(todayString).exceptionally(ex -> {
                    plugin.getLogger().warning("Daily claim field reset failed: " + ex.getMessage());
                    return null;
                });
                plugin.getLogger().info("Daily claim fields reset; total online time is preserved");
            }
        }, 1L, 600L);
    }

    public CompletableFuture<Void> stop() {
        running = false;
        cancelTask(trackingTask);
        cancelTask(saveTask);
        cancelTask(resetTask);
        return plugin.getPlayerTimeManager().saveAll();
    }

    private void cancelTask(ScheduledTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    /**
     * Checks and grants all unclaimed segments the player has reached.
     */
    public void checkAndGrant(Player player) {
        int playerTime = plugin.getPlayerTimeManager().getTime(player.getUniqueId());
        List<RewardManager.RewardSegment> segments = plugin.getRewardManager().getSegmentsForPlayer(player);
        double loginBonus = plugin.getLoginManager().getMultiplier(player.getUniqueId());

        for (int i = 0; i < segments.size(); i++) {
            RewardManager.RewardSegment seg = segments.get(i);
            if (!plugin.getPlayerTimeManager().hasClaimed(player.getUniqueId(), i)
                    && playerTime >= seg.time()) {
                var msg = plugin.getMessageManager().get(
                        plugin.getConfigManager().modeAutoActionbar() ? "auto-grant.actionbar" : "auto-grant.chat",
                        Map.of("time", String.valueOf(seg.time() / 60)));
                if (plugin.getConfigManager().modeAutoActionbar()) {
                    player.sendActionBar(msg);
                } else {
                    player.sendMessage(msg);
                }
                if (!plugin.getRewardManager().grantRewards(player, i, loginBonus)) {
                    player.sendMessage(plugin.getMessageManager().get("gui.inventory-full"));
                    continue;
                }
                plugin.getPlayerTimeManager().setClaimed(player.getUniqueId(), i);
                if (plugin.getConfigManager().modeAutoSound()) {
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);
                }
            }
        }
    }

    private void checkReminder(Player player) {
        int playerTime = plugin.getPlayerTimeManager().getTime(player.getUniqueId());
        List<RewardManager.RewardSegment> segments = plugin.getRewardManager().getSegmentsForPlayer(player);

        for (int i = 0; i < segments.size(); i++) {
            RewardManager.RewardSegment seg = segments.get(i);
            if (!plugin.getPlayerTimeManager().hasClaimed(player.getUniqueId(), i)
                    && !plugin.getPlayerTimeManager().hasReminded(player.getUniqueId(), i)
                    && playerTime >= seg.time()) {

                var reminder = plugin.getMessageManager().get(
                        "reminder.clickable",
                        Map.of("time", String.valueOf(seg.time() / 60)));
                player.sendMessage(reminder);
                plugin.getPlayerTimeManager().setReminded(player.getUniqueId(), i);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.0f);
            }
        }
    }
}
