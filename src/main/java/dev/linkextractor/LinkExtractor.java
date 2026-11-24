package dev.linkextractor;

import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class LinkExtractor {
    private static final int QUEUE_CAPACITY = 16;

    private LinkExtractor() {}

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        if (args.length == 0) {
            System.err.println("Usage: queued-link-extractor URL [URL ...]");
            return;
        }

        var json = new GsonBuilder().disableHtmlEscaping().create();
        try (var client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            var pipeline = new Pipeline(new HttpPageFetcher(client), HtmlLinks::extract, outcome -> {
                System.out.println(json.toJson(outcome));
                if (System.out.checkError()) {
                    throw new IllegalStateException("Cannot write to standard output");
                }
            }, QUEUE_CAPACITY);
            pipeline.run(List.of(args));
        }
    }
}
