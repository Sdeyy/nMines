package dev.sdeyy.nmines.service;

import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.Reward;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class RewardService {
    private final JavaPlugin plugin;
    private final Random random = new Random();
    public RewardService(JavaPlugin plugin) { this.plugin = plugin; }
    public void trigger(MineService.Ref ref, Mine.Composition composition, Mine.BlockEntry block, Player player, Location location) {
        List<Reward> gathered = new ArrayList<>();
        if (block != null) gathered.addAll(block.rewards);
        if (composition != null) gathered.addAll(composition.rewards);
        gathered.addAll(ref.region.rewards); gathered.addAll(ref.mine.rewards);
        Set<String> suppressed = new HashSet<>();
        for (Reward reward : gathered) {
            if (!reward.group.isEmpty() && suppressed.contains(reward.group)) continue;
            if ((!reward.permission.isEmpty() && !player.hasPermission(reward.permission)) || random.nextDouble() * 100 >= reward.chance) continue;
            suppressed.addAll(reward.overrides);
            List<Player> targets = new ArrayList<>();
            if (reward.radius == 0) targets.add(player);
            else for (Player p : location.getWorld().getPlayers()) if (p.getLocation().distanceSquared(location) <= reward.radius * reward.radius) targets.add(p);
            for (Player target : targets) {
                try {
                    if (!reward.message.isEmpty()) target.sendMessage(Text.color(expand(reward.message, target, player, ref)));
                    if (!reward.actionBar.isEmpty()) target.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                            net.md_5.bungee.api.chat.TextComponent.fromLegacyText(Text.color(expand(reward.actionBar, target, player, ref))));
                    ItemStack rewardItem = reward.itemStack();
                    if (rewardItem != null) give(target, rewardItem, target.getLocation());
                    for (String command : reward.commands) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), expand(command, target, player, ref).replaceFirst("^/", ""));
                } catch (RuntimeException e) { plugin.getLogger().log(java.util.logging.Level.WARNING, Language.text("errors.error-in-reward-format", reward.id), e); }
            }
        }
    }
    private String expand(String value, Player target, Player trigger, MineService.Ref ref) {
        return value.replace("{player}", target.getName()).replace("{uuid}", target.getUniqueId().toString())
                .replace("{trigger}", trigger.getName()).replace("{mine}", ref.mine.id).replace("{region}", ref.region.id);
    }
    public static void give(Player player, ItemStack stack, Location fallback) {
        int remaining = stack.getAmount();
        while (remaining > 0) {
            ItemStack part = stack.clone(); part.setAmount(Math.min(remaining, part.getMaxStackSize())); remaining -= part.getAmount();
            for (ItemStack overflow : player.getInventory().addItem(part).values()) fallback.getWorld().dropItemNaturally(fallback, overflow);
        }
    }
    public static void drop(ItemStack stack, Location location) {
        int remaining = stack.getAmount();
        while (remaining > 0) {
            ItemStack part = stack.clone(); part.setAmount(Math.min(remaining, part.getMaxStackSize()));
            remaining -= part.getAmount(); location.getWorld().dropItemNaturally(location, part);
        }
    }
}
