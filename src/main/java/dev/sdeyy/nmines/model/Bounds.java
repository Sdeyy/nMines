package dev.sdeyy.nmines.model;

import dev.sdeyy.nmines.util.Language;
import java.util.UUID;

/** Inclusive coordinates, using longs for all volume calculations. */
public final class Bounds {
    public final UUID world;
    public final int minX, minY, minZ, maxX, maxY, maxZ;
    public Bounds(UUID world, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (world == null) throw new IllegalArgumentException(Language.text("errors.world-is-required"));
        this.world = world;
        minX = Math.min(x1, x2); minY = Math.min(y1, y2); minZ = Math.min(z1, z2);
        maxX = Math.max(x1, x2); maxY = Math.max(y1, y2); maxZ = Math.max(z1, z2);
        if (volume() <= 0) throw new IllegalArgumentException(Language.text("errors.invalid-volume"));
    }
    public long volume() {
        try { return Math.multiplyExact(Math.multiplyExact((long) maxX - minX + 1, (long) maxY - minY + 1), (long) maxZ - minZ + 1); }
        catch (ArithmeticException e) { throw new IllegalArgumentException(Language.text("errors.region-is-too-large")); }
    }
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
    public boolean overlaps(Bounds other) {
        return world.equals(other.world) && minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY && minZ <= other.maxZ && maxZ >= other.minZ;
    }
    public int x(long index) { return minX + (int) (index % ((long) maxX - minX + 1)); }
    public int z(long index) { return minZ + (int) ((index / ((long) maxX - minX + 1)) % ((long) maxZ - minZ + 1)); }
    public int y(long index) { return minY + (int) (index / (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1))); }
    public int index(int x, int y, int z) {
        if (!contains(x, y, z)) return -1;
        return Math.toIntExact((((long) y - minY) * ((long) maxZ - minZ + 1) + z - minZ) * ((long) maxX - minX + 1) + x - minX);
    }
}
