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
    void explainsUnknownOptionsMissingValuesAndInvalidLimits() {
        for (String[] args : List.of(new String[]{"--help"}, new String[]{"--help", "url"})) {
            assertEquals("Unknown option: --help", assertThrows(IllegalArgumentException.class,
                    () -> CommandLine.parse(args)).getMessage());
        }
        assertEquals("Missing value for --overflow", assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[]{"--overflow", "--concurrency", "1", "url"})).getMessage());
        for (String limit : List.of("many", "0", "65")) {
            assertEquals("--concurrency requires an integer from 1 to 64", assertThrows(IllegalArgumentException.class,
                    () -> CommandLine.parse(new String[]{"--concurrency", limit, "url"})).getMessage());
        }
    }

    @Test
    void rejectsUnknownPoliciesAndDuplicateOptions() {
        assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[]{"--overflow", "other", "url"}));
        assertThrows(IllegalArgumentException.class,
                () -> CommandLine.parse(new String[]{"--overflow", "block", "--overflow", "drop-oldest", "url"}));
    }
}
