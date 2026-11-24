package dev.linkextractor;

import java.io.IOException;
import java.net.URI;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;

final class OriginCooldowns {
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

    private final ConcurrentHashMap<Origin, Instant> resumeTimes = new ConcurrentHashMap<>();
    private final InstantSource clock;
    private final Sleeper sleeper;

    OriginCooldowns(InstantSource clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    Instant now() {
        return clock.instant();
    }

    void record(URI uri, String retryAfter) {
        var now = now();
        Instant until = retryTime(retryAfter.strip(), now);
        resumeTimes.merge(Origin.of(uri), until, BinaryOperator.maxBy(Instant::compareTo));
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

    Duration await(URI uri, Duration budget) throws IOException, InterruptedException {
        var origin = Origin.of(uri);
        while (true) {
            var now = now();
            var until = resumeTimes.computeIfPresent(origin, (key, t) -> t.isAfter(now) ? t : null);
            if (until == null) return budget;
            var delay = Duration.between(now, until);
            if (delay.compareTo(budget) > 0) throw new IOException("HTTP 429 (origin cooldown exceeds wait budget)");
            sleeper.sleep(delay);
            budget = budget.minus(delay);
        }
    }
}
