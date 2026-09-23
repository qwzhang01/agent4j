package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The explicit unsafe path (, harness roadmap).
 * <p>
 * Governance exists as the default; the raw path survives for tests,
 * demos and benchmarks — but it must be asked for by <b>name</b>. The
 * class name carries "Unsafe" so an assembly reads as what it is:
 * <pre>
 *   UnsafeAgentBuilder.unsafe"demo", client, registry).build
 * </pre>
 * Anyone reviewing a diff sees the risk at the call site; it cannot hide
 * inside a generic "AgentBuilder".
 * <p>
 * Unsafe means: bare {@link DefaultToolExecutor} (no permission, no
 * approval, no validation, no timeout, no sanitizer, no audit), unknown
 * tools answered with a string the model may misread, no guardrails.
 * Exactly the 0.1.3 default — frozen behavior, available on request.
 */
public class UnsafeAgentBuilder {

    private static final Logger log = LoggerFactory.getLogger(UnsafeAgentBuilder.class);

    private final String name;
    private final io.github.qwzhang01.agent.core.client.ModelClient modelClient;
    private final ToolRegistry toolRegistry;

    private String systemPrompt;
    private int maxSteps = 10;
    private io.github.qwzhang01.agent.core.agent.ContextBuilder contextBuilder;

    private UnsafeAgentBuilder(String name,
                               io.github.qwzhang01.agent.core.client.ModelClient modelClient,
                               ToolRegistry toolRegistry) {
        this.name = name;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
    }

    public static UnsafeAgentBuilder unsafe(String name,
                                            io.github.qwzhang01.agent.core.client.ModelClient modelClient,
                                            ToolRegistry toolRegistry) {
        log.warn("[RuntimeProfile] UNSAFE agent '{}' assembling: bare DefaultToolExecutor, " +
                "no governance, unknown tools string-returned. " +
                "Use SecureAgentBuilder for anything that touches production.", name);
        return new UnsafeAgentBuilder(name, modelClient, toolRegistry);
    }

    public UnsafeAgentBuilder systemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
        return this;
    }

    public UnsafeAgentBuilder maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    public UnsafeAgentBuilder contextBuilder(io.github.qwzhang01.agent.core.agent.ContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
        return this;
    }

    public AgentConfig buildConfig() {
        return new AgentConfig(name, systemPrompt, modelClient, toolRegistry,
                maxSteps, contextBuilder, java.util.List.of());
    }

    public Agent build() {
        return new SimpleAgent(buildConfig());
    }
}
