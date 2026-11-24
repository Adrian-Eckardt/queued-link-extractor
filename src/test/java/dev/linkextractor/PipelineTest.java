package dev.linkextractor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PipelineTest {
    private static final Page PAGE = new Page(URI.create("https://example.org/"), "<a href='/guide'>");

    @Test
    void emitsTheFirstResultWhileTheFinalFetchIsInFlightThenDrainsAndExits() throws Exception {
        var lastFetchStarted = new CountDownLatch(1);
        var releaseLast = new CountDownLatch(1);
        var firstOutput = new CountDownLatch(1);
        var outcomes = new ArrayList<Outcome>();
        var pipeline = new Pipeline(url -> {
            if (url.equals("slow")) {
                lastFetchStarted.countDown();
                releaseLast.await();
            }
            return PAGE;
        }, HtmlLinks::extract, outcome -> {
            outcomes.add(outcome);
            firstOutput.countDown();
        }, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = executor.submit(() -> { pipeline.run(List.of("fast", "slow")); return null; });
            try {
                assertTrue(lastFetchStarted.await(2, TimeUnit.SECONDS));
                assertTrue(firstOutput.await(2, TimeUnit.SECONDS), "First page should already be visible");
                assertFalse(run.isDone());
            } finally {
                releaseLast.countDown();
            }
            run.get(2, TimeUnit.SECONDS);
        }
        var links = List.of("https://example.org/guide");
        assertEquals(List.of(Outcome.success(1, "fast", links), Outcome.success(2, "slow", links)), outcomes);
    }

    @Test
    void aBrokenOutputInterruptsTheOutstandingFetch() {
        var fetching = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var pipeline = new Pipeline(url -> {
            if (url.equals("slow")) {
                fetching.countDown();
                try {
                    new CountDownLatch(1).await();
                } finally {
                    interrupted.countDown();
                }
            }
            return PAGE;
        }, HtmlLinks::extract, outcome -> {
            try {
                assertTrue(fetching.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            throw new IllegalStateException("Output closed");
        }, 1);

        var failure = assertThrows(ExecutionException.class, () -> pipeline.run(List.of("fast", "slow")));
        assertEquals("Output closed", failure.getCause().getMessage());
        assertEquals(0, interrupted.getCount());
    }

    @Test
    void emptyInputTerminatesWithoutAnyOutput() throws Exception {
        var outcomes = new ArrayList<Outcome>();
        new Pipeline(url -> PAGE, HtmlLinks::extract, outcomes::add, 1).run(List.of());
        assertTrue(outcomes.isEmpty());
    }
}
