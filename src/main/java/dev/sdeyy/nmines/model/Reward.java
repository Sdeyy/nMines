package dev.sdeyy.nmines.model;

import java.util.ArrayList;
import java.util.List;

/** Persisted data only. Invalid conditions are rejected rather than silently dropped. */
public final class Reward {
    public String id = "reward";
    public double chance = 100;
    public String permission = "";
    public String group = "";
    public List<String> overrides = new ArrayList<>();
    public List<String> commands = new ArrayList<>();
    public String message = "";
    public String actionBar = "";
    public String item = "";
    public org.bukkit.inventory.ItemStack itemTemplate;
    public int amount = 1;
    public double radius = 0;
    public org.bukkit.inventory.ItemStack itemStack() {
        if (itemTemplate == null && item.isEmpty()) return null;
        org.bukkit.inventory.ItemStack result = itemTemplate == null
                ? new org.bukkit.inventory.ItemStack(org.bukkit.Material.matchMaterial(item)) : itemTemplate.clone();
        result.setAmount(amount); return result;
    }
}
