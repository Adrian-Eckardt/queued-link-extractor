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
    private static final Page PAGE = new Page(URI.create("https://example.org/"), new Page.Markup("<a href='/guide'>".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
    private static final Page UNPARSEABLE = new Page(PAGE.effectiveUri(), new Page.Markup(new byte[0], null));

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
    void failedInputsDoNotPreventOtherOccurrencesFromFinishing() throws Exception {
        var outcomes = new ArrayList<Outcome>();
        var pipeline = new Pipeline(url -> {
            if (url.equals("offline")) throw new java.io.IOException("Timed out");
            if (url.equals("invalid")) throw new IllegalArgumentException("Invalid URL");
            return url.equals("unparseable") ? UNPARSEABLE : PAGE;
        }, page -> {
            if (page == UNPARSEABLE) throw new IllegalStateException();
            return HtmlLinks.extract(page);
        }, outcomes::add, 1);

        pipeline.run(List.of("good", "offline", "invalid", "unparseable", "good"));
        assertEquals(List.of("ok", "error", "error", "error", "ok"),
                outcomes.stream().map(Outcome::status).toList());
        assertEquals("fetch", outcomes.get(1).stage());
        assertEquals("Timed out", outcomes.get(1).error());
        assertEquals("parse", outcomes.get(3).stage());
        assertEquals("IllegalStateException", outcomes.get(3).error());
        assertEquals(List.of(1, 2, 3, 4, 5), outcomes.stream().map(Outcome::input).toList());
        assertEquals(List.of("good", "offline", "invalid", "unparseable", "good"),
                outcomes.stream().map(Outcome::url).toList());
    }

    @Test
    void retainsIndependentWorkerFailuresAndAvoidsSelfSuppression() {
        var primary = new AssertionError("output");
        var secondary = new AssertionError("producer cleanup");
        assertPeerFailure(primary, secondary);
        assertArrayEquals(new Throwable[]{secondary}, primary.getSuppressed());

        var shared = new AssertionError("shared failure");
        assertPeerFailure(shared, shared);
        assertEquals(0, shared.getSuppressed().length);
    }

    private static void assertPeerFailure(AssertionError outputError, AssertionError fetchError) {
        var enteredFetch = new CountDownLatch(1);
        var pipeline = new Pipeline(url -> {
            if (url.equals("first")) return PAGE;
            enteredFetch.countDown();
            try {
                new CountDownLatch(1).await();
            } finally {
                throw fetchError;
            }
        }, HtmlLinks::extract, result -> {
            try {
                assertTrue(enteredFetch.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
            throw outputError;
        }, 1);
        var failure = assertThrows(ExecutionException.class, () -> pipeline.run(List.of("first", "last")));
        assertSame(outputError, failure.getCause());
    }

    @Test
    void aFatalProducerFailureTerminatesTheBatch() {
        var pipeline = new Pipeline(url -> { throw new AssertionError("fatal fetch"); },
                HtmlLinks::extract, result -> fail("unexpected result"), 1);
        var failure = assertThrows(ExecutionException.class, () -> pipeline.run(List.of("first", "last")));
        assertInstanceOf(AssertionError.class, failure.getCause());
        assertEquals(0, failure.getCause().getSuppressed().length);
    }

    @Test
    void aPeerThatAlreadyFinishedAddsNoFailure() throws Exception {
        var primary = new AssertionError("output failed");
        Pipeline.collectPeerFailure(primary, java.util.concurrent.CompletableFuture.completedFuture(null));
        assertEquals(0, primary.getSuppressed().length);
    }

    @Test
    void emptyInputTerminatesWithoutAnyOutput() throws Exception {
        var outcomes = new ArrayList<Outcome>();
        new Pipeline(url -> PAGE, HtmlLinks::extract, outcomes::add, 1).run(List.of());
        assertTrue(outcomes.isEmpty());
    }
}
