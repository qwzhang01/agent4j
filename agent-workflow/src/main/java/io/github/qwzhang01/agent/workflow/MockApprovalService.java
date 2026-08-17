package io.github.qwzhang01.agent.workflow;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-friendly ApprovalService: returns a fixed decision, counts calls.
 */
public final class MockApprovalService implements ApprovalService {

    private final boolean decision;
    private final AtomicInteger calls = new AtomicInteger();

    public MockApprovalService(boolean decision) {
        this.decision = decision;
    }

    public static MockApprovalService autoApprove() {
        return new MockApprovalService(true);
    }

    public static MockApprovalService autoReject() {
        return new MockApprovalService(false);
    }

    @Override
    public boolean approve(Request request) {
        calls.incrementAndGet();
        return decision;
    }

    public int callCount() {
        return calls.get();
    }
}
