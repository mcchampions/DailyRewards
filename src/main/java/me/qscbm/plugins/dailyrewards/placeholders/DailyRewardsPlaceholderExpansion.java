package me.qscbm.plugins.dailyrewards.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.qscbm.plugins.dailyrewards.DailyRewards;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * PlaceholderAPI expansion providing daily reward placeholders.
 * <p>
 * Placeholders:
 *   %dailyrewards_daily_time%        - Today's online time (minutes)
 *   %dailyrewards_daily_time_raw%    - Today's online time (seconds)
 *   %dailyrewards_total_time%        - Total online time (minutes)
 *   %dailyrewards_total_time_raw%    - Total online time (seconds)
 *   %dailyrewards_login_days%            - Current login days
 *   %dailyrewards_claimed%           - Number of segments claimed today
 *   %dailyrewards_daily_time_hms%    - Today's time in HH:MM:SS format
 *   %dailyrewards_total_time_hms%    - Total time in HH:MM:SS format
 */
public class DailyRewardsPlaceholderExpansion extends PlaceholderExpansion {

    private final DailyRewards plugin;

    public DailyRewardsPlaceholderExpansion(DailyRewards plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return plugin.getConfig().getString("placeholder.identifier", "dailyrewards");
    }

    @Override
    public @NotNull String getAuthor() {
        return "QSCBM";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return ""; // PAPI passes null in console/sign contexts
        UUID uuid = player.getUniqueId();

        return switch (params) {
            case "daily_time" -> String.valueOf(plugin.getPlayerTimeManager().getTime(uuid) / 60);
            case "daily_time_raw" -> String.valueOf(plugin.getPlayerTimeManager().getTime(uuid));
            case "total_time" -> String.valueOf(plugin.getPlayerTimeManager().getTotalTime(uuid) / 60);
            case "total_time_raw" -> String.valueOf(plugin.getPlayerTimeManager().getTotalTime(uuid));
            case "login_days" -> String.valueOf(plugin.getPlayerTimeManager().getLoginDays(uuid));
            case "claimed" -> String.valueOf(plugin.getPlayerTimeManager().getClaimedCount(uuid));
            case "daily_time_hms" -> formatHMS(plugin.getPlayerTimeManager().getTime(uuid));
            case "total_time_hms" -> formatHMS(plugin.getPlayerTimeManager().getTotalTime(uuid));
            default -> null;
        };
    }

    private static String formatHMS(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}
