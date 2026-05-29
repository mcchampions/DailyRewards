package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;
import lombok.experimental.Accessors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Caches ALL gui.yml values in memory as parsed objects. No YAML parsing after load.
 */
@Getter
@Accessors(fluent = true)
public class GuiConfig {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Component title;
    private int size;
    private boolean playerInfoEnabled;
    private int playerInfoSlot;
    private String playerInfoNameRaw;
    private List<String> playerInfoLoreRaw;
    private Material unlockedMaterial;
    private String unlockedNameRaw;
    private List<String> unlockedLoreRaw;
    private Material claimedMaterial;
    private String claimedNameRaw;
    private List<String> claimedLoreRaw;
    private Material lockedMaterial;
    private String lockedNameRaw;
    private List<String> lockedLoreRaw;
    private Material fillerMaterial;
    private String fillerNameRaw;
    private int progressBarLength;
    private String progressBarFilledRaw, progressBarEmptyRaw;
    private List<Integer> slotOrder;
    private int navInfoSlot;
    private Material navInfoMaterial;
    private String navInfoNameRaw;
    private List<String> navInfoLoreRaw;

    public void reload(File dataFolder) {
        File file = new File(dataFolder, "gui.yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        title = deserialize(cfg.getString("title", "<dark_aqua>每日在线奖励</dark_aqua>"));
        size = Math.min(54, Math.max(9, cfg.getInt("size", 27)));
        size = (size / 9) * 9;

        playerInfoEnabled = cfg.getBoolean("player-info.enabled", true);
        playerInfoSlot = cfg.getInt("player-info.slot", 4);
        playerInfoNameRaw = cfg.getString("player-info.name", "<green>%player%</green>");
        playerInfoLoreRaw = cfg.getStringList("player-info.lore");

        unlockedMaterial = getMaterial(cfg.getString("unlocked.material", "LIME_STAINED_GLASS_PANE"));
        unlockedNameRaw = cfg.getString("unlocked.name", "<green>%time% 分钟的奖励</green>");
        unlockedLoreRaw = cfg.getStringList("unlocked.lore");

        claimedMaterial = getMaterial(cfg.getString("claimed.material", "GRAY_STAINED_GLASS_PANE"));
        claimedNameRaw = cfg.getString("claimed.name", "<gray>%time% 分钟的奖励 (已领取)</gray>");
        claimedLoreRaw = cfg.getStringList("claimed.lore");

        lockedMaterial = getMaterial(cfg.getString("locked.material", "RED_STAINED_GLASS_PANE"));
        lockedNameRaw = cfg.getString("locked.name", "<red>%time% 分钟的奖励 (未解锁)</red>");
        lockedLoreRaw = cfg.getStringList("locked.lore");

        fillerMaterial = getMaterial(cfg.getString("filler.material", "BLACK_STAINED_GLASS_PANE"));
        fillerNameRaw = cfg.getString("filler.name", " ");

        progressBarLength = cfg.getInt("progress-bar.length", 20);
        progressBarFilledRaw = cfg.getString("progress-bar.filled", "<green>█</green>");
        progressBarEmptyRaw = cfg.getString("progress-bar.empty", "<gray>░</gray>");

        slotOrder = cfg.getIntegerList("slot-order");
        if (slotOrder.isEmpty()) slotOrder = Collections.emptyList();

        navInfoSlot = cfg.getInt("navigation.info.slot", 22);
        navInfoMaterial = getMaterial(cfg.getString("navigation.info.material", "BOOK"));
        navInfoNameRaw = cfg.getString("navigation.info.name", "<gold>个人信息</gold>");
        navInfoLoreRaw = cfg.getStringList("navigation.info.lore");
    }

    // --- Parameterized getters (Lombok can't generate these) ---

    public Component playerInfoName(String player) {
        return deserialize(replace(playerInfoNameRaw, "player", player));
    }
    public List<Component> playerInfoLore(String player, String time, String totalTime, String loginDays) {
        return deserializeList(playerInfoLoreRaw, Map.of(
                "player", player,
                "time", time,
                "total_time", totalTime,
                "login_days", loginDays));
    }
    public Component unlockedName(String time) { return deserialize(replace(unlockedNameRaw, "time", time)); }
    public List<Component> unlockedLore(String time, String totalTime, String loginDays) {
        return deserializeList(unlockedLoreRaw, Map.of("time", time, "total_time", totalTime, "login_days", loginDays));
    }
    public Component claimedName(String time) { return deserialize(replace(claimedNameRaw, "time", time)); }
    public List<Component> claimedLore() { return deserializeList(claimedLoreRaw, null); }
    public Component lockedName(String time) { return deserialize(replace(lockedNameRaw, "time", time)); }
    public List<Component> lockedLore(String time, String remaining, String progressBar) {
        return deserializeList(lockedLoreRaw, Map.of("time", time, "remaining", remaining, "progress_bar", progressBar));
    }
    public Component fillerName() { return deserialize(fillerNameRaw); }
    public Component navInfoName() { return deserialize(navInfoNameRaw); }
    public List<Component> navInfoLore() { return deserializeList(navInfoLoreRaw, null); }
    public String progressBar(int current, int max) {
        if (max <= 0) return "";
        int fillCount = (int) ((double) current / max * progressBarLength);
        fillCount = Math.max(0, Math.min(progressBarLength, fillCount));
        return progressBarFilledRaw.repeat(fillCount) + progressBarEmptyRaw.repeat(progressBarLength - fillCount);
    }

    // --- Helpers ---

    private static Material getMaterial(String name) {
        return name != null ? Material.getMaterial(name.toUpperCase()) : null;
    }

    static Component deserialize(String raw) {
        if (raw == null || raw.isEmpty()) return Component.empty();
        if (raw.contains("<") && raw.contains(">")) {
            try { return MINI.deserialize(raw); } catch (Exception ignored) {}
        }
        return LegacyComponentSerializer.legacySection()
                .deserialize(ChatColor.translateAlternateColorCodes('&', raw));
    }

    private static List<Component> deserializeList(List<String> raw, Map<String, String> vars) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Component> result = new ArrayList<>();
        for (String line : raw) {
            result.add(deserialize(vars != null ? replaceAll(line, vars) : line));
        }
        return result;
    }

    private static String replace(String template, String key, String value) {
        return template.replace("%" + key + "%", value);
    }

    private static String replaceAll(String template, Map<String, String> vars) {
        for (var e : vars.entrySet()) template = template.replace("%" + e.getKey() + "%", e.getValue());
        return template;
    }
}
