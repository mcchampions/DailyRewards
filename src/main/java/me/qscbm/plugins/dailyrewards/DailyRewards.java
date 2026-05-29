package me.qscbm.plugins.dailyrewards;

import lombok.Getter;
import me.qscbm.plugins.dailyrewards.commands.DailyRewardsCommand;
import me.qscbm.plugins.dailyrewards.commands.DailyRewardsCommandTabCompleter;
import me.qscbm.plugins.dailyrewards.listeners.PlayerConnectionListener;
import me.qscbm.plugins.dailyrewards.listeners.RewardGUIListener;
import me.qscbm.plugins.dailyrewards.managers.*;
import me.qscbm.plugins.dailyrewards.placeholders.DailyRewardsPlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
public class DailyRewards extends JavaPlugin {

    @Getter
    private static DailyRewards instance;
    private ConfigManager configManager;
    private GuiConfig guiConfig;
    private DatabaseManager databaseManager;
    private PlayerTimeManager playerTimeManager;
    private RewardManager rewardManager;
    private LoginManager loginManager;
    private MessageManager messageManager;
    private RewardScheduler rewardScheduler;
    private RewardGUIListener rewardGUIListener;
    private HologramManager hologramManager;
    private PlayerNameCache playerNameCache;
    private Object economy;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveResourceIfMissing("rewards.yml");
        saveResourceIfMissing("gui.yml");
        saveResourceIfMissing("messages.yml");

        // Auto-merge missing keys on version change
        ConfigUpdater.update(this, "config.yml", 3);
        ConfigUpdater.update(this, "messages.yml", 2);
        reloadConfig();
        configManager = new ConfigManager();
        configManager.reload(getConfig(), getDataFolder());

        guiConfig = new GuiConfig();
        guiConfig.reload(getDataFolder());

        messageManager = new MessageManager();
        messageManager.load(getDataFolder());

        databaseManager = new DatabaseManager(getLogger(), configManager);
        databaseManager.initialize();

        hookVault();

        playerTimeManager = new PlayerTimeManager(getLogger(), databaseManager);
        playerTimeManager.setMaxCacheSize(configManager.cacheMaxSize());
        playerTimeManager.preloadAll();
        playerNameCache = new PlayerNameCache();

        rewardManager = new RewardManager(getLogger(), getDataFolder());
        rewardManager.loadRewards();

        loginManager = new LoginManager(getLogger(), configManager, databaseManager);
        loginManager.loadConfig();

        rewardScheduler = new RewardScheduler(this);
        rewardScheduler.start();

        rewardGUIListener = new RewardGUIListener(this);

        hologramManager = new HologramManager(this, getLogger(), getDataFolder());
        hologramManager.load();

        var cmd = getCommand("dailyrewards");
        if (cmd != null) {
            cmd.setExecutor(new DailyRewardsCommand(this));
            cmd.setTabCompleter(new DailyRewardsCommandTabCompleter());
        }

        Bukkit.getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(rewardGUIListener, this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new DailyRewardsPlaceholderExpansion(this).register();
            getLogger().info("PlaceholderAPI expansion registered");
        }

        getLogger().info("DailyRewards v2.0.0 enabled"
                + (configManager.debug() ? " [DEBUG]" : ""));
    }

    @Override
    public void onDisable() {
        java.util.concurrent.CompletableFuture<Void> finalSave = null;
        if (rewardScheduler != null) {
            finalSave = rewardScheduler.stop();
        }
        if (hologramManager != null) hologramManager.shutdown();
        if (playerTimeManager != null) {
            try {
                if (finalSave == null) {
                    finalSave = playerTimeManager.saveAll();
                }
                finalSave.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().warning("Final save did not complete within 10s: " + e.getMessage());
            }
        }
        if (databaseManager != null) databaseManager.shutdown();
        instance = null;
        getLogger().info("DailyRewards disabled");
    }

    public void reloadPluginConfig() {
        reloadConfig();
        configManager.reload(getConfig(), getDataFolder());
        guiConfig.reload(getDataFolder());
        messageManager.load(getDataFolder());
        playerNameCache.clear();
        rewardManager.loadRewards();
        loginManager.loadConfig();
        playerTimeManager.setMaxCacheSize(configManager.cacheMaxSize());
        hologramManager.reloadConfig();
    }

    private void hookVault() {
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                var rsp = getServer().getServicesManager().getRegistration(
                        Class.forName("net.milkbowl.vault.economy.Economy"));
                if (rsp != null) {
                    economy = rsp.getProvider();
                    getLogger().info("Vault economy hooked");
                }
            } catch (ClassNotFoundException e) {
                getLogger().warning("Vault economy class not found, money rewards disabled");
            }
        } else {
            getLogger().info("Vault not found, money rewards will be skipped");
        }
    }

    public boolean hasVault() { return economy != null; }

    private void saveResourceIfMissing(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }
}
