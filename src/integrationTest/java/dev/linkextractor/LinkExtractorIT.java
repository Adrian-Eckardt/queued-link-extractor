package dev.linkextractor;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class LinkExtractorIT {
    @Test
    void cliFollowsRedirectsReportsHttpErrorsAndKeepsDuplicateInputs() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/start", request -> {
            try (request) {
                request.getResponseHeaders().set("Location", "/docs/index.html");
                request.sendResponseHeaders(302, -1);
            }
        });
        server.createContext("/docs/index.html", request -> {
            try (request) {
                byte[] html = "<a href='guide?q=1&amp;page=2'>Guide</a>".getBytes(UTF_8);
                request.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                request.sendResponseHeaders(200, html.length);
                request.getResponseBody().write(html);
            }
        });
        server.createContext("/empty", request -> {
            try (request) { request.sendResponseHeaders(204, -1); }
        });
        server.start();
        try {
            var stdout = new ByteArrayOutputStream();
            var original = System.out;
            try (var capture = new PrintStream(stdout, true, UTF_8)) {
                System.setOut(capture);
                LinkExtractor.main(new String[]{origin + "/start", origin + "/missing", "bad url",
                        origin + "/empty", origin + "/start"});
            } finally {
                System.setOut(original);
            }
            var lines = stdout.toString(UTF_8).lines().toList();
            assertEquals(5, lines.size());
            String page = "{\"input\":1,\"url\":\"" + origin + "/start\",\"status\":\"ok\",\"links\":[\""
                    + origin + "/docs/guide?q=1&page=2\"]}";
            assertEquals(page, lines.get(0));
            assertEquals("{\"input\":2,\"url\":\"" + origin
                    + "/missing\",\"status\":\"error\",\"stage\":\"fetch\",\"error\":\"HTTP 404\"}", lines.get(1));
            assertTrue(lines.get(2).startsWith("{\"input\":3,\"url\":\"bad url\",\"status\":\"error\",\"stage\":\"fetch\""));
            assertEquals("{\"input\":4,\"url\":\"" + origin + "/empty\",\"status\":\"ok\",\"links\":[]}", lines.get(3));
            assertEquals(page.replace("\"input\":1", "\"input\":5"), lines.get(4));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anEmptyCommandShowsUsage() throws Exception {
        var stderr = new ByteArrayOutputStream();
        var original = System.err;
        try (var capture = new PrintStream(stderr, true, UTF_8)) {
            System.setErr(capture);
            LinkExtractor.main(new String[0]);
        } finally {
            System.setErr(original);
        }
        assertTrue(stderr.toString(UTF_8).contains("Usage: queued-link-extractor"));
    }

    @Test
    void aBrokenStandardOutputProducesOneDiagnosticAndExitCodeOne() throws Exception {
        var original = System.out;
        var originalErr = System.err;
        var stderr = new ByteArrayOutputStream();
        var exitCode = new java.util.concurrent.atomic.AtomicInteger();
        try (var closed = new PrintStream(OutputStream.nullOutputStream());
             var capture = new PrintStream(stderr, true, UTF_8)) {
            closed.close();
            System.setOut(closed);
            System.setErr(capture);
            LinkExtractor.run(new String[]{"bad url"}, exitCode::set);
        } finally {
            System.setOut(original);
            System.setErr(originalErr);
        }
        assertEquals(1, exitCode.get());
        assertEquals(java.util.List.of("Cannot write to standard output"), stderr.toString(UTF_8).lines().toList());
    }

    @Test
    void unexpectedWorkerFailuresRemainVisibleToTheCaller() {
        var original = System.out;
        try (var broken = new PrintStream(OutputStream.nullOutputStream()) {
            @Override public void println(String line) { throw new AssertionError("writer failed"); }
        }) {
            System.setOut(broken);
            var failure = assertThrows(ExecutionException.class, () -> LinkExtractor.main(new String[]{"bad url"}));
            assertInstanceOf(AssertionError.class, failure.getCause());
        } finally {
            System.setOut(original);
        }
    }
}
