package dev.linkextractor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

record Pipeline(Fetcher fetch, Function<Page, List<String>> parse, Consumer<Outcome> output, int capacity) {
    interface Fetcher {
        Page get(String url) throws IOException, InterruptedException;
    }

    private sealed interface Message {}
    private record Fetched(int input, String url, Page page) implements Message {}
    private enum End implements Message { INSTANCE }

    void run(List<String> urls) throws InterruptedException, ExecutionException {
        var queue = new ArrayBlockingQueue<Message>(capacity);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = new ExecutorCompletionService<Void>(workers);
            completed.submit(() -> { produce(urls, queue); return null; });
            completed.submit(() -> { consume(queue); return null; });
            try {
                completed.take().get();
                completed.take().get();
            } finally {
                // A failed stage can leave its peer blocked on a full or empty queue.
                workers.shutdownNow();
            }
        }
    }

    private void produce(List<String> urls, BlockingQueue<Message> queue) throws IOException, InterruptedException {
        int input = 0;
        for (var url : urls) {
            queue.put(new Fetched(++input, url, fetch.get(url)));
        }
        queue.put(End.INSTANCE);
    }

    private void consume(BlockingQueue<Message> queue) throws InterruptedException {
        while (queue.take() instanceof Fetched fetched) {
            output.accept(Outcome.success(fetched.input(), fetched.url(), parse.apply(fetched.page())));
        }
    }
}
