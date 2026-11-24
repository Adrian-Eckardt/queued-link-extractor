package dev.linkextractor;

import java.util.List;
import java.util.Objects;

record Outcome(int input, String url, String status, List<String> links, String stage, String error) {
    static Outcome success(int input, String url, List<String> links) {
        return new Outcome(input, url, "ok", links, null, null);
    }

    static Outcome failure(int input, String url, String stage, Exception cause) {
        var detail = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
        return new Outcome(input, url, "error", null, stage, detail);
    }
}
