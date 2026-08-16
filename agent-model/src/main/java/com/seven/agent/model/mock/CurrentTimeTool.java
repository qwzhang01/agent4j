package com.seven.agent.model.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.seven.agent.core.tool.Tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * A tool that returns the current time.
 * Demonstrates a tool that takes no parameters.
 */
public class CurrentTimeTool implements Tool {

    @Override
    public String getName() {
        return "get_current_time";
    }

    @Override
    public String getDescription() {
        return "Returns the current date and time. Call this when the user asks about the time or date.";
    }

    @Override
    public String getParametersSchema() {
        return "{}"; // No parameters
    }

    @Override
    public String execute(JsonNode arguments) {
        return "Current time: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
