package io.github.qwzhang01.agent.plugin;

/**
 * Marker interface for plugins that register tools.
 * <p>
 * Implement this interface and declare it in
 * {@code META-INF/services/io.github.qwzhang01.agent.plugin.ToolPlugin}
 * for ServiceLoader discovery.
 * <p>
 * Example:
 * <pre>{@code
 * public class SearchToolPlugin implements ToolPlugin {
 *     @Override
 *     public PluginDescriptor descriptor() {
 *         return new PluginDescriptor("search-tool", "1.0.0", "Web search");
 *     }
 *
 *     @Override
 *     public void onLoad(PluginContext context) {
 *         context.getToolRegistry().register(new SearchTool());
 *     }
 *
 *     @Override
 *     public void onUnload(PluginContext context) {
 *         context.getToolRegistry().unregister("search_web");
 *     }
 * }
 * }</pre>
 */
public interface ToolPlugin extends Plugin {}
