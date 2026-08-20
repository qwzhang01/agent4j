package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Agent-to-Agent client interface (Stage 10 v1 -- interface only, no implementation).
 * <p>
 * Sends tasks and messages to other Agents, and retrieves responses.
 * The actual implementation (HTTP/gRPC/MCP-based) and multi-agent orchestration
 * (task graphs, delegation chains) are Stage 11's territory.
 * <p>
 * Stage 10 introduces this interface to prove the A2A data model is usable
 * and to prepare for Stage 11's multi-agent orchestration layer.
 */
public interface A2AClient {

    /**
     * Discover agents and their capabilities.
     */
    List<AgentCard> discoverAgents();

    /**
     * Send a task to another Agent. Returns the result (synchronous v1).
     *
     * @param task the task to delegate
     * @return the result from the recipient Agent
     */
    JsonNode sendTask(A2ATask task);

    /**
     * Get the status of a previously sent task.
     *
     * @param taskId the task identifier
     * @return status string (e.g. "pending", "running", "completed", "failed")
     */
    String getTaskStatus(String taskId);

    /**
     * Send a message to another Agent (fire-and-forget, no response expected).
     *
     * @param message the message to send
     */
    void sendMessage(A2AMessage message);
}
