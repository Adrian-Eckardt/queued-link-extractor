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

record HttpPageFetcher(HttpClient client, Duration deadline, int maxBytes, OriginCooldowns cooldowns) implements Pipeline.Fetcher {
    private static final Set<String> HTML_TYPES = Set.of("text/html", "application/xhtml+xml");

    private static final Set<Integer> FOLLOWABLE = Set.of(301, 302, 303, 307, 308);
    private static final Duration WAIT_BUDGET = Duration.ofSeconds(5);
    private static final int MAX_REDIRECTS = 5;

    HttpPageFetcher {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Redirects must pass through the origin cooldown check");
        }
    }

    HttpPageFetcher(HttpClient client, Duration deadline, int maxBytes) {
        this(client, deadline, maxBytes, new OriginCooldowns(java.time.InstantSource.system(), Thread::sleep));
    }

    HttpPageFetcher(HttpClient client) {
        this(client, Duration.ofSeconds(20), 2 * 1024 * 1024);
    }

    @Override
    public Page get(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        long endNanos = System.nanoTime() + deadline.toNanos();
        Duration waitBudget = WAIT_BUDGET;
        boolean retried = false;
        int redirects = 0;
        while (true) {
            var request = HttpRequest.newBuilder(uri).GET().build();
            var remaining = timeLeft(endNanos);
            if (waitBudget.compareTo(remaining) > 0) waitBudget = remaining;
            waitBudget = cooldowns.await(uri, waitBudget);
            var response = exchange(request, timeLeft(endNanos));
            int status = response.statusCode();
            if (status / 100 == 2) return new Page(uri, response.body());
            if (status == 429) {
                cooldowns.record(uri, response.headers().firstValue("Retry-After").orElse(""));
                if (retried) throw new IOException("HTTP 429 (retry exhausted)");
                retried = true;
                continue;
            }
            if (FOLLOWABLE.contains(status) || status == 300) {
                String location = response.headers().firstValue("Location").orElse("");
                if (status == 300 || location.isBlank()) throw redirectError(status, location);
                URI target = uri.resolve(location);
                if (!canFollow(uri, target)) throw redirectError(status, location);
                if (++redirects > MAX_REDIRECTS) throw new IOException("HTTP " + status + " (redirect limit exceeded)");
                uri = target;
                continue;
            }
            throw new IOException("HTTP " + status);
        }
    }

    static boolean canFollow(URI from, URI target) {
        return "https".equalsIgnoreCase(target.getScheme())
                || ("http".equalsIgnoreCase(target.getScheme()) && "http".equalsIgnoreCase(from.getScheme()));
    }

    private static IOException redirectError(int status, String location) {
        String destination = location.isBlank() ? "" : " to " + location;
        return new IOException("HTTP " + status + " (redirect not followed" + destination + ")");
    }

    private Duration timeLeft(long endNanos) throws HttpTimeoutException {
        long nanos = endNanos - System.nanoTime();
        if (nanos <= 0) throw new HttpTimeoutException("Download exceeded " + deadline);
        return Duration.ofNanos(nanos);
    }

    private HttpResponse<Page.Markup> exchange(HttpRequest request, Duration remaining)
            throws IOException, InterruptedException {
        var exchange = client.sendAsync(request, this::readHtml);
        try {
            return exchange.get(remaining.toNanos(), TimeUnit.NANOSECONDS);
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
        if (status / 100 != 2) {
            // Return status and headers immediately. Error bodies can be large or slow.
            return BodySubscribers.mapping(BodySubscribers.ofInputStream(), stream -> {
                org.apache.hc.core5.io.Closer.closeQuietly(stream);
                return null;
            });
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
