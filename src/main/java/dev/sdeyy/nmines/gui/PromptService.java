package dev.sdeyy.nmines.gui;

import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class PromptService implements Listener {
    private static final class Prompt {
        final long expires = System.currentTimeMillis() + 60000;
        final Consumer<String> action; final Runnable back;
        final long generation;
        Prompt(Consumer<String> action, Runnable back, long generation) { this.action = action; this.back = back; this.generation = generation; }
    }
    private final Map<UUID, Prompt> pending = new ConcurrentHashMap<>();
    private final JavaPlugin plugin; private final Text text;
    private volatile long generation;
    public PromptService(JavaPlugin plugin, Text text) { this.plugin = plugin; this.text = text; }
    public void ask(Player p, Consumer<String> action, Runnable back) {
        Prompt prompt = new Prompt(action, back, generation); pending.put(p.getUniqueId(), prompt);
        p.closeInventory(); text.message(p, "prompt");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pending.remove(p.getUniqueId(), prompt) && p.isOnline()) { text.message(p, "prompt-cancelled"); returnTo(p, back); }
        }, 1200);
    }
    public void cancel(Player p) { Prompt prompt = pending.remove(p.getUniqueId()); if (prompt != null) { text.message(p, "prompt-cancelled"); returnTo(p, prompt.back); } }
    public void clear() { generation++; pending.clear(); }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void chat(AsyncPlayerChatEvent e) {
        Prompt prompt = pending.remove(e.getPlayer().getUniqueId());
        if (prompt == null) return;
        e.setCancelled(true); String value = e.getMessage().trim(); Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline() || prompt.generation != generation) return;
            try {
                if (!p.hasPermission("nmines.admin")) { text.message(p, "no-permission"); return; }
                if (prompt.expires < System.currentTimeMillis() || value.equalsIgnoreCase(Language.text("input.cancel-word").trim())) { text.message(p, "prompt-cancelled"); return; }
                if (value.length() > 512) throw new IllegalArgumentException(Language.text("errors.maximum-512-characters"));
                prompt.action.accept(value);
            } catch (IllegalArgumentException ex) { text.error(p, ex); }
            finally { returnTo(p, prompt.back); }
        });
    }
    private void returnTo(Player p, Runnable back) {
        if (!p.isOnline()) return;
        try { back.run(); }
        catch (IllegalArgumentException | IllegalStateException e) { p.closeInventory(); text.send(p, Language.text("errors.the-target-changed-open-nm-again")); }
    }
    @EventHandler public void quit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }
}
