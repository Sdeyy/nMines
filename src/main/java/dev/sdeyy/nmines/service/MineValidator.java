package dev.sdeyy.nmines.service;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.RequiredTool;
import dev.sdeyy.nmines.model.Reward;
import dev.sdeyy.nmines.util.Language;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;

public final class MineValidator {
    private final long maxRegion, maxTotal, maxSchematic;
    public MineValidator(FileConfiguration config) {
        maxRegion = Math.min(Integer.MAX_VALUE - 1L, positive(config.getLong("limits.max-region-volume", 1000000)));
        maxTotal = positive(config.getLong("limits.max-total-volume", 10000000));
        maxSchematic = positive(config.getLong("limits.max-schematic-volume", 100000));
    }
    public long maxRegion() { return maxRegion; }
    public long maxSchematic() { return Math.min(maxRegion, maxSchematic); }
    public static void id(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException(Language.text("errors.id-132-characters-using-a-z-0-9-and"));
    }
    public void validate(Map<String, Mine> mines) {
        if (mines.size() > 1000) throw new IllegalArgumentException(Language.text("errors.maximum-1000-mines"));
        List<Bounds> occupied = new ArrayList<>(); long total = 0, chunkReferences = 0;
        Set<String> blockData = new HashSet<>();
        for (Map.Entry<String, Mine> entry : mines.entrySet()) if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().id)) fail(Language.text("errors.definition-key-mismatch"));
        for (Mine m : mines.values()) {
            id(m.id); item(m.icon); template(m.iconItem);
            if (m.requiredTool != null) {
                template(m.requiredTool.item);
                if (!RequiredTool.validId(m.requiredTool.id) || !m.requiredTool.matches(m.requiredTool.item)) fail(Language.text("tools.invalid-definition"));
            }
            if (m.displayName == null || m.displayName.length() > 100) fail(Language.text("errors.name-is-too-long"));
            if (m.resetSeconds < 1 || m.resetSeconds > 31536000) fail(Language.text("errors.time-must-be-between-1-and-31536000-seconds"));
            if (!Double.isFinite(m.resetPercent) || m.resetPercent <= 0 || m.resetPercent >= 100) fail(Language.text("errors.remaining-percentage-must-be-greater-than-0-and-less-than-100"));
            if (m.warningSeconds < 0 || m.warningSeconds > m.resetSeconds) fail(Language.text("errors.warning-time-must-be-within-the-reset-interval"));
            if (m.regions.isEmpty() || m.regions.size() > 100) fail(Language.text("errors.each-mine-needs-between-1-and-100-regions"));
            warp(m.teleport); warp(m.resetTeleport); rewards(m.rewards);
            double regionWeights = 0;
            for (Map.Entry<String, Mine.Region> entry : m.regions.entrySet()) if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().id)) fail(Language.text("errors.definition-key-mismatch"));
            for (Mine.Region r : m.regions.values()) {
                id(r.id); weight(r.weight); regionWeights += r.weight;
                Bounds b = r.bounds;
                if (b.volume() > maxRegion) fail(Language.text("errors.region-exceeds-format", maxRegion));
                total = Math.addExact(total, b.volume());
                if (total > maxTotal) fail(Language.text("errors.total-volume-exceeds-format", maxTotal));
                chunkReferences += ((long) (b.maxX >> 4) - (b.minX >> 4) + 1) * ((long) (b.maxZ >> 4) - (b.minZ >> 4) + 1);
                if (chunkReferences > 100000) fail(Language.text("errors.regions-cover-too-many-chunks-maximum-100000-references"));
                if (b.minX < -29999984 || b.maxX > 29999983 || b.minZ < -29999984 || b.maxZ > 29999983) fail(Language.text("errors.outside-world-boundaries"));
                World w = Bukkit.getWorld(b.world);
                if (w != null && (b.minY < minHeight(w) || b.maxY >= w.getMaxHeight())) fail(Language.text("errors.height-is-outside-the-world-format", r.id));
                for (Bounds other : occupied) if (b.overlaps(other)) fail(Language.text("errors.regions-must-not-overlap-format", m.id, r.id));
                occupied.add(b);
                Set<Integer> mask = new HashSet<>();
                for (int n : r.mask) if (n < 0 || n >= b.volume() || !mask.add(n)) fail(Language.text("errors.invalid-mask-format", r.id));
                if (!r.template.isEmpty() && (r.template.size() != b.volume() || b.volume() > maxSchematic())) fail(Language.text("errors.invalid-schematic-size"));
                blockData.addAll(r.template); rewards(r.rewards);
                if (r.compositions.isEmpty() || r.compositions.size() > 100) fail(Language.text("errors.between-1-and-100-compositions-are-required"));
                double compWeights = 0;
                for (Map.Entry<String, Mine.Composition> entry : r.compositions.entrySet()) if (entry.getValue() == null || !entry.getKey().equals(entry.getValue().id)) fail(Language.text("errors.definition-key-mismatch"));
                for (Mine.Composition c : r.compositions.values()) {
                    id(c.id); weight(c.weight); compWeights += c.weight; rewards(c.rewards);
                    if (c.blocks.isEmpty() || c.blocks.size() > 1000) fail(Language.text("errors.between-1-and-1000-blocks-per-composition-are-required"));
                    Set<String> seen = new HashSet<>(); double sum = 0;
                    for (Mine.BlockEntry e : c.blocks) {
                        weight(e.weight); sum += e.weight; blockData.add(e.data);
                        if (!seen.add(e.data)) fail(Language.text("errors.duplicate-block-format", e.data));
                        item(e.drop);
                        template(e.placementItem);
                        if (e.placementItem != null && (!e.placementItem.getType().isBlock() || e.placementItem.getType() != Bukkit.createBlockData(e.data).getMaterial())) fail(Language.text("errors.the-selected-item-must-match-the-placed-block"));
                        if (e.dropItem != null && (e.dropItem.getAmount() != 1 || !e.dropItem.getType().isItem() || e.dropItem.getType().isAir())) fail(Language.text("errors.invalid-drop-item"));
                        if (e.minAmount < 1 || e.maxAmount < e.minAmount || e.maxAmount > 2304) fail(Language.text("errors.invalid-drop-amount"));
                        rewards(e.rewards);
                    }
                    positiveWeight(sum);
                }
                positiveWeight(compWeights);
            }
            positiveWeight(regionWeights);
        }
        for (String data : blockData) {
            try { Bukkit.createBlockData(data); }
            catch (IllegalArgumentException e) { fail(Language.text("errors.block-is-unavailable-on-this-server-version-format", data)); }
        }
    }
    private void rewards(List<Reward> rewards) {
        Set<String> ids = new HashSet<>();
        if (rewards.size() > 100) fail(Language.text("errors.maximum-100-rewards-per-scope"));
        for (Reward r : rewards) {
            id(r.id); if (!ids.add(r.id)) fail(Language.text("errors.duplicate-reward"));
            if (!Double.isFinite(r.chance) || r.chance < 0 || r.chance > 100) fail(Language.text("errors.invalid-chance"));
            if (!Double.isFinite(r.radius) || r.radius < 0 || r.radius > 128) fail(Language.text("errors.radius-must-be-between-0-and-128"));
            if (!r.item.isEmpty()) item(r.item);
            template(r.itemTemplate);
            if (r.amount < 1 || r.amount > 2304) fail(Language.text("errors.reward-amount-must-be-between-1-and-2304"));
            for (String cmd : r.commands) if (cmd.trim().isEmpty() || cmd.contains("\n") || cmd.contains("\r")) fail(Language.text("errors.invalid-reward-command"));
        }
    }
    private void warp(Mine.Warp w) {
        if (w == null) return;
        if (w.world == null || !Double.isFinite(w.x) || !Double.isFinite(w.y) || !Double.isFinite(w.z)
                || !Float.isFinite(w.yaw) || !Float.isFinite(w.pitch) || Math.abs(w.x) > 29999983 || Math.abs(w.z) > 29999983) fail(Language.text("errors.invalid-teleport"));
        World world = Bukkit.getWorld(w.world);
        if (world != null && (w.y < minHeight(world) || w.y >= world.getMaxHeight())) fail(Language.text("errors.invalid-teleport-height"));
    }
    public static int minHeight(World w) {
        try { return (Integer) World.class.getMethod("getMinHeight").invoke(w); }
        catch (ReflectiveOperationException ignored) { return 0; }
    }
    public static Material item(String name) {
        Material material = Material.matchMaterial(name);
        if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException(Language.text("errors.invalid-item-format", name));
        return material;
    }
    private void template(org.bukkit.inventory.ItemStack item) {
        if (item != null && (item.getAmount() != 1 || item.getType().isAir() || !item.getType().isItem())) fail(Language.text("errors.invalid-item-template"));
    }
    private static void weight(double n) { if (!Double.isFinite(n) || n < 0 || n > 1000000) fail(Language.text("errors.weight-must-be-between-0-and-1000000")); }
    private static void positiveWeight(double n) { if (n <= 0 || !Double.isFinite(n)) fail(Language.text("errors.at-least-one-weight-must-be-positive")); }
    private static long positive(long n) { if (n < 1) throw new IllegalArgumentException(Language.text("errors.limits-must-be-positive")); return n; }
    private static void fail(String s) { throw new IllegalArgumentException(s); }
}
