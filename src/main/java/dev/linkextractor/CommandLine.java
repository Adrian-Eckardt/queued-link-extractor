package dev.linkextractor;

import java.util.HashSet;
import java.util.List;

/** Options apply to the whole batch. URL arguments retain their order, including repeated occurrences. */
record CommandLine(List<String> urls, int fetchLimit, PageQueue.Policy overflow) {
    static CommandLine parse(String[] args) {
        var words = List.of(args);
        var seen = new HashSet<String>();
        int limit = Pipeline.DEFAULT_FETCH_LIMIT;
        var policy = PageQueue.Policy.BLOCK;
        int index = 0;
        while (index < words.size() && words.get(index).startsWith("--")) {
            String option = words.get(index++);
            if (!seen.add(option)) throw new IllegalArgumentException("Duplicate option: " + option);

            switch (option) {
                case "--concurrency" -> limit = parseLimit(valueFor(option, words, index++));
                case "--overflow" -> policy = switch (valueFor(option, words, index++)) {
                    case "block" -> PageQueue.Policy.BLOCK;
                    case "drop-oldest" -> PageQueue.Policy.DROP_OLDEST;
                    default -> throw new IllegalArgumentException("--overflow requires block or drop-oldest");
                };
                default -> throw new IllegalArgumentException("Unknown option: " + option);
            }
        }
        var urls = words.subList(index, words.size());
        if (urls.isEmpty()) throw new IllegalArgumentException("Supply at least one URL");
        if (urls.stream().anyMatch(value -> value.startsWith("--"))) {
            throw new IllegalArgumentException("Place options before the URLs");
        }
        return new CommandLine(urls, limit, policy);
    }

    private static String valueFor(String option, List<String> words, int index) {
        if (index == words.size() || words.get(index).startsWith("--")) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return words.get(index);
    }

    private static int parseLimit(String value) {
        try {
            return Pipeline.checkFetchLimit(Integer.parseInt(value));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("--concurrency requires an integer from 1 to " + Pipeline.MAX_FETCH_LIMIT);
        }
    }
}
