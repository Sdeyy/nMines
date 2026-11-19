package dev.sdeyy.nmines.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired once per completed mine reset, after all of its queued regions finish. */
public final class MineResetCompleteEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String mine;
    private final boolean success;
    public MineResetCompleteEvent(String mine, boolean success) { this.mine = mine; this.success = success; }
    public String getMineId() { return mine; }
    public boolean isSuccessful() { return success; }
    public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
