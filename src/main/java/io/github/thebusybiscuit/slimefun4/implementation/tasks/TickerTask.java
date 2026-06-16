package io.github.thebusybiscuit.slimefun4.implementation.tasks;

import com.xzavier0722.mc.plugin.slimefun4.storage.controller.ASlimefunDataContainer;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunBlockData;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.SlimefunUniversalData;
import com.xzavier0722.mc.plugin.slimefun4.storage.controller.attributes.UniversalBlock;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.bakedlibs.dough.blocks.BlockPosition;
import io.github.bakedlibs.dough.blocks.ChunkPosition;
import io.github.thebusybiscuit.slimefun4.api.ErrorReport;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.ticker.TickLocation;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import lombok.Setter;
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker;
import org.apache.commons.lang.Validate;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.scheduler.BukkitScheduler;

/**
 * The {@link TickerTask} is responsible for ticking every {@link BlockTicker},
 * synchronous or not.
 *
 * @author TheBusyBiscuit
 *
 * @see BlockTicker
 *
 */
public class TickerTask implements Runnable {

    /**
     * This Map holds all currently actively ticking locations.
     * The value of this map (Set entries) MUST be thread-safe and mutable.
     */
    private final Map<ChunkPosition, Set<TickLocation>> tickingLocations = new ConcurrentHashMap<>();

    /**
     * The same chunks as {@link #tickingLocations}, spread over the configured ticker delay.
     * Every bucket is processed in a different server tick to avoid concentrated region scheduler load.
     */
    private volatile java.util.List<java.util.Set<ChunkPosition>> tickingBuckets = java.util.List.of();

    private final Map<ChunkPosition, Integer> chunkBuckets = new ConcurrentHashMap<>();
    private final Map<ChunkPosition, Long> locationCosts = new ConcurrentHashMap<>();
    private final java.util.Set<BlockTicker> cycleTickers = ConcurrentHashMap.newKeySet();
    private final java.util.Set<ChunkPosition> pendingChunkSet = new java.util.HashSet<>();
    private final Map<ChunkPosition, java.util.ArrayDeque<TickLocation>> pendingLocations = new ConcurrentHashMap<>();
    private final java.util.Set<ChunkPosition> runningChunks = ConcurrentHashMap.newKeySet();

    /**
     * This Map tracks how many bugs have occurred in a given Location .
     * If too many bugs happen, we delete that Location.
     */
    private final Map<BlockPosition, Integer> bugs = new ConcurrentHashMap<>();

    private int tickRate = 1;
    private int currentBucket = 0;
    private java.util.ArrayDeque<ChunkPosition> pendingChunks = new java.util.ArrayDeque<>();
    private boolean halted = false;
    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();

    @Setter
    private volatile boolean paused = false;

    /**
     * This method starts the {@link TickerTask} on an asynchronous schedule.
     *
     * @param plugin
     *            The instance of our {@link Slimefun}
     */
    public void start(@Nonnull Slimefun plugin) {
        this.tickRate = Math.max(1, Slimefun.getCfg().getInt("URID.custom-ticker-delay"));
        initializeBuckets();

        com.molean.folia.adapter.Folia.getScheduler().runTaskTimerAsynchronously(plugin, this, 100L, 1L);
    }

    /**
     * This method resets this {@link TickerTask} to run again.
     */
    private void reset() {
        running.set(false);
    }

    @Override
    public void run() {
        if (paused) {
            return;
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        try {
            Slimefun.getProfiler().start();

            // Run our ticker code
            if (!halted) {
                runBucket();
            }

            Slimefun.getProfiler().stop();
        } catch (Exception | LinkageError x) {
            Slimefun.logger()
                    .log(
                            Level.SEVERE,
                            x,
                            () -> "An Exception was caught while ticking the Block Tickers Task for Slimefun v"
                                    + Slimefun.getVersion());
        } finally {
            reset();
        }
    }

    private void runBucket() {
        java.util.ArrayDeque<ChunkPosition> work = pollBucketWork();
        java.util.Map<ChunkPosition, java.util.ArrayDeque<TickLocation>> remainingLocations =
                new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> tickCosts = new java.util.HashMap<>();
        java.util.Set<String> blockedWorlds = new java.util.HashSet<>();

        while (!work.isEmpty()) {
            ChunkPosition chunk = work.removeFirst();
            Set<TickLocation> currentLocations = tickingLocations.get(chunk);

            if (currentLocations == null || currentLocations.isEmpty()) {
                removeChunk(chunk);
                continue;
            }

            if (!chunk.isLoaded()) {
                continue;
            }

            java.util.ArrayDeque<TickLocation> locations = pollTickLocations(chunk, currentLocations);
            if (locations.isEmpty()) {
                continue;
            }

            org.bukkit.World world = chunk.getWorld();
            String worldKey = getWorldKey(world);
            long budget = getTickerBudgetNanos(world);

            if (blockedWorlds.contains(worldKey) || !runningChunks.add(chunk)) {
                deferChunk(chunk, locations, remainingLocations);
                continue;
            }

            java.util.ArrayDeque<TickLocation> delayedLocations = new java.util.ArrayDeque<>();
            java.util.List<TickAction> actions = new java.util.ArrayList<>();
            boolean scheduled = false;

            try {
                while (!locations.isEmpty()) {
                    TickLocation tickLocation = locations.removeFirst();
                    if (!isTickLocationStillEnabled(chunk, tickLocation)) {
                        continue;
                    }

                    long estimatedCost = getEstimatedLocationCost(chunk);
                    if (!tryReserveWorldCost(tickCosts, worldKey, estimatedCost, budget)) {
                        blockedWorlds.add(worldKey);
                        delayedLocations.add(tickLocation);
                        delayedLocations.addAll(locations);
                        break;
                    }

                    TickAction action = createTickAction(tickLocation);
                    if (action == null) {
                        releaseWorldCost(tickCosts, worldKey, estimatedCost);
                    } else {
                        actions.add(action);
                    }
                }

                if (!actions.isEmpty()) {
                    try {
                        dispatchChunk(chunk, actions);
                        scheduled = true;
                    } catch (Exception | LinkageError x) {
                        for (TickAction action : actions) {
                            delayedLocations.add(action.tickLocation);
                        }

                        throw x;
                    }
                }
            } catch (Exception | LinkageError x) {
                delayedLocations.addAll(locations);
                Slimefun.logger()
                        .log(Level.SEVERE, x, () -> "An Exception has occurred while trying to resolve Chunk: " + chunk);
            } finally {
                if (!scheduled) {
                    runningChunks.remove(chunk);
                }
            }

            if (!delayedLocations.isEmpty()) {
                deferChunk(chunk, delayedLocations, remainingLocations);
            }
        }

        synchronized (tickingLocations) {
            pendingChunks = new java.util.ArrayDeque<>();
            pendingChunkSet.clear();

            for (Map.Entry<ChunkPosition, java.util.ArrayDeque<TickLocation>> entry : remainingLocations.entrySet()) {
                ChunkPosition chunk = entry.getKey();
                java.util.ArrayDeque<TickLocation> locations = entry.getValue();
                if (locations != null && !locations.isEmpty() && tickingLocations.containsKey(chunk)) {
                    pendingChunks.add(chunk);
                    pendingChunkSet.add(chunk);
                    pendingLocations.put(chunk, locations);
                }
            }

            finishBucket();
        }
    }

    private java.util.ArrayDeque<ChunkPosition> pollBucketWork() {
        synchronized (tickingLocations) {
            for (ChunkPosition chunk : sortedChunks(tickingBuckets.get(currentBucket))) {
                if (pendingChunkSet.add(chunk)) {
                    pendingChunks.add(chunk);
                }
            }

            java.util.ArrayDeque<ChunkPosition> work = pendingChunks;
            pendingChunks = new java.util.ArrayDeque<>();
            pendingChunkSet.clear();
            return work;
        }
    }

    private java.util.ArrayDeque<ChunkPosition> sortedChunks(Set<ChunkPosition> chunks) {
        java.util.List<ChunkPosition> sorted = new java.util.ArrayList<>(chunks);
        sorted.sort((a, b) -> {
            int world = a.getWorld().getName().compareTo(b.getWorld().getName());
            if (world != 0) {
                return world;
            }

            int x = Integer.compare(a.getX(), b.getX());
            return x != 0 ? x : Integer.compare(a.getZ(), b.getZ());
        });
        return new java.util.ArrayDeque<>(sorted);
    }

    private java.util.ArrayDeque<TickLocation> pollTickLocations(
            ChunkPosition chunk, Set<TickLocation> currentLocations) {
        synchronized (tickingLocations) {
            java.util.ArrayDeque<TickLocation> pending = pendingLocations.remove(chunk);
            if (pending != null) {
                return pending;
            }
        }

        return sortedLocations(currentLocations);
    }

    private java.util.ArrayDeque<TickLocation> sortedLocations(Set<TickLocation> locations) {
        java.util.List<TickLocation> sorted = new java.util.ArrayList<>(locations);
        sorted.sort((a, b) -> {
            BlockPosition aPosition = a.getPosition();
            BlockPosition bPosition = b.getPosition();
            int x = Integer.compare(aPosition.getX(), bPosition.getX());
            if (x != 0) {
                return x;
            }

            int y = Integer.compare(aPosition.getY(), bPosition.getY());
            if (y != 0) {
                return y;
            }

            int z = Integer.compare(aPosition.getZ(), bPosition.getZ());
            if (z != 0) {
                return z;
            }

            String aUuid = a.getUuid() == null ? "" : a.getUuid().toString();
            String bUuid = b.getUuid() == null ? "" : b.getUuid().toString();
            return aUuid.compareTo(bUuid);
        });
        return new java.util.ArrayDeque<>(sorted);
    }

    private boolean isTickLocationStillEnabled(ChunkPosition chunk, TickLocation tickLocation) {
        Set<TickLocation> locations = tickingLocations.get(chunk);
        return locations != null && locations.contains(tickLocation);
    }

    private void deferChunk(
            ChunkPosition chunk,
            java.util.ArrayDeque<TickLocation> locations,
            java.util.Map<ChunkPosition, java.util.ArrayDeque<TickLocation>> remainingLocations) {
        remainingLocations.put(chunk, locations);
    }

    private void finishBucket() {
        synchronized (tickingLocations) {
            currentBucket = (currentBucket + 1) % tickRate;

            if (currentBucket == 0) {
                finishTickerCycle();
            }
        }
    }

    private void finishTickerCycle() {
        for (BlockTicker ticker : cycleTickers) {
            ticker.startNewTick();
        }

        cycleTickers.clear();
    }

    @ParametersAreNonnullByDefault
    private void dispatchChunk(ChunkPosition chunk, java.util.List<TickAction> actions) {
        Location schedulerLocation = actions.get(0).location;

        com.molean.folia.adapter.Folia.runSync(
                () -> {
                    long start = System.nanoTime();
                    try {
                        if (chunk.isLoaded()) {
                            for (TickAction action : actions) {
                                action.tick();
                            }
                        }
                    } finally {
                        long elapsed = System.nanoTime() - start;
                        recordChunkCost(chunk, elapsed, actions.size());
                        runningChunks.remove(chunk);
                    }
                },
                schedulerLocation);
    }

    @Nullable private TickAction createTickAction(@Nonnull TickLocation tickLocation) {
        return tickLocation.isUniversal()
                ? createUniversalTickAction(tickLocation, tickLocation.getUuid(), tickLocation.getLocation())
                : createTickAction(tickLocation, tickLocation.getLocation());
    }

    @Nullable private TickAction createTickAction(@Nonnull TickLocation tickLocation, @Nonnull Location l) {
        var blockData = StorageCacheUtils.getBlock(l);
        if (blockData == null || !blockData.isDataLoaded() || blockData.isPendingRemove()) {
            return null;
        }

        SlimefunItem item = SlimefunItem.getById(blockData.getSfId());
        if (item == null || item.getBlockTicker() == null || item.isDisabledIn(l.getWorld())) {
            return null;
        }

        try {
            return createTickAction(tickLocation, l, item, blockData);
        } catch (Exception x) {
            reportErrors(l, item, x);
            return null;
        }
    }

    @Nullable private TickAction createUniversalTickAction(
            TickLocation tickLocation, UUID uuid, @Nonnull Location l) {
        var data = StorageCacheUtils.getUniversalBlock(uuid);
        if (data == null || !data.isDataLoaded()) {
            return null;
        }

        SlimefunItem item = SlimefunItem.getById(data.getSfId());
        if (item == null || item.getBlockTicker() == null || item.isDisabledIn(l.getWorld())) {
            return null;
        }

        try {
            return createTickAction(tickLocation, l, item, data);
        } catch (Exception x) {
            reportErrors(l, item, x);
            return null;
        }
    }

    @ParametersAreNonnullByDefault
    private TickAction createTickAction(
            TickLocation tickLocation, Location l, SlimefunItem item, ASlimefunDataContainer data) {
        BlockTicker ticker = item.getBlockTicker();

        if (ticker.isSynchronized()) {
            Slimefun.getProfiler().scheduleEntries(1);
            ticker.update();
            cycleTickers.add(ticker);
            return new TickAction(tickLocation, l, item, data, 0L, true, true);
        } else {
            long timestamp = Slimefun.getProfiler().newEntry();
            ticker.update();
            cycleTickers.add(ticker);
            return new TickAction(tickLocation, l, item, data, timestamp, false, false);
        }
    }

    /**
     * Returns the per-world Slimefun ticker budget in milliseconds.
     * Override or replace this method to bind the budget to an external policy.
     */
    protected double getTickerBudget(org.bukkit.World world) {
        return 25.0D;
    }

    private long getTickerBudgetNanos(org.bukkit.World world) {
        double budget;
        try {
            budget = getTickerBudget(world);
        } catch (Exception | LinkageError x) {
            Slimefun.logger().log(Level.WARNING, x, () -> "Failed to resolve Slimefun ticker budget for world: " + world.getName());
            budget = 25.0D;
        }

        if (!Double.isFinite(budget) || budget <= 0.0D) {
            budget = 25.0D;
        }

        return Math.max(1L, (long) (budget * 1_000_000.0D));
    }

    private String getWorldKey(org.bukkit.World world) {
        return world.getUID().toString();
    }

    private long getEstimatedLocationCost(ChunkPosition chunk) {
        return locationCosts.getOrDefault(chunk, 50_000L);
    }

    private boolean tryReserveWorldCost(java.util.Map<String, Long> tickCosts, String worldKey, long cost, long budget) {
        long current = tickCosts.getOrDefault(worldKey, 0L);
        if (current > 0L && current + cost > budget) {
            return false;
        }

        tickCosts.put(worldKey, current + cost);
        return true;
    }

    private void releaseWorldCost(java.util.Map<String, Long> tickCosts, String worldKey, long cost) {
        tickCosts.computeIfPresent(worldKey, (key, current) -> Math.max(0L, current - cost));
    }

    private void recordChunkCost(ChunkPosition chunk, long elapsed, int locations) {
        long elapsedPerLocation = elapsed / Math.max(1, locations);
        locationCosts.merge(chunk, Math.max(1L, elapsedPerLocation), (previous, current) -> (previous * 3L + current) / 4L);
    }

    private void initializeBuckets() {
        java.util.List<java.util.Set<ChunkPosition>> buckets = new java.util.ArrayList<>(tickRate);
        for (int i = 0; i < tickRate; i++) {
            buckets.add(ConcurrentHashMap.newKeySet());
        }

        synchronized (tickingLocations) {
            tickingBuckets = buckets;
            chunkBuckets.clear();
            currentBucket = 0;
            pendingChunks = new java.util.ArrayDeque<>();
            pendingChunkSet.clear();
            pendingLocations.clear();

            for (ChunkPosition chunk : tickingLocations.keySet()) {
                addChunkToBucket(chunk);
            }
        }
    }

    private void initializeBucketsIfNeeded() {
        if (tickingBuckets.isEmpty()) {
            initializeBuckets();
        }
    }

    private int getLightestBucket() {
        java.util.List<java.util.Set<ChunkPosition>> buckets = tickingBuckets;
        int bucket = 0;
        int size = Integer.MAX_VALUE;

        for (int i = 0; i < buckets.size(); i++) {
            int current = 0;
            for (ChunkPosition chunk : buckets.get(i)) {
                Set<TickLocation> locations = tickingLocations.get(chunk);
                current += locations == null ? 0 : locations.size();
            }

            if (current < size) {
                bucket = i;
                size = current;
            }
        }

        return bucket;
    }

    private void addChunkToBucket(ChunkPosition chunk) {
        if (chunkBuckets.containsKey(chunk)) {
            return;
        }

        int bucket = getLightestBucket();
        tickingBuckets.get(bucket).add(chunk);
        chunkBuckets.put(chunk, bucket);
    }

    private void removeChunk(ChunkPosition chunk) {
        synchronized (tickingLocations) {
            Integer bucket = chunkBuckets.remove(chunk);
            if (bucket != null && bucket >= 0 && bucket < tickingBuckets.size()) {
                tickingBuckets.get(bucket).remove(chunk);
            }

            tickingLocations.remove(chunk);
            locationCosts.remove(chunk);
            pendingLocations.remove(chunk);

            if (pendingChunkSet.remove(chunk)) {
                pendingChunks.remove(chunk);
            }
        }
    }

    private final class TickAction {

        private final TickLocation tickLocation;
        private final Location location;
        private final SlimefunItem item;
        private final ASlimefunDataContainer data;
        private final long timestamp;
        private final boolean synchronizedTicker;
        private final boolean skipPendingRemove;

        private TickAction(
                TickLocation tickLocation,
                Location location,
                SlimefunItem item,
                ASlimefunDataContainer data,
                long timestamp,
                boolean synchronizedTicker,
                boolean skipPendingRemove) {
            this.tickLocation = tickLocation;
            this.location = location;
            this.item = item;
            this.data = data;
            this.timestamp = timestamp;
            this.synchronizedTicker = synchronizedTicker;
            this.skipPendingRemove = skipPendingRemove;
        }

        private void tick() {
            if (skipPendingRemove && data.isPendingRemove()) {
                return;
            }

            if (synchronizedTicker) {
                tickBlock(location, item, data, System.nanoTime());
            } else {
                tickBlock(location, item, data, timestamp);
            }
        }
    }

    @ParametersAreNonnullByDefault
    private void tickBlock(Location l, SlimefunItem item, ASlimefunDataContainer data, long timestamp) {
        try {
            if (item.getBlockTicker().isUniversal()) {
                if (data instanceof SlimefunUniversalData universalData) {
                    item.getBlockTicker().tick(l.getBlock(), item, universalData);
                } else {
                    throw new IllegalStateException("BlockTicker is universal but item is non-universal!");
                }
            } else {
                if (data instanceof SlimefunBlockData blockData) {
                    item.getBlockTicker().tick(l.getBlock(), item, blockData);
                } else {
                    throw new IllegalStateException("BlockTicker is non-universal but item is universal!");
                }
            }
        } catch (Exception | LinkageError x) {
            reportErrors(l, item, x);
        } finally {
            Slimefun.getProfiler().closeEntry(l, item, timestamp);
        }
    }

    @ParametersAreNonnullByDefault
    private void reportErrors(Location l, SlimefunItem item, Throwable x) {
        BlockPosition position = new BlockPosition(l);
        int errors = bugs.getOrDefault(position, 0) + 1;

        if (errors == 1) {
            // Generate a new Error-Report
            new ErrorReport<>(x, l, item);
            bugs.put(position, errors);
        } else if (errors == 4) {
            Slimefun.logger().log(Level.SEVERE, "X: {0} Y: {1} Z: {2} ({3})", new Object[] {
                l.getBlockX(), l.getBlockY(), l.getBlockZ(), item.getId()
            });
            Slimefun.logger().log(Level.SEVERE, "在过去的 4 个 Tick 中发生多次错误，该方块对应的机器已被停用。");
            Slimefun.logger().log(Level.SEVERE, "请在 /plugins/Slimefun/error-reports/ 文件夹中查看错误详情。");
            Slimefun.logger().log(Level.SEVERE, "如果你要向他人求助，请向他人发送上述错误报告文件,而不是发送这个窗口的截图");
            Slimefun.logger().log(Level.SEVERE, " ");
            bugs.remove(position);

            disableTicker(l);
        } else {
            bugs.put(position, errors);
        }
    }

    public boolean isHalted() {
        return halted;
    }

    public void halt() {
        halted = true;
    }

    /**
     * This returns the delay between ticks
     *
     * @return The tick delay
     */
    public int getTickRate() {
        return tickRate;
    }

    /**
     * BINARY COMPATIBILITY
     *
     * Use #getTickLocations instead
     *
     * @return A {@link Map} representation of all ticking {@link Location Locations}
     */
    @Nonnull
    public Map<ChunkPosition, Set<Location>> getLocations() {
        return tickingLocations.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(TickLocation::getLocation)
                                .collect(Collectors.toUnmodifiableSet())));
    }

    /**
     * This method returns a <strong>read-only</strong> {@link Map}
     * representation of every {@link ChunkPosition} and its corresponding
     * {@link Set} of ticking {@link Location Locations}.
     *
     * This does include any {@link Location} from an unloaded {@link Chunk} too!
     *
     * @return A {@link Map} representation of all ticking {@link TickLocation Locations}
     */
    @Nonnull
    public Map<ChunkPosition, Set<TickLocation>> getTickLocations() {
        return Collections.unmodifiableMap(tickingLocations);
    }

    /**
     * This method returns a <strong>read-only</strong> {@link Set}
     * of all ticking {@link Location Locations} in a given {@link Chunk}.
     * The {@link Chunk} does not have to be loaded.
     * If no {@link Location} is present, the returned {@link Set} will be empty.
     *
     * @param chunk
     *            The {@link Chunk}
     *
     * @return A {@link Set} of all ticking {@link Location Locations}
     */
    @Nonnull
    public Set<Location> getLocations(@Nonnull Chunk chunk) {
        Validate.notNull(chunk, "The Chunk cannot be null!");

        Set<TickLocation> locations = tickingLocations.getOrDefault(new ChunkPosition(chunk), Collections.emptySet());
        return locations.stream().map(TickLocation::getLocation).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * 返回一个给定区块下的 <strong>只读</strong> 的 {@link Map}
     * 代表每个 {@link ChunkPosition} 中有 {@link UniversalBlock} 属性的物品
     * Tick 的 {@link Location 位置}集合.
     *
     * 其中包含的 {@link Location} 可以是已加载或卸载的 {@link Chunk}
     *
     * @param chunk
     *            {@link Chunk}
     *
     * @return 包含所有机器 Tick {@link TickLocation 位置}的只读 {@link Map}
     */
    @Nonnull
    public Set<TickLocation> getTickLocations(@Nonnull Chunk chunk) {
        Validate.notNull(chunk, "The Chunk cannot be null!");

        return tickingLocations.getOrDefault(new ChunkPosition(chunk), Collections.emptySet());
    }

    /**
     * This enables the ticker at the given {@link Location} and adds it to our "queue".
     *
     * @param l
     *            The {@link Location} to activate
     */
    public void enableTicker(@Nonnull Location l) {
        enableTicker(l, null);
    }

    public void enableTicker(@Nonnull Location l, @Nullable UUID uuid) {
        Validate.notNull(l, "Location cannot be null!");

        synchronized (tickingLocations) {
            initializeBucketsIfNeeded();

            ChunkPosition chunk = new ChunkPosition(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
            final var tickPosition = uuid == null
                    ? new TickLocation(new BlockPosition(l))
                    : new TickLocation(new BlockPosition(l), uuid);

            /*
              Note that all the values in #tickingLocations must be thread-safe.
              Thus, the choice is between the CHM KeySet or a synchronized set.
              The CHM KeySet was chosen since it at least permits multiple concurrent
              reads without blocking.
            */
            Set<TickLocation> newValue = ConcurrentHashMap.newKeySet();
            Set<TickLocation> oldValue = tickingLocations.putIfAbsent(chunk, newValue);

            /**
             * This is faster than doing computeIfAbsent(...)
             * on a ConcurrentHashMap because it won't block the Thread for too long
             */
            if (oldValue != null) {
                oldValue.add(tickPosition);
            } else {
                newValue.add(tickPosition);
            }

            addChunkToBucket(chunk);
        }
    }

    /**
     * This method disables the ticker at the given {@link Location} and removes it from our internal
     * "queue".
     *
     * @param l
     *            The {@link Location} to remove
     */
    public void disableTicker(@Nonnull Location l) {
        Validate.notNull(l, "Location cannot be null!");

        synchronized (tickingLocations) {
            ChunkPosition chunk = new ChunkPosition(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4);
            Set<TickLocation> locations = tickingLocations.get(chunk);

            if (locations != null) {
                locations.removeIf(tk -> l.equals(tk.getLocation()));

                if (locations.isEmpty()) {
                    removeChunk(chunk);
                }
            }
        }
    }

    /**
     * This method disables the ticker at the given {@link UUID} and removes it from our internal
     * "queue".
     *
     * DO NOT USE THIS until you cannot disable by location,
     * or enjoy extremely slow.
     *
     * @param uuid
     *            The {@link UUID} to remove
     */
    public void disableTicker(@Nonnull UUID uuid) {
        Validate.notNull(uuid, "Universal Data ID cannot be null!");

        synchronized (tickingLocations) {
            Set<Map.Entry<ChunkPosition, Set<TickLocation>>> entries = new HashSet<>(tickingLocations.entrySet());

            for (Map.Entry<ChunkPosition, Set<TickLocation>> entry : entries) {
                Set<TickLocation> locations = entry.getValue();
                locations.removeIf(tk -> uuid.equals(tk.getUuid()));

                if (locations.isEmpty()) {
                    removeChunk(entry.getKey());
                }
            }
        }
    }
}
