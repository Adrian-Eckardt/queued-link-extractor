package dev.linkextractor;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded FIFO shared by fetch workers and the consumer. All queue state is guarded by one lock. */
final class PageQueue {
    enum Policy { BLOCK, DROP_OLDEST }
    sealed interface Message {}
    record Fetched(int input, String url, Page page) implements Message {}
    record FetchFailure(Outcome outcome) implements Message {}
    record Dropped(Outcome outcome) implements Message {}
    enum End implements Message { INSTANCE }

    private final int capacity;
    private final Policy policy;
    private final LinkedList<Message> messages = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition readable = lock.newCondition();
    private final Condition writable = lock.newCondition();
    // Drop notices keep their FIFO positions and have their own capacity allowance.
    private int notices;

    PageQueue(int capacity, Policy policy) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
        this.policy = policy;
    }

    void put(Message message) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            // End has a reserved final slot and follows every input handoff.
            while (message != End.INSTANCE && messages.size() - notices >= capacity) {
                if (policy == Policy.DROP_OLDEST && notices < capacity && replaceOldestPage()) break;
                writable.await();
            }
            messages.addLast(message);
            readable.signal();
        } finally {
            lock.unlock();
        }
    }

    private boolean replaceOldestPage() {
        var cursor = messages.listIterator();
        while (cursor.hasNext()) {
            if (cursor.next() instanceof Fetched page) {
                // Replacing in place releases the markup and preserves the input's place in output.
                cursor.set(new Dropped(Outcome.dropped(page.input(), page.url())));
                notices++;
                return true;
            }
        }
        return false;
    }

    Message take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (messages.isEmpty()) readable.await();
            var message = messages.removeFirst();
            if (message instanceof Dropped) notices--;
            writable.signalAll();
            return message;
        } finally {
            lock.unlock();
        }
    }
}
