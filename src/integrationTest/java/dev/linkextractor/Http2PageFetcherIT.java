package dev.linkextractor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import okio.Sink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class Http2PageFetcherIT {
    @Test
    void redirectsAndRetriesWithoutWaitingForUnfinishedHttp2Bodies() throws Exception {
        var address = InetAddress.getByName("127.0.0.1");
        var certificate = new HeldCertificate.Builder().addSubjectAlternativeName(address.getHostAddress()).build();
        var serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        var clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        try (var server = new MockWebServer();
             var client = HttpClient.newBuilder().sslContext(clientTls.sslContext()).build()) {
            server.useHttps(serverTls.sslSocketFactory());
            server.start(address, 0);
            String origin = "https://127.0.0.1:" + server.getPort();
            var fetcher = new HttpPageFetcher(client, Duration.ofSeconds(5), 1024);

            // Send the headers and leave the stream open until the client discards the body.
            var openBodies = new LinkedBlockingQueue<Sink>();
            var redirect = new MockResponse.Builder().code(302).addHeader("location", "/final/page")
                    .socketHandler(stream -> openBodies.add(stream.getSink())).build();
            var limited = new MockResponse.Builder().code(429).addHeader("retry-after", "0")
                    .socketHandler(stream -> openBodies.add(stream.getSink())).build();
            server.enqueue(redirect);
            server.enqueue(new MockResponse.Builder().addHeader("content-type", "text/html")
                    .body("<a href='guide'>Guide</a>").build());
            server.enqueue(limited);
            server.enqueue(limited);

            assertEquals(List.of(origin + "/final/guide"), HtmlLinks.extract(fetcher.get(origin + "/start")));
            var exhausted = assertThrows(IOException.class, () -> fetcher.get(origin + "/limited"));
            assertEquals("HTTP 429 (retry exhausted)", exhausted.getMessage());
            assertEquals(4, server.getRequestCount());
            for (var path : List.of("/start", "/final/page", "/limited", "/limited")) {
                var request = server.takeRequest();
                assertEquals(path, request.getTarget());
                assertEquals("HTTP/2", request.getVersion());
            }

            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    for (int discarded = 0; discarded < 3; discarded++) {
                        assertClientReset(openBodies.take());
                    }
                }, "The server should observe CANCEL on the redirect and both 429 streams");
            } finally {
                // A regression can leave unread bodies open. Abort them before closing the test client.
                client.shutdownNow();
            }
        }
    }

    private static void assertClientReset(Sink body) {
        // Flushing an empty server sink probes its stream without sending data or ending the response.
        var reset = assertThrows(IOException.class, () -> {
            while (true) {
                body.flush();
                Thread.sleep(10);
            }
        });
        assertEquals("stream was reset: CANCEL", reset.getMessage());
    }
}
