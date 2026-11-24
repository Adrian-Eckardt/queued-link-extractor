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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class PipelineTest {
    private static final Page PAGE = new Page(URI.create("https://example.org/"), new Page.Markup("<a href='/guide'>".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
    private static final Page UNPARSEABLE = new Page(PAGE.effectiveUri(), new Page.Markup(new byte[0], null));

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void emitsTheFirstResultWhileTheFinalFetchIsInFlightThenDrainsAndExits(int limit) throws Exception {
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
        }, 1, limit);

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

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aBrokenOutputInterruptsTheOutstandingFetch(int limit) {
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
        }, 1, limit);

        var failure = assertThrows(ExecutionException.class, () -> pipeline.run(List.of("fast", "slow")));
        assertEquals("Output closed", failure.getCause().getMessage());
        assertEquals(0, interrupted.getCount());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void failedInputsDoNotPreventOtherOccurrencesFromFinishing(int limit) throws Exception {
        var outcomes = new ArrayList<Outcome>();
        var pipeline = new Pipeline(url -> {
            if (url.equals("offline")) throw new java.io.IOException("Timed out");
            if (url.equals("invalid")) throw new IllegalArgumentException("Invalid URL");
            return url.equals("unparseable") ? UNPARSEABLE : PAGE;
        }, page -> {
            if (page == UNPARSEABLE) throw new IllegalStateException();
            return HtmlLinks.extract(page);
        }, outcomes::add, 1, limit);

        pipeline.run(List.of("good", "offline", "invalid", "unparseable", "good"));
        outcomes.sort(java.util.Comparator.comparingInt(Outcome::input));
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
    void closingOutputReleasesFullQueueAndInflightWorkers() {
        var allStarted = new CountDownLatch(5);
        var interrupted = new CountDownLatch(1);
        var pipeline = new Pipeline(url -> {
            allStarted.countDown();
            if (url.equals("slow")) {
                try { new CountDownLatch(1).await(); }
                finally { interrupted.countDown(); }
            }
            return PAGE;
        }, HtmlLinks::extract, result -> {
            try { assertTrue(allStarted.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new AssertionError(e); }
            throw new IllegalStateException("closed");
        }, 1, 4);
        var error = assertThrows(ExecutionException.class,
                () -> pipeline.run(List.of("slow", "a", "b", "c", "d")));
        assertEquals("closed", error.getCause().getMessage());
        assertEquals(0, interrupted.getCount());
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
        }, 1, 2);
        var failure = assertThrows(ExecutionException.class, () -> pipeline.run(List.of("first", "last")));
        assertSame(outputError, failure.getCause());
    }

    @Test
    void aFatalProducerFailureTerminatesTheBatch() {
        var pipeline = new Pipeline(url -> { throw new AssertionError("fatal fetch"); },
                HtmlLinks::extract, result -> fail("unexpected result"), 1, 1);
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
    void twoFetchesOverlapAndAFreedSlotStartsTheThirdBeforeTheFirstFinishes() throws Exception {
        var firstPair = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var thirdOutput = new CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        var outcomes = new ArrayList<Outcome>();

        var pipeline = new Pipeline(url -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (url.equals("first") || url.equals("second")) {
                    firstPair.countDown();
                    (url.equals("first") ? releaseFirst : releaseSecond).await();
                }
                return PAGE;
            } finally {
                active.decrementAndGet();
            }
        }, HtmlLinks::extract, outcome -> {
            outcomes.add(outcome);
            if (outcome.input() == 3) thirdOutput.countDown();
        }, 1, 2);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = executor.submit(() -> { pipeline.run(List.of("first", "second", "third")); return null; });
            try {
                assertTrue(firstPair.await(2, TimeUnit.SECONDS), "Both slots must be active");
                releaseSecond.countDown();
                assertTrue(thirdOutput.await(2, TimeUnit.SECONDS), "The slow first input must not delay the third");
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
            }
            run.get(2, TimeUnit.SECONDS);
        }
        assertEquals(2, peak.get());
        assertEquals(List.of(2, 3, 1), outcomes.stream().map(Outcome::input).toList());
    }

    @Test
    void rejectsAWindowThatCannotMakeProgress() {
        assertThrows(IllegalArgumentException.class,
                () -> new Pipeline(url -> PAGE, HtmlLinks::extract, result -> {}, 1, 0));
    }

    @Test
    void drainsParallelFetchFailuresIncludingRepeatedInputs() throws Exception {
        var outcomes = new ArrayList<Outcome>();
        var pipeline = new Pipeline(url -> { throw new java.io.IOException("offline"); },
                HtmlLinks::extract, outcomes::add, 1, 2);
        pipeline.run(List.of("same", "same", "same"));
        assertEquals(List.of(1, 2, 3), outcomes.stream().map(Outcome::input).sorted().toList());
        assertTrue(outcomes.stream().allMatch(result -> result.stage().equals("fetch")));
    }

    @Test
    void interruptingTheCoordinatorStopsAllOutstandingFetches() throws Exception {
        var started = new CountDownLatch(2);
        var stopped = new CountDownLatch(2);
        var pipeline = new Pipeline(url -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            finally { stopped.countDown(); }
            return PAGE;
        }, HtmlLinks::extract, result -> fail("unexpected result"), 1, 2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = executor.submit(() -> { pipeline.run(List.of("one", "two", "three")); return null; });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
            } finally {
                run.cancel(true);
            }
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void aSlowConsumerKeepsItsCurrentPageAndEmitsOneOutcomePerOccurrence() throws Exception {
        var parsingFirst = new CountDownLatch(1);
        var releaseParser = new CountDownLatch(1);
        var fourthFetch = new CountDownLatch(1);
        var outcomes = new ArrayList<Outcome>();
        var pipeline = new Pipeline(url -> {
            if (url.equals("second")) parsingFirst.await();
            if (url.equals("fourth")) {
                fourthFetch.countDown();
                releaseParser.await();
            }
            return PAGE;
        }, HtmlLinks::extract, outcome -> {
            outcomes.add(outcome);
            if (outcome.input() == 1) {
                parsingFirst.countDown();
                try { assertTrue(releaseParser.await(4, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
        }, 1, 1, PageQueue.Policy.DROP_OLDEST);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var run = executor.submit(() -> {
                pipeline.run(List.of("first", "second", "third", "fourth"));
                return null;
            });
            try {
                assertTrue(fourthFetch.await(3, TimeUnit.SECONDS), "third handoff must have displaced second");
            } finally {
                releaseParser.countDown();
            }
            run.get(3, TimeUnit.SECONDS);
        }
        assertEquals(List.of(1, 2, 3, 4), outcomes.stream().map(Outcome::input).toList());
        assertEquals("ok", outcomes.getFirst().status());
        assertEquals(Outcome.dropped(2, "second"), outcomes.get(1));
        assertEquals("ok", outcomes.getLast().status());
    }

    @Test
    void emptyInputTerminatesWithoutAnyOutput() throws Exception {
        var outcomes = new ArrayList<Outcome>();
        new Pipeline(url -> PAGE, HtmlLinks::extract, outcomes::add, 1, 4).run(List.of());
        assertTrue(outcomes.isEmpty());
    }
}
