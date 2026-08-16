package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state of an Agent run.
 * <p>
 * This is the single source of truth for one Agent execution:
 * - Conversation messages (history)
 * - Current step count (for max-step enforcement)
 * - Status (where the loop currently is)
 * <p>
 * In stage 6, this will be serialized to CheckpointStore for pause/resume.
 * In stage 14, this will be exported as RL trajectory.
 */
public class AgentState {

    // ============ Status ============

    public enum Status {
        /** Initial state, not yet running */
        IDLE,
        /** Agent is running (calling model or executing tools) */
        RUNNING,
        /** Agent is waiting for tool execution to complete */
        EXECUTING_TOOL,
        /** Agent finished normally */
        DONE,
        /** Agent hit max steps */
        MAX_STEPS_EXCEEDED,
        /** Agent encountered an error */
        ERROR
    }

    // ============ Fields ============

    private final List<ChatMessage> messages = new ArrayList<>();
    private int currentStep = 0;
    private int maxSteps = 10;
    private Status status = Status.IDLE;
    private String lastError;

    // ============ Constructors ============

    public AgentState() {}

    public AgentState(String systemPrompt, String userInput) {
        if (systemPrompt != null) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userInput));
    }

    // ============ Methods ============

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public void incrementStep() {
        this.currentStep++;
    }

    public boolean hasStepsRemaining() {
        return currentStep < maxSteps;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public boolean isTerminal() {
        return status == Status.DONE || status == Status.ERROR || status == Status.MAX_STEPS_EXCEEDED;
    }

    // ============ Snapshot (for stage 6 Checkpoint) ============

    /**
     * Create a snapshot of the current state.
     * Stage 6 will serialize this to CheckpointStore.
     */
    public AgentState snapshot() {
        var copy = new AgentState();
        copy.messages.addAll(this.messages);
        copy.currentStep = this.currentStep;
        copy.maxSteps = this.maxSteps;
        copy.status = this.status;
        copy.lastError = this.lastError;
        return copy;
    }
}
