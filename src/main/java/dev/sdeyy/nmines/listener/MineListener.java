package dev.sdeyy.nmines.listener;

import dev.sdeyy.nmines.model.Mine;
import dev.sdeyy.nmines.service.MineService;
import dev.sdeyy.nmines.service.RewardService;
import dev.sdeyy.nmines.util.Language;
import dev.sdeyy.nmines.util.Text;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineListener implements Listener {
    private final JavaPlugin plugin;
    private final MineService mines;
    private final RewardService rewards;
    private final Text text;
    private final Random random = new Random();
    private final Map<UUID, Long> notices = new HashMap<>();
    private final Set<Location> pendingChanges = new HashSet<>();
    private final Set<Location> pendingBreaks = new HashSet<>();
    private boolean recountScheduled;
    public MineListener(JavaPlugin plugin, MineService mines, Text text) {
        this.plugin = plugin; this.mines = mines; this.text = text; rewards = new RewardService(plugin);
    }
    private boolean locked(MineService.Ref ref) { return !ref.state.ready || mines.engine().queued(ref.mine.id + ":" + ref.region.id); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        MineService.Ref ref = mines.at(event.getBlock().getLocation());
        if (ref == null) return;
        Player p = event.getPlayer();
        if (locked(ref) || (!mines.access(p, ref.mine) && ref.mine.protect)) { event.setCancelled(true); notify(p, Language.text("protection.this-mine-is-not-available-yet")); return; }
        ItemStack tool = p.getInventory().getItemInMainHand();
        if (ref.mine.requiredTool != null && !ref.mine.requiredTool.matches(tool)) {
            event.setCancelled(true); notify(p, Language.text("tools.required", ref.mine.displayName)); return;
        }
        Location where = event.getBlock().getLocation();
        if (event.getBlock().getType().isAir() || pendingBreaks.contains(where)) { event.setCancelled(true); return; }
        long revision = ref.state.revision;
        BlockData original = event.getBlock().getBlockData().clone();
        Mine.Composition composition = ref.region.compositions.get(ref.state.composition);
        if (composition == null && ref.region.compositions.size() == 1) composition = ref.region.compositions.values().iterator().next();
        Mine.BlockEntry entry = ref.match(composition, original);
        final Mine.Composition comp = composition; final Mine.BlockEntry matched = entry;
        final boolean survival = p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE;
        List<ItemStack> drops = new ArrayList<>();
        tool = tool.clone();
        boolean custom = survival && matched != null && matched.customDrops;
        boolean pickup = survival && ref.mine.autoPickup;
        if (custom || pickup) {
            if (custom && !hasEnchantment(tool, "silk_touch")) {
                int count = matched.minAmount + random.nextInt(matched.maxAmount - matched.minAmount + 1);
                int fortune = level(tool, "fortune");
                if (matched.fortune && fortune > 0) count = Math.min(2304, count * (1 + random.nextInt(Math.min(10, fortune) + 1)));
                drops.add(matched.dropStack(count));
            } else drops.addAll(event.getBlock().getDrops(tool, p));
            event.setDropItems(false);
        }
        pendingBreaks.add(where);
        Bukkit.getScheduler().runTask(plugin, () -> {
            pendingBreaks.remove(where);
            // Observe the final cancellation state, including later protection listeners.
            if (event.isCancelled() || mines.state(ref.mine.id, ref.region.id) != ref.state || !ref.state.ready
                    || ref.state.revision != revision || where.getBlock().getBlockData().equals(original)) return;
            mines.update(where);
            if (!survival) return;
            for (ItemStack drop : event.isDropItems() ? java.util.Collections.<ItemStack>emptyList() : drops) {
                if (pickup && p.isOnline()) RewardService.give(p, drop, where);
                else RewardService.drop(drop, where.clone().add(0.5, 0.5, 0.5));
            }
            if (p.isOnline()) rewards.trigger(ref, comp, matched, p, where);
        });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        MineService.Ref ref = mines.at(event.getBlock().getLocation());
        if (ref == null) return;
        if (containsFluid(event.getBlock())) {
            event.setCancelled(true); notify(event.getPlayer(), Language.text("protection.no-fluids")); return;
        }
        if (locked(ref) || (ref.mine.protect && !event.getPlayer().hasPermission("nmines.bypass"))) {
            event.setCancelled(true); notify(event.getPlayer(), Language.text("protection.you-cannot-build-here")); return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> { if (!event.isCancelled()) { mines.update(event.getBlock().getLocation()); if (event instanceof BlockMultiPlaceEvent) for (BlockState state : ((BlockMultiPlaceEvent) event).getReplacedBlockStates()) mines.update(state.getLocation()); } });
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void multiPlace(BlockMultiPlaceEvent event) {
        for (BlockState state : event.getReplacedBlockStates()) {
            MineService.Ref ref = mines.at(state.getLocation());
            if (ref != null && (containsFluid(state.getBlock()) || locked(ref) || (ref.mine.protect && !event.getPlayer().hasPermission("nmines.bypass")))) { event.setCancelled(true); return; }
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getBlockX() == event.getTo().getBlockX() && event.getFrom().getBlockY() == event.getTo().getBlockY() && event.getFrom().getBlockZ() == event.getTo().getBlockZ() && event.getFrom().getWorld().equals(event.getTo().getWorld()))) return;
        MineService.Ref ref = mines.at(event.getTo());
        if (ref != null && mines.engine().regenerating(ref.mine.id + ":" + ref.region.id)) { event.setTo(event.getFrom()); notify(event.getPlayer(), Language.text("protection.wait-for-regeneration-to-finish")); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getTo() == null) return;
        MineService.Ref ref = mines.at(event.getTo());
        if (ref != null && mines.engine().regenerating(ref.mine.id + ":" + ref.region.id)) { event.setCancelled(true); notify(event.getPlayer(), Language.text("protection.wait-for-regeneration-to-finish")); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void physics(BlockPhysicsEvent event) { mark(event.getBlock()); }
    private void mark(Block block) {
        if (mines.at(block.getLocation()) == null) return;
        pendingChanges.add(block.getLocation());
        if (recountScheduled) return;
        recountScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            List<Location> changed = new ArrayList<>(pendingChanges); pendingChanges.clear(); recountScheduled = false;
            changed.forEach(mines::update);
        });
    }
    private boolean protectedBlock(Block b) { MineService.Ref r = mines.at(b.getLocation()); return r != null && (r.mine.protect || locked(r)); }
    private boolean inMine(Block block) { return mines.at(block.getLocation()) != null; }
    private boolean containsFluid(Block block) {
        BlockData data = block.getBlockData();
        return block.isLiquid() || (data instanceof org.bukkit.block.data.Waterlogged && ((org.bukkit.block.data.Waterlogged) data).isWaterlogged());
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketEmpty(PlayerBucketEmptyEvent event) {
        // getBlock() is the affected block, including waterlogged targets.
        if (inMine(event.getBlock())) { event.setCancelled(true); notify(event.getPlayer(), Language.text("protection.no-fluids")); }
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void dispense(BlockDispenseEvent event) {
        String item = event.getItem().getType().name();
        if (!item.endsWith("_BUCKET") || item.equals("MILK_BUCKET")) return;
        BlockData data = event.getBlock().getBlockData();
        if (!(data instanceof org.bukkit.block.data.Directional)) return;
        Block target = event.getBlock().getRelative(((org.bukkit.block.data.Directional) data).getFacing());
        if (inMine(target)) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void cauldron(CauldronLevelChangeEvent event) { if (inMine(event.getBlock())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(EntityExplodeEvent e) { explosion(e.blockList()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explode(BlockExplodeEvent e) { explosion(e.blockList()); }
    private void explosion(List<Block> blocks) {
        blocks.removeIf(this::protectedBlock);
        List<Location> changed = new ArrayList<>(); blocks.forEach(b -> changed.add(b.getLocation()));
        Bukkit.getScheduler().runTask(plugin, () -> changed.forEach(mines::update));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) { if (inMine(e.getToBlock()) || protectedBlock(e.getBlock())) e.setCancelled(true); else { mark(e.getToBlock()); mark(e.getBlock()); } }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entity(EntityChangeBlockEvent e) { if (protectedBlock(e.getBlock())) e.setCancelled(true); else Bukkit.getScheduler().runTask(plugin, () -> mines.update(e.getBlock().getLocation())); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) { if (protectedBlock(e.getBlock())) e.setCancelled(true); else mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void grow(BlockGrowEvent e) { if (protectedBlock(e.getBlock())) e.setCancelled(true); else mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void form(BlockFormEvent e) { if (protectedBlock(e.getBlock())) e.setCancelled(true); else mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void fade(BlockFadeEvent e) { if (protectedBlock(e.getBlock())) e.setCancelled(true); else mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spread(BlockSpreadEvent e) { if (protectedBlock(e.getBlock()) || protectedBlock(e.getSource())) e.setCancelled(true); else mark(e.getBlock()); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void extend(BlockPistonExtendEvent e) { if (protectedBlock(e.getBlock().getRelative(e.getDirection())) || piston(e.getBlocks(), e.getDirection())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retract(BlockPistonRetractEvent e) { if (protectedBlock(e.getBlock().getRelative(e.getDirection())) || piston(e.getBlocks(), e.getDirection())) e.setCancelled(true); }
    private boolean piston(List<Block> blocks, org.bukkit.block.BlockFace face) {
        for (Block b : blocks) if (protectedBlock(b) || protectedBlock(b.getRelative(face)) || protectedBlock(b.getRelative(face.getOppositeFace()))) return true;
        for (Block b : blocks) { mark(b); mark(b.getRelative(face)); mark(b.getRelative(face.getOppositeFace())); }
        return false;
    }
    private int level(ItemStack tool, String key) { Enchantment e = Enchantment.getByKey(NamespacedKey.minecraft(key)); return e == null ? 0 : tool.getEnchantmentLevel(e); }
    private boolean hasEnchantment(ItemStack tool, String key) { return level(tool, key) > 0; }
    private void notify(Player player, String message) {
        long now = System.currentTimeMillis();
        if (now - notices.getOrDefault(player.getUniqueId(), 0L) > 1500) { text.send(player, message); notices.put(player.getUniqueId(), now); }
    }
    @EventHandler public void quit(org.bukkit.event.player.PlayerQuitEvent e) { notices.remove(e.getPlayer().getUniqueId()); mines.forget(e.getPlayer().getUniqueId()); }
}
