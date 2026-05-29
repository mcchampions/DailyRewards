package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Manages player consecutive login days with milestone rewards.
 */
public class LoginManager {

    private final Logger logger;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final Map<UUID, PlayerLoginData> loginCache = new ConcurrentHashMap<>();
    @Getter
    private final Map<Integer, List<Map<?, ?>>> milestones = new LinkedHashMap<>();

    public LoginManager(Logger logger, ConfigManager configManager, DatabaseManager databaseManager) {
        this.logger = logger;
        this.configManager = configManager;
        this.databaseManager = databaseManager;
    }

    public void loadConfig() {
        milestones.clear();
        milestones.putAll(configManager.loginMilestones());
    }

    /**
     * Called when a player logs in. Returns the (possibly updated) consecutive login days count.
     */
    public CompletableFuture<Integer> onPlayerLogin(UUID uuid, String playerName, String today) {
        return databaseManager.loadPlayerData(uuid).thenApply(record -> {
            int loginDays;
            Set<Integer> claimedMilestones = new HashSet<>();

            if (record != null) {
                String lastLogin = record.lastLogin();
                String lastUpdated = record.lastUpdated();

                if (lastLogin != null) {
                    try {
                        LocalDate lastDate = LocalDate.parse(lastLogin);
                        LocalDate yesterday = LocalDate.now().minusDays(1);
                        if (lastDate.equals(yesterday)) {
                            loginDays = record.login() + 1;
                        } else if (lastDate.equals(LocalDate.now())) {
                            loginDays = record.login();
                        } else {
                            loginDays = 1;
                        }
                    } catch (Exception e) {
                        loginDays = 1;
                    }
                } else {
                    loginDays = 1;
                }

                String raw = record.milestonesClaimed();
                if (raw != null && !raw.isEmpty()) {
                    for (String part : raw.split(",")) {
                        try { claimedMilestones.add(Integer.parseInt(part.trim())); }
                        catch (NumberFormatException ignored) {}
                    }
                }

                if (lastUpdated == null || !lastUpdated.equals(today)) {
                    // New day — daily fields reset elsewhere
                }
            } else {
                loginDays = 1;
            }

            loginDays = Math.max(1, Math.min(loginDays, Integer.MAX_VALUE));
            PlayerLoginData data = new PlayerLoginData(loginDays, today, claimedMilestones);
            loginCache.put(uuid, data);

            return loginDays;
        }).exceptionally(ex -> {
            logger.severe("Failed to compute login days for " + uuid + ": " + ex.getMessage());
            loginCache.put(uuid, new PlayerLoginData(1, today, new HashSet<>()));
            return 1;
        });
    }

    public void onPlayerQuit(UUID uuid) {
        loginCache.remove(uuid);
    }

    public int getLoginDays(UUID uuid) {
        PlayerLoginData data = loginCache.get(uuid);
        return data != null ? data.loginDays : 0;
    }

    public double getMultiplier(UUID uuid) {
        if (!configManager.loginEnabled()) return 0.0;
        int loginDays = getLoginDays(uuid);
        return configManager.loginMultiplier() * Math.min(loginDays, configManager.loginMaxBonus());
    }

    public List<Integer> getReachedMilestones(UUID uuid) {
        PlayerLoginData data = loginCache.get(uuid);
        if (data == null) return Collections.emptyList();
        List<Integer> reached = new ArrayList<>();
        for (int days : milestones.keySet()) {
            if (data.loginDays >= days && !data.claimedMilestones.contains(days)) {
                reached.add(days);
            }
        }
        return reached;
    }

    public void markMilestoneClaimed(UUID uuid, int days) {
        PlayerLoginData data = loginCache.get(uuid);
        if (data != null) {
            data.claimedMilestones.add(days);
        }
    }

    public boolean isEnabled() { return configManager.loginEnabled(); }

    public Set<Integer> getClaimedMilestones(UUID uuid) {
        PlayerLoginData data = loginCache.get(uuid);
        return data != null ? data.claimedMilestones : Collections.emptySet();
    }

    public String serializeClaimedMilestones(UUID uuid) {
        Set<Integer> set = getClaimedMilestones(uuid);
        if (set.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(set);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (int i : sorted) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(i);
        }
        return sb.toString();
    }

    private record PlayerLoginData(int loginDays, String lastLogin, Set<Integer> claimedMilestones) {}
}
