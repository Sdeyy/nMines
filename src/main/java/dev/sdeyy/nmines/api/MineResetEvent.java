package dev.sdeyy.nmines.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired on the server thread before evacuation or any block changes. */
public final class MineResetEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String mine;
    private final List<String> regions;
    private boolean cancelled;
    public MineResetEvent(String mine, List<String> regions) { this.mine = mine; this.regions = Collections.unmodifiableList(new ArrayList<>(regions)); }
    public String getMineId() { return mine; }
    public List<String> getRegionIds() { return regions; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
