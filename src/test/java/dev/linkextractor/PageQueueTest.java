package dev.linkextractor;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import dev.linkextractor.PageQueue.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PageQueueTest {
    private static Fetched page(int index) {
        return new Fetched(index, "same", new Page(URI.create("https://example.org/"),
                new Page.Markup(new byte[0], null)));
    }

    @Test
    void replacesOldestWaitingPagesWithIndexedNoticesInFifoOrder() throws Exception {
        var queue = new PageQueue(2, Policy.DROP_OLDEST);
        for (int index = 1; index <= 4; index++) queue.put(page(index));
        for (int index = 1; index <= 2; index++) {
            var notice = assertInstanceOf(Dropped.class, queue.take()).outcome();
            assertEquals(index, notice.input());
            assertEquals("same", notice.url());
            assertEquals("dropped", notice.status());
        }
        assertEquals(3, assertInstanceOf(Fetched.class, queue.take()).input());
        assertEquals(4, assertInstanceOf(Fetched.class, queue.take()).input());
    }

    private static void assertBackpressure(PageQueue queue, Message next, Message first) throws Exception {
        var started = new CountDownLatch(1);
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = worker.submit(() -> { started.countDown(); queue.put(next); return null; });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS));
                assertEquals(first, queue.take());
                pending.get(2, TimeUnit.SECONDS);
            } finally {
                pending.cancel(true);
            }
        }
    }

    @Test
    void rejectsAnEmptyQueueCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new PageQueue(0, Policy.BLOCK));
    }

    @Test
    void blockModeWaitsForSpaceAndPreservesBothPages() throws Exception {
        var queue = new PageQueue(1, Policy.BLOCK);
        var first = page(1);
        var second = page(2);
        queue.put(first);
        assertBackpressure(queue, second, first);
        assertSame(second, queue.take());
    }

    @Test
    void aFullNoticeAllowanceBackpressuresFurtherEvictions() throws Exception {
        var queue = new PageQueue(1, Policy.DROP_OLDEST);
        queue.put(page(1));
        queue.put(page(2));
        var third = page(3);
        assertBackpressure(queue, third, new Dropped(new Outcome(1, "same", "dropped", null, null, null)));
        assertEquals(2, assertInstanceOf(Dropped.class, queue.take()).outcome().input());
        assertSame(third, queue.take());
    }

    @Test
    void preservesFetchErrorsAndWaitsToAppendTheEndMarker() throws Exception {
        var queue = new PageQueue(1, Policy.DROP_OLDEST);
        var error = new FetchFailure(Outcome.failure(1, "same", "fetch", new java.io.IOException("offline")));
        var next = page(2);
        queue.put(error);
        assertBackpressure(queue, next, error);
        assertBackpressure(queue, End.INSTANCE, next);
        assertSame(End.INSTANCE, queue.take());
    }
}
