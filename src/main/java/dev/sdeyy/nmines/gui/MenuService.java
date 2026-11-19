package dev.sdeyy.nmines.gui;

import dev.sdeyy.nmines.model.Bounds;
import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.model.RequiredTool;
import dev.sdeyy.nmines.model.Reward;
import dev.sdeyy.nmines.service.MineService;
import dev.sdeyy.nmines.service.MineValidator;
import dev.sdeyy.nmines.service.RewardService;
import dev.sdeyy.nmines.service.SelectionService;
import dev.sdeyy.nmines.util.InventoryAccess;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MenuService implements Listener {
    private static final int[] CONTENT = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    private static final class ButtonSpec {
        final Material material;
        final String name;
        final String[] lore;
        ButtonSpec(Material material, String name, String[] lore) { this.material = material; this.name = name; this.lore = lore.clone(); }
        boolean matches(Material material, String name, String[] lore) { return this.material == material && this.name.equals(name) && Arrays.equals(this.lore, lore); }
    }
    private static final class View implements InventoryHolder {
        final UUID owner; final boolean admin; final Inventory inventory;
        final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
        final ItemStack[] background = new ItemStack[54], shown = new ItemStack[54], icons = new ItemStack[54];
        final ButtonSpec[] labels = new ButtonSpec[54];
        ItemStack[] next;
        Consumer<View> render;
        View(Player p, String title, boolean admin) { owner = p.getUniqueId(); this.admin = admin; inventory = Bukkit.createInventory(this, 54, Text.color(title)); }
        public Inventory getInventory() { return inventory; }
    }
    private final JavaPlugin plugin; private final MineService mines; private final Text text;
    private final PromptService prompts; private final SelectionService selections;
    private BukkitTask refresh;
    public MenuService(JavaPlugin plugin, MineService mines, Text text, PromptService prompts, SelectionService selections) {
        this.plugin = plugin; this.mines = mines; this.text = text; this.prompts = prompts; this.selections = selections;
    }
    public void start() {
        refresh = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                InventoryHolder holder = InventoryAccess.top(p).getHolder();
                if (holder instanceof View) {
                    View v = (View) holder;
                    if (!p.hasPermission(v.admin ? "nmines.admin" : "nmines.use")) { p.closeInventory(); continue; }
                    try { draw(v); } catch (IllegalArgumentException e) { p.closeInventory(); text.send(p, Language.text("menus.the-menu-changed-open-it-again")); }
                }
            }
        }, 20, 20);
    }
    public void stop() { if (refresh != null) refresh.cancel(); for (Player p : Bukkit.getOnlinePlayers()) if (InventoryAccess.top(p).getHolder() instanceof View) p.closeInventory(); }
    private void open(Player p, String title, boolean admin, Consumer<View> renderer) {
        if (!p.hasPermission(admin ? "nmines.admin" : "nmines.use")) throw new IllegalArgumentException(Language.text("menus.you-do-not-have-permission"));
        View view = new View(p, title, admin); view.render = renderer; draw(view); p.openInventory(view.inventory);
    }
    private void draw(View v) {
        v.actions.clear();
        if (v.background[0] == null) {
        Material border = material("menus.border", Material.BLACK_STAINED_GLASS_PANE);
        Material accent = material("menus.accent", Material.CYAN_STAINED_GLASS_PANE);
        Material highlight = material("menus.highlight", Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemStack borderItem = item(border, " "), accentItem = item(accent, Language.text("menus.nmines")), highlightItem = item(highlight, " ");
        Arrays.fill(v.background, borderItem);
        for (int i : new int[]{0,8,9,17,36,44,45,53}) v.background[i] = accentItem;
        for (int i : new int[]{3,4,5,48,50}) v.background[i] = highlightItem;
        }
        v.next = v.background.clone();
        v.render.accept(v);
        for (int i = 0; i < v.next.length; i++) if (v.next[i] != v.shown[i] && !v.next[i].equals(v.shown[i])) {
            v.inventory.setItem(i, v.next[i]); v.shown[i] = v.next[i];
        }
    }
    private Material material(String key, Material fallback) { Material m = Material.matchMaterial(plugin.getConfig().getString(key, fallback.name())); return m == null || !m.isItem() || m.isAir() ? fallback : m; }
    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta(); meta.setDisplayName(Text.color(name));
        meta.setLore(Text.lines(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS); stack.setItemMeta(meta); return stack;
    }
    private void button(View v, int slot, Material material, String name, Consumer<InventoryClickEvent> action, String... lore) {
        if (v.labels[slot] == null || !v.labels[slot].matches(material, name, lore)) {
            v.labels[slot] = new ButtonSpec(material, name, lore); v.icons[slot] = item(material, name, lore);
        }
        v.next[slot] = v.icons[slot];
        if (action != null) v.actions.put(slot, action);
    }
    private void templateIcon(View v, int slot, ItemStack template) {
        if (template == null) return;
        ItemStack presentation = v.next[slot], icon = template.clone(); icon.setAmount(1);
        ItemMeta meta = icon.getItemMeta(), label = presentation.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add(""); lore.add(label.getDisplayName()); if (label.hasLore()) lore.addAll(label.getLore());
        meta.setLore(lore); icon.setItemMeta(meta); v.next[slot] = icon;
    }
    private void back(View v, Runnable action) { button(v, 45, Material.ARROW, Language.text("menus.back"), e -> action.run(), Language.text("menus.return-to-the-previous-menu")); }
    private void page(View v, int current, int total, IntConsumer go) {
        if (current > 0) button(v, 48, Material.ARROW, Language.text("menus.previous"), e -> go.accept(current - 1));
        button(v, 49, Material.PAPER, Language.text("menus.page-format", (current + 1), Math.max(1, total)), null);
        if (current + 1 < total) button(v, 50, Material.ARROW, Language.text("menus.next"), e -> go.accept(current + 1));
    }
    public void list(Player p, int requested) {
        open(p, Language.text("titles.mines"), false, v -> {
            List<Mine> visible = mines.all().stream().filter(m -> mines.access(p, m)).sorted(Comparator.comparing(m -> m.id)).collect(Collectors.toList());
            int pages = Math.max(1, (visible.size() + CONTENT.length - 1) / CONTENT.length), pg = Math.min(Math.max(0, requested), pages - 1);
            button(v, 4, Material.NETHER_STAR, Language.text("menus.mines-format", visible.size()), null, Language.text("menus.choose-where-you-want-to-mine"), Language.text("menus.updates-in-real-time"));
            for (int i = 0; i < CONTENT.length && pg * CONTENT.length + i < visible.size(); i++) {
                Mine m = visible.get(pg * CONTENT.length + i);
                button(v, CONTENT[i], MineValidator.item(m.icon), "&b" + m.displayName, e -> { if (e.isRightClick() && p.hasPermission("nmines.admin")) detail(p, m.id); else mines.teleport(p, m.id); },
                        "&8" + m.id, "", Language.text("menus.status-format", status(m)), Language.text("menus.blocks-format", mines.remaining(m), mines.capacity(m)),
                        Language.text("menus.remaining-format", bar(mines.percent(m))), Language.text("menus.reset-format", Text.time(mines.seconds(m))), "", Language.text("menus.click-teleport"), p.hasPermission("nmines.admin") ? Language.text("menus.right-click-manage") : Language.text("menus.happy-mining"));
                templateIcon(v, CONTENT[i], m.iconItem);
            }
            if (visible.isEmpty()) button(v, 22, Material.BARRIER, Language.text("menus.there-are-no-mines-yet"), null, p.hasPermission("nmines.admin") ? Language.text("menus.use-the-create-button-to-get-started") : Language.text("menus.come-back-later"));
            page(v, pg, pages, next -> list(p, next));
            if (p.hasPermission("nmines.admin")) button(v, 47, Material.EMERALD, Language.text("menus.create-mine"), e -> create(p), Language.text("menus.select-two-corners-first"), Language.text("menus.enter-the-id-in-chat"));
            button(v, 53, Material.BARRIER, Language.text("menus.close"), e -> p.closeInventory());
        });
    }
    private String status(Mine m) { return mines.engine().busy(m.id) ? Language.text("menus.processing-format", String.format(Locale.ROOT, "%.0f%%", mines.engine().progress(m.id))) : !mines.ready(m) ? Language.text("menus.pending") : m.enabled ? Language.text("menus.active") : Language.text("menus.paused"); }
    private String bar(double percent) { StringBuilder b = new StringBuilder("&b"); for (int i = 0; i < 10; i++) { if (i == (int) (percent / 10)) b.append("&8"); b.append('▌'); } return b + " &f" + String.format(Locale.ROOT, "%.1f%%", percent); }
    private void create(Player p) {
        Bounds bounds = selections.bounds(p);
        prompts.ask(p, value -> {
            String id = value.toLowerCase(Locale.ROOT); MineValidator.id(id);
            mines.edit(p.getUniqueId(), map -> {
                if (map.containsKey(id)) throw new IllegalArgumentException(Language.text("menus.this-id-already-exists-2"));
                Mine m = newMine(id, bounds); map.put(id, m);
            }); text.message(p, "saved");
        }, () -> list(p, 0));
    }
    public Mine newMine(String id, Bounds bounds) {
        Mine m = new Mine(id); m.resetSeconds = plugin.getConfig().getInt("defaults.reset-seconds", 300);
        m.resetPercent = plugin.getConfig().getDouble("defaults.reset-percent", 30); m.warningSeconds = plugin.getConfig().getInt("defaults.warning-seconds", 10);
        m.protect = plugin.getConfig().getBoolean("defaults.protect", true);
        m.regions.put("default", newRegion("default", bounds)); return m;
    }
    public static Mine.Region newRegion(String id, Bounds bounds) { Mine.Region r = new Mine.Region(id, bounds); r.compositions.put("default", newComposition("default")); return r; }
    public static Mine.Composition newComposition(String id) { Mine.Composition c = new Mine.Composition(id); c.blocks.add(new Mine.BlockEntry("minecraft:stone", 100)); return c; }
    public void detail(Player p, String id) {
        open(p, Language.text("menus.manage-format", id), true, v -> {
            Mine m = mines.get(id);
            button(v, 4, MineValidator.item(m.icon), "&b" + m.displayName, null, Language.text("menus.status-format", status(m)), Language.text("menus.remaining-format", bar(mines.percent(m))), Language.text("menus.regions-format", m.regions.size()));
            templateIcon(v, 4, m.iconItem);
            button(v, 10, Material.DIAMOND_PICKAXE, Language.text("menus.regions-and-blocks"), e -> regions(p, id, 0), Language.text("menus.edit-geometry-compositions-and-block-mixtures"));
            button(v, 12, Material.CLOCK, Language.text("menus.interval-format", m.resetSeconds), e -> timerEditor(p, id), Language.text("timer-menu.open"));
            button(v, 14, Material.COMPARATOR, Language.text("menus.threshold-format", m.resetPercent), e -> promptMine(p, id, Language.text("labels.percentage"), (mine, value) -> mine.resetPercent = Double.parseDouble(value)), Language.text("menus.reset-when-this-percentage-remains"));
            button(v, 16, Material.REPEATER, Language.text("menus.mode-format", Language.text("modes." + m.mode.name().toLowerCase(Locale.ROOT))), e -> edit(p, id, mine -> mine.mode = Mine.Mode.values()[(mine.mode.ordinal() + 1) % Mine.Mode.values().length]), Language.text("menus.time-percentage-both-manual"));
            button(v, 19, Material.ENDER_PEARL, Language.text("menus.set-teleport"), e -> edit(p, id, mine -> mine.teleport = MineService.warp(p.getLocation())), Language.text("menus.use-your-current-location"), Language.text("menus.the-destination-needs-a-floor-and-clear-space"));
            button(v, 21, Material.ENDER_EYE, Language.text("menus.set-reset-exit"), e -> edit(p, id, mine -> mine.resetTeleport = MineService.warp(p.getLocation())), Language.text("menus.safe-exit-outside-all-mines"));
            button(v, 23, Material.NAME_TAG, Language.text("menus.name-and-icon"), e -> { if (e.isRightClick()) inventoryPicker(p, Language.text("menus.choose-icon"), () -> mines.get(id).iconItem, false,
                    selected -> edit(p, id, mine -> { mine.iconItem = selected.clone(); mine.icon = selected.getType().name(); }), () -> detail(p, id), null);
                else promptMine(p, id, Language.text("labels.name"), (mine, value) -> mine.displayName = value); }, Language.text("menus.click-name-supports-colors"), Language.text("menus.right-click-choose-an-inventory-icon"));
            button(v, 25, Material.CHEST, Language.text("menus.rewards-format", m.rewards.size()), e -> rewardMenu(p, id, 0), Language.text("menus.rewards-for-breaking-blocks"), Language.text("menus.commands-items-and-conditions"));
            button(v, 28, m.enabled ? Material.LIME_DYE : Material.GRAY_DYE, m.enabled ? Language.text("menus.mine-enabled") : Language.text("menus.mine-paused"), e -> edit(p, id, mine -> mine.enabled = !mine.enabled), Language.text("menus.click-to-toggle-automatic-resets"));
            button(v, 29, Material.IRON_PICKAXE, Language.text("tools.menu-button"), e -> toolEditor(p, id),
                    Language.text(m.requiredTool == null ? "tools.unrestricted" : "tools.restricted"));
            button(v, 30, Material.SHIELD, Language.text("menus.protection-format", yes(m.protect)), e -> edit(p, id, mine -> mine.protect = !mine.protect), Language.text("menus.prevent-building-and-external-block-changes"));
            button(v, 32, Material.HOPPER, Language.text("menus.auto-pickup-format", yes(m.autoPickup)), e -> edit(p, id, mine -> mine.autoPickup = !mine.autoPickup), Language.text("menus.send-drops-to-the-inventory"), Language.text("menus.overflow-items-drop-on-the-ground"));
            button(v, 34, Material.OAK_DOOR, Language.text("menus.public-access-format", yes(m.publicAccess)), e -> edit(p, id, mine -> mine.publicAccess = !mine.publicAccess), Language.text("menus.private-access-nmines-mine-format", id));
            button(v, 38, Material.BELL, Language.text("menus.warnings-format", yes(m.warn)), e -> { if (e.isRightClick()) promptMine(p, id, Language.text("labels.warning"), (mine, value) -> mine.warningSeconds = Integer.parseInt(value)); else edit(p, id, mine -> mine.warn = !mine.warn); }, Language.text("menus.click-to-enable-or-disable"), Language.text("menus.right-click-warning-time-in-seconds"));
            button(v, 37, Material.SADDLE, Language.text("menus.evacuate-players-format", yes(m.teleportOnReset)), e -> edit(p, id, mine -> mine.teleportOnReset = !mine.teleportOnReset), Language.text("menus.when-disabled-resets-wait"), Language.text("menus.until-all-players-leave-the-region"));
            button(v, 40, Material.ENDER_CHEST, Language.text("menus.rotate-regions-format", yes(m.rotateRegions)), e -> edit(p, id, mine -> mine.rotateRegions = !mine.rotateRegions), Language.text("menus.yes-choose-one-region-by-weight-per-reset"), Language.text("menus.no-regenerate-all-regions"));
            button(v, 42, Material.TNT, Language.text("menus.regenerate-now"), e -> confirm(p, Language.text("menus.regenerate-format", id), () -> mines.reset(id, null), () -> detail(p, id)), Language.text("menus.regeneration-is-spread-across-ticks"), Language.text("menus.evacuate-players-before-starting"));
            button(v, 39, Material.ORANGE_DYE, Language.text("menus.cancel-jobs"), e -> confirm(p, Language.text("menus.cancel-jobs"), () -> mines.cancel(id), () -> detail(p, id)), Language.text("menus.cancel-jobs-lore"));
            button(v, 41, Material.LIME_DYE, Language.text("menus.retry-jobs"), e -> mines.retry(id), Language.text("menus.retry-jobs-lore"));
            back(v, () -> list(p, 0));
            button(v, 47, Material.REDSTONE, Language.text("menus.delete-mine"), e -> confirm(p, Language.text("menus.delete-format", id), () -> mines.edit(p.getUniqueId(), map -> map.remove(id)), () -> list(p, 0)), Language.text("menus.restore-the-definition-with-nm-undo"), Language.text("menus.world-blocks-are-kept"));
            button(v, 49, Material.BOOK, Language.text("menus.help"), e -> { p.closeInventory(); p.performCommand("nm help"); }, Language.text("menus.view-all-available-commands"));
            button(v, 51, Material.COMPASS, Language.text("menus.visit"), e -> mines.teleport(p, id));
        });
    }
    private String yes(boolean b) { return b ? Language.text("menus.yes") : Language.text("menus.no"); }
    public void toolEditor(Player p, String id) {
        open(p, Language.text("tools.title", id), true, v -> {
            Mine mine = mines.get(id); RequiredTool required = mine.requiredTool;
            button(v, 4, Material.IRON_PICKAXE, Language.text("tools.menu-button"), null,
                    Language.text(required == null ? "tools.unrestricted" : "tools.restricted"), Language.text("tools.identity-lore"));
            button(v, 12, Material.EMERALD, Language.text("tools.choose"), e -> inventoryPicker(p, Language.text("tools.choose"),
                    () -> mines.get(id).requiredTool == null ? null : mines.get(id).requiredTool.copy(), false,
                    selected -> { edit(p, id, m -> m.requiredTool = RequiredTool.fromItem(selected)); text.send(p, Language.text("tools.configured", id)); },
                    () -> toolEditor(p, id), null), Language.text("tools.choose-lore"), Language.text("tools.original-lore"));
            if (required != null) {
                v.next[22] = preview(required.copy());
                button(v, 14, Material.TRIPWIRE_HOOK, Language.text("tools.register"), e -> {
                    RequiredTool current = mines.get(id).requiredTool;
                    if (current == null) throw new IllegalArgumentException(Language.text("tools.not-configured"));
                    p.getInventory().setItemInMainHand(current.register(p.getInventory().getItemInMainHand()));
                    text.send(p, Language.text("tools.registered"));
                }, Language.text("tools.register-lore"));
                button(v, 16, Material.CHEST, Language.text("tools.give"), e -> {
                    RequiredTool current = mines.get(id).requiredTool;
                    if (current == null) throw new IllegalArgumentException(Language.text("tools.not-configured"));
                    RewardService.give(p, current.copy(), p.getLocation()); text.send(p, Language.text("tools.given", p.getName()));
                }, Language.text("tools.give-lore"));
                button(v, 31, Material.BARRIER, Language.text("tools.clear"), e -> edit(p, id, m -> m.requiredTool = null), Language.text("tools.clear-lore"));
            }
            back(v, () -> detail(p, id));
        });
    }
    public void timerEditor(Player p, String id) {
        final int initial = mines.get(id).resetSeconds;
        final int[] draft = { initial };
        open(p, Language.text("timer-menu.title", id), true, v -> {
            Mine current = mines.get(id);
            button(v, 4, Material.CLOCK, Language.text("timer-menu.heading"), null, Language.text("timer-menu.instructions"));
            int[] amounts = {1, 5, 10, 30}, addSlots = {10, 12, 14, 16}, removeSlots = {28, 30, 32, 34};
            for (int i = 0; i < amounts.length; i++) {
                final int step = amounts[i];
                boolean canAdd = (long) draft[0] + step <= 31536000;
                boolean canRemove = draft[0] - step >= 1;
                button(v, addSlots[i], canAdd ? Material.LIME_DYE : Material.GRAY_DYE,
                        Language.text(step == 1 ? "timer-menu.add-one" : "timer-menu.add-many", step),
                        e -> adjustTime(p, draft, step), Language.text(canAdd ? "timer-menu.add-lore" : "timer-menu.maximum", step));
                button(v, removeSlots[i], canRemove ? Material.RED_DYE : Material.GRAY_DYE,
                        Language.text(step == 1 ? "timer-menu.remove-one" : "timer-menu.remove-many", step),
                        e -> adjustTime(p, draft, -step), Language.text(canRemove ? "timer-menu.remove-lore" : "timer-menu.minimum", step));
            }
            button(v, 22, Material.CLOCK, Language.text("timer-menu.current", draft[0]), null,
                    Language.text("timer-menu.formatted", Text.time(draft[0])), Language.text("timer-menu.saved", current.resetSeconds),
                    Language.text("timer-menu.warning", Math.min(current.warningSeconds, draft[0])));
            button(v, 49, Material.LIME_CONCRETE, Language.text("timer-menu.save"), e -> {
                edit(p, id, mine -> {
                    if (mine.resetSeconds != initial) throw new IllegalArgumentException(Language.text("timer-menu.conflict"));
                    mine.resetSeconds = draft[0]; mine.warningSeconds = Math.min(mine.warningSeconds, mine.resetSeconds);
                }); detail(p, id);
            }, Language.text("timer-menu.save-lore"));
            back(v, () -> detail(p, id));
            button(v, 53, Material.BARRIER, Language.text("timer-menu.cancel"), e -> detail(p, id), Language.text("timer-menu.cancel-lore"));
        });
    }
    private void adjustTime(Player p, int[] draft, int delta) {
        long changed = (long) draft[0] + delta;
        if (changed < 1) { text.send(p, Language.text("timer-menu.minimum")); return; }
        if (changed > 31536000) { text.send(p, Language.text("timer-menu.maximum")); return; }
        draft[0] = (int) changed;
    }
    private void promptMine(Player p, String id, String label, BiConsumer<Mine, String> action) {
        text.send(p, Language.text("menus.new-value-for-format", label));
        prompts.ask(p, value -> mines.edit(p.getUniqueId(), map -> action.accept(required(map, id), value)), () -> detail(p, id));
    }
    private void edit(Player p, String id, Consumer<Mine> action) { mines.editMine(p.getUniqueId(), id, action); text.message(p, "saved"); }
    public static Mine required(Map<String, Mine> map, String id) { Mine m = map.get(id.toLowerCase(Locale.ROOT)); if (m == null) throw new IllegalArgumentException(Language.text("menus.mine-not-found")); return m; }
    public void regions(Player p, String id, int requested) {
        open(p, Language.text("menus.regions-2-format", id), true, v -> {
            Mine m = mines.get(id); List<Mine.Region> entries = new ArrayList<>(m.regions.values()); int pg = Math.min(requested, (entries.size() - 1) / CONTENT.length);
            for (int i = 0; i < CONTENT.length && pg * CONTENT.length + i < entries.size(); i++) {
                Mine.Region r = entries.get(pg * CONTENT.length + i); Bounds b = r.bounds;
                button(v, CONTENT[i], r.template.isEmpty() ? Material.GRASS_BLOCK : Material.STRUCTURE_BLOCK, "&b" + r.id, e -> {
                    if (e.isShiftClick() && e.isRightClick()) confirm(p, Language.text("menus.delete-region"), () -> edit(p, id, mine -> mine.regions.remove(r.id)), () -> regions(p, id, 0));
                    else if (e.isShiftClick()) rewardMenu(p, id + ":" + r.id, 0);
                    else if (e.isRightClick()) prompts.ask(p, value -> edit(p, id, mine -> mine.regions.get(r.id).weight = Double.parseDouble(value)), () -> regions(p, id, pg));
                    else compositions(p, id, r.id, 0);
                }, Language.text("menus.volume-format", b.volume()), Language.text("menus.height-format", b.minY, b.maxY), Language.text("menus.rotation-weight-format", r.weight),
                        Language.text("menus.compositions-format", r.compositions.size()), "", Language.text("menus.click-compositions"), Language.text("menus.right-click-weight"), Language.text("menus.shift-click-rewards"), Language.text("menus.shift-right-click-delete"));
            }
            back(v, () -> detail(p, id)); page(v, pg, (entries.size() + CONTENT.length - 1) / CONTENT.length, n -> regions(p, id, n));
            button(v, 47, Material.EMERALD, Language.text("menus.add-selection"), e -> {
                Bounds b = selections.bounds(p);
                prompts.ask(p, value -> { MineValidator.id(value); edit(p, id, mine -> { if (mine.regions.containsKey(value)) throw new IllegalArgumentException(Language.text("menus.a-region-with-this-id-already-exists")); mine.regions.put(value, newRegion(value, b)); }); }, () -> regions(p, id, 0));
            }, Language.text("menus.mark-two-corners-with-nm-wand"));
        });
    }
    public void compositions(Player p, String id, String rid, int requested) {
        open(p, Language.text("menus.compositions-2-format", rid), true, v -> {
            Mine.Region r = region(mines.get(id), rid); List<Mine.Composition> entries = new ArrayList<>(r.compositions.values()); int pg = Math.min(requested, (entries.size() - 1) / CONTENT.length);
            for (int i = 0; i < CONTENT.length && pg * CONTENT.length + i < entries.size(); i++) {
                Mine.Composition c = entries.get(pg * CONTENT.length + i);
                button(v, CONTENT[i], Material.IRON_ORE, "&b" + c.id, e -> {
                    if (e.isShiftClick() && e.isRightClick()) confirm(p, Language.text("menus.delete-composition"), () -> edit(p, id, mine -> region(mine, rid).compositions.remove(c.id)), () -> compositions(p, id, rid, 0));
                    else if (e.isShiftClick()) rewardMenu(p, id + ":" + rid + ":" + c.id, 0);
                    else if (e.isRightClick()) prompts.ask(p, value -> edit(p, id, mine -> composition(mine, rid, c.id).weight = Double.parseDouble(value)), () -> compositions(p, id, rid, pg));
                    else blocks(p, id, rid, c.id, 0);
                }, Language.text("menus.blocks-format-2", c.blocks.size()), Language.text("menus.weight-format", c.weight), "", Language.text("menus.click-edit-mixture"), Language.text("menus.right-click-weight"), Language.text("menus.shift-click-rewards"), Language.text("menus.shift-right-click-delete"));
            }
            back(v, () -> regions(p, id, 0)); page(v, pg, (entries.size() + CONTENT.length - 1) / CONTENT.length, n -> compositions(p, id, rid, n));
            button(v, 47, Material.EMERALD, Language.text("menus.add-composition"), e -> prompts.ask(p, value -> { MineValidator.id(value); edit(p, id, mine -> { Mine.Region target = region(mine, rid); if (target.compositions.containsKey(value)) throw new IllegalArgumentException(Language.text("menus.a-composition-with-this-id-already-exists")); target.compositions.put(value, newComposition(value)); }); }, () -> compositions(p, id, rid, 0)));
        });
    }
    public void blocks(Player p, String id, String rid, String cid, int requested) {
        open(p, Language.text("menus.mixture-format", cid), true, v -> {
            Mine.Composition c = composition(mines.get(id), rid, cid); int pg = Math.min(requested, (c.blocks.size() - 1) / CONTENT.length);
            double total = c.blocks.stream().mapToDouble(b -> b.weight).sum();
            for (int i = 0; i < CONTENT.length && pg * CONTENT.length + i < c.blocks.size(); i++) {
                int index = pg * CONTENT.length + i; Mine.BlockEntry b = c.blocks.get(index); Material mat = Bukkit.createBlockData(b.data).getMaterial();
                button(v, CONTENT[i], mat.isItem() && !mat.isAir() ? mat : Material.PAPER, "&b" + b.data, e -> {
                    if (e.isShiftClick() && e.isRightClick()) { edit(p, id, mine -> composition(mine, rid, cid).blocks.remove(index)); }
                    else if (e.isRightClick()) rewardMenu(p, id + ":" + rid + ":" + cid + ":" + index, 0);
                    else blockEditor(p, id, rid, cid, index, b.data);
                }, Language.text("menus.index-format", index), Language.text("menus.weight-format", b.weight), Language.text("menus.actual-chance-format", String.format(Locale.ROOT, "%.2f%%", b.weight * 100 / total)), Language.text("menus.drops-format", (b.customDrops ? b.drop : Language.text("menus.vanilla"))), "", Language.text("menus.click-edit-block-and-drops"), Language.text("menus.right-click-rewards"), Language.text("menus.shift-right-click-delete"));
                templateIcon(v, CONTENT[i], b.placementItem);
            }
            back(v, () -> compositions(p, id, rid, 0)); page(v, pg, (c.blocks.size() + CONTENT.length - 1) / CONTENT.length, n -> blocks(p, id, rid, cid, n));
            button(v, 47, Material.EMERALD, Language.text("menus.add-block"), e -> inventoryPicker(p, Language.text("menus.choose-block"), () -> null, true,
                    selected -> selectBlock(p, id, rid, cid, -1, null, selected), () -> blocks(p, id, rid, cid, 0), null),
                    Language.text("menus.choose-a-block-from-your-inventory"), Language.text("menus.or-click-a-block-in-the-bottom-inventory"), Language.text("menus.custom-items-keep-their-custom-drop"));
            v.actions.put(-1, e -> { ItemStack selected = e.getCurrentItem(); if (usable(selected)) selectBlock(p, id, rid, cid, -1, null, selected); });
        });
    }
    public static Mine.Region region(Mine m, String id) { Mine.Region r = m.regions.get(id); if (r == null) throw new IllegalArgumentException(Language.text("menus.region-not-found")); return r; }
    private Mine.BlockEntry checkedBlock(Mine m, String r, String c, int index, String expected) {
        List<Mine.BlockEntry> entries = composition(m, r, c).blocks;
        if (index < 0 || index >= entries.size() || !entries.get(index).data.equals(expected)) throw new IllegalArgumentException(Language.text("menus.the-mixture-changed-open-the-editor-again"));
        return entries.get(index);
    }
    private void blockEditor(Player p, String id, String rid, String cid, int index, String data) {
        open(p, Language.text("menus.edit-block"), true, v -> {
            Mine.BlockEntry b = checkedBlock(mines.get(id), rid, cid, index, data);
            button(v, 4, Material.DIAMOND_PICKAXE, "&b" + data, null, Language.text("menus.target-format", rid, cid, index));
            button(v, 11, Material.COMPARATOR, Language.text("menus.weight-2-format", b.weight), e -> prompts.ask(p, value -> edit(p, id, m -> checkedBlock(m, rid, cid, index, data).weight = Double.parseDouble(value)), () -> blockEditor(p, id, rid, cid, index, data)));
            button(v, 13, Material.GRASS_BLOCK, Language.text("menus.change-block"), e -> inventoryPicker(p, Language.text("menus.choose-block"), () -> checkedBlock(mines.get(id), rid, cid, index, data).placementItem, true,
                    selected -> selectBlock(p, id, rid, cid, index, data, selected), () -> blocks(p, id, rid, cid, index / CONTENT.length), null), Language.text("menus.select-a-block-from-your-inventory"));
            button(v, 15, Material.CHEST, Language.text("menus.custom-drops-format", yes(b.customDrops)), e -> edit(p, id, m -> { Mine.BlockEntry block = checkedBlock(m, rid, cid, index, data); block.customDrops = !block.customDrops; }), Language.text("menus.disabled-normal-minecraft-drops"), Language.text("menus.silk-touch-keeps-the-vanilla-drop"));
            button(v, 20, MineValidator.item(b.drop), Language.text("menus.item-format", b.drop), e -> {
                if (usable(e.getCursor())) selectDrop(p, id, rid, cid, index, data, e.getCursor());
                else dropPicker(p, id, rid, cid, index, data);
            }, Language.text("menus.click-to-choose-from-your-inventory"), Language.text("menus.or-click-an-item-in-the-bottom-inventory"), Language.text("menus.properties-are-kept-the-item-is-not-consumed"));
            if (b.dropItem != null) v.next[20] = dropPreview(b);
            v.actions.put(-1, e -> selectDrop(p, id, rid, cid, index, data, e.getCurrentItem()));
            button(v, 22, Material.PAPER, Language.text("menus.amount-format", b.minAmount, b.maxAmount), e -> prompts.ask(p, value -> edit(p, id, m -> { String[] values = value.split("\\s+"); if (values.length != 2) throw new IllegalArgumentException(Language.text("menus.enter-minimum-maximum")); Mine.BlockEntry block = checkedBlock(m, rid, cid, index, data); block.minAmount = Integer.parseInt(values[0]); block.maxAmount = Integer.parseInt(values[1]); }), () -> blockEditor(p, id, rid, cid, index, data)), Language.text("menus.enter-two-numbers-minimum-maximum"));
            button(v, 24, Material.ENCHANTED_BOOK, Language.text("menus.fortune-format", yes(b.fortune)), e -> edit(p, id, m -> { Mine.BlockEntry block = checkedBlock(m, rid, cid, index, data); block.fortune = !block.fortune; }));
            button(v, 31, Material.GOLD_INGOT, Language.text("menus.rewards-format", b.rewards.size()), e -> rewardMenu(p, id + ":" + rid + ":" + cid + ":" + index, 0));
            back(v, () -> blocks(p, id, rid, cid, index / CONTENT.length));
        });
    }
    private ItemStack dropPreview(Mine.BlockEntry b) {
        ItemStack preview = b.dropStack(1); ItemMeta meta = preview.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.addAll(Text.lines(Language.text("menus.decoration"), Language.text("menus.selected-drop-item"), Language.text("menus.click-an-inventory-item-to-change-it")));
        meta.setLore(lore); preview.setItemMeta(meta); return preview;
    }
    private void selectDrop(Player p, String id, String rid, String cid, int index, String data, ItemStack selected) {
        if (!usable(selected)) return;
        ItemStack template = selected.clone(); template.setAmount(1);
        edit(p, id, mine -> { Mine.BlockEntry b = checkedBlock(mine, rid, cid, index, data); b.dropItem = template.clone(); b.drop = template.getType().name(); b.customDrops = true; });
    }
    private boolean usable(ItemStack item) { return item != null && item.getAmount() > 0 && !item.getType().isAir(); }
    private ItemStack preview(ItemStack source) {
        ItemStack result = source.clone(); result.setAmount(1); ItemMeta meta = result.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.addAll(Text.lines(Language.text("menus.decoration"), Language.text("menus.select-an-item-from-your-inventory"), Language.text("menus.copied-without-consuming-the-original")));
        meta.setLore(lore); result.setItemMeta(meta); return result;
    }
    private void inventoryPicker(Player p, String title, Supplier<ItemStack> current, boolean blockOnly, Consumer<ItemStack> select, Runnable back, Runnable clear) {
        open(p, Language.text("menus.decoration-2-format", title), true, v -> {
            button(v, 13, Material.PAPER, blockOnly ? Language.text("menus.click-a-block-in-your-inventory") : Language.text("menus.click-an-item-in-your-inventory"), null,
                    Language.text("menus.the-custom-item-and-its-properties-are-kept"), Language.text("menus.the-original-item-is-not-consumed"), Language.text("menus.you-can-also-use-the-item-on-your-cursor"));
            ItemStack selected = current.get();
            v.next[22] = usable(selected) ? preview(selected) : item(Material.GRAY_DYE, Language.text("menus.no-item-selected"));
            Consumer<ItemStack> choose = candidate -> {
                if (!usable(candidate)) return;
                if (blockOnly && !candidate.getType().isBlock()) throw new IllegalArgumentException(Language.text("menus.choose-a-placeable-block-from-your-inventory"));
                ItemStack template = candidate.clone(); template.setAmount(1); select.accept(template); back.run();
            };
            v.actions.put(-1, e -> choose.accept(e.getCurrentItem()));
            v.actions.put(22, e -> choose.accept(e.getCursor()));
            if (clear != null) button(v, 31, Material.BARRIER, Language.text("menus.remove-item"), e -> { clear.run(); back.run(); }, Language.text("menus.disable-this-item-reward"));
            back(v, back);
        });
    }
    private void selectBlock(Player p, String id, String rid, String cid, int index, String expected, ItemStack selected) {
        if (!usable(selected)) return;
        if (!selected.getType().isBlock()) throw new IllegalArgumentException(Language.text("menus.choose-a-placeable-block-from-your-inventory"));
        ItemStack template = selected.clone(); template.setAmount(1);
        org.bukkit.block.data.BlockData state = Bukkit.createBlockData(template.getType());
        ItemMeta meta = template.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BlockDataMeta && ((org.bukkit.inventory.meta.BlockDataMeta) meta).hasBlockData())
            state = ((org.bukkit.inventory.meta.BlockDataMeta) meta).getBlockData(template.getType());
        String blockData = state.getAsString();
        edit(p, id, mine -> {
            Mine.Composition c = composition(mine, rid, cid);
            for (int i = 0; i < c.blocks.size(); i++) if (i != index && c.blocks.get(i).data.equals(blockData)) throw new IllegalArgumentException(Language.text("menus.that-block-already-exists-in-this-composition"));
            Mine.BlockEntry b = index < 0 ? new Mine.BlockEntry(blockData, 10) : checkedBlock(mine, rid, cid, index, expected);
            b.data = blockData; b.placementItem = template.clone();
            if (template.hasItemMeta()) { b.dropItem = template.clone(); b.drop = template.getType().name(); b.customDrops = true; }
            if (index < 0) c.blocks.add(b);
        });
    }
    private void dropPicker(Player p, String id, String rid, String cid, int index, String data) {
        inventoryPicker(p, Language.text("menus.choose-drop-item"), () -> checkedBlock(mines.get(id), rid, cid, index, data).dropStack(1), false,
                selected -> selectDrop(p, id, rid, cid, index, data, selected), () -> blockEditor(p, id, rid, cid, index, data), null);
    }
    public static Mine.Composition composition(Mine m, String r, String c) { Mine.Composition comp = region(m, r).compositions.get(c); if (comp == null) throw new IllegalArgumentException(Language.text("menus.composition-not-found")); return comp; }
    public static List<Reward> rewardTarget(Mine m, String path) {
        String[] parts = path.split(":", -1);
        if (parts.length == 1) return m.rewards;
        Mine.Region r = region(m, parts[1]); if (parts.length == 2) return r.rewards;
        Mine.Composition c = composition(m, parts[1], parts[2]); if (parts.length == 3) return c.rewards;
        if (parts.length == 4) { int i = Integer.parseInt(parts[3]); if (i < 0 || i >= c.blocks.size()) throw new IllegalArgumentException(Language.text("menus.invalid-block-index")); return c.blocks.get(i).rewards; }
        throw new IllegalArgumentException(Language.text("menus.target-mine-region-composition-index"));
    }
    public void rewardMenu(Player p, String target, int requested) {
        String id = target.split(":")[0];
        open(p, Language.text("menus.rewards-2"), true, v -> {
            List<Reward> entries = rewardTarget(mines.get(id), target); int pg = Math.min(requested, Math.max(0, (entries.size() - 1) / CONTENT.length));
            for (int i = 0; i < CONTENT.length && pg * CONTENT.length + i < entries.size(); i++) {
                Reward r = entries.get(pg * CONTENT.length + i);
                button(v, CONTENT[i], Material.GOLD_INGOT, "&6" + r.id, e -> {
                    if (e.isShiftClick() && e.isRightClick()) edit(p, id, mine -> rewardTarget(mine, target).removeIf(reward -> reward.id.equals(r.id)));
                    else rewardEditor(p, target, r.id);
                }, Language.text("menus.chance-format", r.chance), Language.text("menus.commands-format", r.commands.size()), Language.text("menus.item-2-format", (r.item.isEmpty() ? Language.text("menus.none") : r.item)), Language.text("menus.permission-format", (r.permission.isEmpty() ? Language.text("menus.none") : r.permission)), "", Language.text("menus.click-edit"), Language.text("menus.shift-right-click-delete"));
            }
            back(v, () -> detail(p, id)); page(v, pg, Math.max(1, (entries.size() + CONTENT.length - 1) / CONTENT.length), n -> rewardMenu(p, target, n));
            button(v, 47, Material.EMERALD, Language.text("menus.add-reward"), e -> prompts.ask(p, value -> {
                MineValidator.id(value); edit(p, id, mine -> { List<Reward> list = rewardTarget(mine, target); if (list.stream().anyMatch(r -> r.id.equals(value))) throw new IllegalArgumentException(Language.text("menus.this-id-already-exists")); Reward r = new Reward(); r.id = value; r.message = Language.text("menus.good-job-player"); list.add(r); });
            }, () -> rewardMenu(p, target, 0)));
        });
    }
    private Reward reward(Mine m, String target, String id) { return rewardTarget(m, target).stream().filter(r -> r.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException(Language.text("menus.reward-not-found"))); }
    private void rewardEditor(Player p, String target, String rewardId) {
        String id = target.split(":")[0];
        open(p, Language.text("menus.edit-reward"), true, v -> {
            Reward r = reward(mines.get(id), target, rewardId);
            button(v, 4, Material.NETHER_STAR, "&6" + rewardId, null, Language.text("menus.target-format-2", target));
            rewardField(v, 10, Material.COMMAND_BLOCK, Language.text("menus.command"), p, target, rewardId, (value, s) -> { value.commands.clear(); if (!s.equals("-")) value.commands.add(s); }, r.commands.isEmpty() ? Language.text("menus.none") : r.commands.get(0));
            rewardField(v, 12, Material.PAPER, Language.text("menus.message"), p, target, rewardId, (value, s) -> value.message = s.equals("-") ? "" : s, r.message);
            button(v, 14, r.item.isEmpty() ? Material.DIAMOND : MineValidator.item(r.item), Language.text("menus.reward-item"), e -> {
                if (usable(e.getCursor())) selectRewardItem(p, target, rewardId, e.getCursor());
                else inventoryPicker(p, Language.text("menus.choose-reward"), () -> reward(mines.get(id), target, rewardId).itemStack(), false,
                        selected -> selectRewardItem(p, target, rewardId, selected), () -> rewardEditor(p, target, rewardId),
                        () -> edit(p, id, mine -> { Reward value = reward(mine, target, rewardId); value.item = ""; value.itemTemplate = null; }));
            }, Language.text("menus.choose-an-item-from-your-inventory"), Language.text("menus.keep-names-lore-enchantments-and-data"), Language.text("menus.you-can-also-click-an-item-below"));
            if (r.itemTemplate != null) v.next[14] = preview(r.itemTemplate);
            v.actions.put(-1, e -> selectRewardItem(p, target, rewardId, e.getCurrentItem()));
            rewardField(v, 23, Material.PAPER, Language.text("menus.item-amount"), p, target, rewardId, (value, s) -> value.amount = Integer.parseInt(s), String.valueOf(r.amount));
            rewardField(v, 16, Material.COMPARATOR, Language.text("menus.chance-0100"), p, target, rewardId, (value, s) -> value.chance = Double.parseDouble(s), String.valueOf(r.chance));
            rewardField(v, 20, Material.TRIPWIRE_HOOK, Language.text("menus.required-permission"), p, target, rewardId, (value, s) -> value.permission = s.equals("-") ? "" : s, r.permission);
            rewardField(v, 22, Material.COMPASS, Language.text("menus.radius-0-triggering-player-only"), p, target, rewardId, (value, s) -> value.radius = Double.parseDouble(s), String.valueOf(r.radius));
            rewardField(v, 24, Material.NAME_TAG, Language.text("menus.group"), p, target, rewardId, (value, s) -> value.group = s.equals("-") ? "" : s, r.group);
            rewardField(v, 29, Material.BELL, Language.text("menus.action-bar"), p, target, rewardId, (value, s) -> value.actionBar = s.equals("-") ? "" : s, r.actionBar);
            rewardField(v, 31, Material.REDSTONE, Language.text("menus.overridden-groups-comma-separated"), p, target, rewardId, (value, s) -> { value.overrides.clear(); if (!s.equals("-")) value.overrides.addAll(Arrays.asList(s.split(","))); }, String.join(",", r.overrides));
            back(v, () -> rewardMenu(p, target, 0));
        });
    }
    private void rewardField(View v, int slot, Material icon, String label, Player p, String target, String rid, BiConsumer<Reward, String> setter, String current) {
        String id = target.split(":")[0];
        button(v, slot, icon, "&e" + label, e -> prompts.ask(p, value -> edit(p, id, mine -> setter.accept(reward(mine, target, rid), value)), () -> rewardEditor(p, target, rid)), Language.text("menus.current-format", current), "", Language.text("menus.click-to-edit"), Language.text("menus.use-to-clear-text-or-commands"));
    }
    private void selectRewardItem(Player p, String target, String rewardId, ItemStack selected) {
        if (!usable(selected)) return;
        ItemStack template = selected.clone(); template.setAmount(1);
        edit(p, target.split(":")[0], mine -> { Reward value = reward(mine, target, rewardId); value.itemTemplate = template.clone(); value.item = template.getType().name(); });
    }
    public void confirm(Player p, String label, Runnable action, Runnable back) {
        open(p, Language.text("menus.confirm-action"), true, v -> {
            button(v, 13, Material.PAPER, "&e" + label, null, Language.text("menus.review-the-action-before-continuing"));
            button(v, 21, Material.LIME_CONCRETE, Language.text("menus.confirm"), e -> { action.run(); back.run(); });
            button(v, 23, Material.RED_CONCRETE, Language.text("menus.cancel"), e -> back.run());
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent e) {
        InventoryHolder holder = InventoryAccess.top(e).getHolder(); if (!(holder instanceof View)) return;
        e.setCancelled(true); View v = (View) holder;
        if (!(e.getWhoClicked() instanceof Player)) return; Player p = (Player) e.getWhoClicked();
        if (!v.owner.equals(p.getUniqueId()) || !p.hasPermission(v.admin ? "nmines.admin" : "nmines.use")) { p.closeInventory(); return; }
        if (e.getClick() != ClickType.LEFT && e.getClick() != ClickType.RIGHT && e.getClick() != ClickType.SHIFT_LEFT && e.getClick() != ClickType.SHIFT_RIGHT) return;
        Consumer<InventoryClickEvent> action = e.getRawSlot() >= 54 ? v.actions.get(-1) : v.actions.get(e.getRawSlot());
        if (action == null) return;
        try { action.accept(e); if (InventoryAccess.top(p) == v.inventory) draw(v); if (plugin.getConfig().getBoolean("menus.sound", true)) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f); }
        catch (IllegalArgumentException | IllegalStateException ex) { text.error(p, ex); }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent e) { if (InventoryAccess.top(e).getHolder() instanceof View) e.setCancelled(true); }
}
