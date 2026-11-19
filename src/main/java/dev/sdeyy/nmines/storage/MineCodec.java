package dev.sdeyy.nmines.storage;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.RequiredTool;
import dev.sdeyy.nmines.model.Reward;
import dev.sdeyy.nmines.util.Language;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public final class MineCodec {
    /** Join already encoded single-mine documents without reserializing unchanged item metadata. */
    public String join(Map<String, String> snapshots) {
        if (snapshots.isEmpty()) return "schema: 1\nmines: {}\n";
        StringBuilder yaml = new StringBuilder("schema: 1\nmines:\n");
        String marker = "\nmines:\n";
        for (String snapshot : snapshots.values()) {
            int start = snapshot.indexOf(marker);
            if (start < 0) throw new IllegalArgumentException("Expected a single-mine document");
            yaml.append(snapshot, start + marker.length(), snapshot.length());
        }
        return yaml.toString();
    }
    public String encode(Map<String, Mine> mines) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        ConfigurationSection root = yaml.createSection("mines");
        for (Mine m : mines.values()) writeMine(root.createSection(m.id), m);
        return yaml.saveToString();
    }
    public Map<String, Mine> decode(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(text); }
        catch (InvalidConfigurationException e) { throw new IllegalArgumentException(Language.text("errors.invalid-yaml-format", e.getMessage()), e); }
        if (yaml.getInt("schema", -1) != 1) throw new IllegalArgumentException(Language.text("errors.unsupported-mines-yml-schema-version"));
        Map<String, Mine> mines = new LinkedHashMap<>();
        ConfigurationSection root = required(yaml, "mines");
        for (String id : root.getKeys(false)) mines.put(id, readMine(id, required(root, id)));
        return mines;
    }
    public Map<String, Mine> copy(Map<String, Mine> source) { return decode(encode(source)); }

    private void writeMine(ConfigurationSection s, Mine m) {
        s.set("display-name", m.displayName); s.set("icon", m.icon); s.set("enabled", m.enabled);
        if (m.iconItem != null) s.set("icon-item", m.iconItem.clone());
        s.set("public", m.publicAccess); s.set("protect", m.protect); s.set("rotate-regions", m.rotateRegions);
        s.set("teleport-on-reset", m.teleportOnReset); s.set("warn", m.warn); s.set("auto-pickup", m.autoPickup);
        s.set("mode", m.mode.name()); s.set("reset-seconds", m.resetSeconds);
        s.set("reset-percent", m.resetPercent); s.set("warning-seconds", m.warningSeconds);
        warp(s, "teleport", m.teleport); warp(s, "reset-teleport", m.resetTeleport);
        if (m.requiredTool != null) {
            s.set("required-tool.id", m.requiredTool.id); s.set("required-tool.item", m.requiredTool.copy());
        }
        rewards(s, m.rewards);
        ConfigurationSection regions = s.createSection("regions");
        for (Mine.Region r : m.regions.values()) {
            ConfigurationSection rs = regions.createSection(r.id);
            Bounds b = r.bounds;
            rs.set("world", b.world.toString()); rs.set("min", Arrays.asList(b.minX, b.minY, b.minZ));
            rs.set("max", Arrays.asList(b.maxX, b.maxY, b.maxZ)); rs.set("weight", r.weight);
            if (!r.mask.isEmpty()) rs.set("mask", r.mask);
            if (!r.template.isEmpty()) {
                Map<String, Integer> palette = new LinkedHashMap<>();
                List<Integer> indices = new ArrayList<>();
                for (String data : r.template) {
                    if (!palette.containsKey(data)) palette.put(data, palette.size());
                    indices.add(palette.get(data));
                }
                rs.set("template.palette", new ArrayList<>(palette.keySet())); rs.set("template.blocks", indices);
            }
            rewards(rs, r.rewards);
            ConfigurationSection comps = rs.createSection("compositions");
            for (Mine.Composition c : r.compositions.values()) {
                ConfigurationSection cs = comps.createSection(c.id);
                cs.set("weight", c.weight); rewards(cs, c.rewards);
                ConfigurationSection blocks = cs.createSection("blocks");
                for (int i = 0; i < c.blocks.size(); i++) {
                    Mine.BlockEntry entry = c.blocks.get(i);
                    ConfigurationSection bs = blocks.createSection("b" + i);
                    bs.set("data", entry.data); bs.set("weight", entry.weight);
                    bs.set("custom-drops", entry.customDrops); bs.set("drop", entry.drop);
                    if (entry.dropItem != null) bs.set("drop-item", entry.dropItem.clone());
                    if (entry.placementItem != null) bs.set("placement-item", entry.placementItem.clone());
                    bs.set("min-amount", entry.minAmount); bs.set("max-amount", entry.maxAmount);
                    bs.set("fortune", entry.fortune); rewards(bs, entry.rewards);
                }
            }
        }
    }
    private Mine readMine(String id, ConfigurationSection s) {
        types(s, "display-name icon mode", "reset-percent", "reset-seconds warning-seconds", "enabled public protect rotate-regions teleport-on-reset warn auto-pickup", "", "");
        Mine m = new Mine(id);
        m.displayName = s.getString("display-name", id); m.icon = s.getString("icon", "DIAMOND_PICKAXE");
        m.iconItem = readItem(s, "icon-item"); if (m.iconItem != null) m.icon = m.iconItem.getType().name();
        m.enabled = s.getBoolean("enabled", true); m.publicAccess = s.getBoolean("public", true);
        m.protect = s.getBoolean("protect", true); m.rotateRegions = s.getBoolean("rotate-regions");
        m.teleportOnReset = s.getBoolean("teleport-on-reset", true); m.warn = s.getBoolean("warn", true);
        m.autoPickup = s.getBoolean("auto-pickup");
        m.mode = Mine.Mode.valueOf(s.getString("mode", "BOTH")); m.resetSeconds = s.getInt("reset-seconds", 300);
        m.resetPercent = s.getDouble("reset-percent", 30); m.warningSeconds = s.getInt("warning-seconds", 10);
        m.teleport = readWarp(s.getConfigurationSection("teleport"));
        m.resetTeleport = readWarp(s.getConfigurationSection("reset-teleport"));
        if (s.contains("required-tool")) {
            ConfigurationSection tool = required(s, "required-tool");
            types(tool, "id", "", "", "", "", "");
            org.bukkit.inventory.ItemStack item = readItem(tool, "item");
            if (item == null) throw new IllegalArgumentException(Language.text("tools.invalid-definition"));
            m.requiredTool = new RequiredTool(tool.getString("id", ""), item);
        }
        m.rewards.addAll(readRewards(s));
        ConfigurationSection regions = required(s, "regions");
        for (String rid : regions.getKeys(false)) {
            ConfigurationSection rs = required(regions, rid);
            types(rs, "world", "weight", "", "", "", "min max mask");
            List<Integer> min = rs.getIntegerList("min"), max = rs.getIntegerList("max");
            if (min.size() != 3 || max.size() != 3) throw new IllegalArgumentException(Language.text("errors.invalid-coordinates-format", id, rid));
            Mine.Region r = new Mine.Region(rid, new Bounds(UUID.fromString(rs.getString("world", "")), min.get(0), min.get(1), min.get(2), max.get(0), max.get(1), max.get(2)));
            r.weight = rs.getDouble("weight", 100); r.mask.addAll(rs.getIntegerList("mask"));
            if (rs.isConfigurationSection("template")) {
                types(required(rs, "template"), "", "", "", "", "palette", "blocks");
                List<String> palette = rs.getStringList("template.palette");
                for (Integer n : rs.getIntegerList("template.blocks")) {
                    if (n < 0 || n >= palette.size()) throw new IllegalArgumentException(Language.text("errors.invalid-palette-format", rid));
                    r.template.add(palette.get(n));
                }
                if (r.template.isEmpty()) throw new IllegalArgumentException(Language.text("errors.empty-schematic-format", rid));
            }
            r.rewards.addAll(readRewards(rs));
            ConfigurationSection comps = required(rs, "compositions");
            for (String cid : comps.getKeys(false)) {
                ConfigurationSection cs = required(comps, cid);
                types(cs, "", "weight", "", "", "", "");
                Mine.Composition c = new Mine.Composition(cid); c.weight = cs.getDouble("weight", 100);
                c.rewards.addAll(readRewards(cs));
                ConfigurationSection blocks = required(cs, "blocks");
                for (String bid : blocks.getKeys(false)) {
                    ConfigurationSection bs = required(blocks, bid);
                    types(bs, "data drop", "weight", "min-amount max-amount", "custom-drops fortune", "", "");
                    Mine.BlockEntry b = new Mine.BlockEntry(bs.getString("data", ""), bs.getDouble("weight", 100));
                    b.placementItem = readItem(bs, "placement-item");
                    b.customDrops = bs.getBoolean("custom-drops"); b.drop = bs.getString("drop", "COBBLESTONE");
                    if (bs.contains("drop-item")) {
                        org.bukkit.inventory.ItemStack saved = bs.getItemStack("drop-item");
                        if (saved == null) throw new IllegalArgumentException(Language.text("errors.invalid-drop-item-in-format", bs.getCurrentPath()));
                        b.dropItem = saved.clone(); b.dropItem.setAmount(1); b.drop = b.dropItem.getType().name();
                    }
                    b.minAmount = bs.getInt("min-amount", 1); b.maxAmount = bs.getInt("max-amount", 1);
                    b.fortune = bs.getBoolean("fortune", true); b.rewards.addAll(readRewards(bs)); c.blocks.add(b);
                }
                r.compositions.put(cid, c);
            }
            m.regions.put(rid, r);
        }
        return m;
    }
    private void warp(ConfigurationSection s, String key, Mine.Warp w) {
        if (w == null) return;
        ConfigurationSection t = s.createSection(key); t.set("world", w.world.toString());
        t.set("x", w.x); t.set("y", w.y); t.set("z", w.z); t.set("yaw", w.yaw); t.set("pitch", w.pitch);
    }
    private Mine.Warp readWarp(ConfigurationSection t) {
        if (t != null) types(t, "world", "x y z yaw pitch", "", "", "", "");
        return t == null ? null : new Mine.Warp(UUID.fromString(t.getString("world", "")), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"), (float) t.getDouble("yaw"), (float) t.getDouble("pitch"));
    }
    private void rewards(ConfigurationSection s, List<Reward> rewards) {
        ConfigurationSection rs = s.createSection("rewards");
        for (Reward r : rewards) {
            ConfigurationSection t = rs.createSection(r.id); t.set("chance", r.chance); t.set("permission", r.permission);
            t.set("group", r.group); t.set("overrides", r.overrides); t.set("commands", r.commands);
            t.set("message", r.message); t.set("actionbar", r.actionBar); t.set("item", r.item); t.set("amount", r.amount); t.set("radius", r.radius);
            if (r.itemTemplate != null) t.set("item-template", r.itemTemplate.clone());
        }
    }
    private List<Reward> readRewards(ConfigurationSection s) {
        List<Reward> result = new ArrayList<>();
        ConfigurationSection rs = s.getConfigurationSection("rewards");
        if (rs == null) { if (s.contains("rewards")) throw new IllegalArgumentException(Language.text("errors.rewards-must-be-a-section")); return result; }
        for (String id : rs.getKeys(false)) {
            ConfigurationSection t = required(rs, id);
            Set<String> allowed = new HashSet<>(Arrays.asList("chance", "permission", "group", "overrides", "commands", "message", "actionbar", "item", "item-template", "amount", "radius"));
            for (String key : t.getKeys(false)) if (!allowed.contains(key)) throw new IllegalArgumentException(Language.text("errors.unknown-reward-field-format", key));
            types(t, "permission group message actionbar item", "chance radius", "amount", "", "overrides commands", "");
            Reward r = new Reward(); r.id = id; r.chance = t.getDouble("chance", 100);
            r.permission = t.getString("permission", ""); r.group = t.getString("group", "");
            r.overrides.addAll(t.getStringList("overrides")); r.commands.addAll(t.getStringList("commands"));
            r.message = t.getString("message", ""); r.actionBar = t.getString("actionbar", ""); r.item = t.getString("item", "");
            r.itemTemplate = readItem(t, "item-template"); if (r.itemTemplate != null) r.item = r.itemTemplate.getType().name();
            r.amount = t.getInt("amount", 1); r.radius = t.getDouble("radius", 0); result.add(r);
        }
        return result;
    }
    private ConfigurationSection required(ConfigurationSection s, String key) {
        ConfigurationSection child = s.getConfigurationSection(key);
        if (child == null) throw new IllegalArgumentException(Language.text("errors.missing-section-format", s.getCurrentPath(), key));
        return child;
    }
    private org.bukkit.inventory.ItemStack readItem(ConfigurationSection s, String key) {
        if (!s.contains(key)) return null;
        org.bukkit.inventory.ItemStack item = s.getItemStack(key);
        if (item == null || item.getType().isAir() || item.getAmount() < 1) throw new IllegalArgumentException(Language.text("errors.invalid-item-format-2", s.getCurrentPath(), key));
        org.bukkit.inventory.ItemStack copy = item.clone(); copy.setAmount(1); return copy;
    }
    private void types(ConfigurationSection s, String strings, String numbers, String integers, String booleans, String stringLists, String integerLists) {
        for (String key : strings.split(" ")) if (!key.isEmpty() && s.contains(key) && !(s.get(key) instanceof String)) invalid(s, key);
        for (String key : numbers.split(" ")) if (!key.isEmpty() && s.contains(key) && !(s.get(key) instanceof Number)) invalid(s, key);
        for (String key : integers.split(" ")) if (!key.isEmpty() && s.contains(key)) { Object v = s.get(key); if (!(v instanceof Number) || !integer((Number) v)) invalid(s, key); }
        for (String key : booleans.split(" ")) if (!key.isEmpty() && s.contains(key) && !(s.get(key) instanceof Boolean)) invalid(s, key);
        for (String key : stringLists.split(" ")) if (!key.isEmpty() && s.contains(key)) {
            Object value = s.get(key); if (!(value instanceof List)) invalid(s, key);
            for (Object element : (List<?>) value) if (!(element instanceof String)) invalid(s, key);
        }
        for (String key : integerLists.split(" ")) if (!key.isEmpty() && s.contains(key)) {
            Object value = s.get(key); if (!(value instanceof List)) invalid(s, key);
            for (Object element : (List<?>) value) if (!(element instanceof Number) || !integer((Number) element)) invalid(s, key);
        }
    }
    private boolean integer(Number n) { double v = n.doubleValue(); return Double.isFinite(v) && v == Math.floor(v) && v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE; }
    private void invalid(ConfigurationSection s, String key) { throw new IllegalArgumentException(Language.text("errors.invalid-type-at-format", s.getCurrentPath(), key)); }
}
