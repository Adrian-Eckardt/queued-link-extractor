package dev.linkextractor;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class HttpPageFetcherIT {
    private HttpServer server;
    private HttpClient client;
    private java.util.concurrent.ExecutorService handlers;
    private String origin;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        handlers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(handlers);
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        handlers.shutdownNow();
        handlers.close();
        client.close();
    }

    private void serve(String path, String type, String body) {
        server.createContext(path, exchange -> {
            try (exchange) {
                if (type != null) exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(body.getBytes(ISO_8859_1));
            }
        });
    }

    @Test
    void appliesTheDeadlineAfterHeadersAndContinuesToTheNextUrl() throws Exception {
        var release = new CountDownLatch(1);
        var sent = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('<');
                exchange.getResponseBody().flush();
                sent.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        serve("/good", "text/html", "<a href='/next'>");
        var results = new ArrayList<Outcome>();
        var fetcher = new HttpPageFetcher(client, Duration.ofSeconds(1), 256);
        try {
            new Pipeline(fetcher, HtmlLinks::extract, results::add, 1, 1)
                    .run(List.of(origin + "/slow", origin + "/good"));
            assertEquals(0, sent.getCount());
            assertEquals("fetch", results.getFirst().stage());
            assertTrue(results.getFirst().error().contains("Download exceeded"));
            assertEquals(List.of(origin + "/next"), results.getLast().links());
        } finally {
            release.countDown();
        }
    }

    @Test
    void enforcesTheByteLimitOnChunkedBodiesAndAcceptsTheExactLimit() throws Exception {
        serve("/fits", "text/html", "1234");
        serve("/large", "text/html", "12345");
        var fetcher = new HttpPageFetcher(client, Duration.ofSeconds(2), 4);
        assertThrows(IOException.class, () -> fetcher.get(origin + "/large"));
        assertEquals(4, fetcher.get(origin + "/fits").markup().bytes().length);
    }

    @Test
    void checksMediaTypesAndHonorsHeaderAndDocumentCharsets() throws Exception {
        var fetcher = new HttpPageFetcher(client);
        serve("/meta", "text/html", "<meta charset=windows-1252><a href='/café'>");
        serve("/header", "application/xhtml+xml; charset=\"ISO-8859-1\"", "<meta charset=utf-8><a href='/café'>");
        serve("/pdf", "application/pdf", "binary");
        serve("/unspecified", null, "<p>hi");
        assertEquals(List.of(origin + "/café"), HtmlLinks.extract(fetcher.get(origin + "/meta")));
        assertEquals(List.of(origin + "/café"), HtmlLinks.extract(fetcher.get(origin + "/header")));
        assertTrue(assertThrows(IOException.class, () -> fetcher.get(origin + "/pdf"))
                .getMessage().contains("Unsupported Content-Type"));
        assertTrue(assertThrows(IOException.class, () -> fetcher.get(origin + "/unspecified"))
                .getMessage().contains("Missing Content-Type"));
    }

    @Test
    void retriesOneRateLimitedGetAndStopsOnPersistent429OrAuthorizationErrors() throws Exception {
        var fetcher = new HttpPageFetcher(client);
        for (int status : new int[]{401, 403, 429}) {
            var hits = new java.util.concurrent.atomic.AtomicInteger();
            server.createContext("/s" + status, exchange -> {
                try (exchange) {
                    hits.incrementAndGet();
                    exchange.getResponseHeaders().set("Retry-After", "0");
                    exchange.sendResponseHeaders(status, -1);
                }
            });
            var error = assertThrows(IOException.class, () -> fetcher.get(origin + "/s" + status));
            assertTrue(error.getMessage().contains("HTTP " + status));
            assertEquals(status == 429 ? 2 : 1, hits.get());
        }
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/recover", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Retry-After", "0");
                exchange.sendResponseHeaders(attempts.incrementAndGet() == 1 ? 429 : 204, -1);
            }
        });
        assertTrue(HtmlLinks.extract(fetcher.get(origin + "/recover")).isEmpty());
        assertEquals(2, attempts.get());
    }

    @Test
    void sharedCooldownGatesRedirectHopsWhileAnotherOriginCompletes() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.EPOCH);
        var sleeping = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        var firstWait = new CountDownLatch(1);
        server.createContext("/limited", exchange -> {
            try (exchange) {
                int status = hits.incrementAndGet() == 1 ? 429 : 204;
                exchange.sendResponseHeaders(status, -1);
            }
        });
        var other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String otherOrigin = "http://127.0.0.1:" + other.getAddress().getPort();
        other.createContext("/good", exchange -> { try (exchange) { exchange.sendResponseHeaders(204, -1); } });
        other.createContext("/forward", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", origin + "/limited");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        other.start();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            // Both waits use virtual time and release together after the healthy request finishes.
            var ticks = new java.util.concurrent.atomic.AtomicLong();
            var observed = new OriginCooldowns(now::get, ticks::get, delay -> {
                long wake = ticks.get() + delay.toNanos();
                firstWait.countDown();
                sleeping.countDown();
                release.await();
                ticks.accumulateAndGet(wake, Math::max);
            });
            var shared = new HttpPageFetcher(client, Duration.ofSeconds(10), 256, observed);
            var first = workers.submit(() -> shared.get(origin + "/limited"));
            try {
                assertTrue(firstWait.await(2, TimeUnit.SECONDS));
                var redirected = workers.submit(() -> shared.get(otherOrigin + "/forward"));
                assertTrue(sleeping.await(2, TimeUnit.SECONDS));
                assertEquals(1, hits.get());
                assertTrue(HtmlLinks.extract(shared.get(otherOrigin + "/good")).isEmpty());
                release.countDown();
                assertEquals(origin + "/limited", redirected.get(2, TimeUnit.SECONDS).effectiveUri().toString());
                assertTrue(HtmlLinks.extract(first.get(2, TimeUnit.SECONDS)).isEmpty());
                assertEquals(3, hits.get());
            } finally {
                release.countDown();
            }
        } finally {
            other.stop(0);
        }
    }

    @Test
    void aLongRetryAfterRejectsLaterSameOriginInputsWithoutAnotherRequest() throws Exception {
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/", exchange -> {
            try (exchange) {
                hits.incrementAndGet();
                exchange.getResponseHeaders().set("Retry-After", "120");
                exchange.sendResponseHeaders(429, -1);
            }
        });
        var fetcher = new HttpPageFetcher(client);
        var limited = assertThrows(IOException.class, () -> fetcher.get(origin + "/a"));
        assertTrue(limited.getMessage().startsWith("HTTP 429"));
        var deferred = assertThrows(IOException.class, () -> fetcher.get(origin + "/b"));
        assertEquals("Origin cooling down: delay exceeds wait budget", deferred.getMessage());
        assertEquals(1, hits.get());
    }

    @Test
    void refusesInvalidRedirectsAndStopsRedirectLoops() throws Exception {
        server.createContext("/redirects", exchange -> {
            try (exchange) {
                String location = exchange.getRequestURI().getQuery();
                if (location != null) exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/loop", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/loop");
                exchange.sendResponseHeaders(301, -1);
            }
        });
        var fetcher = new HttpPageFetcher(client);
        assertTrue(assertThrows(IOException.class, () -> fetcher.get(origin + "/redirects"))
                .getMessage().contains("redirect not followed"));
        assertTrue(assertThrows(IOException.class, () -> fetcher.get(origin + "/redirects?ftp://example.org"))
                .getMessage().contains("redirect not followed to ftp://"));
        for (String location : List.of("/bad path", "https://missing_host/")) {
            String query = location.replace(" ", "%20");
            assertEquals("HTTP 302 (redirect not followed to " + location + ")",
                    assertThrows(IOException.class, () -> fetcher.get(origin + "/redirects?" + query)).getMessage());
        }
        assertTrue(assertThrows(IOException.class, () -> fetcher.get(origin + "/loop"))
                .getMessage().contains("redirect limit exceeded"));
        assertThrows(java.net.http.HttpTimeoutException.class,
                () -> new HttpPageFetcher(client, Duration.ZERO, 256).get(origin + "/unused"));
        try (var redirectsAutomatically = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            assertThrows(IllegalArgumentException.class, () -> new HttpPageFetcher(redirectsAutomatically));
        }
        var http = java.net.URI.create("http://example.org");
        var https = java.net.URI.create("https://example.org");
        assertTrue(HttpPageFetcher.canFollow(http, https));
        assertFalse(HttpPageFetcher.canFollow(https, http));
    }

    @Test
    void reportsUnfollowedRedirectsAndHandlesBothNoContentStatuses() throws Exception {
        var fetcher = new HttpPageFetcher(client);
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/destination");
                exchange.sendResponseHeaders(300, -1);
            }
        });
        server.createContext("/reset", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(205, -1); }
        });
        String error = assertThrows(IOException.class, () -> fetcher.get(origin + "/redirect")).getMessage();
        assertTrue(error.contains("HTTP 300 (redirect not followed to /destination)"), error);
        assertTrue(HtmlLinks.extract(fetcher.get(origin + "/reset")).isEmpty());
    }
}
