package dev.linkextractor;

import java.net.URI;

record Page(URI effectiveUri, String html) {}
