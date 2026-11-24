package dev.linkextractor;

import java.util.List;

record Outcome(int input, String url, String status, List<String> links) {
    static Outcome success(int input, String url, List<String> links) {
        return new Outcome(input, url, "ok", links);
    }
}
