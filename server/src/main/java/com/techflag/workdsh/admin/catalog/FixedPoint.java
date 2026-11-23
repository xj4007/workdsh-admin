package com.techflag.workdsh.admin.catalog;

import java.math.BigDecimal;

/** Formats integer fixed-point values without ever converting money to floating point. */
final class FixedPoint {
    private FixedPoint() {}

    /** 1000 units with scale 1000 renders as "1.000"; the unit itself is unchanged. */
    static String credits(long units, int scale) {
        if (scale <= 0) return Long.toString(units);
        int digits = 0;
        int value = scale;
        while (value > 1 && value % 10 == 0) {
            value /= 10;
            digits++;
        }
        if (value != 1) return Long.toString(units);
        return BigDecimal.valueOf(units, digits).toPlainString();
    }

    /** 15000 basis points renders as "1.5". */
    static String multiplier(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 4).stripTrailingZeros().toPlainString();
    }
}
