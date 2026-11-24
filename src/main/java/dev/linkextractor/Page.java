package dev.linkextractor;

import java.net.URI;

/** Carries the final response URI for resolving relative links and the original bytes for charset detection. */
record Page(URI effectiveUri, Markup markup) {
    record Markup(byte[] bytes, String charset) {}
}
