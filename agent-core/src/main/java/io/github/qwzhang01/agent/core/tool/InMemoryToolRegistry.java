package io.github.qwzhang01.agent.core.tool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of ToolRegistry.
 * <p>
 * Stage 1-2: simple map-based registry.
 * Stage 3: will be replaced/augmented by Plugin-aware registry.
 */
public class InMemoryToolRegistry implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        tools.put(tool.getName(), tool);
    }

    @Override
    public void unregister(String name) {
        tools.remove(name);
    }

    @Override
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    @Override
    public List<Tool> listTools() {
        return new ArrayList<>(tools.values());
    }

    @Override
    public List<String> getToolSchemas() {
        List<String> schemas = new ArrayList<>();
        for (Tool tool : tools.values()) {
            // Build a simple JSON schema string for the model
            String schema = String.format("""
                            {
                              "name": "%s",
                              "description": "%s",
                              "parameters": %s
                            }""".trim(),
                    tool.getName(),
                    tool.getDescription().replace("\"", "\\\""),
                    tool.getParametersSchema() != null ? tool.getParametersSchema() : "{}"
            );
            schemas.add(schema);
        }
        return schemas;
    }
}
