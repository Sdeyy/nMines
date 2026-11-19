package dev.sdeyy.nmines.engine;

import java.util.BitSet;

public final class RegionState {
    private final BitSet occupied = new BitSet();
    private long remaining;
    public boolean ready;
    public long capacity;
    public long revision;
    public String composition;
    public void set(int index, boolean solid) {
        if (occupied.get(index) == solid) return;
        occupied.set(index, solid); remaining += solid ? 1 : -1;
    }
    public void replace(BitSet blocks) {
        occupied.clear(); occupied.or(blocks); remaining = occupied.cardinality();
    }
    public long remaining() { return remaining; }
}
