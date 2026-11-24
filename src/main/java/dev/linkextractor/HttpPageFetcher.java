package dev.linkextractor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.hc.core5.http.ContentType;

record HttpPageFetcher(HttpClient client, Duration deadline, int maxBytes) implements Pipeline.Fetcher {
    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private static final Set<Integer> REDIRECTS = Set.of(300, 301, 302, 303, 307, 308);

    HttpPageFetcher(HttpClient client) {
        this(client, Duration.ofSeconds(20), 2 * 1024 * 1024);
    }

    @Override
    public Page get(String url) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(URI.create(url)).GET().build();
        var exchange = client.sendAsync(request, this::readHtml);
        try {
            var response = exchange.get(deadline.toNanos(), TimeUnit.NANOSECONDS);
            return new Page(response.uri(), response.body());
        } catch (TimeoutException e) {
            throw new HttpTimeoutException("Download exceeded " + deadline);
        } catch (ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        } finally {
            // Also cancel an in-flight exchange if the producer is interrupted.
            exchange.cancel(true);
        }
    }

    private BodySubscriber<Page.Markup> readHtml(HttpResponse.ResponseInfo response) {
        int status = response.statusCode();
        if (REDIRECTS.contains(status)) {
            String location = response.headers().firstValue("Location").map(value -> " to " + value).orElse("");
            throw new IllegalArgumentException("HTTP " + status + " (redirect not followed" + location + ")");
        }
        if (status / 100 != 2) {
            throw new IllegalArgumentException("HTTP " + status);
        }
        if (status == 204 || status == 205) {
            return BodySubscribers.limiting(BodySubscribers.replacing(new Page.Markup(new byte[0], null)), 0);
        }
        String header = response.headers().firstValue("Content-Type")
                .orElseThrow(() -> new IllegalArgumentException("Missing Content-Type"));
        ContentType type = ContentType.parse(header);
        if (!HTML_TYPES.contains(type.getMimeType().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported Content-Type: " + header);
        }
        var bytes = BodySubscribers.limiting(BodySubscribers.ofByteArray(), maxBytes);
        return BodySubscribers.mapping(bytes, body -> new Page.Markup(body, Objects.toString(type.getCharset(), null)));
    }
}
