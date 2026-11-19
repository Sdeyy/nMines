package dev.sdeyy.nmines.service;

import dev.sdeyy.nmines.util.Language;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

public final class Settings {
    private Settings() { }
    public static void validate(ConfigurationSection c) {
        range(c, "engine.blocks-per-tick", 3000, 1, 100000, true);
        range(c, "engine.milliseconds-per-tick", 3, 0.05, 40, false);
        range(c, "engine.chunk-loads-per-tick", 1, 1, 16, true);
        range(c, "limits.max-region-volume", 1000000, 1, 10000000, true);
        range(c, "limits.max-total-volume", 10000000, 1, 100000000, true);
        range(c, "limits.max-schematic-volume", 100000, 1, 1000000, true);
        double seconds = range(c, "defaults.reset-seconds", 300, 1, 31536000, true);
        range(c, "defaults.reset-percent", 30, 0.01, 99.99, false);
        range(c, "defaults.warning-seconds", 10, 0, seconds, true);
        for (String key : new String[]{"border", "accent", "highlight"}) if (c.contains("menus." + key)) {
            Material m = Material.matchMaterial(c.getString("menus." + key, ""));
            if (m == null || !m.isItem() || m.isAir()) throw new IllegalArgumentException(Language.text("errors.invalid-menu-material-format", key));
        }
    }
    private static double range(ConfigurationSection c, String key, double fallback, double min, double max, boolean integer) {
        Object raw = c.get(key, fallback);
        if (!(raw instanceof Number)) throw new IllegalArgumentException(Language.text("errors.must-be-a-number-format", key));
        double n = ((Number) raw).doubleValue();
        if (!Double.isFinite(n) || n < min || n > max || (integer && n != Math.floor(n))) throw new IllegalArgumentException(Language.text("errors.out-of-range-format", key, min, max));
        return n;
    }
}
