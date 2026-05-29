package me.qscbm.plugins.dailyrewards.managers;

import lombok.Getter;
import me.qscbm.plugins.dailyrewards.managers.DatabaseManager.PlayerDataRecord;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages per-player online time with an in-memory cache.
 * <p>
 * Cache behavior:
 *   - Player data is loaded from DB on first access (async on join) and cached in memory.
 *   - Writes mark the entry dirty; periodic saves flush only dirty entries to DB.
 *   - On quit: saves the entry immediately (write-through) but keeps it cached.
 *   - Offline lookups return cached data — no DB round-trip.
 */
public class PlayerTimeManager {

    private final Logger logger;
    private final DatabaseManager databaseManager;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    @Getter
    private int maxCacheSize = 10000;

    public PlayerTimeManager(Logger logger, DatabaseManager databaseManager) {
        this.logger = logger;
        this.databaseManager = databaseManager;
    }

    public void setMaxCacheSize(int max) {
        this.maxCacheSize = Math.max(100, max);
    }

    // --- Load / Async init ---

    /**
     * Starts async DB load for a newly-joined player.
     * Immediately puts a default entry so synchronous accessors never block.
     * If already cached (re-join), reuses the cache — avoiding a DB query.
     * DB load does NOT overwrite fields that were already modified (dirty=true).
     */
    public void loadPlayerAsync(UUID uuid) {
        String today = LocalDate.now().toString();

        PlayerData existing = cache.get(uuid);
        if (existing != null) {
            existing.lastAccess = System.currentTimeMillis();
            if (!today.equals(existing.lastDate)) {
                existing.dailyTime.set(0);
                existing.claimedSegments.clear();
                existing.remindedSegments.clear();
                existing.lastDate = today;
                existing.markDirty();
            }
            existing.online = true;
            return;
        }

        PlayerData data = new PlayerData(uuid, 0, 0, today);
        data.online = true;
        cache.put(uuid, data);
        evictIfNeeded();

        data.loadFuture = databaseManager.loadPlayerData(uuid).thenAccept(record -> {
            PlayerData cached = cache.get(uuid);
            if (cached == null) return;
            if (record == null) {
                cached.loaded = true;
                return;
            }

            // Only restore from DB if no local modifications happened yet
            if (!cached.dirty) {
                cached.totalTime.set(record.totalTime());
                cached.loginDays.set(record.login());

                if (today.equals(record.lastUpdated())) {
                    cached.dailyTime.set(record.dailyTime());
                    cached.claimedSegments.addAll(parseSegmentList(record.claimedSegments()));
                    cached.remindedSegments.addAll(parseSegmentList(record.remindedSegments()));
                    String raw = record.milestonesClaimed();
                    if (raw != null && !raw.isEmpty()) {
                        for (String part : raw.split(",")) {
                            try { cached.claimedMilestones.add(Integer.parseInt(part.trim())); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
            } else {
                // Local data is newer — keep it but restore accumulated totals
                if (record.totalTime() > cached.totalTime.get()) {
                    cached.totalTime.set(record.totalTime());
                }
                if (record.login() > cached.loginDays.get()) {
                    cached.loginDays.set(record.login());
                }
                if (today.equals(record.lastUpdated())) {
                    cached.dailyTime.updateAndGet(current -> Math.max(current, record.dailyTime()));
                    cached.claimedSegments.addAll(parseSegmentList(record.claimedSegments()));
                    cached.remindedSegments.addAll(parseSegmentList(record.remindedSegments()));
                    String raw = record.milestonesClaimed();
                    if (raw != null && !raw.isEmpty()) {
                        for (String part : raw.split(",")) {
                            try { cached.claimedMilestones.add(Integer.parseInt(part.trim())); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
            }
            cached.loaded = true;
        }).exceptionally(ex -> {
            logger.log(Level.SEVERE, "Async load failed for " + uuid, ex);
            PlayerData cached = cache.get(uuid);
            if (cached != null) cached.loaded = true;
            return null;
        });
    }

    // --- Accessors ---

    public int getTime(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null ? data.dailyTime.get() : 0;
    }

    public int getTotalTime(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null ? data.totalTime.get() : 0;
    }

    public int getLoginDays(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null ? data.loginDays.get() : 0;
    }

    public int getClaimedCount(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null ? data.claimedSegments.size() : 0;
    }

    // --- Mutators (mark dirty) ---

    public void setLoginDays(UUID uuid, int loginDays) {
        PlayerData data = cache.get(uuid);
        if (data != null) { data.loginDays.set(loginDays); data.markDirty(); }
    }

    public void addTime(UUID uuid, int seconds) {
        PlayerData data = cache.get(uuid);
        if (data != null) {
            data.dailyTime.addAndGet(seconds);
            data.totalTime.addAndGet(seconds);
            data.markDirty();
        }
    }

    public void resetToday(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) {
            data.dailyTime.set(0);
            data.lastDate = LocalDate.now().toString();
            data.claimedSegments.clear();
            data.remindedSegments.clear();
            data.markDirty();
        }
    }

    public void resetAllForNewDay(String today) {
        for (PlayerData data : cache.values()) {
            if (!today.equals(data.lastDate) || data.dailyTime.get() != 0
                    || !data.claimedSegments.isEmpty() || !data.remindedSegments.isEmpty()) {
                data.dailyTime.set(0);
                data.lastDate = today;
                data.claimedSegments.clear();
                data.remindedSegments.clear();
                data.markDirty();
            }
        }
    }

    public void setClaimed(UUID uuid, int segmentIndex) {
        PlayerData data = cache.get(uuid);
        if (data != null) { data.claimedSegments.add(segmentIndex); data.markDirty(); }
    }

    public boolean hasClaimed(UUID uuid, int segmentIndex) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null && data.claimedSegments.contains(segmentIndex);
    }

    public void setReminded(UUID uuid, int segmentIndex) {
        PlayerData data = cache.get(uuid);
        if (data != null) { data.remindedSegments.add(segmentIndex); data.markDirty(); }
    }

    public boolean hasReminded(UUID uuid, int segmentIndex) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null && data.remindedSegments.contains(segmentIndex);
    }

    public void setMilestoneClaimed(UUID uuid, int days) {
        PlayerData data = cache.get(uuid);
        if (data != null) { data.claimedMilestones.add(days); data.markDirty(); }
    }

    public boolean hasMilestoneClaimed(UUID uuid, int days) {
        PlayerData data = cache.get(uuid);
        if (data != null) data.lastAccess = System.currentTimeMillis();
        return data != null && data.claimedMilestones.contains(days);
    }

    // --- Online/offline lifecycle ---

    /**
     * Marks a player offline and immediately saves their data (write-through).
     * The cache entry is kept for future lookups.
     */
    public void markOffline(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data != null) {
            data.online = false;
        }
        save(uuid);
    }

    public int getCacheSize() {
        return cache.size();
    }

    public Set<UUID> getTrackedPlayers() {
        return Collections.unmodifiableSet(cache.keySet());
    }

    /**
     * Returns top players sorted by the given field, reading directly from the in-memory cache.
     * No DB query — data is always up-to-date with real-time tracking.
     */
    public List<TopEntry> getTopPlayers(String orderBy, int limit) {
        return cache.entrySet().stream()
                .map(e -> new TopEntry(e.getKey(), e.getValue().dailyTime.get(),
                        e.getValue().totalTime.get(), e.getValue().loginDays.get()))
                .sorted((a, b) -> switch (orderBy) {
                    case "daily_time" -> Integer.compare(b.dailyTime, a.dailyTime);
                    case "login" -> Integer.compare(b.loginDays, a.loginDays);
                    default -> Integer.compare(b.totalTime, a.totalTime);
                })
                .limit(limit)
                .toList();
    }

    public record TopEntry(UUID uuid, int dailyTime, int totalTime, int loginDays) {}

    // --- Persistence ---

    /**
     * Saves a single player immediately — clears dirty only after DB write succeeds.
     */
    public CompletableFuture<Void> save(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return CompletableFuture.completedFuture(null);
        return data.loadFuture.thenCompose(v -> saveLoaded(uuid, data));
    }

    private CompletableFuture<Void> saveLoaded(UUID uuid, PlayerData data) {
        CompletableFuture<Void> existing = data.saveInProgress;
        if (existing != null && !existing.isDone()) {
            return existing;
        }

        long savedVersion = data.version.get();
        PlayerDataRecord record = data.toRecord(uuid);
        CompletableFuture<Void> future = databaseManager.savePlayerData(record).thenRun(() -> data.clearDirtyIfVersion(savedVersion)).whenComplete((v, ex) -> {
            if (ex != null) {
                logger.log(Level.SEVERE, "Save failed for " + uuid, ex);
            }
            data.saveInProgress = null;
        });
        data.saveInProgress = future;
        return future;
    }

    /**
     * Saves only dirty cache entries in a single batch.
     * Clears dirty flag only after the DB write succeeds.
     */
    public CompletableFuture<Void> saveDirty() {
        List<PlayerDataRecord> dirty = new ArrayList<>();
        List<SavedEntry> sources = new ArrayList<>();
        for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
            PlayerData data = entry.getValue();
            if (data.dirty && data.loaded) {
                long savedVersion = data.version.get();
                sources.add(new SavedEntry(data, savedVersion));
                dirty.add(data.toRecord(entry.getKey()));
            }
        }
        if (dirty.isEmpty()) return CompletableFuture.completedFuture(null);
        return databaseManager.saveAll(dirty).thenRun(() -> {
            for (SavedEntry entry : sources) {
                entry.data().clearDirtyIfVersion(entry.version());
            }
        });
    }

    /**
     * Saves all cached entries. Used on shutdown.
     */
    public CompletableFuture<Void> saveAll() {
        List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (PlayerData data : cache.values()) {
            loads.add(data.loadFuture);
        }
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).thenCompose(v -> {
            List<PlayerDataRecord> all = new ArrayList<>();
            List<SavedEntry> sources = new ArrayList<>();
            for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
                PlayerData data = entry.getValue();
                sources.add(new SavedEntry(data, data.version.get()));
                all.add(data.toRecord(entry.getKey()));
            }
            return databaseManager.saveAll(all).thenRun(() -> {
                for (SavedEntry entry : sources) {
                    entry.data().clearDirtyIfVersion(entry.version());
                }
            });
        });
    }

    // --- Eviction ---

    private void evictIfNeeded() {
        if (cache.size() <= maxCacheSize) return;

        List<Map.Entry<UUID, PlayerData>> sorted = new ArrayList<>(cache.entrySet());
        sorted.sort(Comparator.comparingLong(e -> e.getValue().lastAccess));

        int need = cache.size() - maxCacheSize;
        int removed = 0;
        for (Map.Entry<UUID, PlayerData> entry : sorted) {
            if (removed >= need) break;
            UUID uuid = entry.getKey();
            PlayerData data = entry.getValue();
            if (data.online) continue;
            if (data.dirty) {
                if (data.evictionSaveInProgress.compareAndSet(false, true)) {
                    save(uuid).whenComplete((v, ex) -> {
                        data.evictionSaveInProgress.set(false);
                        if (ex == null && !data.online && !data.dirty) {
                            cache.remove(uuid, data);
                        }
                    });
                }
                removed++;
                continue;
            }
            if (cache.remove(uuid, data)) {
                removed++;
            }
        }
    }

    // --- Helpers ---

    private static List<Integer> parseSegmentList(String raw) {
        List<Integer> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            try { list.add(Integer.parseInt(t)); }
            catch (NumberFormatException ignored) {}
        }
        return list;
    }

    // --- Data class ---

    private static class PlayerData {
        final UUID uuid;
        final AtomicInteger dailyTime;
        final AtomicInteger totalTime;
        final AtomicInteger loginDays;
        volatile boolean dirty;
        volatile boolean online;
        volatile boolean loaded;
        volatile CompletableFuture<Void> loadFuture = CompletableFuture.completedFuture(null);
        volatile CompletableFuture<Void> saveInProgress;
        volatile long lastAccess = System.currentTimeMillis();
        volatile String lastDate;
        final Set<Integer> claimedSegments = ConcurrentHashMap.newKeySet();
        final Set<Integer> remindedSegments = ConcurrentHashMap.newKeySet();
        final Set<Integer> claimedMilestones = ConcurrentHashMap.newKeySet();
        final AtomicLong version = new AtomicLong();
        final AtomicBoolean evictionSaveInProgress = new AtomicBoolean();

        PlayerData(UUID uuid, int dailyTime, int totalTime, String lastDate) {
            this.uuid = uuid;
            this.dailyTime = new AtomicInteger(dailyTime);
            this.totalTime = new AtomicInteger(totalTime);
            this.loginDays = new AtomicInteger(0);
            this.lastDate = lastDate;
        }

        void markDirty() {
            dirty = true;
            version.incrementAndGet();
        }

        void clearDirtyIfVersion(long savedVersion) {
            if (version.get() == savedVersion) {
                dirty = false;
            }
        }

        PlayerDataRecord toRecord(UUID uuid) {
            lastAccess = System.currentTimeMillis();
            return new PlayerDataRecord(
                    uuid, dailyTime.get(), totalTime.get(), loginDays.get(),
                    lastDate, lastDate,
                    sortedJoin(claimedSegments), sortedJoin(remindedSegments),
                    sortedJoin(claimedMilestones)
            );
        }

        private static String sortedJoin(Set<Integer> set) {
            if (set.isEmpty()) return "";
            List<Integer> sorted = new ArrayList<>(set);
            Collections.sort(sorted);
            StringBuilder sb = new StringBuilder();
            for (int v : sorted) {
                if (!sb.isEmpty()) sb.append(',');
                sb.append(v);
            }
            return sb.toString();
        }
    }

    private record SavedEntry(PlayerData data, long version) {}
}
