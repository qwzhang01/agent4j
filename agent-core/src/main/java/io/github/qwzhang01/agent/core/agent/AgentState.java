package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable state of an Agent run.
 * <p>
 * This is the single source of truth for one Agent execution:
 * - Conversation messages (history, never agent instructions)
 * - Current step count (for max-step enforcement)
 * - Status (where the loop currently is)
 * <p>
 * Agent instructions belong to {@link AgentConfig#getSystemPrompt()} and are
 * injected by the loop at the model boundary. Old checkpoints must explicitly
 * migrate their leading persona with {@link #migrateLegacySystemPrompt(String)}.
 * <p>
 * Stage 6: Jackson-serializable so {@code AgentNode} can park a snapshot
 * on the workflow blackboard ({@code agentState:{nodeId}}) and restore
 * it after a process restart. Stage 14 records trajectory at the model
 * boundary instead of dumping this object.
 * <p>
 * Stage 19 / P3: identity is still configuration, not history — this object
 * stores only the last active <em>name</em> ({@link #getLastActiveAgentName()}),
 * never an {@link AgentConfig} reference. The loop resolves that name through
 * {@link HandoffTargetResolver} on resume. Old checkpoints without the field
 * stay on the entry persona (Jackson {@code ignoreUnknown}).
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
    private String lastActiveAgentName;
    public AgentState() {
    }

    // ============ Constructors ============

    /** Create conversation history with one user message, without agent instructions. */
    public AgentState(String userInput) {
        messages.add(ChatMessage.user(userInput));
    }

    /**
     * Explicitly migrate the leading persona from an old checkpoint.
     * The caller must supply the OLD agent's exact prompt, not the target agent's.
     * Fails without modifying history if the leading message does not match.
     * Other SYSTEM messages are intentionally not removed: their meaning cannot
     * be inferred safely and requires caller-side migration.
     *
     * @param expectedPrompt exact legacy persona text
     */
    public void migrateLegacySystemPrompt(String expectedPrompt) {
        if (expectedPrompt == null || messages.isEmpty()
                || messages.get(0).role() != ChatRole.SYSTEM
                || !expectedPrompt.equals(messages.get(0).content())) {
            throw new IllegalArgumentException("Legacy leading SYSTEM does not match expectedPrompt");
        }
        messages.remove(0);
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

    /**
     * Name of the config that last owned the loop after a handoff.
     * Null means the entry persona. Persisted so a later
     * {@code agent.run(input, state)} through the entry agent resumes as B.
     */
    public String getLastActiveAgentName() {
        return lastActiveAgentName;
    }

    public void setLastActiveAgentName(String lastActiveAgentName) {
        this.lastActiveAgentName = lastActiveAgentName;
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
        copy.lastActiveAgentName = this.lastActiveAgentName;
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
