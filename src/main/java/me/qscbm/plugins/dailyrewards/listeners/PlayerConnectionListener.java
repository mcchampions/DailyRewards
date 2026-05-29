package me.qscbm.plugins.dailyrewards.listeners;

import me.qscbm.plugins.dailyrewards.DailyRewards;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class PlayerConnectionListener implements Listener {

    private final DailyRewards plugin;

    public PlayerConnectionListener(DailyRewards plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String today = LocalDate.now().toString();

        // Start async data load (non-blocking)
        plugin.getPlayerTimeManager().loadPlayerAsync(player.getUniqueId());

        // Handle login on a delay to let data load
        if (plugin.getLoginManager().isEnabled()) {
            plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> plugin.getLoginManager().onPlayerLogin(player.getUniqueId(), player.getName(), today)
                    .thenAccept(loginDays -> {
                        // Sync login days back to PlayerTimeManager
                        plugin.getPlayerTimeManager().setLoginDays(player.getUniqueId(), loginDays);

                        // All Bukkit API calls must run on the main region thread
                        plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduledTask -> {
                            if (!player.isOnline()) return;

                            if (loginDays > 1) {
                                player.sendMessage(plugin.getMessageManager().prefix()
                                        .append(plugin.getMessageManager().get("login.login",
                                                Map.of("login_days", String.valueOf(loginDays)))));
                            }

                            checkMilestones(player, loginDays, today);
                        });
                    }), 1, java.util.concurrent.TimeUnit.SECONDS);
        }

        // Send auto-grant catchup after a short delay
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduledTask -> plugin.getRewardScheduler().checkAndGrant(player)), 2, java.util.concurrent.TimeUnit.SECONDS);

        // Refresh holograms after data loads
        plugin.getServer().getAsyncScheduler().runDelayed(plugin, task -> plugin.getServer().getGlobalRegionScheduler().run(plugin, scheduledTask -> plugin.getHologramManager().refreshAll()), 3, java.util.concurrent.TimeUnit.SECONDS);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        // Write-through save and mark offline, but keep in cache
        plugin.getPlayerTimeManager().markOffline(player.getUniqueId());
        plugin.getLoginManager().onPlayerQuit(player.getUniqueId());
    }

    private void checkMilestones(Player player, int loginDays, String today) {
        List<Integer> reached = plugin.getLoginManager().getReachedMilestones(player.getUniqueId());
        for (int days : reached) {
            Map<Integer, List<Map<?, ?>>> milestones = plugin.getLoginManager().getMilestones();
            List<Map<?, ?>> rewards = milestones.get(days);
            if (rewards != null) {
                player.sendMessage(plugin.getMessageManager().get("gui.processing"));
                if (!plugin.getRewardManager().grantRawRewards(player, rewards, 0.0)) {
                    player.sendMessage(plugin.getMessageManager().get("gui.inventory-full"));
                    continue;
                }
                plugin.getLoginManager().markMilestoneClaimed(player.getUniqueId(), days);
                plugin.getPlayerTimeManager().setMilestoneClaimed(player.getUniqueId(), days);
                player.sendMessage(plugin.getMessageManager().get("login.milestone",
                        Map.of("days", String.valueOf(days))));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
            }
        }
    }
}
