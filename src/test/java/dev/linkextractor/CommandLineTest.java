package dev.linkextractor;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineTest {
    @Test
    void optionsCanAppearInEitherOrderBeforeTheUrls() {
        var expected = new CommandLine(List.of("a", "b"), 2, PageQueue.Policy.DROP_OLDEST);
        assertEquals(expected, CommandLine.parse(new String[]{"--overflow", "drop-oldest", "--concurrency", "2", "a", "b"}));
        assertEquals(expected, CommandLine.parse(new String[]{"--concurrency", "2", "--overflow", "drop-oldest", "a", "b"}));
    }

    @Test
    void preservesTheLosslessDefaultAndAcceptsAnExplicitBlockPolicy() {
        var defaults = new CommandLine(List.of("url"), 4, PageQueue.Policy.BLOCK);
        assertEquals(defaults, CommandLine.parse(new String[]{"url"}));
        assertEquals(defaults, CommandLine.parse(new String[]{"--overflow", "block", "url"}));
    }

    @Test
    void rejectsUnknownPoliciesAndDuplicateOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[]{"--overflow", "other", "url"}));
        assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[]{"--overflow", "block", "--overflow", "drop-oldest", "url"}));
    }
}
