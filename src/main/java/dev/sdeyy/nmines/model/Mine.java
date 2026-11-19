package dev.sdeyy.nmines.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Configuration is copied before edits; runtime state lives in MineService. */
public final class Mine {
    public enum Mode { TIME, PERCENTAGE, BOTH, MANUAL }
    public String id;
    public String displayName;
    public String icon = "DIAMOND_PICKAXE";
    public org.bukkit.inventory.ItemStack iconItem;
    public boolean enabled = true, publicAccess = true, protect = true, rotateRegions = false;
    public boolean teleportOnReset = true, warn = true, autoPickup = false;
    public int resetSeconds = 300, warningSeconds = 10;
    public double resetPercent = 30;
    public Mode mode = Mode.BOTH;
    public Warp teleport, resetTeleport;
    public RequiredTool requiredTool;
    public final Map<String, Region> regions = new LinkedHashMap<>();
    public final List<Reward> rewards = new ArrayList<>();

    public Mine(String id) { this.id = id; this.displayName = id; }

    public static final class Warp {
        public UUID world;
        public double x, y, z;
        public float yaw, pitch;
        public Warp(UUID world, double x, double y, double z, float yaw, float pitch) {
            this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
    }
    public static final class Region {
        public String id;
        public Bounds bounds;
        public double weight = 100;
        /** Empty mask means a cuboid. Nonempty mask contains dense bounding-box indices. */
        public List<Integer> mask = new ArrayList<>();
        /** Dense block data for imported schematics. Includes air and preserves placement offset. */
        public List<String> template = new ArrayList<>();
        public final Map<String, Composition> compositions = new LinkedHashMap<>();
        public final List<Reward> rewards = new ArrayList<>();
        public Region(String id, Bounds bounds) { this.id = id; this.bounds = bounds; }
    }
    public static final class Composition {
        public String id;
        public double weight = 100;
        public final List<BlockEntry> blocks = new ArrayList<>();
        public final List<Reward> rewards = new ArrayList<>();
        public Composition(String id) { this.id = id; }
    }
    public static final class BlockEntry {
        public String data;
        public double weight;
        public boolean customDrops = false, fortune = true;
        public String drop = "COBBLESTONE";
        /** Item chosen for this block; placement uses its Bukkit block state. */
        public org.bukkit.inventory.ItemStack placementItem;
        /** A one-item template; Bukkit persists its full item metadata. */
        public org.bukkit.inventory.ItemStack dropItem;
        public int minAmount = 1, maxAmount = 1;
        public final List<Reward> rewards = new ArrayList<>();
        public BlockEntry(String data, double weight) { this.data = data; this.weight = weight; }
        public org.bukkit.inventory.ItemStack dropStack(int amount) {
            org.bukkit.inventory.ItemStack stack = dropItem == null
                    ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.matchMaterial(drop)) : dropItem.clone();
            stack.setAmount(amount);
            return stack;
        }
    }
}
