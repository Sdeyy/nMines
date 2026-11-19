package dev.sdeyy.nmines.util;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class Text {
    public Text(JavaPlugin plugin) { }
    public static String color(String value) { return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value); }
    public static java.util.List<String> lines(String... values) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String value : values) for (String line : value.split("\\r?\\n", -1)) lines.add(color(line));
        return lines;
    }
    public void send(CommandSender to, String value) {
        for (String line : value.split("\\r?\\n", -1)) to.sendMessage(color(Language.text("messages.prefix") + line));
    }
    public void message(CommandSender to, String key) { send(to, Language.text("messages." + key).replace("{cancel}", Language.text("input.cancel-word"))); }
    public void error(CommandSender to, Throwable error) { send(to, Language.text("messages.error").replace("{error}", Language.error(error))); }
    public String format(String key, String mine, int seconds) {
        return Language.text("messages." + key).replace("{mine}", mine).replace("{seconds}", String.valueOf(seconds));
    }
    public static String time(long seconds) { return String.format("%02d:%02d", Math.max(0, seconds) / 60, Math.max(0, seconds) % 60); }
}
