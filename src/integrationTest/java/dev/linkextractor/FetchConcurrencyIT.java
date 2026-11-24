package dev.linkextractor;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class FetchConcurrencyIT {
    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void realRequestsOverlapWithinTheExplicitOrDefaultLimit(int limit) throws Exception {
        var active = new AtomicInteger();
        var peak = new AtomicInteger();
        var firstWave = new CountDownLatch(limit);
        var allStarted = new CountDownLatch(limit + 1);
        var release = new Semaphore(0);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        var args = new ArrayList<String>();
        if (limit == 2) Collections.addAll(args, "--concurrency", "2");
        args.addAll(Collections.nCopies(limit + 1, url));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", request -> {
                try (request) {
                    peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                    firstWave.countDown();
                    allStarted.countDown();
                    try { release.acquire(); }
                    finally { active.decrementAndGet(); }
                    request.sendResponseHeaders(204, -1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            try {
                var cli = executor.submit(() -> { LinkExtractor.main(args.toArray(String[]::new)); return null; });
                try {
                    assertTrue(firstWave.await(4, TimeUnit.SECONDS), "Fetches should overlap");
                    release.release();
                    assertTrue(allStarted.await(4, TimeUnit.SECONDS), "A free slot should admit another URL");
                } finally {
                    release.release(limit + 1);
                }
                cli.get(4, TimeUnit.SECONDS);
                assertEquals(limit, peak.get());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void invalidConcurrencyProducesUsageErrorsBeforeFetching() throws Exception {
        var original = System.err;
        var messages = new ByteArrayOutputStream();
        try (var capture = new PrintStream(messages, true, UTF_8)) {
            System.setErr(capture);
            for (var args : List.of(List.of("--concurrency"), List.of("--concurrency", "1"),
                    List.of("--concurrency", "0", "unused"), List.of("--concurrency", "many", "unused"),
                    List.of("--concurrency", "65", "unused"), List.of("unused", "--concurrency", "1"),
                    List.of("--unknown", "unused"))) {
                var code = new AtomicInteger();
                LinkExtractor.run(args.toArray(String[]::new), code::set);
                assertEquals(2, code.get());
            }
        } finally {
            System.setErr(original);
        }
        assertEquals(7, messages.toString(UTF_8).lines().count());
    }
}
