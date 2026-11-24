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
    void reportsUnfollowedRedirectsAndHandlesBothNoContentStatuses() throws Exception {
        var fetcher = new HttpPageFetcher(client);
        server.createContext("/redirect", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", "/destination");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/reset", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(205, -1); }
        });
        String error = assertThrows(IOException.class, () -> fetcher.get(origin + "/redirect")).getMessage();
        assertTrue(error.contains("HTTP 302 (redirect not followed to /destination)"), error);
        assertTrue(HtmlLinks.extract(fetcher.get(origin + "/reset")).isEmpty());
    }
}
