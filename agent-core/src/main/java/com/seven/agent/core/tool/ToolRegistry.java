package com.seven.agent.core.tool;

import java.util.List;
import java.util.Optional;

/**
 * Registry for tools available to an Agent.
 * <p>
 * Design principle: Registry and Executor are separated because:
 * - Registry is about "what tools exist" (metadata)
 * - Executor is about "how to run a tool safely" (execution + error handling)
 * <p>
 * In stage 3, this will be backed by the Plugin system for hot-pluggable tools.
 * For now (stage 1-2), it's a simple in-memory map.
 */
public interface ToolRegistry {

    /**
     * Register a tool.
     */
    void register(Tool tool);

    /**
     * Unregister a tool by name.
     */
    void unregister(String name);

    /**
     * Get a tool by name.
     */
    Optional<Tool> getTool(String name);

    /**
     * List all registered tools.
     */
    List<Tool> listTools();

    /**
     * Get tool descriptions as JSON schema strings (for ModelRequest.tools).
     */
    List<String> getToolSchemas();
}
