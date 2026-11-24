package dev.linkextractor;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OriginCooldownsTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final URI A = URI.create("https://EXAMPLE.org/a");
    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private final ArrayList<Duration> sleeps = new ArrayList<>();
    private final OriginCooldowns cooldowns = new OriginCooldowns(now::get, delay -> {
        sleeps.add(delay);
        now.updateAndGet(t -> t.plus(delay));
    });
    private static final Duration FIVE = Duration.ofSeconds(5);

    @Test
    void sharesTheLongestDeadlineAcrossPathsCaseAndDefaultPortOnly() throws Exception {
        cooldowns.record(A, "Thu, 1 Jan 2026 00:00:04 GMT");
        cooldowns.record(A, "2");
        for (var other : new String[]{"http://example.org", "https://elsewhere.example", "https://example.org:8443"}) {
            assertEquals(FIVE, cooldowns.await(URI.create(other), FIVE));
        }
        assertEquals(Duration.ofSeconds(1), cooldowns.await(URI.create("https://example.ORG:443/b"), FIVE));
        assertEquals(java.util.List.of(Duration.ofSeconds(4)), sleeps);
        assertEquals(FIVE, cooldowns.await(A, FIVE));
    }

    @Test
    void honorsAnExtensionReceivedDuringTheWait() throws Exception {
        final var policy = new AtomicReference<OriginCooldowns>();
        policy.set(new OriginCooldowns(now::get, delay -> {
            now.updateAndGet(t -> t.plus(delay));
            if (now.get().equals(START.plusSeconds(1))) policy.get().record(A, "2");
        }));
        policy.get().record(A, "1");
        assertEquals(Duration.ofSeconds(2), policy.get().await(A, FIVE));
    }

    @Test
    void acceptsHistoricalHttpDatesAndExpiredDates() throws Exception {
        cooldowns.record(A, "Thursday, 01-Jan-26 00:00:02 GMT");
        assertEquals(Duration.ofSeconds(3), cooldowns.await(A, FIVE));
        cooldowns.record(A, "Thu Jan  1 00:00:05 2026");
        assertEquals(Duration.ofSeconds(2), cooldowns.await(A, FIVE));
        cooldowns.record(A, "Sunday, 06-Nov-94 08:49:37 GMT");
        assertEquals(FIVE, cooldowns.await(A, FIVE));
    }

    @Test
    void defaultsInvalidHeadersAndNeverTruncatesAnExcessiveServerDelay() throws Exception {
        cooldowns.record(A, "not-a-date");
        assertEquals(Duration.ofSeconds(4), cooldowns.await(A, FIVE));
        cooldowns.record(A, "99999999999999999999999");
        assertThrows(IOException.class, () -> cooldowns.await(A, FIVE));
        assertEquals(1, sleeps.size());
    }
}
