package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.util.*;

/**
 * Caches ALL config.yml values in memory on load, avoiding repeated YAML parsing.
 * Call {@link #reload(FileConfiguration, File)} to refresh after config reload.
 */
@Getter
@Accessors(fluent = true)
public class ConfigManager {

    private int resetHour, resetMinute;
    private int trackingInterval;
    private boolean trackingAsync;
    private boolean modeAutoGrant, modeGuiEnabled, modeAutoActionbar, modeAutoSound, modeAutoReminder;
    private boolean loginEnabled;
    private double loginMultiplier;
    private int loginMaxBonus;
    private String dbType, dbSqliteFile;
    private String dbMysqlHost;
    private int dbMysqlPort;
    private String dbMysqlDatabase, dbMysqlUser, dbMysqlPassword;
    private int dbMysqlPoolMaxSize, dbMysqlPoolMinIdle;
    private long dbMysqlPoolConnTimeout, dbMysqlPoolIdleTimeout, dbMysqlPoolMaxLifetime;
    private String dbMysqlPropertiesUseSSL, dbMysqlPropertiesServerTimezone, dbMysqlPropertiesAllowPublicKeyRetrieval;
    private int storageSaveInterval;
    private boolean storageAsyncSave;
    private int cacheMaxSize;
    private boolean guiUsePaperBuilder, guiAutoRefresh;
    private int guiReopenDelay;
    private int leaderboardMaxEntries;
    private String leaderboardDefaultType;
    private String hologramProvider;
    private int hologramUpdateInterval;
    private boolean debug;
    private Map<Integer, List<Map<?, ?>>> loginMilestones;
    private File dataFolder;

    public void reload(FileConfiguration config, File dataFolder) {
        this.dataFolder = dataFolder;

        resetHour = config.getInt("reset.hour", 0);
        resetMinute = config.getInt("reset.minute", 0);

        trackingInterval = Math.max(1, config.getInt("tracking.interval", 60));
        trackingAsync = config.getBoolean("tracking.async", true);

        modeAutoGrant = config.getBoolean("mode.auto-grant", true);
        modeGuiEnabled = config.getBoolean("mode.gui-enabled", true);
        modeAutoActionbar = config.getBoolean("mode.auto-actionbar", true);
        modeAutoSound = config.getBoolean("mode.auto-sound", true);
        modeAutoReminder = config.getBoolean("mode.auto-reminder", true);

        loginEnabled = config.getBoolean("login.enabled", true);
        loginMultiplier = config.getDouble("login.multiplier", 0.05);
        loginMaxBonus = config.getInt("login.max-bonus", 30);

        dbType = config.getString("database.type", "sqlite");
        dbSqliteFile = config.getString("database.sqlite.file", "playerdata.db");
        dbMysqlHost = config.getString("database.mysql.host", "localhost");
        dbMysqlPort = config.getInt("database.mysql.port", 3306);
        dbMysqlDatabase = config.getString("database.mysql.database", "dailyrewards");
        dbMysqlUser = config.getString("database.mysql.user", "root");
        dbMysqlPassword = config.getString("database.mysql.password", "");
        dbMysqlPoolMaxSize = config.getInt("database.mysql.pool.maximum-pool-size", 10);
        dbMysqlPoolMinIdle = config.getInt("database.mysql.pool.minimum-idle", 2);
        dbMysqlPoolConnTimeout = config.getLong("database.mysql.pool.connection-timeout", 5000);
        dbMysqlPoolIdleTimeout = config.getLong("database.mysql.pool.idle-timeout", 600000);
        dbMysqlPoolMaxLifetime = config.getLong("database.mysql.pool.max-lifetime", 1800000);
        dbMysqlPropertiesUseSSL = config.getString("database.mysql.properties.useSSL", "false");
        dbMysqlPropertiesServerTimezone = config.getString("database.mysql.properties.serverTimezone", "UTC");
        dbMysqlPropertiesAllowPublicKeyRetrieval = config.getString("database.mysql.properties.allowPublicKeyRetrieval", "true");

        storageSaveInterval = Math.max(60, config.getInt("storage.save-interval", 1200));
        storageAsyncSave = config.getBoolean("storage.async-save", true);

        cacheMaxSize = Math.max(100, config.getInt("cache.max-size", 10000));

        guiUsePaperBuilder = config.getBoolean("gui.use-paper-builder", true);
        guiAutoRefresh = config.getBoolean("gui.auto-refresh", true);
        guiReopenDelay = config.getInt("gui.reopen-delay", 2);

        leaderboardMaxEntries = config.getInt("leaderboard.max-entries", 10);
        leaderboardDefaultType = config.getString("leaderboard.default-type", "total_time");

        hologramProvider = config.getString("hologram.provider", "text_display");
        hologramUpdateInterval = config.getInt("hologram.update-interval", 60);

        debug = config.getBoolean("debug", false);

        loginMilestones = new LinkedHashMap<>();
        ConfigurationSection ms = config.getConfigurationSection("login.milestones");
        if (ms != null) {
            for (String key : ms.getKeys(false)) {
                try {
                    int days = Integer.parseInt(key);
                    List<Map<?, ?>> rewards = ms.getMapList(key);
                    if (!rewards.isEmpty()) loginMilestones.put(days, rewards);
                } catch (NumberFormatException ignored) {}
            }
        }
    }
}
