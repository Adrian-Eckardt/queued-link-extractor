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
    private final java.util.concurrent.atomic.AtomicLong ticks = new java.util.concurrent.atomic.AtomicLong();
    private static final Duration END = Duration.ofDays(1);
    private final ArrayList<Duration> sleeps = new ArrayList<>();
    private final OriginCooldowns cooldowns = new OriginCooldowns(now::get, ticks::get, delay -> {
        sleeps.add(delay);
        ticks.addAndGet(delay.toNanos());
        now.updateAndGet(t -> t.plus(delay));
    });
    private static final Duration FIVE = Duration.ofSeconds(5);

    @Test
    void sharesTheLongestDeadlineAcrossPathsCaseAndDefaultPortOnly() throws Exception {
        cooldowns.record(A, "Thu, 1 Jan 2026 00:00:04 GMT");
        cooldowns.record(A, "2");
        for (var other : new String[]{"http://example.org", "https://elsewhere.example", "https://example.org:8443"}) {
            assertEquals(FIVE, cooldowns.await(URI.create(other), FIVE, END));
        }
        assertEquals(Duration.ofSeconds(1), cooldowns.await(URI.create("https://example.ORG:443/b"), FIVE, END));
        assertEquals(java.util.List.of(Duration.ofSeconds(4)), sleeps);
        assertEquals(FIVE.minusMillis(100), cooldowns.await(A, FIVE, END));
    }

    @Test
    void honorsAnExtensionReceivedDuringTheWait() throws Exception {
        final var policy = new AtomicReference<OriginCooldowns>();
        policy.set(new OriginCooldowns(now::get, ticks::get, delay -> {
            ticks.addAndGet(delay.toNanos());
            now.updateAndGet(t -> t.plus(delay));
            if (now.get().equals(START.plusSeconds(1))) policy.get().record(A, "2");
        }));
        policy.get().record(A, "1");
        assertEquals(Duration.ofSeconds(2), policy.get().await(A, FIVE, END));
    }

    @Test
    void acceptsHistoricalHttpDatesAndExpiredDates() throws Exception {
        cooldowns.record(A, "Thursday, 01-Jan-26 00:00:02 GMT");
        assertEquals(Duration.ofSeconds(3), cooldowns.await(A, FIVE, END));
        cooldowns.record(A, "Thu Jan  1 00:00:05 2026");
        assertEquals(Duration.ofSeconds(2), cooldowns.await(A, FIVE, END));
        cooldowns.record(A, "Sunday, 06-Nov-94 08:49:37 GMT");
        assertEquals(FIVE.minusMillis(100), cooldowns.await(A, FIVE, END));
    }

    @Test
    void wallClockCorrectionsCannotShortenOrExtendRecordedCooldowns() throws Exception {
        cooldowns.record(A, "2");
        now.set(START.minusSeconds(600));
        assertEquals(Duration.ofSeconds(3), cooldowns.await(A, FIVE, END));
        now.set(START);
        cooldowns.record(A, "Thu, 1 Jan 2026 00:00:03 GMT");
        now.set(START.plusSeconds(600));
        assertEquals(Duration.ofSeconds(2), cooldowns.await(A, FIVE, END));
        assertEquals(java.util.List.of(Duration.ofSeconds(2), Duration.ofSeconds(3)), sleeps);
    }

    @Test
    void theInputDeadlineAndTheWaitBudgetHaveDifferentDiagnostics() {
        cooldowns.record(A, "30");
        var deadline = assertThrows(java.net.http.HttpTimeoutException.class,
                () -> cooldowns.await(A, FIVE, Duration.ofSeconds(2)));
        assertEquals("Input deadline prevents the next request", deadline.getMessage());
        var budget = assertThrows(OriginCooldowns.WaitBudgetExceeded.class, () -> cooldowns.await(A, FIVE, END));
        assertFalse(budget.getMessage().contains("429"));
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void simultaneousWaitersCannotAllClaimTheFirstRecoverySlot() throws Exception {
        var sleeping = new java.util.concurrent.CountDownLatch(3);
        var admitted = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.atomic.AtomicInteger();
        var policy = new OriginCooldowns(now::get, ticks::get, delay -> {
            long wake = ticks.get() + delay.toNanos();
            sleeping.countDown();
            release.await();
            ticks.accumulateAndGet(wake, Math::max);
        });
        policy.record(A, "2");
        ticks.set(Duration.ofSeconds(2).toNanos());
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<java.util.concurrent.Future<Void>>();
            for (int i = 0; i < 4; i++) jobs.add(workers.submit(() -> {
                policy.await(A, FIVE, END);
                completed.incrementAndGet();
                admitted.countDown();
                return null;
            }));
            try {
                assertTrue(sleeping.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(admitted.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(1, completed.get());
            } finally {
                release.countDown();
            }
            for (var job : jobs) job.get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(4, completed.get());
        assertTrue(ticks.get() >= Duration.ofMillis(2300).toNanos());
    }

    @Test
    void defaultsInvalidHeadersAndNeverTruncatesAnExcessiveServerDelay() throws Exception {
        cooldowns.record(A, "not-a-date");
        assertEquals(Duration.ofSeconds(4), cooldowns.await(A, FIVE, END));
        cooldowns.record(A, "99999999999999999999999");
        assertThrows(IOException.class, () -> cooldowns.await(A, FIVE, END));
        assertEquals(1, sleeps.size());
    }
}
