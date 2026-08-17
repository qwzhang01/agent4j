package io.github.qwzhang01.agent.workflow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Console-based ApprovalService for interactive demos:
 * prints the request and reads y/n from stdin.
 */
public final class ConsoleApprovalService implements ApprovalService {

    @Override
    public boolean approve(Request request) {
        System.out.println();
        System.out.println("┌─ Approval required ─────────────────────────");
        System.out.println("│ Node:    " + request.nodeId());
        System.out.println("│ Summary: " + request.summary());
        System.out.println("│ Payload: " + request.payload());
        System.out.print("└─ Approve? (y/n): ");

        try {
            String line = new BufferedReader(new InputStreamReader(System.in)).readLine();
            return line != null && line.trim().toLowerCase().startsWith("y");
        } catch (IOException e) {
            return false;
        }
    }
}
