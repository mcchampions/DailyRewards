package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;
import me.qscbm.plugins.dailyrewards.DailyRewards;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class RewardManager {

    private static final Pattern PATTERN = Pattern.compile("(?i)[0-9a-f]{6}");
    private final Logger logger;
    private final File dataFolder;
    @Getter
    private final Map<String, List<RewardSegment>> rewardMap = new LinkedHashMap<>();
    private List<String> groupPriority = new ArrayList<>();
    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final Pattern MINI_TAG = Pattern.compile("</?[a-z][a-z0-9_]*(:[^>]+)?>");

    public RewardManager(Logger logger, File dataFolder) {
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    public void loadRewards() {
        rewardMap.clear();
        File file = new File(dataFolder, "rewards.yml");
        if (!file.exists()) {
            logger.warning("rewards.yml not found, creating default");
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        groupPriority = config.getStringList("group-priority");
        if (groupPriority.isEmpty()) {
            ConfigurationSection gs = config.getConfigurationSection("groups");
            if (gs != null) {
                groupPriority = new ArrayList<>(gs.getKeys(false));
            }
            // Ensure "default" is last
            groupPriority.remove("default");
            groupPriority.add("default");
        }

        ConfigurationSection groupsSection = config.getConfigurationSection("groups");
        if (groupsSection == null) return;

        for (String group : groupsSection.getKeys(false)) {
            List<RewardSegment> segments = new ArrayList<>();
            List<Map<?, ?>> segmentList = groupsSection.getMapList(group);
            for (int idx = 0; idx < segmentList.size(); idx++) {
                Map<?, ?> segmentMap = segmentList.get(idx);
                Object timeRaw = segmentMap.get("time");
                if (!(timeRaw instanceof Number)) continue;
                int time = ((Number) timeRaw).intValue();

                DisplayItem displayItem = null;
                if (segmentMap.containsKey("display-item") && segmentMap.get("display-item") instanceof Map<?, ?> d) {
                    Material mat = Material.getMaterial(String.valueOf(d.get("material")).toUpperCase());
                    if (mat != null) {
                        displayItem = new DisplayItem(mat,
                                (String) d.get("name"),
                                safeStringList(d.get("lore")));
                    }
                }

                List<Reward> rewards = new ArrayList<>();
                Object rewardsObj = segmentMap.get("rewards");
                if (rewardsObj instanceof List) {
                    for (Object entry : (List<?>) rewardsObj) {
                        if (!(entry instanceof Map<?, ?> r)) continue;
                        Reward reward = buildReward(r);
                        if (reward != null) rewards.add(reward);
                    }
                }
                segments.add(new RewardSegment(idx, time, rewards, displayItem));
            }
            rewardMap.put(group, segments);
        }
        logger.info("Loaded " + rewardMap.size() + " reward groups");
    }

    private Reward buildReward(Map<?, ?> r) {
        String type = String.valueOf(r.get("type")).toLowerCase();
        return switch (type) {
            case "item" -> buildItemReward(r);
            case "experience", "exp", "xp" -> new ExperienceReward(toInt(r.get("amount"), 0));
            case "money", "vault", "eco" -> new MoneyReward(toDouble(r.get("amount"), 0.0));
            case "command", "cmd" -> new CommandReward(
                    String.valueOf(r.get("command")),
                    toBool(r.get("console"), true));
            case "sound" -> buildSoundReward(r);
            case "message", "msg" -> new MessageReward(
                    String.valueOf(r.get("message")),
                    toBool(r.get("actionbar"), false));
            case "potion_effect", "potion", "effect" -> buildPotionEffectReward(r);
            case "title" -> buildTitleReward(r);
            case "firework", "fw" -> buildFireworkReward(r);
            default -> { logger.warning("Unknown reward type: " + type); yield null; }
        };
    }

    /**
     * Returns reward segments for a player, respecting group priority.
     */
    public List<RewardSegment> getSegmentsForPlayer(Player player) {
        for (String group : groupPriority) {
            if (!rewardMap.containsKey(group)) continue;
            if ("default".equalsIgnoreCase(group)) continue;
            if (player.hasPermission("dailyrewards.group." + group)) {
                return rewardMap.get(group);
            }
        }
        return rewardMap.getOrDefault("default", Collections.emptyList());
    }

    /**
     * Grants all rewards for a specific segment to the player.
     * Returns false if the player's inventory cannot hold the item rewards.
     */
    public boolean grantRewards(Player player, int segmentIndex, double loginBonus) {
        List<RewardSegment> segments = getSegmentsForPlayer(player);
        if (segmentIndex < 0 || segmentIndex >= segments.size()) return false;

        List<Reward> rewards = segments.get(segmentIndex).rewards();
        List<ItemStack> itemStacks = new ArrayList<>();
        for (Reward reward : rewards) {
            if (reward instanceof ItemReward ir) {
                int amount = applyLoginBonus(ir.amount, loginBonus, false);
                itemStacks.add(createItemStack(ir, amount));
            }
        }
        if (!itemStacks.isEmpty() && !hasInventorySpace(player, itemStacks)) {
            return false;
        }

        int itemIdx = 0;
        for (Reward reward : rewards) {
            if (reward instanceof ItemReward) {
                player.getInventory().addItem(itemStacks.get(itemIdx++));
            } else {
                grant(player, reward, loginBonus);
            }
        }
        return true;
    }

    public boolean grantRewards(Player player, int segmentIndex) {
        return grantRewards(player, segmentIndex, 0.0);
    }

    /**
     * Grants raw rewards from a list of maps (used for milestone rewards).
     * Returns false if the player's inventory cannot hold the item rewards.
     */
    public boolean grantRawRewards(Player player, List<Map<?, ?>> rawRewards, double loginBonus) {
        List<ItemStack> itemStacks = new ArrayList<>();
        List<Reward> nonItems = new ArrayList<>();
        for (Map<?, ?> r : rawRewards) {
            Reward reward = buildReward(r);
            if (reward == null) continue;
            if (reward instanceof ItemReward ir) {
                int amount = applyLoginBonus(ir.amount, loginBonus, false);
                itemStacks.add(createItemStack(ir, amount));
            } else {
                nonItems.add(reward);
            }
        }
        if (!itemStacks.isEmpty() && !hasInventorySpace(player, itemStacks)) {
            return false;
        }
        for (ItemStack stack : itemStacks) {
            player.getInventory().addItem(stack);
        }
        for (Reward reward : nonItems) {
            grant(player, reward, loginBonus);
        }
        return true;
    }

    private void grant(Player player, Reward reward, double loginBonus) {
        if (reward instanceof ItemReward ir) {
            int amount = applyLoginBonus(ir.amount, loginBonus, false);
            ItemStack item = new ItemStack(ir.material, Math.max(1, amount));
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (ir.name != null) meta.displayName(deserialize(ir.name));
                if (ir.lore != null && !ir.lore.isEmpty()) {
                    meta.lore(ir.lore.stream().map(RewardManager::deserialize).toList());
                }
                item.setItemMeta(meta);
            }
            player.getInventory().addItem(item);
        } else if (reward instanceof ExperienceReward er) {
            player.giveExp(applyLoginBonus(er.amount, loginBonus, false));
        } else if (reward instanceof MoneyReward mr) {
            Object eco = getEconomy();
            if (eco != null) {
                try {
                    Method depositMethod = eco.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class);
                    depositMethod.invoke(eco, player, applyLoginBonusDouble(mr.amount, loginBonus));
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to grant money via Vault: " + e.getMessage());
                }
            }
        } else if (reward instanceof CommandReward cr) {
            String cmd = cr.command.replace("%player%", player.getName());
            if (cr.console) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                player.performCommand(cmd);
            }
        } else if (reward instanceof SoundReward sr) {
            player.playSound(player.getLocation(), sr.sound, sr.volume, sr.pitch);
        } else if (reward instanceof MessageReward mr) {
            Component msg = deserialize(mr.message.replace("%player%", player.getName()));
            if (mr.actionbar) {
                player.sendActionBar(msg);
            } else {
                player.sendMessage(msg);
            }
        } else if (reward instanceof PotionEffectReward pe) {
            player.addPotionEffect(new PotionEffect(pe.effect, pe.duration * 20, pe.amplifier, pe.ambient, pe.particles, pe.icon));
        } else if (reward instanceof TitleReward tr) {
            Title title = Title.title(
                    deserialize(tr.title.replace("%player%", player.getName())),
                    deserialize(tr.subtitle.replace("%player%", player.getName())),
                    Title.Times.times(
                            Duration.ofMillis(tr.fadeIn * 50L),
                            Duration.ofMillis(tr.stay * 50L),
                            Duration.ofMillis(tr.fadeOut * 50L)
                    )
            );
            player.showTitle(title);
        } else if (reward instanceof FireworkReward fr) {
            spawnFirework(player, fr);
        }
    }

    private static void spawnFirework(Player player, FireworkReward fr) {
        Firework fw = player.getWorld().spawn(player.getLocation(), Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.setPower(fr.power);

        FireworkEffect.Builder builder = FireworkEffect.builder();
        List<Color> colors = fr.colors.stream()
                .map(RewardManager::parseFireworkColor)
                .filter(Objects::nonNull)
                .toList();
        builder.withColor(colors.isEmpty() ? List.of(Color.RED) : colors);
        if (fr.fadeColors != null) {
            List<Color> fadeColors = fr.fadeColors.stream()
                    .map(RewardManager::parseFireworkColor)
                    .filter(Objects::nonNull)
                    .toList();
            if (!fadeColors.isEmpty()) builder.withFade(fadeColors);
        }
        if (fr.flicker) builder.withFlicker();
        if (fr.trail) builder.withTrail();
        try { builder.with(FireworkEffect.Type.valueOf(fr.explosionType.toUpperCase())); }
        catch (Exception e) { builder.with(FireworkEffect.Type.BALL); }

        meta.addEffect(builder.build());
        fw.setFireworkMeta(meta);
    }

    public int getTotalSegments(String group) {
        List<RewardSegment> segs = rewardMap.get(group);
        return segs != null ? segs.size() : 0;
    }

    // --- Reward building helpers ---

    private ItemReward buildItemReward(Map<?, ?> r) {
        String matStr = String.valueOf(r.get("material"));
        Material mat = Material.getMaterial(matStr.toUpperCase());
        if (mat == null) { logger.warning("Invalid material: " + matStr); return null; }
        return new ItemReward(mat, toInt(r.get("amount"), 1),
                (String) r.get("name"), safeStringList(r.get("lore")));
    }

    private SoundReward buildSoundReward(Map<?, ?> r) {
        try {
            Sound sound = Sound.valueOf(String.valueOf(r.get("sound")).toUpperCase());
            return new SoundReward(sound,
                    (float) toDouble(r.get("volume"), 1.0),
                    (float) toDouble(r.get("pitch"), 1.0));
        } catch (IllegalArgumentException e) {
            logger.warning("Invalid sound: " + r.get("sound"));
            return null;
        }
    }

    private PotionEffectReward buildPotionEffectReward(Map<?, ?> r) {
        try {
            PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(
                    String.valueOf(r.get("effect")).toLowerCase()));
            if (type == null) { logger.warning("Invalid potion effect: " + r.get("effect")); return null; }
            return new PotionEffectReward(type,
                    toInt(r.get("duration"), 600),
                    toInt(r.get("amplifier"), 0),
                    toBool(r.get("ambient"), true),
                    toBool(r.get("particles"), true),
                    toBool(r.get("icon"), true));
        } catch (Exception e) {
            logger.warning("Invalid potion effect: " + r.get("effect"));
            return null;
        }
    }

    private static TitleReward buildTitleReward(Map<?, ?> r) {
        Object titleObj = r.get("title");
        Object subtitleObj = r.get("subtitle");
        return new TitleReward(
                titleObj != null ? String.valueOf(titleObj) : "",
                subtitleObj != null ? String.valueOf(subtitleObj) : "",
                toInt(r.get("fade-in"), 10),
                toInt(r.get("stay"), 70),
                toInt(r.get("fade-out"), 20));
    }

    @SuppressWarnings({"rawtypes"})
    private static FireworkReward buildFireworkReward(Map<?, ?> r) {
        Object colorsObj = r.get("colors");
        if (colorsObj == null) colorsObj = List.of("FF0000");
        List<String> colors = new ArrayList<>();
        if (colorsObj instanceof List list) {
            for (Object o : list) colors.add(String.valueOf(o));
        }
        Object fadeObj = r.get("fade-colors");
        List<String> fadeColors = null;
        if (fadeObj instanceof List list) {
            fadeColors = new ArrayList<>();
            for (Object o : list) fadeColors.add(String.valueOf(o));
        }
        Object typeObj = r.get("type");
        return new FireworkReward(
                toInt(r.get("power"), 1),
                colors, fadeColors,
                typeObj != null ? String.valueOf(typeObj) : "BALL",
                toBool(r.get("flicker"), false),
                toBool(r.get("trail"), false));
    }

    // --- Helper methods ---
    private static List<String> safeStringList(Object raw) {
        if (raw instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object o : (List<?>) raw) result.add(String.valueOf(o));
            return result;
        }
        return new ArrayList<>();
    }

    private static int toInt(Object raw, int def) {
        if (raw instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(raw)); }
        catch (Exception e) { return def; }
    }

    private static double toDouble(Object raw, double def) {
        if (raw instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(raw)); }
        catch (Exception e) { return def; }
    }

    private static boolean toBool(Object raw, boolean def) {
        if (raw instanceof Boolean b) return b;
        if (raw == null) return def;
        return Boolean.parseBoolean(String.valueOf(raw));
    }

    private static Component deserialize(String raw) {
        if (raw == null) return Component.empty();
        if (MINI_TAG.matcher(raw).find()) {
            try { return MINI.deserialize(raw); } catch (Exception ignored) {}
        }
        String legacy = ChatColor.translateAlternateColorCodes('&', raw);
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    private static Color parseFireworkColor(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (!value.isEmpty() && value.charAt(0) == '#') value = value.substring(1);
        if (PATTERN.matcher(value).matches()) {
            try { return Color.fromRGB(Integer.parseInt(value, 16)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "WHITE" -> Color.WHITE;
            case "SILVER" -> Color.SILVER;
            case "GRAY", "GREY" -> Color.GRAY;
            case "BLACK" -> Color.BLACK;
            case "RED" -> Color.RED;
            case "MAROON" -> Color.MAROON;
            case "YELLOW", "GOLD" -> Color.YELLOW;
            case "OLIVE" -> Color.OLIVE;
            case "LIME" -> Color.LIME;
            case "GREEN" -> Color.GREEN;
            case "AQUA", "CYAN" -> Color.AQUA;
            case "TEAL" -> Color.TEAL;
            case "BLUE" -> Color.BLUE;
            case "NAVY" -> Color.NAVY;
            case "FUCHSIA", "MAGENTA" -> Color.FUCHSIA;
            case "PURPLE" -> Color.PURPLE;
            case "ORANGE" -> Color.ORANGE;
            default -> null;
        };
    }

    private static int applyLoginBonus(int amount, double bonus, boolean isDouble) {
        if (bonus <= 0) return amount;
        return (int) Math.round(amount * (1.0 + bonus));
    }

    private static double applyLoginBonusDouble(double amount, double bonus) {
        if (bonus <= 0) return amount;
        return amount * (1.0 + bonus);
    }

    private static Object getEconomy() {
        return DailyRewards.getInstance().getEconomy();
    }

    // --- Inventory helpers ---

    private static ItemStack createItemStack(ItemReward ir, int amount) {
        ItemStack item = new ItemStack(ir.material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (ir.name != null) meta.displayName(deserialize(ir.name));
            if (ir.lore != null && !ir.lore.isEmpty()) {
                meta.lore(ir.lore.stream().map(RewardManager::deserialize).toList());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private static boolean hasInventorySpace(Player player, List<ItemStack> items) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        ItemStack[] clone = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null) clone[i] = contents[i].clone();
        }

        for (ItemStack item : items) {
            int remaining = item.getAmount();
            // Try stacking with similar existing items first
            for (int i = 0; i < clone.length && remaining > 0; i++) {
                ItemStack slot = clone[i];
                if (slot != null && slot.isSimilar(item) && slot.getAmount() < slot.getMaxStackSize()) {
                    int space = slot.getMaxStackSize() - slot.getAmount();
                    int add = Math.min(remaining, space);
                    slot.setAmount(slot.getAmount() + add);
                    remaining -= add;
                }
            }
            // Fill empty slots
            int maxStack = item.getMaxStackSize();
            for (int i = 0; i < clone.length && remaining > 0; i++) {
                if (clone[i] == null) {
                    int toPlace = Math.min(remaining, maxStack);
                    clone[i] = item.clone();
                    clone[i].setAmount(toPlace);
                    remaining -= toPlace;
                }
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    // --- Data classes ---

    public record DisplayItem(Material material, String name, List<String> lore) {}

    public interface Reward { String rewardType(); }

    public record ItemReward(Material material, int amount, String name, List<String> lore) implements Reward {
        public String rewardType() { return "item"; }
    }

    public record ExperienceReward(int amount) implements Reward {
        public String rewardType() { return "experience"; }
    }

    public record MoneyReward(double amount) implements Reward {
        public String rewardType() { return "money"; }
    }

    public record CommandReward(String command, boolean console) implements Reward {
        public String rewardType() { return "command"; }
    }

    public record SoundReward(Sound sound, float volume, float pitch) implements Reward {
        public String rewardType() { return "sound"; }
    }

    public record MessageReward(String message, boolean actionbar) implements Reward {
        public String rewardType() { return "message"; }
    }

    public record PotionEffectReward(PotionEffectType effect, int duration, int amplifier,
                                      boolean ambient, boolean particles, boolean icon) implements Reward {
        public String rewardType() { return "potion_effect"; }
    }

    public record TitleReward(String title, String subtitle, int fadeIn, int stay, int fadeOut) implements Reward {
        public String rewardType() { return "title"; }
    }

    public record FireworkReward(int power, List<String> colors, List<String> fadeColors,
                                  String explosionType, boolean flicker, boolean trail) implements Reward {
        public String rewardType() { return "firework"; }
    }

    public record RewardSegment(int index, int time, List<Reward> rewards, DisplayItem displayItem) {
            public RewardSegment(int index, int time, List<Reward> rewards, DisplayItem displayItem) {
                this.index = index;
                this.time = time;
                this.rewards = Collections.unmodifiableList(rewards);
                this.displayItem = displayItem;
            }
        }
}
