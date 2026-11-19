package dev.sdeyy.nmines.integration;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.util.Language;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses the public WE7 API reflectively so no WE implementation is mandatory. */
public final class WorldEditBridge {
    private final JavaPlugin plugin;
    public WorldEditBridge(JavaPlugin plugin) { this.plugin = plugin; }
    public Mine.Region selection(Player p, String id, long limit) {
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object actor = call(adapter, "adapt", p), world = call(adapter, "adapt", p.getWorld());
            Object we = call(Class.forName("com.sk89q.worldedit.WorldEdit"), "getInstance");
            Object session = call(call(we, "getSessionManager"), "get", actor);
            Object selected = call(session, "getSelection", world);
            Object min = call(selected, "getMinimumPoint"), max = call(selected, "getMaximumPoint");
            Bounds b = new Bounds(p.getWorld().getUID(), coord(min, "x"), coord(min, "y"), coord(min, "z"), coord(max, "x"), coord(max, "y"), coord(max, "z"));
            if (b.volume() > limit) throw new IllegalArgumentException(Language.text("worldedit.selection-is-too-large-format", b.volume()));
            Mine.Region region = new Mine.Region(id, b);
            if (!selected.getClass().getSimpleName().equals("CuboidRegion")) {
                for (int i = 0; i < b.volume(); i++) if ((Boolean) call(selected, "contains", vector(b.x(i), b.y(i), b.z(i)))) region.mask.add(i);
                if (region.mask.isEmpty()) throw new IllegalArgumentException(Language.text("worldedit.empty-selection"));
            }
            return region;
        } catch (ReflectiveOperationException e) { throw failure(e); }
    }
    public Mine.Region schematic(String id, String filename, Location origin, long limit) {
        if (!filename.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]{0,100}\\.(schem|schematic)") || filename.contains("..")) throw new IllegalArgumentException(Language.text("worldedit.invalid-schematic-filename"));
        Path folder = plugin.getDataFolder().toPath().resolve("schematics").toAbsolutePath().normalize();
        Path file = folder.resolve(filename).normalize();
        if (!file.startsWith(folder)) throw new IllegalArgumentException(Language.text("worldedit.invalid-path"));
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > 16 * 1024 * 1024) throw new IllegalArgumentException(Language.text("worldedit.schematic-missing-or-larger-than-16-mib-in-plugins-nmines-schemat"));
            if (!file.toRealPath().startsWith(folder.toRealPath())) throw new IllegalArgumentException(Language.text("worldedit.schematic-is-outside-the-allowed-folder"));
            Object format = call(Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats"), "findByFile", file.toFile());
            if (format == null) throw new IllegalArgumentException(Language.text("worldedit.unrecognized-format"));
            Object clipboard;
            try (InputStream input = Files.newInputStream(file)) {
                Object reader = call(format, "getReader", input);
                try { clipboard = call(reader, "read"); } finally { call(reader, "close"); }
            }
            Object selected = call(clipboard, "getRegion"), min = call(selected, "getMinimumPoint"), max = call(selected, "getMaximumPoint");
            int x = coord(min, "x"), y = coord(min, "y"), z = coord(min, "z");
            int dx = coord(max, "x") - x, dy = coord(max, "y") - y, dz = coord(max, "z") - z;
            Bounds b = new Bounds(origin.getWorld().getUID(), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ(), Math.addExact(origin.getBlockX(), dx), Math.addExact(origin.getBlockY(), dy), Math.addExact(origin.getBlockZ(), dz));
            if (b.volume() > limit) throw new IllegalArgumentException(Language.text("worldedit.schematic-exceeds-format", limit));
            Mine.Region r = new Mine.Region(id, b);
            for (int i = 0; i < b.volume(); i++) {
                Object block = call(clipboard, "getBlock", vector(x + b.x(i) - b.minX, y + b.y(i) - b.minY, z + b.z(i) - b.minZ));
                r.template.add((String) call(block, "getAsString"));
            }
            return r;
        } catch (ReflectiveOperationException e) { throw failure(e); }
        catch (IOException e) { throw new IllegalArgumentException(Language.text("worldedit.could-not-read-schematic-format", e.getMessage()), e); }
    }
    private Object vector(int x, int y, int z) throws ReflectiveOperationException { return call(Class.forName("com.sk89q.worldedit.math.BlockVector3"), "at", x, y, z); }
    private int coord(Object vector, String name) throws ReflectiveOperationException {
        try { return ((Number) call(vector, name)).intValue(); }
        catch (NoSuchMethodException e) { return ((Number) call(vector, "getBlock" + name.toUpperCase(java.util.Locale.ROOT))).intValue(); }
    }
    private IllegalArgumentException failure(Exception e) {
        Throwable cause = e instanceof InvocationTargetException ? ((InvocationTargetException) e).getTargetException() : e;
        return new IllegalArgumentException(Language.text("worldedit.worldedit-fawe-7-and-a-complete-selection-are-required-format", cause.getMessage()), cause);
    }
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            boolean fits = true;
            for (int i = 0; i < args.length; i++) {
                Class<?> t = m.getParameterTypes()[i];
                if (!(t.isInstance(args[i]) || (t == int.class && args[i] instanceof Integer))) { fits = false; break; }
            }
            if (fits) return m.invoke(target instanceof Class ? null : target, args);
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
