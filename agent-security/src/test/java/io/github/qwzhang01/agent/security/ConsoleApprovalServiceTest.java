package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the System.in close bug (Stage 9).
 * <p>
 * Bug: readFromConsole used to create a fresh try-with-resources
 * Scanner(System.in) per request. Scanner.close() closes the underlying
 * System.in - a process-global, non-reopenable stream - so the second console
 * approval crashed with NoSuchElementException instead of waiting for input.
 * <p>
 * Fix: one shared scanner, never closed + fail-closed on exhausted stdin.
 */
class ConsoleApprovalServiceTest {

    private static ToolCall tool(String name) {
        return ToolCall.of("call_" + name, name, "{}");
    }

    @Test
    void sharedScannerSurvivesConsecutiveApprovals() {
        // Two scripted answers ("y" then "n") - with the old bug the second
        // request would crash because the first one closed System.in.
        ConsoleApprovalService.replaceStdinScannerForTest(
                new ByteArrayInputStream("y\nn\n".getBytes(StandardCharsets.UTF_8)));

        ConsoleApprovalService service = ConsoleApprovalService.console();

        assertTrue(service.request(tool("danger_a"), "run-1"),
                "first approval should read 'y' and approve");
        assertFalse(service.request(tool("danger_b"), "run-1"),
                "second approval must not crash and should read 'n' and reject");
    }

    @Test
    void failsClosedWhenStdinIsExhausted() {
        // Empty stream -> nextLine() hits EOF -> NoSuchElementException.
        // Approval must fail closed (reject), never propagate the exception.
        ConsoleApprovalService.replaceStdinScannerForTest(
                new ByteArrayInputStream(new byte[0]));

        ConsoleApprovalService service = ConsoleApprovalService.console();

        assertFalse(service.request(tool("danger_c"), "run-2"),
                "EOF/closed stdin must reject (fail-closed), not throw");
    }

    @Test
    void nonYesAnswersAreRejected() {
        ConsoleApprovalService.replaceStdinScannerForTest(
                new ByteArrayInputStream("maybe\n".getBytes(StandardCharsets.UTF_8)));

        ConsoleApprovalService service = ConsoleApprovalService.console();

        assertFalse(service.request(tool("danger_d"), "run-3"),
                "any answer other than y/yes must be rejected");
    }
}
