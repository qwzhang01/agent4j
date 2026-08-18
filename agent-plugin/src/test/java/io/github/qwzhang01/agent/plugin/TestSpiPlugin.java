package io.github.qwzhang01.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;

/**
 * SPI-discovered plugin used by {@link PluginManagerTest}.
 * Registered in {@code META-INF/services/io.github.qwzhang01.agent.plugin.ToolPlugin}.
 */
public class TestSpiPlugin implements ToolPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("test-spi-plugin", "1.0.0", "SPI fixture for unit tests");
    }

    @Override
    public void onLoad(PluginContext context) {
        context.getToolRegistry().register(new Tool() {
            @Override
            public String getName() {
                return "spi_ping";
            }

            @Override
            public String getDescription() {
                return "SPI ping";
            }

            @Override
            public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }

            @Override
            public String execute(JsonNode arguments) {
                return "pong";
            }
        });
    }

    @Override
    public void onUnload(PluginContext context) {
        context.getToolRegistry().unregister("spi_ping");
    }
}
