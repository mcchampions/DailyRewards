package me.qscbm.plugins.dailyrewards.gui;

import me.qscbm.plugins.dailyrewards.DailyRewards;
import me.qscbm.plugins.dailyrewards.managers.GuiConfig;
import me.qscbm.plugins.dailyrewards.managers.RewardManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class RewardGUI {

    private final DailyRewards plugin;
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    public RewardGUI(DailyRewards plugin) {
        this.plugin = plugin;
    }

    /**
     * Opens the reward GUI for a player and returns the slot-to-segment-index mapping.
     */
    public Map<Integer, Integer> open(Player player) {
        GuiConfig cfg = plugin.getGuiConfig();
        int size = cfg.size();
        Inventory inv = Bukkit.createInventory(null, size, cfg.title());

        // Fill background
        Material fillerMat = cfg.fillerMaterial();
        if (fillerMat != null) {
            ItemStack filler = new ItemStack(fillerMat);
            ItemMeta fm = filler.getItemMeta();
            fm.displayName(cfg.fillerName());
            filler.setItemMeta(fm);
            for (int i = 0; i < size; i++) {
                inv.setItem(i, filler.clone());
            }
        }

        // Player info head
        if (cfg.playerInfoEnabled()) {
            int infoSlot = cfg.playerInfoSlot();
            if (infoSlot >= 0 && infoSlot < size) {
                inv.setItem(infoSlot, createPlayerInfoItem(player, cfg));
            }
        }

        // Reward segments
        List<RewardManager.RewardSegment> segments = plugin.getRewardManager().getSegmentsForPlayer(player);
        int playerTime = plugin.getPlayerTimeManager().getTime(player.getUniqueId());

        List<Integer> slotOrder = new ArrayList<>(cfg.slotOrder() != null ? cfg.slotOrder() : Collections.emptyList());
        if (slotOrder.isEmpty()) {
            for (int i = 0; i < segments.size() && i < size; i++) {
                slotOrder.add(i);
            }
        }

        Map<Integer, Integer> slotMap = new HashMap<>();
        for (int i = 0; i < segments.size() && i < slotOrder.size(); i++) {
            int slot = slotOrder.get(i);
            if (slot < 0 || slot >= size) continue;
            slotMap.put(slot, i);
            RewardManager.RewardSegment seg = segments.get(i);
            inv.setItem(slot, createSegmentItem(player, seg, i, playerTime, cfg));
        }

        // Navigation button
        int navSlot = cfg.navInfoSlot();
        Material navMat = cfg.navInfoMaterial();
        if (navMat != null && navSlot >= 0 && navSlot < size) {
            ItemStack nav = new ItemStack(navMat);
            ItemMeta nm = nav.getItemMeta();
            nm.displayName(cfg.navInfoName());
            nm.lore(cfg.navInfoLore());
            nav.setItemMeta(nm);
            inv.setItem(navSlot, nav);
        }

        player.openInventory(inv);
        return slotMap;
    }

    private ItemStack createPlayerInfoItem(Player player, GuiConfig cfg) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        if (skull.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            int pt = plugin.getPlayerTimeManager().getTime(player.getUniqueId());
            int tt = plugin.getPlayerTimeManager().getTotalTime(player.getUniqueId());
            int loginDays = plugin.getLoginManager().getLoginDays(player.getUniqueId());

            meta.displayName(cfg.playerInfoName(player.getName()));
            meta.lore(cfg.playerInfoLore(player.getName(), String.valueOf(pt / 60),
                    String.valueOf(tt / 60), String.valueOf(loginDays)));
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private ItemStack createSegmentItem(Player player, RewardManager.RewardSegment seg,
                                         int index, int playerTime, GuiConfig cfg) {
        boolean claimed = plugin.getPlayerTimeManager().hasClaimed(player.getUniqueId(), index);
        boolean unlocked = playerTime >= seg.time();
        int remaining = seg.time() - playerTime;

        // Use segment-specific display item if available
        RewardManager.DisplayItem di = seg.displayItem();
        if (di != null && di.material() != null) {
            ItemStack item = new ItemStack(di.material());
            ItemMeta meta = item.getItemMeta();
            if (di.name() != null) meta.displayName(deserialize(di.name()));
            List<Component> lore = new ArrayList<>();
            if (di.lore() != null) {
                for (String line : di.lore()) lore.add(deserialize(line));
            }
            // Status line
            if (claimed) lore.add(deserialize("<gray>已领取</gray>"));
            else if (unlocked) lore.add(deserialize("<green><bold>点击领取!</bold></green>"));
            else lore.add(deserialize("<red>未解锁 - 还需 " + (remaining / 60) + " 分钟</red>"));
            meta.lore(lore);
            item.setItemMeta(meta);
            return item;
        }

        // Use generic config-based display
        Material mat = claimed ? cfg.claimedMaterial() : unlocked ? cfg.unlockedMaterial() : cfg.lockedMaterial();
        if (mat == null) mat = Material.GLASS_PANE;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();

        String time = String.valueOf(seg.time() / 60);
        String remainingMinutes = String.valueOf(Math.max(0, remaining / 60));
        List<Component> lore;
        if (claimed) {
            meta.displayName(cfg.claimedName(time));
            lore = cfg.claimedLore();
        } else if (unlocked) {
            meta.displayName(cfg.unlockedName(time));
            String totalTime = String.valueOf(plugin.getPlayerTimeManager().getTotalTime(player.getUniqueId()) / 60);
            String loginDays = String.valueOf(plugin.getLoginManager().getLoginDays(player.getUniqueId()));
            lore = cfg.unlockedLore(time, totalTime, loginDays);
        } else {
            meta.displayName(cfg.lockedName(time));
            lore = cfg.lockedLore(time, remainingMinutes, cfg.progressBar(playerTime, seg.time()));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final java.util.regex.Pattern MINI_TAG = java.util.regex.Pattern.compile("</?[a-z][a-z0-9_]*(:[^>]+)?>");

    static Component deserialize(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (MINI_TAG.matcher(raw).find()) {
            try { return MINI.deserialize(raw); } catch (Exception ignored) {}
        }
        return LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', raw));
    }
}
