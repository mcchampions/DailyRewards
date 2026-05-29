package me.qscbm.plugins.dailyrewards.listeners;

import me.qscbm.plugins.dailyrewards.DailyRewards;
import me.qscbm.plugins.dailyrewards.gui.RewardGUI;
import me.qscbm.plugins.dailyrewards.managers.RewardManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class RewardGUIListener implements Listener {

    private final DailyRewards plugin;
    private final Map<UUID, Map<Integer, Integer>> openGUIs = new HashMap<>();

    public RewardGUIListener(DailyRewards plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Map<Integer, Integer> slotMap = openGUIs.get(player.getUniqueId());
        if (slotMap == null) return;

        event.setCancelled(true);
        int slot = event.getRawSlot();

        // Player head or info book click — show detailed stats then refresh
        int playerInfoSlot = plugin.getGuiConfig().playerInfoSlot();
        int navInfoSlot = plugin.getGuiConfig().navInfoSlot();
        if (slot == playerInfoSlot || slot == navInfoSlot) {
            UUID uuid = player.getUniqueId();
            int daily = plugin.getPlayerTimeManager().getTime(uuid) / 60;
            int total = plugin.getPlayerTimeManager().getTotalTime(uuid) / 60;
            int loginDays = plugin.getLoginManager().getLoginDays(uuid);
            int claimed = plugin.getPlayerTimeManager().getClaimedCount(uuid);
            int segs = plugin.getRewardManager().getSegmentsForPlayer(player).size();

            player.sendMessage(Component.text("§3===== §b" + player.getName() + " 的奖励统计 §3====="));
            player.sendMessage(Component.text("§7今日在线: §e" + daily + " 分钟"));
            player.sendMessage(Component.text("§7总在线: §e" + total + " 分钟"));
            player.sendMessage(Component.text("§7连续登录: §e" + loginDays + " 天"));
            player.sendMessage(Component.text("§7今日已领取: §e" + claimed + "/" + segs + " 个奖励段"));
            player.sendMessage(Component.text("§7点击物品即可领取对应分段奖励"));
            reopenGUI(player);
            return;
        }

        if (!slotMap.containsKey(slot)) return;

        int segmentIndex = slotMap.get(slot);
        if (plugin.getPlayerTimeManager().hasClaimed(player.getUniqueId(), segmentIndex)) {
            player.sendMessage(Component.text("你已经领取过这个奖励了!")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        List<RewardManager.RewardSegment> segments = plugin.getRewardManager().getSegmentsForPlayer(player);
        if (segmentIndex < 0 || segmentIndex >= segments.size()) return;

        int requiredTime = segments.get(segmentIndex).time();
        int playerTime = plugin.getPlayerTimeManager().getTime(player.getUniqueId());

        if (playerTime < requiredTime) {
            int remaining = (requiredTime - playerTime) / 60;
            player.sendMessage(Component.text("你还需要 " + remaining + " 分钟才能领取这个奖励!")
                    .color(net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        double loginBonus = plugin.getLoginManager().getMultiplier(player.getUniqueId());
        if (!plugin.getRewardManager().grantRewards(player, segmentIndex, loginBonus)) {
            player.sendMessage(plugin.getMessageManager().get("gui.inventory-full"));
            player.closeInventory();
            return;
        }
        plugin.getPlayerTimeManager().setClaimed(player.getUniqueId(), segmentIndex);

        player.sendMessage(Component.text("恭喜! 你领取了在线 " + (requiredTime / 60) + " 分钟的奖励!")
                .color(net.kyori.adventure.text.format.NamedTextColor.GREEN));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.0f);

        // Reopen GUI to refresh state after a short delay
        int delay = plugin.getConfigManager().guiReopenDelay();
        if (plugin.getConfigManager().guiAutoRefresh()) {
            plugin.getServer().getGlobalRegionScheduler().runDelayed(plugin, task -> {
                if (player.isOnline()) reopenGUI(player);
            }, delay);
        } else {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            openGUIs.remove(player.getUniqueId());
        }
    }

    public void registerOpenGUI(Player player, Map<Integer, Integer> slotMap) {
        openGUIs.put(player.getUniqueId(), slotMap);
    }

    private void reopenGUI(Player player) {
        RewardGUI gui = new RewardGUI(plugin);
        Map<Integer, Integer> newSlotMap = gui.open(player);
        registerOpenGUI(player, newSlotMap);
    }

}
