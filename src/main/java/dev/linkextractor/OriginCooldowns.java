package dev.linkextractor;

import java.io.IOException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

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
        resumeTimes.merge(Origin.of(uri), until, java.util.function.BinaryOperator.maxBy(Instant::compareTo));
    }

    private static Instant retryTime(String value, Instant now) {
        if (value.matches("[0-9]+")) {
            try {
                return now.plusSeconds(Long.parseLong(value));
            } catch (NumberFormatException | DateTimeException | ArithmeticException outOfRange) {
                return Instant.MAX;
            }
        }
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException invalidOrAbsent) {
            return now.plusSeconds(1);
        }
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
