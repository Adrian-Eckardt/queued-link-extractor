package dev.linkextractor;

import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class LinkExtractor {
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
                    throw new OutputClosed();
                }
            }, QUEUE_CAPACITY);
            pipeline.run(List.of(args));
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
