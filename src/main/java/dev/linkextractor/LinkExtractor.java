package dev.linkextractor;

import com.google.gson.GsonBuilder;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

/** Wires the HTTP fetcher, HTML parser and JSON Lines writer for a single command-line invocation. */
public final class LinkExtractor {
    private static final String USAGE = "Usage: queued-link-extractor [--concurrency N] [--overflow block|drop-oldest] URL [URL ...]";
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

        CommandLine command;
        try {
            command = CommandLine.parse(args);
        } catch (IllegalArgumentException invalid) {
            System.err.println(invalid.getMessage());
            System.err.println(USAGE);
            exit.accept(2);
            return;
        }

        var json = new GsonBuilder().disableHtmlEscaping().create();
        try (var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build()) {
            var pipeline = new Pipeline(new HttpPageFetcher(client), HtmlLinks::extract, outcome -> {
                System.out.println(json.toJson(outcome));
                // PrintStream records write failures internally. Surface a closed pipe to stop the workers.
                if (System.out.checkError()) {
                    throw new OutputClosed();
                }
            }, QUEUE_CAPACITY, command.fetchLimit(), command.overflow());
            pipeline.run(command.urls());
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
