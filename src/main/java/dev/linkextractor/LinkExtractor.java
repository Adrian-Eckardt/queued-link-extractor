package dev.linkextractor;

import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class LinkExtractor {
    private static final String USAGE = "Usage: queued-link-extractor [--concurrency N] URL [URL ...]";
    private static final int QUEUE_CAPACITY = 16;

    private static final class OutputClosed extends RuntimeException {
        OutputClosed() {
            super("Cannot write to standard output");
        }
    }

    private LinkExtractor() {}

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        run(args, System::exit);
    }

    static void run(String[] args, java.util.function.IntConsumer exit) throws InterruptedException, ExecutionException {
        if (args.length == 0) {
            System.err.println(USAGE);
            return;
        }

        var urls = List.of(args);
        int fetchLimit = Pipeline.DEFAULT_FETCH_LIMIT;
        if (urls.getFirst().equals("--concurrency")) {
            if (urls.size() < 3) {
                System.err.println(USAGE);
                exit.accept(2);
                return;
            }
            try {
                fetchLimit = Pipeline.checkFetchLimit(Integer.parseInt(urls.get(1)));
            } catch (IllegalArgumentException invalid) {
                System.err.println("--concurrency requires an integer from 1 to " + Pipeline.MAX_FETCH_LIMIT);
                exit.accept(2);
                return;
            }
            urls = urls.subList(2, urls.size());
        }

        if (urls.stream().anyMatch(value -> value.startsWith("--"))) {
            System.err.println(USAGE);
            exit.accept(2);
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
                    throw new OutputClosed();
                }
            }, QUEUE_CAPACITY, fetchLimit);
            pipeline.run(urls);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof OutputClosed closed) {
                System.err.println(closed.getMessage());
                exit.accept(1);
                return;
            }
            throw failure;
        }
    }
}
