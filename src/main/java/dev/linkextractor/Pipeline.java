package dev.linkextractor;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

record Pipeline(Fetcher fetch, Parser parse, Consumer<Outcome> output, int capacity, int fetchLimit) {
    Pipeline {
        if (fetchLimit < 1) throw new IllegalArgumentException("Concurrency must be positive");
    }

    Pipeline(Fetcher fetch, Parser parse, Consumer<Outcome> output, int capacity) {
        this(fetch, parse, output, capacity, 1);
    }

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
        if (urls.isEmpty()) return;
        var queue = new ArrayBlockingQueue<Message>(capacity);
        var remaining = new AtomicInteger(urls.size());
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = new ExecutorCompletionService<Void>(workers);
            var pending = new HashSet<Future<Void>>();
            pending.add(completed.submit(() -> { consume(queue); return null; }));
            var inputs = urls.listIterator();
            try {
                do {
                    // The consumer occupies one entry until all inputs have been handed off.
                    while (inputs.hasNext() && pending.size() <= fetchLimit) {
                        int input = inputs.nextIndex() + 1;
                        String url = inputs.next();
                        pending.add(completed.submit(() -> {
                            queue.put(fetchPage(input, url));
                            if (remaining.decrementAndGet() == 0) queue.put(End.INSTANCE);
                            return null;
                        }));
                    }
                    var finished = completed.take();
                    pending.remove(finished);
                    finished.get();
                } while (!pending.isEmpty());
            } catch (ExecutionException failure) {
                workers.shutdownNow();
                for (var peer : pending) collectPeerFailure(failure.getCause(), peer);
                throw failure;
            } finally {
                workers.shutdownNow();
            }
        }
    }

    static void collectPeerFailure(Throwable primary, Future<Void> peer)
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

    private Message fetchPage(int input, String url) throws InterruptedException {
        try {
            return new Fetched(input, url, fetch.get(url));
        } catch (IOException | RuntimeException e) {
            return new FetchFailure(Outcome.failure(input, url, "fetch", e));
        }
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
