package dev.sdeyy.nmines.util;

import java.lang.reflect.Method;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.inventory.Inventory;

/** InventoryView became an interface in newer Bukkit APIs. Reflect only this boundary. */
public final class InventoryAccess {
    private static final Method TOP;
    static {
        try { TOP = Class.forName("org.bukkit.inventory.InventoryView").getMethod("getTopInventory"); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    private InventoryAccess() { }
    public static Inventory top(HumanEntity player) { return topView(player.getOpenInventory()); }
    public static Inventory top(InventoryEvent event) { return topView(event.getView()); }
    private static Inventory topView(Object view) {
        try { return (Inventory) TOP.invoke(view); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(Language.text("errors.could-not-access-the-inventory"), e); }
    }
}
