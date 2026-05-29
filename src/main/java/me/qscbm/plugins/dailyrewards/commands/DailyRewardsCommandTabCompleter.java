package me.qscbm.plugins.dailyrewards.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.stream.Collectors;

public class DailyRewardsCommandTabCompleter implements TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("open", "update", "addtime", "save", "reload", "reset", "info", "top", "hologram", "help");
    private static final List<String> LEADERBOARD_TYPES = List.of("total_time", "daily_time", "login");
    private static final List<String> HOLOGRAM_ACTIONS = List.of("create", "remove", "list", "refresh");

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(SUBCOMMANDS);
            if (!sender.hasPermission("dailyrewards.update")) completions.remove("update");
            if (!sender.hasPermission("dailyrewards.addtime")) completions.remove("addtime");
            if (!sender.hasPermission("dailyrewards.save")) completions.remove("save");
            if (!sender.hasPermission("dailyrewards.reload")) completions.remove("reload");
            if (!sender.hasPermission("dailyrewards.reset")) completions.remove("reset");
            if (!sender.hasPermission("dailyrewards.top")) completions.remove("top");
            if (!sender.hasPermission("dailyrewards.info")) completions.remove("info");
            if (!sender.hasPermission("dailyrewards.hologram")) completions.remove("hologram");
            return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
        }

        if (args.length == 2) {
            if ("reset".equalsIgnoreCase(args[0]) || "addtime".equalsIgnoreCase(args[0])) {
                if (sender.hasPermission("dailyrewards.reset") || sender.hasPermission("dailyrewards.addtime")) {
                    return StringUtil.copyPartialMatches(args[1],
                            Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                            new ArrayList<>());
                }
            }
            if ("top".equalsIgnoreCase(args[0])) {
                return StringUtil.copyPartialMatches(args[1], LEADERBOARD_TYPES, new ArrayList<>());
            }
            if ("info".equalsIgnoreCase(args[0])) {
                if (sender.hasPermission("dailyrewards.info.others")) {
                    return StringUtil.copyPartialMatches(args[1],
                            Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                            new ArrayList<>());
                }
            }
            if ("hologram".equalsIgnoreCase(args[0]) || "holo".equalsIgnoreCase(args[0])
                || "hd".equalsIgnoreCase(args[0])) {
                if (sender.hasPermission("dailyrewards.hologram")) {
                    return StringUtil.copyPartialMatches(args[1], HOLOGRAM_ACTIONS, new ArrayList<>());
                }
            }
        }

        if (args.length == 3) {
            if (("hologram".equalsIgnoreCase(args[0]) || "holo".equalsIgnoreCase(args[0])
                 || "hd".equalsIgnoreCase(args[0]))) {
                if ("create".equalsIgnoreCase(args[1])) {
                    return StringUtil.copyPartialMatches(args[2], LEADERBOARD_TYPES, new ArrayList<>());
                }
                if ("remove".equalsIgnoreCase(args[1]) || "delete".equalsIgnoreCase(args[1])) {
                    try {
                        var holograms = me.qscbm.plugins.dailyrewards.DailyRewards.getInstance()
                                .getHologramManager().getHolograms();
                        return StringUtil.copyPartialMatches(args[2],
                                holograms.stream()
                                        .map(h -> h.getId().toString().substring(0, 8))
                                        .collect(Collectors.toList()),
                                new ArrayList<>());
                    } catch (Exception ignored) {}
                }
            }
        }

        return Collections.emptyList();
    }
}
