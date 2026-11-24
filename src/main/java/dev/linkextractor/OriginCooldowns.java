package dev.linkextractor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.function.BinaryOperator;
import java.util.function.LongSupplier;

final class OriginCooldowns {
    static final class WaitBudgetExceeded extends IOException {
        WaitBudgetExceeded() {
            super("Origin cooling down: delay exceeds wait budget");
        }
    }

    interface Sleeper {
        void sleep(Duration delay) throws InterruptedException;
    }

    private record Origin(String scheme, String host, int port) {
        static Origin of(URI uri) {
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port == -1) port = scheme.equals("https") ? 443 : 80;
            return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT), port);
        }
    }

    private static final DateTimeFormatter ASCTIME = DateTimeFormatter
            .ofPattern("EEE MMM [ ]d HH:mm:ss uuuu", Locale.US).withZone(ZoneOffset.UTC);

    private static final Duration RECOVERY_SPACING = Duration.ofMillis(100);
    private final HashMap<Origin, Duration> nextAdmission = new HashMap<>();
    private final InstantSource clock;
    private final LongSupplier ticks;
    private final long start;
    private final Sleeper sleeper;

    OriginCooldowns(InstantSource clock, LongSupplier ticks, Sleeper sleeper) {
        this.clock = clock;
        this.ticks = ticks;
        this.start = ticks.getAsLong();
        this.sleeper = sleeper;
    }

    Duration elapsed() {
        return Duration.ofNanos(ticks.getAsLong() - start);
    }

    synchronized void record(URI uri, String retryAfter) {
        var wallTime = clock.instant();
        var delay = Duration.between(wallTime, retryTime(retryAfter.strip(), wallTime));
        var until = elapsed().plus(delay);
        nextAdmission.merge(Origin.of(uri), until, BinaryOperator.maxBy(Duration::compareTo));
    }

    private static Instant retryTime(String value, Instant now) {
        if (value.matches("[0-9]+")) {
            try {
                return now.plusSeconds(Long.parseLong(value));
            } catch (NumberFormatException | DateTimeException | ArithmeticException outOfRange) {
                return Instant.MAX;
            }
        }
        // HTTP recipients also accept the historical RFC 850 and asctime date formats.
        var rfc850 = new DateTimeFormatterBuilder().appendPattern("EEEE, dd-MMM-")
                .appendValueReduced(ChronoField.YEAR, 2, 2, now.atZone(ZoneOffset.UTC).getYear() - 49)
                .appendPattern(" HH:mm:ss 'GMT'").toFormatter(Locale.US).withZone(ZoneOffset.UTC);
        for (var format : List.of(DateTimeFormatter.RFC_1123_DATE_TIME, rfc850, ASCTIME)) {
            try {
                return Instant.from(format.parse(value));
            } catch (DateTimeParseException unrecognized) {
                // Try the next HTTP date form.
            }
        }
        return now.plusSeconds(1);
    }

    Duration await(URI uri, Duration budget, Duration deadline) throws IOException, InterruptedException {
        var origin = Origin.of(uri);
        while (true) {
            var delay = claimOrDelay(origin, budget, deadline);
            if (delay.isZero()) return budget;
            var before = elapsed();
            sleeper.sleep(delay);
            budget = budget.minus(elapsed().minus(before));
        }
    }

    private synchronized Duration claimOrDelay(Origin origin, Duration budget, Duration deadline) throws IOException {
        var now = elapsed();
        var next = nextAdmission.get(origin);
        var delay = Duration.ZERO;
        if (next != null && next.compareTo(now) > 0) delay = next.minus(now);
        var timeLeft = deadline.minus(now);
        if (timeLeft.compareTo(budget) <= 0 && delay.compareTo(timeLeft) >= 0) {
            throw new HttpTimeoutException("Input deadline prevents the next request");
        }
        if (delay.compareTo(budget) > 0) throw new WaitBudgetExceeded();
        if (next != null && delay.isZero()) nextAdmission.put(origin, now.plus(RECOVERY_SPACING));
        return delay;
    }
}
