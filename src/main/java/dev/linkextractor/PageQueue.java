package dev.linkextractor;

import java.util.LinkedList;

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
    private int notices;

    PageQueue(int capacity, Policy policy) {
        if (capacity < 1) throw new IllegalArgumentException("Queue capacity must be positive");
        this.capacity = capacity;
        this.policy = policy;
    }

    synchronized void put(Message message) throws InterruptedException {
        while (messages.size() - notices >= capacity) {
            if (policy == Policy.DROP_OLDEST && message instanceof Fetched
                    && notices < capacity && replaceOldestPage()) {
                break;
            }
            wait();
        }
        messages.addLast(message);
        notifyAll();
    }

    private boolean replaceOldestPage() {
        var cursor = messages.listIterator();
        while (cursor.hasNext()) {
            if (cursor.next() instanceof Fetched page) {
                cursor.set(new Dropped(Outcome.dropped(page.input(), page.url())));
                notices++;
                return true;
            }
        }
        return false;
    }

    synchronized Message take() throws InterruptedException {
        while (messages.isEmpty()) wait();
        var message = messages.removeFirst();
        if (message instanceof Dropped) notices--;
        notifyAll();
        return message;
    }
}
