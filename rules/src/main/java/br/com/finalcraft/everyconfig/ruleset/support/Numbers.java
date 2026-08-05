package br.com.finalcraft.everyconfig.ruleset.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a bound value into the exact decimal every numeric constraint compares against.
 *
 * <p>One comparison type for every numeric target — including {@code double} and {@code float}, which Bean
 * Validation excludes from {@code @Min}/{@code @Max} for fear of rounding. A declared divergence: a percentage
 * bounded by {@code @Min(0) @Max(100)} is the ordinary case in a config file, and comparing in
 * {@link BigDecimal} is exact for the decision being made (is it above the bound), which is all the bound asks.
 */
public final class Numbers {

    private Numbers() {
    }

    /** {@code value} as an exact decimal, or null when it carries no comparable number (a non-number, or a
     *  {@code NaN}/infinite floating point value, which no bound can hold). */
    public static BigDecimal decimalOf(final Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof Double || value instanceof Float) {
            final double d = ((Number) value).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : BigDecimal.valueOf(d);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof AtomicInteger || value instanceof AtomicLong) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Number) {
            return parse(value.toString());
        }
        return null;
    }

    /** {@code value} as an exact decimal, accepting a textual number too — the shape {@code @Digits} allows. */
    public static BigDecimal decimalOfNumberOrText(final Object value) {
        if (value instanceof CharSequence) {
            return parse(value.toString().trim());
        }
        return decimalOf(value);
    }

    private static BigDecimal parse(final String text) {
        try {
            return new BigDecimal(text);
        } catch (final NumberFormatException notANumber) {
            return null;
        }
    }
}
