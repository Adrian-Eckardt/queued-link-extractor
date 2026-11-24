package dev.linkextractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.jsoup.Jsoup;

final class HtmlLinks {
    private HtmlLinks() {}

    /** Resolves anchors against the document base and preserves their order and duplicates. */
    static List<String> extract(Page page) throws IOException {
        var document = Jsoup.parse(new ByteArrayInputStream(page.markup().bytes()),
                page.markup().charset(), page.effectiveUri().toString());
        return document.select("a[href]").stream()
                .map(anchor -> anchor.absUrl("href"))
                .filter(java.util.function.Predicate.not(String::isEmpty))
                .toList();
    }
}
