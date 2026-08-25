package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
 * Stage 6: Jackson-serializable so {@code AgentNode} can park a snapshot
 * on the workflow blackboard ({@code agentState:{nodeId}}) and restore
 * it after a process restart. Stage 14 records trajectory at the model
 * boundary instead of dumping this object.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentState {

    // ============ Status ============

    private final List<ChatMessage> messages = new ArrayList<>();

    // ============ Fields ============
    private int currentStep = 0;
    private int maxSteps = 10;
    private Status status = Status.IDLE;
    private String lastError;
    public AgentState() {
    }

    // ============ Constructors ============

    public AgentState(String systemPrompt, String userInput) {
        if (systemPrompt != null) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(userInput));
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    /** Jackson / checkpoint restore: replace the live history. */
    public void setMessages(List<ChatMessage> messages) {
        this.messages.clear();
        if (messages != null) {
            this.messages.addAll(messages);
        }
    }

    public void setCurrentStep(int currentStep) {
        this.currentStep = currentStep;
    }

    // ============ Methods ============

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

    @JsonIgnore
    public boolean isTerminal() {
        return status == Status.DONE || status == Status.ERROR || status == Status.MAX_STEPS_EXCEEDED;
    }

    /**
     * Create a snapshot of the current state for the workflow blackboard.
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

    // ============ Snapshot (for stage 6 Checkpoint) ============

    public enum Status {
        /**
         * Initial state, not yet running
         */
        IDLE,
        /**
         * Agent is running (calling model or executing tools)
         */
        RUNNING,
        /**
         * Agent is waiting for tool execution to complete
         */
        EXECUTING_TOOL,
        /**
         * Agent finished normally
         */
        DONE,
        /**
         * Agent hit max steps
         */
        MAX_STEPS_EXCEEDED,
        /**
         * Agent encountered an error
         */
        ERROR
    }
}
