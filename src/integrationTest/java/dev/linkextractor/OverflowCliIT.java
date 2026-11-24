package dev.linkextractor;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class OverflowCliIT {
    @Test
    @Timeout(20)
    void stalledStdoutEmitsExactDroppedJsonForEvictedOccurrences() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        // Hold the first output while inputs 2 through 19 compete for 16 queue slots.
        // Keep input 20 in flight until the consumer has reported both evictions and parsed input 4.
        var writing = new CountDownLatch(1);
        var lastRequest = new CountDownLatch(1);
        var fourthOutput = new CountDownLatch(1);
        server.createContext("/", request -> {
            try (request) {
                switch (request.getRequestURI().getPath()) {
                    case "/2" -> assertTrue(writing.await(5, TimeUnit.SECONDS));
                    case "/20" -> {
                        lastRequest.countDown();
                        assertTrue(fourthOutput.await(5, TimeUnit.SECONDS));
                    }
                }
                request.sendResponseHeaders(204, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        var args = new ArrayList<>(List.of("--overflow", "drop-oldest", "--concurrency", "1"));
        for (int i = 1; i <= 20; i++) args.add(base + i);
        var lines = new ArrayList<String>();
        var original = System.out;
        try (var out = new PrintStream(OutputStream.nullOutputStream()) {
            @Override public void println(String line) {
                lines.add(line);
                if (lines.size() == 1) {
                    writing.countDown();
                    try { assertTrue(lastRequest.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                }
                if (lines.size() == 4) fourthOutput.countDown();
            }
        }) {
            System.setOut(out);
            LinkExtractor.main(args.toArray(String[]::new));
        } finally {
            System.setOut(original);
            server.stop(0);
        }
        assertEquals(20, lines.size());
        for (int i = 1; i <= 20; i++) {
            String fields = i == 2 || i == 3 ? "\"status\":\"dropped\"" : "\"status\":\"ok\",\"links\":[]";
            assertEquals("{\"input\":" + i + ",\"url\":\"" + base + i + "\"," + fields + "}", lines.get(i - 1));
        }
    }
}
