package snack;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Deadlines run in each office's local working hours, so a refill reported at 16:30 in Oslo
 * isn't passed around while everyone is asleep.
 */
final class WorkingHours {
    // ponytail: Mon-Fri 09-17 with no public holidays; add a per-office holiday list if that bites.
    static final LocalTime OPEN = LocalTime.of(9, 0);
    static final LocalTime CLOSE = LocalTime.of(17, 0);

    private WorkingHours() {
    }

    static boolean isOpen(ZoneId zone, Instant t) {
        ZonedDateTime local = t.atZone(zone);
        LocalTime time = local.toLocalTime();
        return isWorkday(local.toLocalDate()) && !time.isBefore(OPEN) && time.isBefore(CLOSE);
    }

    /** {@code from} plus {@code duration} counted only inside working hours. */
    static Instant plus(ZoneId zone, Instant from, Duration duration) {
        ZonedDateTime t = nextOpen(from.atZone(zone));
        Duration left = duration;
        while (true) {
            Duration restOfDay = Duration.between(t, t.with(CLOSE));
            if (left.compareTo(restOfDay) <= 0) {
                return t.plus(left).toInstant();
            }
            left = left.minus(restOfDay);
            t = nextOpen(t.with(CLOSE));
        }
    }

    static Instant endOfNextWorkday(ZoneId zone, Instant from) {
        LocalDate day = from.atZone(zone).toLocalDate().plusDays(1);
        while (!isWorkday(day)) {
            day = day.plusDays(1);
        }
        return day.atTime(CLOSE).atZone(zone).toInstant();
    }

    private static ZonedDateTime nextOpen(ZonedDateTime t) {
        if (isWorkday(t.toLocalDate()) && t.toLocalTime().isBefore(CLOSE)) {
            return t.toLocalTime().isBefore(OPEN) ? t.with(OPEN) : t;
        }
        LocalDate day = t.toLocalDate().plusDays(1);
        while (!isWorkday(day)) {
            day = day.plusDays(1);
        }
        return day.atTime(OPEN).atZone(t.getZone());
    }

    private static boolean isWorkday(LocalDate day) {
        return day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
