package dev.sdeyy.nmines.engine;

import java.util.function.LongSupplier;

/** Both limits are checked before every cell, including skipped mask cells. */
public final class WorkBudget {
    private final int maxSteps;
    private final long deadline;
    private final LongSupplier clock;
    private int steps;
    public WorkBudget(int maxSteps, long nanos, LongSupplier clock) {
        this.maxSteps = maxSteps; this.clock = clock; this.deadline = clock.getAsLong() + nanos;
    }
    public boolean take() {
        if (steps >= maxSteps || clock.getAsLong() >= deadline) return false;
        steps++; return true;
    }
    public int steps() { return steps; }
}
