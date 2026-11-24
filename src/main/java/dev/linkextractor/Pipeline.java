package dev.linkextractor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

record Pipeline(Fetcher fetch, Parser parse, Consumer<Outcome> output, int capacity) {
    interface Fetcher {
        Page get(String url) throws IOException, InterruptedException;
    }

    interface Parser {
        List<String> extract(Page page) throws IOException;
    }

    private sealed interface Message {}
    private record Fetched(int input, String url, Page page) implements Message {}
    private record FetchFailure(Outcome outcome) implements Message {}
    private enum End implements Message { INSTANCE }

    void run(List<String> urls) throws InterruptedException, ExecutionException {
        var queue = new ArrayBlockingQueue<Message>(capacity);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = new ExecutorCompletionService<Void>(workers);
            var producer = completed.submit(() -> { produce(urls, queue); return null; });
            var consumer = completed.submit(() -> { consume(queue); return null; });
            try {
                var first = completed.take();
                var peer = first == producer ? consumer : producer;
                try {
                    first.get();
                } catch (ExecutionException primary) {
                    workers.shutdownNow();
                    collectPeerFailure(primary.getCause(), peer);
                    throw primary;
                }
                peer.get();
            } finally {
                // A failed stage can leave its peer blocked on a full or empty queue.
                workers.shutdownNow();
            }
        }
    }

    static void collectPeerFailure(Throwable primary, java.util.concurrent.Future<Void> peer)
            throws InterruptedException {
        try {
            peer.get();
        } catch (ExecutionException secondary) {
            var cause = secondary.getCause();
            if (!(cause instanceof InterruptedException) && cause != primary) {
                primary.addSuppressed(cause);
            }
        }
    }

    private void produce(List<String> urls, BlockingQueue<Message> queue) throws InterruptedException {
        int input = 0;
        for (var url : urls) {
            input++;
            Message message;
            try {
                message = new Fetched(input, url, fetch.get(url));
            } catch (IOException | RuntimeException e) {
                message = new FetchFailure(Outcome.failure(input, url, "fetch", e));
            }
            queue.put(message);
        }
        queue.put(End.INSTANCE);
    }

    private void consume(BlockingQueue<Message> queue) throws InterruptedException {
        while (true) {
            switch (queue.take()) {
                case Fetched page -> output.accept(parsePage(page));
                case FetchFailure failure -> output.accept(failure.outcome());
                case End ignored -> { return; }
            }
        }
    }

    private Outcome parsePage(Fetched fetched) {
        try {
            return Outcome.success(fetched.input(), fetched.url(), parse.extract(fetched.page()));
        } catch (IOException | RuntimeException e) {
            return Outcome.failure(fetched.input(), fetched.url(), "parse", e);
        }
    }
}
