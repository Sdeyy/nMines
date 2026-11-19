package dev.sdeyy.nmines.model;

import dev.sdeyy.nmines.util.Language;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** A shared tool-family identifier, not a unique serial number per physical item. */
public final class RequiredTool {
    public static final NamespacedKey KEY = Objects.requireNonNull(NamespacedKey.fromString("nmines:tool-id"));
    public final String id;
    public final ItemStack item;
    public RequiredTool(String id, ItemStack item) { this.id = id; this.item = item.clone(); }

    public static RequiredTool fromItem(ItemStack selected) {
        if (selected == null || selected.getAmount() < 1 || selected.getType().isAir() || !selected.getType().isItem()) throw new IllegalArgumentException(Language.text("tools.empty-hand"));
        ItemStack template = selected.clone(); template.setAmount(1);
        ItemMeta meta = template.getItemMeta();
        String identifier = meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (!validId(identifier)) identifier = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, identifier); template.setItemMeta(meta);
        return new RequiredTool(identifier, template);
    }
    public static boolean validId(String value) {
        if (value == null) return false;
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException e) { return false; }
    }
    /** Hot path: material and one PDC lookup. Never compare lore, enchants or serialized items. */
    public boolean matches(ItemStack candidate) {
        if (candidate == null || candidate.getAmount() < 1 || candidate.getType() != item.getType() || !candidate.hasItemMeta()) return false;
        ItemMeta meta = candidate.getItemMeta();
        return meta != null && id.equals(meta.getPersistentDataContainer().get(KEY, PersistentDataType.STRING));
    }
    public ItemStack copy() { return item.clone(); }

    /** Expensive identity comparison happens once, only when an administrator registers an existing item. */
    public ItemStack register(ItemStack candidate) {
        if (matches(candidate)) return candidate.clone();
        if (candidate == null || candidate.getAmount() < 1 || candidate.getType() != item.getType()
                || !comparable(candidate).isSimilar(comparable(item))) throw new IllegalArgumentException(Language.text("tools.registration-mismatch"));
        ItemStack registered = candidate.clone(); ItemMeta meta = registered.getItemMeta();
        meta.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id); registered.setItemMeta(meta);
        return registered;
    }
    private ItemStack comparable(ItemStack original) {
        ItemStack copy = original.clone(); copy.setAmount(1); ItemMeta meta = copy.getItemMeta();
        meta.getPersistentDataContainer().remove(KEY);
        if (meta instanceof Damageable) ((Damageable) meta).setDamage(0);
        copy.setItemMeta(meta); return copy;
    }
}
