package dev.linkextractor;

import java.util.List;
import org.jsoup.Jsoup;

final class HtmlLinks {
    private HtmlLinks() {}

    static List<String> extract(Page page) {
        var document = Jsoup.parse(page.html(), page.effectiveUri().toString());
        return document.select("a[href]").stream()
                .map(anchor -> anchor.absUrl("href"))
                .filter(java.util.function.Predicate.not(String::isEmpty))
                .toList();
    }
}
