package dev.sdeyy.nmines.service;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SelectionService implements Listener {
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final NamespacedKey key;
    private final Text text;
    public SelectionService(JavaPlugin plugin, Text text) { key = new NamespacedKey(plugin, "selection-wand"); this.text = text; }
    public void wand(Player player) {
        ItemStack item = new ItemStack(Material.GOLDEN_AXE); ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(Text.color(Language.text("selection.nmines-selection-wand")));
        meta.setLore(Text.lines(Language.text("selection.left-click-position-1"), Language.text("selection.right-click-position-2")));
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1); item.setItemMeta(meta);
        if (!player.getInventory().addItem(item).isEmpty()) throw new IllegalArgumentException(Language.text("selection.free-up-an-inventory-slot"));
    }
    public void set(Player p, int n, Location l) {
        Location[] points = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]); points[n] = l.clone();
        text.send(p, Language.text("selection.position-format", (n + 1), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
    }
    public Bounds bounds(Player p) {
        Location[] points = selections.get(p.getUniqueId());
        if (points == null || points[0] == null || points[1] == null) throw new IllegalArgumentException(Language.text("selection.select-two-corners-with-nm-wand-or-nm-pos1-and-nm-pos2"));
        if (!points[0].getWorld().equals(points[1].getWorld())) throw new IllegalArgumentException(Language.text("selection.both-positions-must-be-in-the-same-world"));
        Location a = points[0], b = points[1];
        return new Bounds(a.getWorld().getUID(), a.getBlockX(), a.getBlockY(), a.getBlockZ(), b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }
    public Location origin(Player p) {
        Location[] points = selections.get(p.getUniqueId());
        if (points == null || points[0] == null) throw new IllegalArgumentException(Language.text("selection.mark-the-origin-with-nm-pos1"));
        return points[0].clone();
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null || !e.getPlayer().hasPermission("nmines.admin")) return;
        ItemStack item = e.getItem();
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE)) return;
        if (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            e.setCancelled(true); set(e.getPlayer(), e.getAction() == Action.LEFT_CLICK_BLOCK ? 0 : 1, e.getClickedBlock().getLocation());
        }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { selections.remove(e.getPlayer().getUniqueId()); }
}
