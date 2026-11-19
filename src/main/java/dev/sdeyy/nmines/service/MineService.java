package dev.sdeyy.nmines.service;

import dev.sdeyy.nmines.api.MineResetCompleteEvent;
import dev.sdeyy.nmines.api.MineResetEvent;
import dev.sdeyy.nmines.engine.RegenerationEngine;
import dev.sdeyy.nmines.engine.RegionState;
import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.Weighted;
import dev.sdeyy.nmines.storage.MineRepository;
import dev.sdeyy.nmines.storage.WriteQueue;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MineService {
    public static final class Ref {
        public final Mine mine;
        public final Mine.Region region;
        public final RegionState state;
        private final Map<Mine.Composition, Map<Material, List<BlockPattern>>> patterns = new HashMap<>();
        Ref(Mine m, Mine.Region r, RegionState s) {
            mine = m; region = r; state = s;
            for (Mine.Composition c : r.compositions.values()) {
                Map<Material, List<BlockPattern>> materials = new java.util.EnumMap<>(Material.class);
                for (Mine.BlockEntry entry : c.blocks) {
                    BlockData data = Bukkit.createBlockData(entry.data);
                    materials.computeIfAbsent(data.getMaterial(), k -> new ArrayList<>()).add(new BlockPattern(entry, data));
                }
                patterns.put(c, materials);
            }
        }
        public Mine.BlockEntry match(Mine.Composition composition, BlockData data) {
            Map<Material, List<BlockPattern>> materials = patterns.get(composition);
            List<BlockPattern> candidates = materials == null ? null : materials.get(data.getMaterial());
            if (candidates != null) for (BlockPattern pattern : candidates) if (pattern.data.matches(data)) return pattern.entry;
            return null;
        }
    }
    private static final class BlockPattern {
        final Mine.BlockEntry entry;
        final BlockData data;
        BlockPattern(Mine.BlockEntry entry, BlockData data) { this.entry = entry; this.data = data; }
    }
    private static final class History {
        final Map<String, String> before, after;
        History(Map<String, String> before, Map<String, String> after) { this.before = before; this.after = after; }
        long size() { return before.values().stream().mapToLong(String::length).sum() + after.values().stream().mapToLong(String::length).sum(); }
    }
    private final JavaPlugin plugin;
    private final Text text;
    private final java.util.function.LongSupplier clock;
    private final MineRepository repository;
    private final WriteQueue writes;
    private final Map<String, String> definitions = new LinkedHashMap<>();
    private boolean stopped;
    private Map<String, Mine> mines = new LinkedHashMap<>();
    private final Map<String, RegionState> states = new HashMap<>();
    private final Map<String, List<Ref>> chunks = new HashMap<>();
    private final Map<String, Ref> regionRefs = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>(), lastReset = new HashMap<>();
    private final Map<String, Long> lastWarningSeconds = new HashMap<>();
    private final Set<String> failures = new HashSet<>();
    private final Set<String> resetRequests = new HashSet<>();
    private final Map<String, String> activeCompositions = new HashMap<>();
    private final Map<String, Long> savedCapacities = new HashMap<>();
    private volatile Map<String, Map<String, String>> placeholderSnapshot = Collections.emptyMap();
    private final Map<UUID, Deque<History>> undo = new HashMap<>(), redo = new HashMap<>();
    private final Random random = new Random();
    private RegenerationEngine engine;
    private MineValidator validator;
    private BukkitTask ticker;
    public MineService(JavaPlugin plugin, Text text) throws IOException {
        this(plugin, text, System::currentTimeMillis);
    }
    public MineService(JavaPlugin plugin, Text text, java.util.function.LongSupplier clock) throws IOException {
        this.plugin = plugin; this.text = text;
        this.clock = clock;
        repository = new MineRepository(plugin.getDataFolder().toPath().resolve("mines.yml"));
        Map<String, Mine> loaded = repository.load();
        validator = new MineValidator(plugin.getConfig()); validator.validate(loaded);
        engine = new RegenerationEngine(plugin); mines = loaded;
        for (Mine m : mines.values()) definitions.put(m.id, encode(m));
        String saveError = Language.text("mines.queued-save-failed");
        writes = new WriteQueue((file, error) -> plugin.getLogger().log(java.util.logging.Level.SEVERE, saveError + ": " + file, error));
        Path runtime = plugin.getDataFolder().toPath().resolve("runtime.yml");
        if (Files.exists(runtime)) {
            YamlConfiguration saved = new YamlConfiguration();
            try {
                saved.load(runtime.toFile());
                org.bukkit.configuration.ConfigurationSection active = saved.getConfigurationSection("compositions");
                if (active != null) for (String key : active.getKeys(false)) activeCompositions.put(key, active.getString(key));
                org.bukkit.configuration.ConfigurationSection capacities = saved.getConfigurationSection("capacities");
                if (capacities != null) for (String key : capacities.getKeys(false)) savedCapacities.put(key, capacities.getLong(key));
            } catch (Exception e) { plugin.getLogger().warning(Language.text("mines.invalid-runtime-yml-blocks-will-be-recounted-format", e.getMessage())); }
        }
    }
    public void start() {
        engine.start(); rebuild(Collections.emptyMap());
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }
    public void stop() {
        if (stopped) return;
        if (ticker != null) ticker.cancel();
        if (engine != null) { if (!states.isEmpty()) saveRuntime(); engine.stop(); }
        stopped = true;
        try { writes.close(); }
        catch (IOException e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, Language.text("mines.queued-save-failed"), e); }
    }
    public void flushSaves() throws IOException { writes.flush(); }
    public Collection<Mine> all() { return Collections.unmodifiableCollection(mines.values()); }
    public Mine get(String id) {
        Mine m = mines.get(id.toLowerCase(Locale.ROOT));
        if (m == null) throw new IllegalArgumentException(Language.text("mines.mine-does-not-exist-format", id));
        return m;
    }
    public MineValidator validator() { return validator; }
    public RegenerationEngine engine() { return engine; }
    public String placeholder(String id, String key) { Map<String, String> values = placeholderSnapshot.get(id); return values == null ? "" : values.get(key); }
    public int placeholderCount() { return placeholderSnapshot.size(); }
    public boolean access(Player p, Mine m) { return p.hasPermission("nmines.admin") || m.publicAccess || p.hasPermission("nmines.mine." + m.id); }
    public RegionState state(String mine, String region) { return states.get(mine + ":" + region); }
    public boolean ready(Mine m) {
        for (Mine.Region r : m.regions.values()) { RegionState s = state(m.id, r.id); if (s == null || !s.ready) return false; }
        return true;
    }
    public long remaining(Mine m) { return m.regions.values().stream().mapToLong(r -> state(m.id, r.id) == null ? 0 : state(m.id, r.id).remaining()).sum(); }
    public long capacity(Mine m) { return m.regions.values().stream().mapToLong(r -> state(m.id, r.id) == null ? 0 : state(m.id, r.id).capacity).sum(); }
    public double percent(Mine m) { long n = capacity(m); return n == 0 ? 0 : Math.min(100, remaining(m) * 100.0 / n); }
    public long seconds(Mine m) { long now = clock.getAsLong(); return Math.max(0, (deadlines.getOrDefault(m.id, now) - now + 999) / 1000); }
    public Ref at(Location location) {
        if (location.getWorld() == null) return null;
        List<Ref> refs = chunks.get(chunkKey(location.getWorld().getUID(), location.getBlockX() >> 4, location.getBlockZ() >> 4));
        if (refs == null) return null;
        for (Ref ref : refs) {
            int index = ref.region.bounds.index(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            if (index >= 0 && (ref.region.mask.isEmpty() || Collections.binarySearch(ref.region.mask, index) >= 0)) return ref;
        }
        return null;
    }
    public void update(Location location) {
        Ref ref = at(location);
        if (ref != null && ref.state.ready) {
            int index = ref.region.bounds.index(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            ref.state.set(index, !location.getBlock().getType().isAir());
        }
    }
    public void edit(UUID actor, Consumer<Map<String, Mine>> operation) {
        Map<String, Mine> changed = new LinkedHashMap<>();
        definitions.forEach((id, data) -> changed.put(id, repository.codec.decode(data).get(id)));
        operation.accept(changed); commit(actor, changed);
    }
    public void editMine(UUID actor, String id, Consumer<Mine> operation) {
        Mine current = get(id);
        Mine copy = repository.codec.decode(definitions.get(current.id)).get(current.id);
        operation.accept(copy);
        if (!current.id.equals(copy.id)) throw new IllegalArgumentException(Language.text("mines.use-rename-command"));
        Map<String, Mine> changed = new LinkedHashMap<>(mines); changed.put(current.id, copy);
        commit(actor, changed);
    }
    private String encode(Mine mine) { return repository.codec.encode(Collections.singletonMap(mine.id, mine)); }
    private void commit(UUID actor, Map<String, Mine> changed) {
        validator.validate(changed);
        Map<String, String> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        Set<String> ids = new java.util.LinkedHashSet<>(mines.keySet()); ids.addAll(changed.keySet());
        Map<String, String> next = new LinkedHashMap<>(definitions);
        for (String id : ids) {
            Mine m = changed.get(id);
            String old = definitions.get(id), value = m == null ? null : m == mines.get(id) ? old : encode(m);
            if (java.util.Objects.equals(old, value)) { if (m != null) changed.put(id, mines.get(id)); continue; }
            if (engine.busy(id) || resetRequests.contains(id)) throw new IllegalArgumentException(Language.text("mines.mine-is-busy-format", id));
            if (old != null) before.put(id, old);
            if (value != null) { after.put(id, value); next.put(id, value); } else next.remove(id);
        }
        if (before.isEmpty() && after.isEmpty()) return;
        save(next);
        Deque<History> stack = undo.computeIfAbsent(actor, k -> new ArrayDeque<>());
        stack.addLast(new History(before, after));
        while (stack.size() > 10 || stack.stream().mapToLong(History::size).sum() > 4 * 1024 * 1024) stack.removeFirst();
        redo.remove(actor);
        definitions.clear(); definitions.putAll(next);
        publish(changed);
    }
    public void history(UUID actor, boolean forward) {
        Deque<History> from = (forward ? redo : undo).get(actor);
        if (from == null || from.isEmpty()) throw new IllegalArgumentException(Language.text("mines.there-are-no-changes-to-format", (forward ? Language.text("errors.redo") : Language.text("errors.undo"))));
        History h = from.peekLast();
        Map<String, String> expected = forward ? h.before : h.after, target = forward ? h.after : h.before;
        Set<String> ids = new HashSet<>(h.before.keySet()); ids.addAll(h.after.keySet());
        Map<String, Mine> restored = new LinkedHashMap<>(mines);
        Map<String, String> next = new LinkedHashMap<>(definitions);
        for (String id : ids) {
            if (engine.busy(id) || resetRequests.contains(id)) throw new IllegalArgumentException(Language.text("mines.mine-is-busy-format", id));
            if (!java.util.Objects.equals(definitions.get(id), expected.get(id))) throw new IllegalArgumentException(Language.text("mines.another-edit-changed-the-mines-that-change-will-not-be-overwritte"));
            if (target.containsKey(id)) { restored.put(id, repository.codec.decode(target.get(id)).get(id)); next.put(id, target.get(id)); }
            else { restored.remove(id); next.remove(id); }
        }
        validator.validate(restored); save(next);
        definitions.clear(); definitions.putAll(next); publish(restored);
        from.removeLast(); (forward ? undo : redo).computeIfAbsent(actor, k -> new ArrayDeque<>()).addLast(h);
    }
    public void forget(UUID actor) { undo.remove(actor); redo.remove(actor); }
    public void reload() throws IOException {
        writes.flush();
        Map<String, Mine> loaded = repository.load();
        MineValidator nextValidator = new MineValidator(plugin.getConfig()); nextValidator.validate(loaded);
        RegenerationEngine nextEngine = new RegenerationEngine(plugin);
        engine.cancel(null); engine.stop(); engine = nextEngine; validator = nextValidator; engine.start();
        definitions.clear(); for (Mine m : loaded.values()) definitions.put(m.id, encode(m));
        undo.clear(); redo.clear(); failures.clear(); publish(loaded);
    }
    private void save(Map<String, String> data) {
        try { writes.checkHealthy(); writes.submit(plugin.getDataFolder().toPath().resolve("mines.yml"), repository.codec.join(data)); }
        catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, Language.text("mines.could-not-save-mines-yml"), e);
            throw new IllegalArgumentException(Language.text("mines.queued-save-failed"), e);
        }
    }
    private void publish(Map<String, Mine> changed) { Map<String, Mine> old = mines; mines = changed; rebuild(old); saveRuntime(); }
    private void rebuild(Map<String, Mine> old) {
        chunks.clear(); Set<String> valid = new HashSet<>();
        for (Mine m : mines.values()) {
            Mine previous = old.get(m.id);
            if (previous == null || previous.resetSeconds != m.resetSeconds || previous.enabled != m.enabled || previous.mode != m.mode) scheduleReset(m.id, clock.getAsLong() + m.resetSeconds * 1000L);
            for (Mine.Region r : m.regions.values()) {
                Collections.sort(r.mask);
                String key = m.id + ":" + r.id; valid.add(key);
                Mine.Region prev = previous == null ? null : previous.regions.get(r.id);
                RegionState s = states.get(key);
                // Renaming a mine keeps the physical region and its selected composition.
                if (s == null && previous == null) {
                    for (Mine oldMine : old.values()) if (!mines.containsKey(oldMine.id)) {
                        for (Mine.Region oldRegion : oldMine.regions.values()) if (sameGeometry(oldRegion, r)) {
                            RegionState oldState = states.get(oldMine.id + ":" + oldRegion.id);
                            if (oldState != null && oldState.ready) { s = oldState; prev = oldRegion; }
                        }
                    }
                }
                if (s == null || prev == null || !sameGeometry(prev, r)) {
                    s = new RegionState();
                    if (prev == null) s.capacity = Math.max(0, Math.min(r.bounds.volume(), savedCapacities.getOrDefault(key, 0L)));
                }
                states.put(key, s);
                if (s.composition == null) s.composition = activeCompositions.get(key);
                if (s.composition != null && !r.compositions.containsKey(s.composition)) s.composition = null;
                if (s.composition != null) activeCompositions.put(key, s.composition); else activeCompositions.remove(key);
                Ref ref = regionRefs.get(key);
                if (ref == null || ref.mine != m || ref.state != s) { ref = new Ref(m, r, s); regionRefs.put(key, ref); }
                Bounds b = r.bounds;
                for (int x = b.minX >> 4; x <= (b.maxX >> 4); x++) for (int z = b.minZ >> 4; z <= (b.maxZ >> 4); z++)
                    chunks.computeIfAbsent(chunkKey(b.world, x, z), k -> new ArrayList<>()).add(ref);
                if (!s.ready && !engine.queued(key) && !failures.contains(m.id)) {
                    engine.enqueue(m.id, r, r.compositions.values().iterator().next(), s, false, (ok, error) -> { if (!ok) failures.add(m.id); });
                }
            }
        }
        states.keySet().retainAll(valid); deadlines.keySet().retainAll(mines.keySet());
        regionRefs.keySet().retainAll(valid);
        lastWarningSeconds.keySet().retainAll(mines.keySet());
        lastReset.keySet().retainAll(mines.keySet()); failures.retainAll(mines.keySet());
        activeCompositions.keySet().retainAll(valid); snapshots();
    }
    private boolean sameGeometry(Mine.Region a, Mine.Region b) {
        Bounds x = a.bounds, y = b.bounds;
        return x.world.equals(y.world) && x.minX == y.minX && x.minY == y.minY && x.minZ == y.minZ
                && x.maxX == y.maxX && x.maxY == y.maxY && x.maxZ == y.maxZ && a.mask.equals(b.mask) && a.template.equals(b.template);
    }
    private static String chunkKey(UUID world, int x, int z) { return world + ":" + x + ":" + z; }
    public void reset(String id, String regionId) {
        String key = get(id).id;
        if (!resetRequests.add(key)) throw new IllegalArgumentException(Language.text("mines.a-reset-request-is-already-in-progress"));
        try { resetInternal(key, regionId); } finally { resetRequests.remove(key); }
    }
    private void resetInternal(String id, String regionId) {
        Mine m = get(id);
        if (engine.busy(m.id)) throw new IllegalArgumentException(Language.text("mines.the-mine-is-being-processed"));
        List<Mine.Region> selected = new ArrayList<>();
        if (regionId != null) {
            Mine.Region r = m.regions.get(regionId);
            if (r == null) throw new IllegalArgumentException(Language.text("menus.region-not-found")); selected.add(r);
        } else if (m.rotateRegions) selected.add(Weighted.pick(m.regions.values(), r -> r.weight, random));
        else selected.addAll(m.regions.values());
        for (Mine.Region r : selected) if (Bukkit.getWorld(r.bounds.world) == null) throw new IllegalArgumentException(Language.text("mines.world-is-not-loaded-format", r.id));
        MineResetEvent event = new MineResetEvent(m.id, selected.stream().map(r -> r.id).collect(java.util.stream.Collectors.toList()));
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) throw new IllegalArgumentException(Language.text("mines.reset-cancelled-by-another-plugin"));
        if (mines.get(id) != m) throw new IllegalArgumentException(Language.text("mines.the-definition-changed-during-the-event-request-the-reset-again"));
        // Plan every evacuation before queuing the first region.
        Map<Player, Location> evacuations = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) for (Mine.Region r : selected) {
            if (!intersectsPlayer(r, p)) continue;
            if (!m.teleportOnReset) throw new IllegalArgumentException(Language.text("mines.players-are-inside-and-teleport-on-reset-is-disabled"));
            evacuations.put(p, evacuation(m, r)); break;
        }
        for (Map.Entry<Player, Location> entry : evacuations.entrySet()) {
            if (!entry.getKey().teleport(entry.getValue())) throw new IllegalArgumentException(Language.text("mines.another-plugin-cancelled-a-teleport-reset-cancelled"));
            entry.getKey().setFallDistance(0);
        }
        final int[] pending = {selected.size()};
        for (Mine.Region r : selected) {
            RegionState s = state(m.id, r.id);
            Mine.Composition c = Weighted.pick(r.compositions.values(), comp -> comp.weight, random);
            engine.enqueue(m.id, r, c, s, true, (success, error) -> {
                if (!success) {
                    failures.add(m.id); s.composition = null; activeCompositions.remove(m.id + ":" + r.id);
                    plugin.getLogger().severe(Language.text("mines.mine-format", m.id, error));
                }
                else activeCompositions.put(m.id + ":" + r.id, c.id);
                if (--pending[0] == 0) {
                    scheduleReset(m.id, clock.getAsLong() + m.resetSeconds * 1000L);
                    lastReset.put(m.id, clock.getAsLong());
                    if (!failures.contains(m.id)) announce(m, "reset-done", 0);
                    saveRuntime(); snapshots();
                    Bukkit.getPluginManager().callEvent(new MineResetCompleteEvent(m.id, !failures.contains(m.id)));
                }
            });
        }
        failures.remove(m.id); announce(m, "reset-start", 0);
    }
    public void recount(String id) {
        Mine m = get(id);
        if (engine.busy(m.id)) throw new IllegalArgumentException(Language.text("mines.the-mine-is-being-processed"));
        for (Mine.Region r : m.regions.values()) engine.enqueue(m.id, r, r.compositions.values().iterator().next(), state(m.id, r.id), false,
                (ok, error) -> { if (!ok) failures.add(m.id); });
        failures.remove(m.id);
    }
    public int cancel(String id) {
        Mine m = get(id);
        if (engine.busy(m.id)) failures.add(m.id);
        int count = engine.cancel(m.id);
        snapshots(); return count;
    }
    public void retry(String id) {
        Mine m = get(id);
        if (engine.busy(m.id)) cancel(m.id);
        recount(m.id);
    }
    private void scheduleReset(String id, long deadline) {
        deadlines.put(id, deadline);
        lastWarningSeconds.remove(id);
    }
    private void tick() {
        if (engine.paused()) { snapshots(); return; }
        long now = clock.getAsLong();
        for (Mine m : new ArrayList<>(mines.values())) {
            if (!m.enabled || failures.contains(m.id) || engine.busy(m.id) || !ready(m)) continue;
            boolean timed = m.mode == Mine.Mode.TIME || m.mode == Mine.Mode.BOTH;
            boolean percentage = m.mode == Mine.Mode.PERCENTAGE || m.mode == Mine.Mode.BOTH;
            long seconds = seconds(m);
            // Scheduler catch-up or clock adjustments may evaluate the same second again.
            if (timed && m.warn && seconds > 0 && seconds <= m.warningSeconds
                    && seconds < lastWarningSeconds.getOrDefault(m.id, Long.MAX_VALUE)) {
                lastWarningSeconds.put(m.id, seconds);
                announce(m, "warning", (int) seconds);
            }
            boolean percentageDue = percentage && now - lastReset.getOrDefault(m.id, 0L) >= 5000 && capacity(m) > 0 && percent(m) <= m.resetPercent;
            if ((timed && seconds == 0) || percentageDue) {
                try { reset(m.id, null); }
                catch (IllegalArgumentException e) { scheduleReset(m.id, now + 5000); lastReset.put(m.id, now); plugin.getLogger().warning(m.id + ": " + e.getMessage()); }
            }
        }
        snapshots();
    }
    private void saveRuntime() {
        YamlConfiguration yaml = new YamlConfiguration();
        activeCompositions.forEach((key, value) -> yaml.set("compositions." + key, value));
        states.forEach((key, value) -> { if (value.ready || value.capacity > 0) yaml.set("capacities." + key, value.capacity); });
        writes.submit(plugin.getDataFolder().toPath().resolve("runtime.yml"), yaml.saveToString());
    }
    private void snapshots() {
        Map<String, Map<String, String>> next = new HashMap<>();
        for (Mine m : mines.values()) {
            Map<String, String> values = new HashMap<>();
            values.put("name", m.displayName); values.put("remaining", String.valueOf(remaining(m)));
            values.put("total", String.valueOf(capacity(m))); values.put("mined", String.valueOf(Math.max(0, capacity(m) - remaining(m))));
            values.put("percent", String.format(Locale.ROOT, "%.1f", percent(m))); values.put("seconds", String.valueOf(seconds(m)));
            values.put("time", Text.time(seconds(m))); values.put("status", engine.busy(m.id) ? Language.text("status.regenerating") : !ready(m) || failures.contains(m.id) ? Language.text("status.pending") : m.enabled ? Language.text("status.active") : Language.text("status.paused"));
            values.put("progress", String.format(Locale.ROOT, "%.1f", engine.progress(m.id))); next.put(m.id, Collections.unmodifiableMap(values));
        }
        placeholderSnapshot = Collections.unmodifiableMap(next);
    }
    public boolean inside(Mine.Region r, Location l) {
        if (l.getWorld() == null || !r.bounds.world.equals(l.getWorld().getUID())) return false;
        int index = r.bounds.index(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        return index >= 0 && (r.mask.isEmpty() || Collections.binarySearch(r.mask, index) >= 0);
    }
    private boolean intersectsPlayer(Mine.Region r, Player p) {
        if (!p.getWorld().getUID().equals(r.bounds.world)) return false;
        org.bukkit.util.BoundingBox box = p.getBoundingBox();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(Math.nextDown(box.getMaxX())); x++)
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(Math.nextDown(box.getMaxY())); y++)
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(Math.nextDown(box.getMaxZ())); z++)
                    if (inside(r, new Location(p.getWorld(), x, y, z))) return true;
        return false;
    }
    private Location evacuation(Mine m, Mine.Region r) {
        Location location = location(m.resetTeleport != null ? m.resetTeleport : m.teleport);
        if (location != null && safe(location) && outsideAll(location)) return location;
        World w = Bukkit.getWorld(r.bounds.world);
        int x = r.bounds.maxX + 2, z = r.bounds.maxZ + 2;
        Location fallback = new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5);
        if (safe(fallback) && outsideAll(fallback)) return fallback;
        Location spawn = w.getSpawnLocation();
        if (safe(spawn) && outsideAll(spawn)) return spawn;
        throw new IllegalArgumentException(Language.text("mines.set-a-safe-nm-setresettp-outside-all-mines"));
    }
    private boolean outsideAll(Location l) {
        for (Mine mine : mines.values()) for (Mine.Region region : mine.regions.values()) if (inside(region, l)) return false;
        return true;
    }
    public static boolean safe(Location l) {
        World w = l.getWorld(); if (w == null || l.getY() <= MineValidator.minHeight(w) || l.getY() + 1 >= w.getMaxHeight()) return false;
        Material floor = w.getBlockAt(l.getBlockX(), l.getBlockY() - 1, l.getBlockZ()).getType();
        return floor.isSolid() && floor != Material.MAGMA_BLOCK && floor != Material.CACTUS
                && l.getBlock().isPassable() && l.clone().add(0, 1, 0).getBlock().isPassable()
                && !l.getBlock().isLiquid() && !l.clone().add(0, 1, 0).getBlock().isLiquid();
    }
    public static Location location(Mine.Warp w) {
        if (w == null || Bukkit.getWorld(w.world) == null) return null;
        return new Location(Bukkit.getWorld(w.world), w.x, w.y, w.z, w.yaw, w.pitch);
    }
    public static Mine.Warp warp(Location l) { return new Mine.Warp(l.getWorld().getUID(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()); }
    public void teleport(Player p, String id) {
        Mine m = get(id);
        if (!p.hasPermission("nmines.teleport") || !access(p, m)) throw new IllegalArgumentException(Language.text("mines.you-do-not-have-permission-for-this-mine"));
        Location l = location(m.teleport);
        if (l == null || !safe(l)) throw new IllegalArgumentException(Language.text("mines.the-mine-has-no-safe-teleport-use-nm-settp"));
        if (engine.busy(m.id) && !outsideAll(l)) throw new IllegalArgumentException(Language.text("mines.wait-for-regeneration-to-finish"));
        if (!p.teleport(l)) throw new IllegalArgumentException(Language.text("mines.teleport-cancelled-by-another-plugin"));
        p.setFallDistance(0);
    }
    private void announce(Mine m, String key, int seconds) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean nearby = false;
            for (Mine.Region r : m.regions.values()) if (p.getWorld().getUID().equals(r.bounds.world)) {
                Bounds b = r.bounds; Location l = p.getLocation();
                if (l.getX() >= b.minX - 64 && l.getX() <= b.maxX + 64 && l.getZ() >= b.minZ - 64 && l.getZ() <= b.maxZ + 64) { nearby = true; break; }
            }
            if (nearby && access(p, m)) text.send(p, text.format(key, m.displayName, seconds));
        }
    }
}
