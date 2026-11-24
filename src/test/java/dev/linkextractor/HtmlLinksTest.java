package dev.linkextractor;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HtmlLinksTest {
    private static final URI LOCATION = URI.create("https://example.org/docs/index.html");

    private static Page page(String html) {
        return new Page(LOCATION, new Page.Markup(html.getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
    }

    @Test
    void resolvesLinksAgainstTheDocumentLocationAndPreservesOrderAndDuplicates() throws Exception {
        var page = page("""
                <a href="guide">Guide</a><a name="part">Section</a>
                <a href="/about">About</a><a href="guide">Guide again</a>
                """);

        assertEquals(List.of("https://example.org/docs/guide", "https://example.org/about",
                "https://example.org/docs/guide"), HtmlLinks.extract(page));
    }

    @Test
    void honorsAnHtmlBaseEvenInAnUnclosedAnchor() throws Exception {
        var page = page("<base href='/manual/'><a href='start'>Start");

        assertEquals(List.of("https://example.org/manual/start"), HtmlLinks.extract(page));
    }

    @Test
    void preservesNonHttpSchemesAndSkipsUnresolvableTargets() throws Exception {
        var input = page("""
                <a href="tel:+123"></a><a href="mailto:a@example.org"></a>
                <a href="data:text/plain,hello"></a><a href="javascript:void(0)"></a>
                <a href="http://[broken"></a>
                """);

        assertEquals(List.of("tel:+123", "mailto:a@example.org", "data:text/plain,hello",
                "javascript:void(0)"), HtmlLinks.extract(input));
    }

    @Test
    void emitsAnEmptyListForAPageWithNoLinks() throws Exception {
        assertEquals(List.of(), HtmlLinks.extract(page("<p>Plain text</p>")));
    }
}
