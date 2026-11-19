package dev.sdeyy.nmines.command;

import dev.sdeyy.nmines.NMinesPlugin;
import dev.sdeyy.nmines.gui.MenuService;
import dev.sdeyy.nmines.integration.WorldEditBridge;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.RequiredTool;
import dev.sdeyy.nmines.model.Reward;
import dev.sdeyy.nmines.service.MineService;
import dev.sdeyy.nmines.service.MineValidator;
import dev.sdeyy.nmines.service.RewardService;
import dev.sdeyy.nmines.service.SelectionService;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

public final class MineCommand implements TabExecutor {
    private static final UUID CONSOLE = new UUID(0, 0);
    private static final List<String> COMMANDS = Arrays.asList("help", "list", "gui", "wand", "pos1", "pos2", "create", "region", "composition", "block", "drop", "reward", "tool", "reset", "recount", "rename", "delete", "name", "icon", "mode", "timer", "percent", "warning", "start", "stop", "tasks", "public", "protect", "pickup", "rotate", "evacuate", "settp", "setresettp", "tp", "info", "reload", "undo", "redo", "confirm", "cancel");
    private static final class Confirmation {
        final long expires = System.currentTimeMillis() + 15000;
        final Runnable action;
        Confirmation(Runnable action) { this.action = action; }
    }
    private final NMinesPlugin plugin; private final MineService mines; private final Text text;
    private final SelectionService selections; private final MenuService menus;
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();
    public MineCommand(NMinesPlugin plugin, MineService mines, Text text, SelectionService selections, MenuService menus) {
        this.plugin = plugin; this.mines = mines; this.text = text; this.selections = selections; this.menus = menus;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            String sub = args.length == 0 ? "gui" : args[0].toLowerCase(Locale.ROOT);
            if (!sender.hasPermission("nmines.use") && !sender.hasPermission("nmines.admin")) { text.message(sender, "no-permission"); return true; }
            if (sub.equals("help")) { help(sender); return true; }
            if (sub.equals("list")) { for (Mine m : mines.all()) if (!(sender instanceof Player) || mines.access((Player) sender, m)) text.send(sender, "&b" + m.id + " &8• &f" + m.displayName + " &8• &7" + String.format(Locale.ROOT, "%.1f%%", mines.percent(m))); return true; }
            if (sub.equals("gui")) { if (args.length > 1) menus.detail(player(sender), arg(args, 1)); else menus.list(player(sender), 0); return true; }
            if (sub.equals("tp")) { mines.teleport(player(sender), arg(args, 1)); return true; }
            if (sub.equals("cancel")) { plugin.prompts().cancel(player(sender)); confirmations.remove(actor(sender)); return true; }
            if (!sender.hasPermission("nmines.admin")) { text.message(sender, "no-permission"); return true; }
            switch (sub) {
                case "wand": selections.wand(player(sender)); break;
                case "pos1": selections.set(player(sender), 0, player(sender).getLocation()); break;
                case "pos2": selections.set(player(sender), 1, player(sender).getLocation()); break;
                case "create": create(sender, args); break;
                case "region": region(sender, args); break;
                case "composition": composition(sender, args); break;
                case "block": block(sender, args); break;
                case "drop": drop(sender, args); break;
                case "reward": reward(sender, args); break;
                case "tool": tool(sender, args); break;
                case "reset": {
                    String target = arg(args, 1);
                    if (target.equals("*")) {
                        List<String> ids = mines.all().stream().map(m -> m.id).collect(Collectors.toList());
                        confirm(sender, () -> { int count = 0; for (String id : ids) { try { mines.reset(id, null); count++; } catch (IllegalArgumentException ex) { text.send(sender, "&c" + id + ": " + ex.getMessage()); } } text.send(sender, Language.text("commands.resets-queued-format", count)); });
                    } else { String[] path = path(target); mines.reset(path[0], path.length > 1 ? path[1] : null); }
                    break;
                }
                case "delete": { String id = arg(args, 1); mines.get(id); confirm(sender, () -> edit(sender, map -> { if (map.remove(id) == null) throw new IllegalArgumentException(Language.text("menus.mine-not-found")); })); break; }
                case "rename": { String id = arg(args, 1), next = arg(args, 2); MineValidator.id(next); edit(sender, map -> { Mine m = MenuService.required(map, id); if (map.containsKey(next)) throw new IllegalArgumentException(Language.text("commands.the-destination-id-already-exists")); map.remove(id); m.id = next; map.put(next, m); }); break; }
                case "name": editMine(sender, args, m -> m.displayName = join(args, 2)); break;
                case "icon": editMine(sender, args, m -> { m.icon = MineValidator.item(arg(args, 2)).name(); m.iconItem = null; }); break;
                case "mode": editMine(sender, args, m -> m.mode = Mine.Mode.valueOf(arg(args, 2).toUpperCase(Locale.ROOT))); break;
                case "timer":
                    if (args.length == 2) menus.timerEditor(player(sender), arg(args, 1));
                    else editMine(sender, args, m -> { m.resetSeconds = Integer.parseInt(arg(args, 2)); m.warningSeconds = Math.min(m.warningSeconds, m.resetSeconds); });
                    break;
                case "percent": editMine(sender, args, m -> m.resetPercent = Double.parseDouble(arg(args, 2))); break;
                case "warning": editMine(sender, args, m -> { m.warningSeconds = Integer.parseInt(arg(args, 2)); m.warn = m.warningSeconds > 0; }); break;
                case "start": case "stop": {
                    boolean enabled = sub.equals("start");
                    if (arg(args, 1).equals("*")) confirm(sender, () -> edit(sender, map -> map.values().forEach(m -> m.enabled = enabled)));
                    else editMine(sender, args, m -> m.enabled = enabled);
                    break;
                }
                case "tasks": {
                    String op = arg(args, 1).toLowerCase(Locale.ROOT);
                    if (op.equals("cancel")) text.send(sender, Language.text("commands.jobs-cancelled", mines.cancel(arg(args, 2))));
                    else if (op.equals("retry")) { mines.retry(arg(args, 2)); text.send(sender, Language.text("commands.recount-queued")); }
                    else if (op.equals("save")) { mines.flushSaves(); text.send(sender, Language.text("commands.saves-complete")); }
                    else if (op.equals("start") || op.equals("stop")) {
                        mines.engine().pause(op.equals("stop")); text.send(sender, op.equals("stop") ? Language.text("commands.tasks-paused-use-nm-tasks-start-to-resume") : Language.text("commands.tasks-resumed"));
                    } else throw new IllegalArgumentException(Language.text("commands.usage-tasks-start-stop"));
                    break;
                }
                case "public": editMine(sender, args, m -> m.publicAccess = bool(arg(args, 2))); break;
                case "protect": editMine(sender, args, m -> m.protect = bool(arg(args, 2))); break;
                case "pickup": editMine(sender, args, m -> m.autoPickup = bool(arg(args, 2))); break;
                case "rotate": editMine(sender, args, m -> m.rotateRegions = bool(arg(args, 2))); break;
                case "evacuate": editMine(sender, args, m -> m.teleportOnReset = bool(arg(args, 2))); break;
                case "settp": { Mine.Warp warp = MineService.warp(player(sender).getLocation()); editMine(sender, args, m -> m.teleport = warp); break; }
                case "setresettp": { Mine.Warp warp = MineService.warp(player(sender).getLocation()); editMine(sender, args, m -> m.resetTeleport = warp); break; }
                case "info": info(sender, arg(args, 1)); break;
                case "recount": mines.recount(arg(args, 1)); text.send(sender, Language.text("commands.recount-queued")); break;
                case "reload": plugin.reload(); text.send(sender, Language.text("commands.configuration-language-and-mines-reloaded")); break;
                case "undo": mines.history(actor(sender), false); text.send(sender, Language.text("commands.definition-restored-world-blocks-were-not-changed")); break;
                case "redo": mines.history(actor(sender), true); text.send(sender, Language.text("commands.change-reapplied")); break;
                case "confirm": { Confirmation c = confirmations.remove(actor(sender)); if (c == null || c.expires < System.currentTimeMillis()) throw new IllegalArgumentException(Language.text("commands.there-is-no-active-confirmation")); c.action.run(); break; }
                default: throw new IllegalArgumentException(Language.text("commands.unknown-command-use-nm-help"));
            }
        } catch (IllegalArgumentException | IllegalStateException e) { text.error(sender, e); }
        catch (Exception e) { plugin.getLogger().log(java.util.logging.Level.SEVERE, Language.text("commands.error-executing-nm"), e); text.send(sender, Language.text("commands.could-not-complete-the-operation-check-the-console")); }
        return true;
    }
    private void create(CommandSender sender, String[] a) {
        String id = arg(a, 1); MineValidator.id(id); Player p = player(sender);
        Mine.Region region = a.length > 2 && a[2].equalsIgnoreCase("we") ? new WorldEditBridge(plugin).selection(p, "default", mines.validator().maxSchematic()) : MenuService.newRegion("default", selections.bounds(p));
        if (region.compositions.isEmpty()) region.compositions.put("default", MenuService.newComposition("default"));
        edit(sender, map -> { if (map.containsKey(id)) throw new IllegalArgumentException(Language.text("commands.a-mine-with-this-id-already-exists")); Mine mine = menus.newMine(id, region.bounds); mine.regions.put("default", region); map.put(id, mine); });
    }
    private void tool(CommandSender sender, String[] args) {
        String operation = arg(args, 1), id = arg(args, 2);
        if (operation.equals("set")) {
            RequiredTool tool = RequiredTool.fromItem(player(sender).getInventory().getItemInMainHand());
            editTarget(sender, id, map -> MenuService.required(map, id).requiredTool = tool); text.send(sender, Language.text("tools.configured", id)); return;
        }
        if (operation.equals("clear")) { editTarget(sender, id, map -> MenuService.required(map, id).requiredTool = null); return; }
        RequiredTool tool = mines.get(id).requiredTool;
        if (tool == null) throw new IllegalArgumentException(Language.text("tools.not-configured"));
        if (operation.equals("register")) {
            Player holder = player(sender); holder.getInventory().setItemInMainHand(tool.register(holder.getInventory().getItemInMainHand()));
            text.send(sender, Language.text("tools.registered")); return;
        }
        if (operation.equals("give")) {
            Player target = args.length > 3 ? Bukkit.getPlayerExact(args[3]) : player(sender);
            if (target == null) throw new IllegalArgumentException(Language.text("tools.player-not-online"));
            RewardService.give(target, tool.copy(), target.getLocation()); text.send(sender, Language.text("tools.given", target.getName())); return;
        }
        throw new IllegalArgumentException(Language.text("tools.usage"));
    }
    private void region(CommandSender sender, String[] a) {
        String op = arg(a, 1), target = arg(a, 2); String[] parts = path(target);
        if (parts.length != 2) throw new IllegalArgumentException(Language.text("commands.target-mine-region"));
        String id = parts[0], rid = parts[1]; MineValidator.id(rid);
        if (op.equals("add") || op.equals("redefine") || op.equals("schematic")) {
            Player p = player(sender); Mine.Region created;
            if (op.equals("schematic")) created = new WorldEditBridge(plugin).schematic(rid, arg(a, 3), selections.origin(p), mines.validator().maxSchematic());
            else if (a.length > 3 && a[3].equalsIgnoreCase("we")) created = new WorldEditBridge(plugin).selection(p, rid, mines.validator().maxSchematic());
            else created = MenuService.newRegion(rid, selections.bounds(p));
            editTarget(sender, id, map -> {
                Mine m = MenuService.required(map, id);
                if (op.equals("add") || op.equals("schematic")) { if (m.regions.containsKey(rid)) throw new IllegalArgumentException(Language.text("menus.a-region-with-this-id-already-exists")); if (created.compositions.isEmpty()) created.compositions.put("default", MenuService.newComposition("default")); m.regions.put(rid, created); }
                else { Mine.Region old = MenuService.region(m, rid); old.bounds = created.bounds; old.mask = created.mask; old.template = created.template; }
            });
        } else if (op.equals("remove")) editTarget(sender, id, map -> { if (MenuService.required(map, id).regions.remove(rid) == null) throw new IllegalArgumentException(Language.text("menus.region-not-found")); });
        else if (op.equals("weight")) editTarget(sender, id, map -> MenuService.region(MenuService.required(map, id), rid).weight = Double.parseDouble(arg(a, 3)));
        else throw new IllegalArgumentException(Language.text("commands.usage-region-add-redefine-remove-weight-schematic-mine-region-we"));
    }
    private void composition(CommandSender sender, String[] a) {
        String op = arg(a, 1); String[] p = path(arg(a, 2)); if (p.length != 3) throw new IllegalArgumentException(Language.text("commands.target-mine-region-composition")); MineValidator.id(p[2]);
        editTarget(sender, p[0], map -> {
            Mine.Region r = MenuService.region(MenuService.required(map, p[0]), p[1]);
            if (op.equals("add")) { if (r.compositions.containsKey(p[2])) throw new IllegalArgumentException(Language.text("menus.a-composition-with-this-id-already-exists")); r.compositions.put(p[2], MenuService.newComposition(p[2])); }
            else if (op.equals("remove")) { if (r.compositions.remove(p[2]) == null) throw new IllegalArgumentException(Language.text("menus.composition-not-found")); }
            else if (op.equals("weight")) MenuService.composition(MenuService.required(map, p[0]), p[1], p[2]).weight = Double.parseDouble(arg(a, 3));
            else throw new IllegalArgumentException(Language.text("commands.usage-composition-add-remove-weight-mine-region-composition-weigh"));
        });
    }
    private void block(CommandSender sender, String[] a) {
        String op = arg(a, 1); String[] p = path(arg(a, 2)); if (p.length != 3) throw new IllegalArgumentException(Language.text("commands.target-mine-region-composition"));
        editTarget(sender, p[0], map -> {
            Mine.Composition c = MenuService.composition(MenuService.required(map, p[0]), p[1], p[2]);
            if (op.equals("set")) { String data = Bukkit.createBlockData(arg(a, 3).toLowerCase(Locale.ROOT)).getAsString(); double weight = Double.parseDouble(arg(a, 4)); Mine.BlockEntry existing = c.blocks.stream().filter(b -> b.data.equals(data)).findFirst().orElse(null); if (existing == null) c.blocks.add(new Mine.BlockEntry(data, weight)); else existing.weight = weight; }
            else if (op.equals("remove")) { int index = index(c, arg(a, 3)); c.blocks.remove(index); }
            else throw new IllegalArgumentException(Language.text("commands.usage-block-set-mine-region-composition-block-weight-block-remove"));
        });
    }
    private void drop(CommandSender sender, String[] a) {
        String[] p = path(arg(a, 1)); if (p.length != 4) throw new IllegalArgumentException(Language.text("commands.usage-drop-mine-region-composition-index-material-vanilla-min-max"));
        editTarget(sender, p[0], map -> {
            Mine.Composition c = MenuService.composition(MenuService.required(map, p[0]), p[1], p[2]); Mine.BlockEntry b = c.blocks.get(index(c, p[3]));
            String item = arg(a, 2); b.customDrops = !item.equalsIgnoreCase("vanilla");
            if (b.customDrops) { b.drop = MineValidator.item(item).name(); b.dropItem = null; b.minAmount = Integer.parseInt(arg(a, 3)); b.maxAmount = Integer.parseInt(arg(a, 4)); b.fortune = a.length <= 5 || bool(a[5]); }
        });
    }
    private void reward(CommandSender sender, String[] a) {
        String op = arg(a, 1), target = arg(a, 2), rid = arg(a, 3); String id = path(target)[0]; MineValidator.id(rid);
        editTarget(sender, id, map -> {
            List<Reward> list = MenuService.rewardTarget(MenuService.required(map, id), target);
            Reward reward = list.stream().filter(r -> r.id.equals(rid)).findFirst().orElse(null);
            if (op.equals("remove")) { if (!list.remove(reward)) throw new IllegalArgumentException(Language.text("menus.reward-not-found")); return; }
            if (op.equals("add")) { if (reward != null) throw new IllegalArgumentException(Language.text("menus.this-id-already-exists")); reward = new Reward(); reward.id = rid; if (a.length > 4) reward.commands.add(join(a, 4)); list.add(reward); return; }
            if (reward == null) throw new IllegalArgumentException(Language.text("menus.reward-not-found"));
            String value = join(a, 4);
            switch (op) {
                case "command": reward.commands.clear(); if (!value.equals("-")) reward.commands.add(value); break;
                case "appendcommand": reward.commands.add(value); break;
                case "message": reward.message = value.equals("-") ? "" : value; break;
                case "actionbar": reward.actionBar = value.equals("-") ? "" : value; break;
                case "permission": reward.permission = value.equals("-") ? "" : value; break;
                case "group": reward.group = value.equals("-") ? "" : value; break;
                case "overrides": reward.overrides.clear(); if (!value.equals("-")) reward.overrides.addAll(Arrays.asList(value.split(","))); break;
                case "chance": reward.chance = Double.parseDouble(value); break;
                case "radius": reward.radius = Double.parseDouble(value); break;
                case "item":
                    if (arg(a, 4).equalsIgnoreCase("hand")) {
                        org.bukkit.inventory.ItemStack selected = player(sender).getInventory().getItemInMainHand();
                        if (selected.getType().isAir() || selected.getAmount() < 1) throw new IllegalArgumentException(Language.text("commands.hold-the-reward-item-in-your-main-hand"));
                        reward.itemTemplate = selected.clone(); reward.itemTemplate.setAmount(1); reward.item = selected.getType().name();
                    } else { reward.itemTemplate = null; reward.item = value.equals("-") ? "" : MineValidator.item(arg(a, 4)).name(); }
                    if (!reward.item.isEmpty()) reward.amount = a.length > 5 ? Integer.parseInt(a[5]) : 1;
                    break;
                default: throw new IllegalArgumentException(Language.text("commands.usage-reward-add-remove-command-appendcommand-message-actionbar-p"));
            }
        });
    }
    private int index(Mine.Composition c, String value) { int n = Integer.parseInt(value); if (n < 0 || n >= c.blocks.size()) throw new IllegalArgumentException(Language.text("commands.index-out-of-range")); return n; }
    private void editMine(CommandSender sender, String[] args, Consumer<Mine> action) { mines.editMine(actor(sender), arg(args, 1), action); text.message(sender, "saved"); }
    private void editTarget(CommandSender sender, String id, Consumer<Map<String, Mine>> action) {
        mines.editMine(actor(sender), id, mine -> action.accept(Collections.singletonMap(mine.id, mine))); text.message(sender, "saved");
    }
    private void edit(CommandSender sender, Consumer<Map<String, Mine>> action) { mines.edit(actor(sender), action); text.message(sender, "saved"); }
    private void confirm(CommandSender sender, Runnable action) {
        Confirmation confirmation = new Confirmation(action); UUID id = actor(sender); confirmations.put(id, confirmation);
        Bukkit.getScheduler().runTaskLater(plugin, () -> confirmations.remove(id, confirmation), 300);
        text.send(sender, Language.text("commands.type-nm-confirm-within-15-seconds-to-continue"));
    }
    public void clear() { confirmations.clear(); }
    private static String arg(String[] a, int i) { if (a.length <= i) throw new IllegalArgumentException(Language.text("commands.missing-arguments-use-nm-help")); return a[i]; }
    private static String join(String[] a, int i) { arg(a, i); return String.join(" ", Arrays.copyOfRange(a, i, a.length)); }
    private static String[] path(String value) { String[] parts = value.toLowerCase(Locale.ROOT).split(":", -1); if (parts.length > 4) throw new IllegalArgumentException(Language.text("commands.target-path-is-too-long")); return parts; }
    private static boolean bool(String s) { if (!s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) throw new IllegalArgumentException(Language.text("commands.expected-value-true-or-false")); return Boolean.parseBoolean(s); }
    private static Player player(CommandSender s) { if (!(s instanceof Player)) throw new IllegalArgumentException(Language.text("commands.this-command-requires-a-player")); return (Player) s; }
    private static UUID actor(CommandSender s) { return s instanceof Player ? ((Player) s).getUniqueId() : CONSOLE; }
    private void info(CommandSender s, String id) {
        Mine m = mines.get(id); text.send(s, "&b" + m.displayName + " &8(" + m.id + ")");
        text.send(s, Language.text("commands.status-format", (m.enabled ? Language.text("commands.active") : Language.text("commands.paused")), Language.text("modes." + m.mode.name().toLowerCase(Locale.ROOT)), Text.time(mines.seconds(m))));
        text.send(s, Language.text("commands.remaining-blocks-format", mines.remaining(m), mines.capacity(m), mines.engine().size()));
        for (Mine.Region r : m.regions.values()) { text.send(s, Language.text("commands.region-format", r.id, r.bounds.volume())); for (Mine.Composition c : r.compositions.values()) { text.send(s, Language.text("commands.composition-format", c.id, c.weight)); for (int i = 0; i < c.blocks.size(); i++) text.send(s, "    &8[" + i + "] &f" + c.blocks.get(i).data + " &8• &7" + c.blocks.get(i).weight); } }
    }
    private void help(CommandSender s) {
        text.send(s, Language.text("commands.nmines-command-guide"));
        text.send(s, Language.text("commands.nm-nm-list-nm-tp-mine"));
        if (!s.hasPermission("nmines.admin")) return;
        for (String line : Arrays.asList(Language.text("commands.wand-pos1-pos2"), Language.text("commands.create-id-we-gui-mine"), Language.text("commands.region-add-redefine-mine-region-we"), Language.text("commands.region-schematic-mine-region-file-schem"), Language.text("commands.region-remove-weight-mine-region-weight"), Language.text("commands.composition-add-remove-weight-mine-region-composition-weight"), Language.text("commands.block-set-mine-region-composition-block-weight"), Language.text("commands.block-remove-mine-region-composition-index"), Language.text("commands.drop-mine-region-composition-index-material-vanilla-min-max-fortu"), Language.text("commands.reward-add-remove-command-appendcommand-message-actionbar-item-ch"), Language.text("commands.mode-mine-time-percentage-both-manual"), Language.text("commands.timer-percent-warning-mine-value"), Language.text("commands.name-icon-rename-mine-value"), Language.text("commands.public-protect-pickup-rotate-evacuate-mine-true-false"), Language.text("commands.settp-setresettp-start-stop-info-recount-mine"), Language.text("commands.reset-mine-region-delete-mine-confirm"), Language.text("commands.undo-redo-reload-cancel-tasks-start-stop"))) text.send(s, "&f/nm " + line);
        text.send(s, Language.text("tools.help"));
    }
    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        if (!s.hasPermission("nmines.use") && !s.hasPermission("nmines.admin")) return Collections.emptyList();
        List<String> options = new ArrayList<>();
        if (a.length == 1) options.addAll(s.hasPermission("nmines.admin") ? COMMANDS : Arrays.asList("help", "list", "gui", "tp"));
        else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            boolean nested = Arrays.asList("region", "composition", "block", "reward", "tool").contains(sub);
            if (sub.equals("tasks") && s.hasPermission("nmines.admin")) {
                if (a.length == 2) options.addAll(Arrays.asList("start", "stop", "cancel", "retry", "save"));
                else if (a.length == 3 && (a[1].equalsIgnoreCase("cancel") || a[1].equalsIgnoreCase("retry"))) for (Mine mine : mines.all()) options.add(mine.id);
            } else if (nested && a.length == 2 && s.hasPermission("nmines.admin")) {
                if (sub.equals("tool")) options.addAll(Arrays.asList("set", "register", "give", "clear"));
                if (sub.equals("region")) options.addAll(Arrays.asList("add", "remove", "redefine", "weight", "schematic"));
                if (sub.equals("composition")) options.addAll(Arrays.asList("add", "remove", "weight"));
                if (sub.equals("block")) options.addAll(Arrays.asList("set", "remove"));
                if (sub.equals("reward")) options.addAll(Arrays.asList("add", "remove", "command", "appendcommand", "message", "actionbar", "item", "chance", "permission", "radius", "group", "overrides"));
            } else if (a.length == (nested ? 3 : 2)) {
                for (Mine m : mines.all()) if (!(s instanceof Player) || mines.access((Player) s, m)) {
                    options.add(m.id);
                    if (s.hasPermission("nmines.admin") && !sub.equals("tool")) for (Mine.Region r : m.regions.values()) { options.add(m.id + ":" + r.id); for (Mine.Composition c : r.compositions.values()) { options.add(m.id + ":" + r.id + ":" + c.id); if (sub.equals("reward") || sub.equals("drop")) for (int i = 0; i < c.blocks.size(); i++) options.add(m.id + ":" + r.id + ":" + c.id + ":" + i); } }
                }
            } else if (s.hasPermission("nmines.admin") && a.length == 3) {
                if (sub.equals("mode")) for (Mine.Mode mode : Mine.Mode.values()) options.add(mode.name());
                if (Arrays.asList("public", "protect", "pickup", "rotate", "evacuate").contains(sub)) options.addAll(Arrays.asList("true", "false"));
                if (sub.equals("create")) options.add("we");
            }
        }
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().limit(100).collect(Collectors.toList());
    }
}
