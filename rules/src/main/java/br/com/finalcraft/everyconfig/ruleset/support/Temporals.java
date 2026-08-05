package br.com.finalcraft.everyconfig.ruleset.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Date;

/**
 * Places a temporal value on the timeline the four temporal constraints judge against.
 *
 * <p>Each type is compared to its OWN {@code now()}, read from the default clock and zone: a
 * {@link LocalDate} against today, a {@link LocalTime} against the time of day. There is no clock provider
 * and no tolerance window — a config file states a date, and the question is only which side of now it falls
 * on. The types listed here are the declared surface; anything else is a declaration defect, not a silent pass.
 */
public final class Temporals {

    private Temporals() {
    }

    /** Whether this type is one the temporal constraints can place on the timeline. */
    public static boolean isSupported(final Class<?> type) {
        return Instant.class.isAssignableFrom(type)
                || LocalDate.class.isAssignableFrom(type)
                || LocalDateTime.class.isAssignableFrom(type)
                || LocalTime.class.isAssignableFrom(type)
                || OffsetDateTime.class.isAssignableFrom(type)
                || OffsetTime.class.isAssignableFrom(type)
                || ZonedDateTime.class.isAssignableFrom(type)
                || Year.class.isAssignableFrom(type)
                || YearMonth.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type);
    }

    /** Negative in the past, zero exactly now, positive in the future; null when the value carries no
     *  comparable instant. */
    public static Integer compareToNow(final Object value) {
        if (value instanceof Instant) {
            return ((Instant) value).compareTo(Instant.now());
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).compareTo(LocalDate.now());
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).compareTo(LocalDateTime.now());
        }
        if (value instanceof LocalTime) {
            return ((LocalTime) value).compareTo(LocalTime.now());
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).compareTo(OffsetDateTime.now());
        }
        if (value instanceof OffsetTime) {
            return ((OffsetTime) value).compareTo(OffsetTime.now());
        }
        if (value instanceof ZonedDateTime) {
            return ((ZonedDateTime) value).compareTo(ZonedDateTime.now());
        }
        if (value instanceof Year) {
            return ((Year) value).compareTo(Year.now());
        }
        if (value instanceof YearMonth) {
            return ((YearMonth) value).compareTo(YearMonth.now());
        }
        if (value instanceof Date) {
            return ((Date) value).compareTo(new Date());
        }
        return null;
    }
}
