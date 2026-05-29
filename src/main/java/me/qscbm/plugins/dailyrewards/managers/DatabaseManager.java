package me.qscbm.plugins.dailyrewards.managers;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DatabaseManager {

    private final Logger logger;
    private final ConfigManager config;
    private String dbType;
    private HikariDataSource hikari;
    private String sqliteUrl;
    private ExecutorService executor;
    private final ConcurrentLinkedQueue<CompletableFuture<?>> pendingTasks = new ConcurrentLinkedQueue<>();

    public DatabaseManager(Logger logger, ConfigManager config) {
        this.logger = logger;
        this.config = config;
    }

    public void initialize() {
        dbType = config.dbType().toLowerCase();
        if ("mysql".equals(dbType)) {
            initMySQL();
        } else {
            initSQLite();
        }
        initExecutor();
        createTables();
        logger.info("Database initialized (" + dbType + ")");
    }

    private void initExecutor() {
        int threads = "mysql".equals(dbType)
                ? Math.max(2, Math.min(4, config.dbMysqlPoolMaxSize()))
                : 1;
        AtomicInteger threadId = new AtomicInteger();
        executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "DailyRewards-DB-" + threadId.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
    }

    private void initSQLite() {
        File file = new File(config.dataFolder(), config.dbSqliteFile());
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        sqliteUrl = "jdbc:sqlite:" + file.getAbsolutePath();
    }

    private void initMySQL() {
        String params = "?useSSL=" + config.dbMysqlPropertiesUseSSL()
                + "&serverTimezone=" + config.dbMysqlPropertiesServerTimezone()
                + "&allowPublicKeyRetrieval=" + config.dbMysqlPropertiesAllowPublicKeyRetrieval();

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + config.dbMysqlHost() + ":" + config.dbMysqlPort() + "/" + config.dbMysqlDatabase() + params);
        hc.setUsername(config.dbMysqlUser());
        hc.setPassword(config.dbMysqlPassword());
        hc.setMaximumPoolSize(config.dbMysqlPoolMaxSize());
        hc.setMinimumIdle(config.dbMysqlPoolMinIdle());
        hc.setConnectionTimeout(config.dbMysqlPoolConnTimeout());
        hc.setIdleTimeout(config.dbMysqlPoolIdleTimeout());
        hc.setMaxLifetime(config.dbMysqlPoolMaxLifetime());
        hc.setPoolName("DailyRewards-MySQL");
        hikari = new HikariDataSource(hc);
    }

    private Connection getConnection() throws SQLException {
        Connection conn = hikari != null ? hikari.getConnection() : DriverManager.getConnection(sqliteUrl);
        if (hikari == null) {
            configureSQLiteConnection(conn);
        }
        return conn;
    }

    private void configureSQLiteConnection(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA busy_timeout=5000");
        }
    }

    private void createTables() {
        boolean mysql = "mysql".equals(dbType);
        String autoCol = mysql ? "INT AUTO_INCREMENT" : "INTEGER";
        String textT = "TEXT";
        String intT = mysql ? "INT" : "INTEGER";

        String sql = "CREATE TABLE IF NOT EXISTS player_data (" +
                "id " + autoCol + " PRIMARY KEY" + (mysql ? "" : " AUTOINCREMENT") + ", " +
                "uuid VARCHAR(36) NOT NULL UNIQUE, " +
                "daily_time " + intT + " DEFAULT 0, " +
                "total_time " + intT + " DEFAULT 0, " +
                "login " + intT + " DEFAULT 0, " +
                "last_updated " + textT + ", " +
                "last_login " + textT + ", " +
                "claimed_segments " + textT + (mysql ? "" : " DEFAULT ''") + ", " +
                "reminded_segments " + textT + (mysql ? "" : " DEFAULT ''") + ", " +
                "milestones_claimed " + textT + (mysql ? "" : " DEFAULT ''") +
                ")";

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            if (!mysql) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
            }
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to create table", e);
            return;
        }

        // Rename streak → login (for pre-v2 databases that had streak column)
        String renameSql = "mysql".equals(dbType)
                ? "ALTER TABLE player_data CHANGE COLUMN streak login " + intT + " DEFAULT 0"
                : "ALTER TABLE player_data RENAME COLUMN streak TO login";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(renameSql);
        } catch (SQLException ignored) {}

        // Add columns that may be missing from legacy tables
        String[] migrations = {
                "ALTER TABLE player_data ADD COLUMN login " + intT + " DEFAULT 0",
                "ALTER TABLE player_data ADD COLUMN last_login " + textT,
                "ALTER TABLE player_data ADD COLUMN milestones_claimed " + textT + (mysql ? "" : " DEFAULT ''")
        };
        for (String alt : migrations) {
            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(alt);
            } catch (SQLException ignored) {}
        }

        createLeaderboardIndexes();
    }

    private void createLeaderboardIndexes() {
        createIndex("idx_player_data_total_time", "total_time");
        createIndex("idx_player_data_daily_time", "daily_time");
        createIndex("idx_player_data_login", "login");
    }

    private void createIndex(String indexName, String columnName) {
        String sql = "mysql".equals(dbType)
                ? "CREATE INDEX " + indexName + " ON player_data (" + columnName + " DESC)"
                : "CREATE INDEX IF NOT EXISTS " + indexName + " ON player_data (" + columnName + " DESC)";
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            if (isDuplicateIndex(e)) {
                return;
            }
            logger.log(Level.WARNING, "Failed to create leaderboard index " + indexName, e);
        }
    }

    private boolean isDuplicateIndex(SQLException e) {
        if (!"mysql".equals(dbType)) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        return e.getErrorCode() == 1061 || message.contains("duplicate key name");
    }

    public CompletableFuture<Void> savePlayerData(PlayerDataRecord record) {
        return track(CompletableFuture.runAsync(() -> saveOne(record), executor));
    }

    private void saveOne(PlayerDataRecord r) {
        String sql = "mysql".equals(dbType) ?
                "INSERT INTO player_data (uuid,daily_time,total_time,login,last_updated,last_login,claimed_segments,reminded_segments,milestones_claimed) " +
                "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE daily_time=VALUES(daily_time),total_time=VALUES(total_time)," +
                "login=VALUES(login),last_updated=VALUES(last_updated),last_login=VALUES(last_login)," +
                "claimed_segments=VALUES(claimed_segments),reminded_segments=VALUES(reminded_segments),milestones_claimed=VALUES(milestones_claimed)" :
                "INSERT OR REPLACE INTO player_data (uuid,daily_time,total_time,login,last_updated,last_login,claimed_segments,reminded_segments,milestones_claimed) " +
                "VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.uuid().toString());
            ps.setInt(2, r.dailyTime());
            ps.setInt(3, r.totalTime());
            ps.setInt(4, r.login());
            ps.setString(5, r.lastUpdated());
            ps.setString(6, r.lastLogin());
            ps.setString(7, nvl(r.claimedSegments()));
            ps.setString(8, nvl(r.remindedSegments()));
            ps.setString(9, nvl(r.milestonesClaimed()));
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "Failed to save player data for " + r.uuid(), e);
            throw new CompletionException(e);
        }
    }

    public CompletableFuture<Void> saveAll(Collection<PlayerDataRecord> records) {
        if (records.isEmpty()) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            String sql = "mysql".equals(dbType) ?
                    "INSERT INTO player_data (uuid,daily_time,total_time,login,last_updated,last_login,claimed_segments,reminded_segments,milestones_claimed) " +
                    "VALUES (?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE daily_time=VALUES(daily_time),total_time=VALUES(total_time)," +
                    "login=VALUES(login),last_updated=VALUES(last_updated),last_login=VALUES(last_login)," +
                    "claimed_segments=VALUES(claimed_segments),reminded_segments=VALUES(reminded_segments),milestones_claimed=VALUES(milestones_claimed)" :
                    "INSERT OR REPLACE INTO player_data (uuid,daily_time,total_time,login,last_updated,last_login,claimed_segments,reminded_segments,milestones_claimed) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)";
            Connection conn = null;
            try {
                conn = getConnection();
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (PlayerDataRecord r : records) {
                        ps.setString(1, r.uuid().toString());
                        ps.setInt(2, r.dailyTime());
                        ps.setInt(3, r.totalTime());
                        ps.setInt(4, r.login());
                        ps.setString(5, r.lastUpdated());
                        ps.setString(6, r.lastLogin());
                        ps.setString(7, nvl(r.claimedSegments()));
                        ps.setString(8, nvl(r.remindedSegments()));
                        ps.setString(9, nvl(r.milestonesClaimed()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                if (conn != null) {
                    try { conn.rollback(); } catch (SQLException ignored) {}
                }
                logger.log(Level.SEVERE, "Batch save failed", e);
                throw new CompletionException(e);
            } finally {
                if (conn != null) {
                    try { conn.close(); } catch (SQLException ignored) {}
                }
            }
        }, executor);
        return track(future);
    }

    public CompletableFuture<PlayerDataRecord> loadPlayerData(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT * FROM player_data WHERE uuid = ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerDataRecord(
                                uuid,
                                rs.getInt("daily_time"), rs.getInt("total_time"), rs.getInt("login"),
                                rs.getString("last_updated"), rs.getString("last_login"),
                                rs.getString("claimed_segments"), rs.getString("reminded_segments"),
                                rs.getString("milestones_claimed")
                        );
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to load data for " + uuid, e);
                throw new CompletionException(e);
            }
            return null;
        }, executor);
    }

    public CompletableFuture<List<PlayerDataRecord>> getTopPlayers(String orderBy, int limit) {
        return track(CompletableFuture.supplyAsync(() -> {
            List<PlayerDataRecord> list = new ArrayList<>();
            String col = "total_time".equals(orderBy) ? "total_time" :
                         "login".equals(orderBy) ? "login" : "daily_time";
            String sql = "SELECT * FROM player_data ORDER BY " + col + " DESC LIMIT ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new PlayerDataRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getInt("daily_time"), rs.getInt("total_time"), rs.getInt("login"),
                                rs.getString("last_updated"), rs.getString("last_login"),
                                rs.getString("claimed_segments"), rs.getString("reminded_segments"),
                                rs.getString("milestones_claimed")
                        ));
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Leaderboard query failed", e);
                throw new CompletionException(e);
            }
            return list;
        }, executor));
    }

    public CompletableFuture<Void> resetDailyClaimFields(String today) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            String sql = "UPDATE player_data SET daily_time = 0, claimed_segments = '', "
                    + "reminded_segments = '', last_updated = ? WHERE last_updated IS NULL OR last_updated <> ?";
            try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, today);
                ps.setString(2, today);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to reset daily claim fields", e);
                throw new CompletionException(e);
            }
        }, executor);
        return track(future);
    }

    public void shutdown() {
        // Wait for pending async database work (max 5 seconds)
        if (!pendingTasks.isEmpty()) {
            try {
                CompletableFuture.allOf(pendingTasks.toArray(new CompletableFuture[0]))
                        .get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.warning("Some pending database tasks did not complete before shutdown");
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (hikari != null) {
            hikari.close();
        }
    }

    private <T> CompletableFuture<T> track(CompletableFuture<T> future) {
        pendingTasks.add(future);
        future.whenComplete((v, ex) -> pendingTasks.remove(future));
        return future;
    }

    private static String nvl(String s) { return s == null ? "" : s; }

    public record PlayerDataRecord(
            UUID uuid, int dailyTime, int totalTime, int login,
            String lastUpdated, String lastLogin,
            String claimedSegments, String remindedSegments,
            String milestonesClaimed
    ) {}
}
