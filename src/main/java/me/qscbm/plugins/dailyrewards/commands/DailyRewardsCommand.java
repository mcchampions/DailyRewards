package me.qscbm.plugins.dailyrewards.commands;

import me.qscbm.plugins.dailyrewards.DailyRewards;
import me.qscbm.plugins.dailyrewards.gui.RewardGUI;
import me.qscbm.plugins.dailyrewards.managers.HologramManager.HologramData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;

public class DailyRewardsCommand implements CommandExecutor {

    private final DailyRewards plugin;
    private static final Set<String> LEADERBOARD_TYPES = Set.of("total_time", "daily_time", "login");

    public DailyRewardsCommand(DailyRewards plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "open" -> handleOpen(sender);
            case "reload" -> handleReload(sender);
            case "reset" -> handleReset(sender, args);
            case "info" -> handleInfo(sender, args);
            case "top" -> handleTop(sender, args);
            case "addtime" -> handleAddTime(sender, args);
            case "update" -> handleUpdate(sender);
            case "save" -> handleSave(sender);
            case "hologram", "holo", "hd" -> handleHologram(sender, args);
            case "help" -> { sendHelp(sender); yield true; }
            default -> { sender.sendMessage(msg("&c未知子命令。使用 /dr help 查看帮助")); yield true; }
        };
    }

    private boolean handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&c只有玩家可以使用此命令"));
            return true;
        }
        if (!player.hasPermission("dailyrewards.use")) {
            player.sendMessage(msg("&c你没有权限使用此命令"));
            return true;
        }
        if (!plugin.getConfigManager().modeGuiEnabled()) {
            player.sendMessage(msg("&cGUI模式已被管理员禁用"));
            return true;
        }
        RewardGUI gui = new RewardGUI(plugin);
        Map<Integer, Integer> slotMap = gui.open(player);
        plugin.getRewardGUIListener().registerOpenGUI(player, slotMap);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("dailyrewards.reload")) {
            sender.sendMessage(msg("&c你没有权限重载配置"));
            return true;
        }
        plugin.reloadPluginConfig();
        sender.sendMessage(msg("&a配置已重载，共缓存 " + plugin.getPlayerTimeManager().getCacheSize() + " 条玩家数据"));
        return true;
    }

    private boolean handleReset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dailyrewards.reset")) {
            sender.sendMessage(msg("&c你没有权限重置数据"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("&e用法: /dr reset <玩家>"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(msg("&c玩家不在线或不存在"));
            return true;
        }
        plugin.getPlayerTimeManager().resetToday(target.getUniqueId());
        sender.sendMessage(msg("&a已重置 " + target.getName() + " 的今日在线时间"));
        target.sendMessage(msg("&c你的每日在线时间已被管理员重置"));
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dailyrewards.info")) {
            sender.sendMessage(msg("&c你没有权限查看信息"));
            return true;
        }

        Player target;
        if (args.length >= 2 && sender.hasPermission("dailyrewards.info.others")) {
            target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(msg("&c玩家不在线或不存在"));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(msg("&c请指定玩家: /dr info <玩家>"));
            return true;
        }

        UUID uuid = target.getUniqueId();
        int dailyTime = plugin.getPlayerTimeManager().getTime(uuid);
        int totalTime = plugin.getPlayerTimeManager().getTotalTime(uuid);
        int loginDays = plugin.getLoginManager().getLoginDays(uuid);
        int claimed = plugin.getPlayerTimeManager().getClaimedCount(uuid);
        int totalSegs = plugin.getRewardManager().getTotalSegments("default");

        sender.sendMessage(msg("&3===== &b" + target.getName() + " 的奖励统计 &3====="));
        sender.sendMessage(msg("&7今日在线: &e" + (dailyTime / 60) + " 分钟"));
        sender.sendMessage(msg("&7总在线: &e" + (totalTime / 60) + " 分钟"));
        sender.sendMessage(msg("&7连续登录: &e" + loginDays + " 天"));
        sender.sendMessage(msg("&7今日已领取: &e" + claimed + "/" + totalSegs + " 个分段"));
        return true;
    }

    private boolean handleTop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dailyrewards.top")) {
            sender.sendMessage(msg("&c你没有权限查看排行榜"));
            return true;
        }

        String type = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) :
                plugin.getConfigManager().leaderboardDefaultType();
        if (!LEADERBOARD_TYPES.contains(type)) {
            sender.sendMessage(msg("&c无效的类型。可用: total_time, daily_time, login"));
            return true;
        }
        int limit = plugin.getConfigManager().leaderboardMaxEntries();

        String headerText = switch (type) {
            case "daily_time" -> "&3===== &b今日在线排行榜 &3=====";
            case "login" -> "&3===== &b连续登录排行榜 &3=====";
            default -> "&3===== &b总在线时长排行榜 &3=====";
        };
        sender.sendMessage(msg(headerText));

        var tops = plugin.getPlayerTimeManager().getTopPlayers(type, limit);
        if (tops.isEmpty()) {
            sender.sendMessage(msg("&7暂无数据"));
            return true;
        }
        int rank = 1;
        for (var top : tops) {
            String name = plugin.getPlayerNameCache().getName(top.uuid());
            int value = switch (type) {
                case "daily_time" -> top.dailyTime() / 60;
                case "login" -> top.loginDays();
                default -> top.totalTime() / 60;
            };
            String unit = "login".equals(type) ? " 天" : " 分钟";
            sender.sendMessage(msg("&7No." + rank + " &e" + name + " &7- &f" + value + unit));
            rank++;
        }
        return true;
    }

    private boolean handleHologram(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dailyrewards.hologram")) {
            sender.sendMessage(msg("&c你没有权限管理全息排行榜"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(msg("&e用法: /dr hologram <create|remove|list|refresh>"));
            return true;
        }

        return switch (args[1].toLowerCase()) {
            case "create" -> handleHologramCreate(sender, args);
            case "remove", "delete" -> handleHologramRemove(sender, args);
            case "list" -> handleHologramList(sender);
            case "refresh", "update" -> handleHologramRefresh(sender);
            default -> {
                sender.sendMessage(msg("&e用法: /dr hologram <create|remove|list|refresh>"));
                yield true;
            }
        };
    }

    private boolean handleHologramCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("&c只有玩家可以创建全息排行榜"));
            return true;
        }
        String type = args.length >= 3 ? args[2] : "total_time";
        if (!List.of("total_time", "daily_time", "login").contains(type)) {
            sender.sendMessage(msg("&c无效的类型。可用: total_time, daily_time, login"));
            return true;
        }
        Location loc = player.getLocation().add(0, 2.5, 0);
        UUID id = plugin.getHologramManager().create(loc, type);
        sender.sendMessage(msg("&a全息排行榜已创建! ID: &e" + id.toString().substring(0, 8)));
        return true;
    }

    private boolean handleHologramRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(msg("&e用法: /dr hologram remove <ID>"));
            sender.sendMessage(msg("&7使用 /dr hologram list 查看所有ID"));
            return true;
        }
        String idPrefix = args[2];
        var holograms = plugin.getHologramManager().getHolograms();
        UUID match = null;
        for (HologramData hd : holograms) {
            if (hd.getId().toString().startsWith(idPrefix)) {
                match = hd.getId();
                break;
            }
        }
        if (match == null) {
            sender.sendMessage(msg("&c未找到匹配的全息排行榜: " + idPrefix));
            return true;
        }
        plugin.getHologramManager().remove(match);
        sender.sendMessage(msg("&a全息排行榜已删除"));
        return true;
    }

    private boolean handleHologramList(CommandSender sender) {
        var holograms = plugin.getHologramManager().getHolograms();
        if (holograms.isEmpty()) {
            sender.sendMessage(msg("&7当前没有全息排行榜"));
            return true;
        }
        sender.sendMessage(msg("&3===== &b全息排行榜列表 &3====="));
        for (HologramData hd : holograms) {
            String shortId = hd.getId().toString().substring(0, 8);
            String typeName = switch (hd.getType()) {
                case "daily_time" -> "今日在线";
                case "login" -> "连续登录";
                default -> "总在线时长";
            };
            String loc = String.format("%.0f,%.0f,%.0f", hd.getX(), hd.getY(), hd.getZ());
            sender.sendMessage(msg("&e" + shortId + " &7- " + typeName
                    + " &7@ &f" + hd.getWorldName() + " " + loc));
        }
        return true;
    }

    private boolean handleHologramRefresh(CommandSender sender) {
        plugin.getHologramManager().refreshAll();
        sender.sendMessage(msg("&a所有全息排行榜已刷新"));
        return true;
    }

    private boolean handleAddTime(CommandSender sender, String[] args) {
        if (!sender.hasPermission("dailyrewards.addtime")) {
            sender.sendMessage(msg("&c你没有权限执行此操作"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(msg("&e用法: /dr addtime <玩家> <秒数>"));
            return true;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(msg("&c玩家不在线或不存在"));
            return true;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg("&c秒数必须为整数"));
            return true;
        }
        if (seconds <= 0 || seconds > 86400) {
            sender.sendMessage(msg("&c秒数范围为 1 ~ 86400 (24小时)"));
            return true;
        }
        plugin.getPlayerTimeManager().addTime(target.getUniqueId(), seconds);
        plugin.getRewardScheduler().checkAndGrant(target);
        sender.sendMessage(msg("&a已为 " + target.getName() + " 添加 " + seconds + " 秒在线时间"));
        target.sendMessage(msg("&a管理员已为你添加 " + (seconds / 60) + " 分钟在线时间"));
        return true;
    }

    private boolean handleUpdate(CommandSender sender) {
        if (!sender.hasPermission("dailyrewards.update")) {
            sender.sendMessage(msg("&c你没有权限执行此操作"));
            return true;
        }
        int count = 0;
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            plugin.getRewardScheduler().checkAndGrant(p);
            count++;
        }
        plugin.getHologramManager().refreshAll();
        sender.sendMessage(msg("&a已为 " + count + " 名在线玩家更新奖励状态，全息榜已刷新"));
        return true;
    }

    private boolean handleSave(CommandSender sender) {
        if (!sender.hasPermission("dailyrewards.save")) {
            sender.sendMessage(msg("&c你没有权限执行此操作"));
            return true;
        }
        plugin.getPlayerTimeManager().saveDirty().whenComplete((v, ex) ->
                plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
                    if (ex != null) {
                        sender.sendMessage(msg("&c数据保存失败，请查看控制台日志"));
                        return;
                    }
                    sender.sendMessage(msg("&a数据已手动保存 (缓存: "
                            + plugin.getPlayerTimeManager().getCacheSize() + " 人)"));
                }));
        return true;
    }

    private static void sendHelp(CommandSender sender) {
        sender.sendMessage(msg("&3===== &b每日奖励帮助 &3====="));
        sender.sendMessage(msg("&e/dr open &7- 打开奖励GUI"));
        if (sender.hasPermission("dailyrewards.info")) {
            sender.sendMessage(msg("&e/dr info [玩家] &7- 查看统计信息"));
        }
        if (sender.hasPermission("dailyrewards.top")) {
            sender.sendMessage(msg("&e/dr top &7- 查看总在线时长排行榜"));
            sender.sendMessage(msg("&e/dr top <total_time|daily_time|login> &7- 指定排行榜类型"));
        }
        if (sender.hasPermission("dailyrewards.hologram")) {
            sender.sendMessage(msg("&e/dr hologram create [类型] &7- 创建全息排行榜"));
            sender.sendMessage(msg("&e/dr hologram remove <ID> &7- 删除全息排行榜"));
            sender.sendMessage(msg("&e/dr hologram list &7- 列出所有全息排行榜"));
            sender.sendMessage(msg("&e/dr hologram refresh &7- 立即刷新全息排行榜"));
        }
        if (sender.hasPermission("dailyrewards.addtime")) {
            sender.sendMessage(msg("&e/dr addtime <玩家> <秒数> &7- 手动添加在线时间"));
        }
        if (sender.hasPermission("dailyrewards.update")) {
            sender.sendMessage(msg("&e/dr update &7- 立即更新所有在线玩家奖励状态"));
        }
        if (sender.hasPermission("dailyrewards.save")) {
            sender.sendMessage(msg("&e/dr save &7- 立即保存数据到数据库"));
        }
        if (sender.hasPermission("dailyrewards.reload")) {
            sender.sendMessage(msg("&e/dr reload &7- 重载配置"));
        }
        if (sender.hasPermission("dailyrewards.reset")) {
            sender.sendMessage(msg("&e/dr reset <玩家> &7- 重置在线时间"));
        }
    }

    private static Component msg(String raw) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', raw));
    }
}
