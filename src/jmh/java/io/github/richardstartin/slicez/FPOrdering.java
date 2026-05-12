package io.github.richardstartin.slicez;

public class FPOrdering {

    public static long ordinalOf(double value) {
        if (value == Double.POSITIVE_INFINITY) {
            return 0xFFFFFFFFFFFFFFFFL;
        }
        if (value == Double.NEGATIVE_INFINITY || Double.isNaN(value)) {
            return 0;
        }
        long bits = Double.doubleToLongBits(value);
        if ((bits & Long.MIN_VALUE) == Long.MIN_VALUE) {
            bits = bits == Long.MIN_VALUE ? Long.MIN_VALUE : ~bits;
        } else {
            bits ^= Long.MIN_VALUE;
        }
        return bits;
    }
}

