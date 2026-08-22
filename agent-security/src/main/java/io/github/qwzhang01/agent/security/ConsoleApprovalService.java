package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.function.Function;

/**
 * Console-based approval service (Stage 9 v1).
 * <p>
 * Three factory modes:
 * <ul>
 *   <li>{@link #autoApprove()} - always approves (for testing / non-interactive)</li>
 *   <li>{@link #autoReject()} - always rejects (for testing)</li>
 *   <li>{@link #console()} - reads from stdin (for interactive demos)</li>
 * </ul>
 * <p>
 * For custom logic (e.g. webhook, Slack, REST API), implement
 * {@link ToolApprovalService} directly or use {@link #callback(Function)}.
 */
public class ConsoleApprovalService implements ToolApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ConsoleApprovalService.class);

    /**
     * Shared scanner over System.in - created once and NEVER closed.
     * <p>
     * Closing a {@link Scanner} closes its underlying stream, and System.in is
     * a process-global, non-reopenable stream. A previous version created a
     * fresh try-with-resources Scanner per request, which closed System.in
     * after the first approval and made every subsequent console approval
     * crash with NoSuchElementException.
     */
    private static Scanner stdinScanner = new Scanner(System.in);

    private final Function<ToolCall, Boolean> decisionFunction;

    private ConsoleApprovalService(Function<ToolCall, Boolean> decisionFunction) {
        this.decisionFunction = decisionFunction;
    }

    /**
     * Always approves - for testing or non-interactive automation.
     */
    public static ConsoleApprovalService autoApprove() {
        return new ConsoleApprovalService(tc -> true);
    }

    /**
     * Always rejects - for testing.
     */
    public static ConsoleApprovalService autoReject() {
        return new ConsoleApprovalService(tc -> false);
    }

    /**
     * Reads y/n from stdin. For interactive demos.
     */
    public static ConsoleApprovalService console() {
        return new ConsoleApprovalService(ConsoleApprovalService::readFromConsole);
    }

    /**
     * Custom decision function. For programmatic approval (webhook, etc.).
     */
    public static ConsoleApprovalService callback(Function<ToolCall, Boolean> fn) {
        return new ConsoleApprovalService(fn);
    }

    @Override
    public boolean request(ToolCall toolCall, String runId) {
        boolean approved = decisionFunction.apply(toolCall);
        log.info("[Approval] Tool '{}' {} (runId={})",
                toolCall.name(), approved ? "approved" : "rejected", runId);
        return approved;
    }

    /**
     * Reads y/n from the shared stdin scanner (never closed). Fails closed
     * (rejects) when stdin is unavailable - closed or at EOF, e.g. in
     * non-interactive/scheduled runs - instead of propagating
     * {@link NoSuchElementException} up through the agent loop.
     */
    private static boolean readFromConsole(ToolCall toolCall) {
        System.out.println("\n[APPROVAL REQUEST]");
        System.out.println("  Tool: " + toolCall.name());
        System.out.println("  Args: " + toolCall.arguments());
        System.out.print("  Allow execution? (y/n): ");
        try {
            String line = stdinScanner.nextLine().trim().toLowerCase();
            return line.equals("y") || line.equals("yes");
        } catch (NoSuchElementException e) {
            log.warn("[Approval] No console input available (stdin closed or EOF), "
                    + "rejecting tool '{}' (runId context: fail-closed)", toolCall.name());
            return false;
        }
    }

    // ============ Test Hook ============

    /**
     * Test hook: replaces the shared stdin scanner. Package-private, only for
     * tests that need to script console input without touching real stdin.
     */
    static void replaceStdinScannerForTest(InputStream in) {
        stdinScanner = new Scanner(in);
    }
}
