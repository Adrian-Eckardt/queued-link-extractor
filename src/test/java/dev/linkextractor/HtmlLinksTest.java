package dev.linkextractor;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlLinksTest {
    private static final URI LOCATION = URI.create("https://example.org/docs/index.html");

    @Test
    void resolvesLinksAgainstTheDocumentLocationAndPreservesOrderAndDuplicates() {
        var page = new Page(LOCATION, """
                <a href="guide">Guide</a><a name="part">Section</a>
                <a href="/about">About</a><a href="guide">Guide again</a>
                """);

        assertEquals(List.of("https://example.org/docs/guide", "https://example.org/about",
                "https://example.org/docs/guide"), HtmlLinks.extract(page));
    }

    @Test
    void honorsAnHtmlBaseEvenInAnUnclosedAnchor() {
        var page = new Page(LOCATION, "<base href='/manual/'><a href='start'>Start");

        assertEquals(List.of("https://example.org/manual/start"), HtmlLinks.extract(page));
    }

    @Test
    void emitsAnEmptyListForAPageWithNoLinks() {
        assertEquals(List.of(), HtmlLinks.extract(new Page(LOCATION, "<p>Plain text</p>")));
    }
}
