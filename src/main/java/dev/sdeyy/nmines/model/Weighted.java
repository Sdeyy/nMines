package dev.sdeyy.nmines.model;

import dev.sdeyy.nmines.util.Language;
import java.util.Collection;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/** Computes weights on every selection; no stale cache after removal or editing. */
public final class Weighted {
    private Weighted() { }
    public static <T> T pick(Collection<T> values, ToDoubleFunction<T> weight, Random random) {
        double total = 0;
        T last = null;
        for (T value : values) {
            double w = weight.applyAsDouble(value);
            if (!Double.isFinite(w) || w < 0) throw new IllegalArgumentException(Language.text("errors.invalid-weight"));
            if (w > 0) { total += w; last = value; }
        }
        if (!Double.isFinite(total) || total <= 0) throw new IllegalArgumentException(Language.text("errors.at-least-one-weight-must-be-positive"));
        double point = random.nextDouble() * total;
        for (T value : values) {
            point -= weight.applyAsDouble(value);
            if (point < 0) return value;
        }
        return last;
    }
}
