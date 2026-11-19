package dev.sdeyy.nmines.engine;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.util.Language;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** World access and queue mutations are confined to the server thread. */
public final class RegenerationEngine {
    private final JavaPlugin plugin;
    private final Deque<Job> queue = new ArrayDeque<>();
    private final Map<String, Job> jobs = new HashMap<>();
    private final Map<String, Progress> progress = new HashMap<>();
    private final Random random = new Random();
    private final int blocksPerTick, chunksPerTick;
    private final long nanosPerTick;
    private BukkitTask task;
    private boolean paused;

    public RegenerationEngine(JavaPlugin plugin) {
        this.plugin = plugin;
        blocksPerTick = Math.max(1, Math.min(100000, plugin.getConfig().getInt("engine.blocks-per-tick", 3000)));
        double ms = plugin.getConfig().getDouble("engine.milliseconds-per-tick", 3);
        if (!Double.isFinite(ms) || ms <= 0 || ms > 40) throw new IllegalArgumentException(Language.text("engine.time-budget-must-be-greater-than-0-and-at-most-40-ms"));
        nanosPerTick = (long) (ms * 1000000);
        chunksPerTick = Math.max(1, Math.min(16, plugin.getConfig().getInt("engine.chunk-loads-per-tick", 1)));
    }
    public void start() { task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }
    public void stop() { if (task != null) task.cancel(); queue.clear(); jobs.clear(); progress.clear(); }
    public boolean busy(String mine) { return progress.containsKey(mine); }
    public boolean busy() { return !jobs.isEmpty(); }
    public boolean queued(String key) { return jobs.containsKey(key); }
    public boolean regenerating(String key) { Job job = jobs.get(key); return job != null && job.reset; }
    public int size() { return jobs.size(); }
    public boolean paused() { return paused; }
    public int cancel(String mine) {
        List<Job> cancelled = new ArrayList<>();
        int size = queue.size();
        for (int i = 0; i < size; i++) {
            Job job = queue.removeFirst();
            if (mine == null || job.mine.equals(mine)) { untrack(job); job.state.ready = false; cancelled.add(job); }
            else queue.addLast(job);
        }
        for (Job job : cancelled) complete(job, false, Language.text("engine.job-cancelled"));
        return cancelled.size();
    }
    public void pause(boolean paused) { this.paused = paused; }
    public double progress(String mine) {
        Progress p = progress.get(mine);
        return p == null || p.total == 0 ? 100 : 100.0 * p.processed / p.total;
    }
    public void enqueue(String mine, Mine.Region region, Mine.Composition composition, RegionState state, boolean reset, BiConsumer<Boolean, String> done) {
        String key = mine + ":" + region.id;
        if (jobs.containsKey(key)) throw new IllegalArgumentException(Language.text("engine.region-is-already-queued"));
        Job job = new Job(key, mine, region, composition, state, reset, done);
        Progress p = progress.computeIfAbsent(mine, k -> new Progress()); p.total += region.bounds.volume(); p.jobs++;
        jobs.put(key, job); queue.addLast(job); state.ready = false; state.revision++;
    }
    private void tick() {
        if (paused) return;
        WorkBudget budget = new WorkBudget(blocksPerTick, nanosPerTick, System::nanoTime);
        int loads = 0, stalled = 0;
        int slice = Math.max(1, Math.min(64, blocksPerTick / Math.max(1, queue.size()))), used = 0;
        while (!queue.isEmpty() && budget.take()) {
            Job job = queue.peekFirst();
            World world = Bukkit.getWorld(job.region.bounds.world);
            if (world == null) {
                queue.removeFirst(); queue.addLast(job);
                if (++stalled >= queue.size()) break;
                continue;
            }
            Bounds b = job.region.bounds;
            int x = job.x, y = job.y, z = job.z;
            try {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    if (loads >= chunksPerTick) {
                        queue.removeFirst(); queue.addLast(job); used = 0;
                        if (++stalled >= queue.size()) break;
                        continue;
                    }
                    loads++;
                    if (!world.loadChunk(x >> 4, z >> 4, true)) { finish(job, false, Language.text("engine.could-not-load-a-chunk")); continue; }
                }
                stalled = 0;
                int index = b.index(x, y, z);
                if (job.mask == null || job.mask.get(index)) {
                    Block block = world.getBlockAt(x, y, z);
                    if (job.reset) {
                        BlockData data = job.data(index, random);
                        String type = block.getType().name();
                        if (type.contains("CHEST") || type.equals("BARREL") || type.contains("SHULKER_BOX") || type.contains("FURNACE") || type.equals("HOPPER") || type.equals("DISPENSER") || type.equals("DROPPER")
                                || type.equals("SMOKER") || type.equals("BREWING_STAND") || type.equals("LECTERN") || type.equals("DECORATED_POT") || type.equals("CRAFTER")) {
                            if (block.getState() instanceof InventoryHolder) ((InventoryHolder) block.getState()).getInventory().clear();
                        }
                        block.setBlockData(data, false);
                    }
                    boolean solid = !block.getType().isAir();
                    job.observed.set(index, solid);
                    job.capacity++;
                }
                job.advance();
                progress.get(job.mine).processed++;
            } catch (RuntimeException e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, Language.text("engine.error-processing-format", job.key), e);
                finish(job, false, e.getMessage());
                used = 0; continue;
            }
            if (job.cursor >= b.volume()) { finish(job, true, ""); used = 0; }
            else if (++used >= slice) { queue.removeFirst(); queue.addLast(job); used = 0; }
        }
    }
    private void finish(Job job, boolean success, String error) {
        remove(job);
        if (success) {
            job.state.replace(job.observed);
            // Intentional air in a composition is not a block that players mined.
            if (job.reset) job.state.capacity = job.observed.cardinality();
            else if (job.state.capacity <= 0) job.state.capacity = job.capacity;
            job.state.ready = true;
            if (job.reset) job.state.composition = job.composition.id;
        }
        complete(job, success, error);
    }
    private void remove(Job job) {
        queue.remove(job); untrack(job);
    }
    private void untrack(Job job) {
        jobs.remove(job.key);
        Progress p = progress.get(job.mine);
        if (p != null && --p.jobs == 0) progress.remove(job.mine);
    }
    private static final class Progress {
        long processed, total;
        int jobs;
    }
    private void complete(Job job, boolean success, String error) {
        try { job.done.accept(success, error); }
        catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, Language.text("engine.error-processing-format", job.key), e); }
    }
    private static final class Job {
        final String key, mine;
        final Mine.Region region;
        final Mine.Composition composition;
        final RegionState state;
        final boolean reset;
        final BiConsumer<Boolean, String> done;
        final BitSet observed = new BitSet(), mask;
        final List<BlockData> palette = new ArrayList<>();
        final double[] weights;
        final Map<String, BlockData> templatePalette = new HashMap<>();
        long cursor, capacity;
        int x, y, z;
        Job(String key, String mine, Mine.Region region, Mine.Composition composition, RegionState state, boolean reset, BiConsumer<Boolean, String> done) {
            this.key = key; this.mine = mine; this.region = region; this.composition = composition;
            this.state = state; this.reset = reset; this.done = done;
            x = region.bounds.minX; y = region.bounds.minY; z = region.bounds.minZ;
            if (region.mask.isEmpty()) mask = null;
            else { mask = new BitSet(); region.mask.forEach(mask::set); }
            weights = new double[composition.blocks.size()];
            double sum = 0;
            if (reset) {
                for (int i = 0; i < composition.blocks.size(); i++) {
                    Mine.BlockEntry b = composition.blocks.get(i); sum += b.weight; weights[i] = sum;
                    palette.add(Bukkit.createBlockData(b.data));
                }
                for (String data : region.template) templatePalette.computeIfAbsent(data, Bukkit::createBlockData);
            }
        }
        void advance() {
            cursor++;
            Bounds b = region.bounds;
            int startX = Math.max(b.minX, (x >> 4) << 4), startZ = Math.max(b.minZ, (z >> 4) << 4);
            int endX = Math.min(b.maxX, ((x >> 4) << 4) + 15), endZ = Math.min(b.maxZ, ((z >> 4) << 4) + 15);
            if (x < endX) { x++; return; }
            x = startX;
            if (z < endZ) { z++; return; }
            z = startZ;
            if (y < b.maxY) { y++; return; }
            y = b.minY;
            if (endX < b.maxX) { x = endX + 1; return; }
            x = b.minX; z = endZ + 1;
        }
        BlockData data(int index, Random random) {
            if (!region.template.isEmpty()) return templatePalette.get(region.template.get(index));
            double point = random.nextDouble() * weights[weights.length - 1];
            int low = 0, high = weights.length - 1;
            while (low < high) { int middle = (low + high) >>> 1; if (point < weights[middle]) high = middle; else low = middle + 1; }
            return palette.get(low);
        }
    }
}
