package dev.linkextractor;

import java.net.URI;

record Page(URI effectiveUri, Markup markup) {
    record Markup(byte[] bytes, String charset) {}
}
