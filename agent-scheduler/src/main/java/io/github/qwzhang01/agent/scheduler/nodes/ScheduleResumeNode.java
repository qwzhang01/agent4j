package io.github.qwzhang01.agent.scheduler.nodes;

import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;

import java.time.Duration;

/**
 * Workflow node that schedules a resume after a delay, then pauses.
 * <p>
 * On first execution: registers a scheduled resume with the scheduler
 * and throws PauseException. The scheduler auto-resumes after the delay.
 * On resume: passes through (the scheduled resume happened).
 * <p>
 * Usage:
 * <pre>{@code
 * .node(ScheduleResumeNode.of("check-later", Duration.ofHours(2)))
 * }</pre>
 */
public final class ScheduleResumeNode implements WorkflowNode {

    private final String id;
    private final Duration delay;
    private final boolean recurring;

    private ScheduleResumeNode(String id, Duration delay, boolean recurring) {
        this.id = id;
        this.delay = delay;
        this.recurring = recurring;
    }

    /** One-time resume after delay. */
    public static ScheduleResumeNode of(String id, Duration delay) {
        return new ScheduleResumeNode(id, delay, false);
    }

    /** Recurring resume every interval. */
    public static ScheduleResumeNode recurring(String id, Duration interval) {
        return new ScheduleResumeNode(id, interval, true);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        Object sched = ctx.scheduler();
        if (!(sched instanceof TaskScheduler scheduler)) {
            throw new IllegalStateException("ScheduleResumeNode requires a TaskScheduler in the context");
        }

        if (ctx.isResuming()) {
            // Resume path: the scheduled resume happened
            return NodeResult.of("resumed after " + delay);
        } else {
            // First execution: schedule and pause
            if (recurring) {
                scheduler.scheduleRecurringResume(ctx.runId(), delay);
            } else {
                scheduler.scheduleResume(ctx.runId(), delay);
            }
            throw new PauseException(id, "scheduled resume in " + delay);
        }
    }
}
