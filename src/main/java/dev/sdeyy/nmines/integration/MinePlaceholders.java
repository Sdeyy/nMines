package dev.sdeyy.nmines.integration;

import dev.sdeyy.nmines.service.MineService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class MinePlaceholders extends PlaceholderExpansion {
    private final MineService mines;
    public MinePlaceholders(MineService mines) { this.mines = mines; }
    public String getIdentifier() { return "nmines"; }
    public String getAuthor() { return "Sdeyy"; }
    public String getVersion() { return "1.0.0"; }
    public boolean persist() { return true; }
    public String onRequest(OfflinePlayer player, String params) {
        if (params.equals("count")) return String.valueOf(mines.placeholderCount());
        int split = params.indexOf('_'); if (split < 0) return null;
        String key = params.substring(0, split), id = params.substring(split + 1);
        return mines.placeholder(id, key);
    }
}
