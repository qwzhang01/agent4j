package io.github.qwzhang01.agent.channel.collab;

import io.github.qwzhang01.agent.scheduler.TaskStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link TaskBoard} projection (Stage 12 M12.3).
 * The board is fed events directly - proving it is a pure materialized
 * view of the visibility stream (design D6).
 */
class TaskBoardTest {

    private static final String CH = "team-eng";

    private final ExecutionVisibility visibility = new ExecutionVisibility(CH);
    private final TaskBoard board = new TaskBoard();

    TaskBoardTest() {
        visibility.subscribe(board);
    }

    private void emit(VisibilityEvent.Type type, String taskId, String actor, String target, String detail) {
        visibility.publish(VisibilityEvent.of(CH, type, taskId, actor, target, detail));
    }

    @Test
    @DisplayName("TASK_STARTED creates a RUNNING task owned by the actor; detail is the description")
    void taskStarted_createsTask() {
        emit(VisibilityEvent.Type.TASK_STARTED, "t1", "alice", null, "调研 X 库迁移方案");

        ChannelTask task = board.task("t1").orElseThrow();
        assertEquals("调研 X 库迁移方案", task.description());
        assertEquals("alice", task.owner());
        assertEquals(TaskStatus.RUNNING, task.status());
        assertEquals(1, board.size());
    }

    @Test
    @DisplayName("lifecycle events move the status: WAITING_HUMAN -> RESUMED -> COMPLETED")
    void lifecycle_statusTransitions() {
        emit(VisibilityEvent.Type.TASK_STARTED, "t1", "alice", null, "d");
        emit(VisibilityEvent.Type.WAITING_HUMAN, "t1", "alice", "选方案", "waiting");
        assertEquals(TaskStatus.WAITING_HUMAN, board.task("t1").orElseThrow().status());

        emit(VisibilityEvent.Type.RESUMED, "t1", "bob", "bob", "resumed");
        assertEquals(TaskStatus.RUNNING, board.task("t1").orElseThrow().status());

        emit(VisibilityEvent.Type.TASK_COMPLETED, "t1", "alice", null, "done");
        assertEquals(TaskStatus.SUCCEEDED, board.task("t1").orElseThrow().status());
        assertTrue(board.task("t1").orElseThrow().isTerminal());
    }

    @Test
    @DisplayName("TASK_HANDOFF moves ownership to the event target")
    void handoff_movesOwner() {
        emit(VisibilityEvent.Type.TASK_STARTED, "t1", "alice", null, "d");
        emit(VisibilityEvent.Type.TASK_HANDOFF, "t1", "alice", "bob", "初稿在记忆里");

        assertEquals("bob", board.task("t1").orElseThrow().owner());
        assertTrue(board.byOwner("alice").isEmpty());
        assertEquals(1, board.byOwner("bob").size());
    }

    @Test
    @DisplayName("AGENT_REPLIED is conversation-level and never touches the board")
    void agentReplied_ignored() {
        emit(VisibilityEvent.Type.TASK_STARTED, "t1", "alice", null, "d");
        emit(VisibilityEvent.Type.AGENT_REPLIED, null, "eng-bot", "alice", "回答...");

        assertEquals(1, board.size());
        assertEquals(TaskStatus.RUNNING, board.task("t1").orElseThrow().status());
    }

    @Test
    @DisplayName("events for unknown tasks are ignored (projection, not enforcement)")
    void unknownTask_ignored() {
        assertDoesNotThrow(() -> emit(VisibilityEvent.Type.WAITING_HUMAN, "ghost", "a", "x", "y"));
        assertTrue(board.task("ghost").isEmpty());
    }

    @Test
    @DisplayName("read views: byStatus / byOwner / tasks in creation order")
    void readViews() {
        emit(VisibilityEvent.Type.TASK_STARTED, "t1", "alice", null, "first");
        emit(VisibilityEvent.Type.TASK_STARTED, "t2", "bob", null, "second");
        emit(VisibilityEvent.Type.TASK_COMPLETED, "t1", "alice", null, "done");

        assertEquals(List.of("t2"), board.byStatus(TaskStatus.RUNNING).stream().map(ChannelTask::taskId).toList());
        assertEquals(List.of("t1"), board.byStatus(TaskStatus.SUCCEEDED).stream().map(ChannelTask::taskId).toList());
        assertEquals(List.of("t1", "t2"), board.tasks().stream().map(ChannelTask::taskId).toList(),
                "creation order, not completion order");
    }
}
