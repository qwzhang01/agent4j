package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.RetryModelClient;
import io.github.qwzhang01.agent.core.client.TimeoutModelClient;
import io.github.qwzhang01.agent.core.model.ReasoningConfig;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Auto-configures a {@link ModelClient} and an {@link AgentFactory}.
 * <p>
 * Does not register an {@code Agent} bean — inject {@link AgentFactory} and
 * create agents with per-character system prompts.
 * <p>
 * Bean names: {@code modelClient}, {@code agentFactory}.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
@ConditionalOnProperty(prefix = "agent4j", name = "enabled", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Duration RETRY_INITIAL_BACKOFF = Duration.ofMillis(500);
    private static final double RETRY_BACKOFF_MULTIPLIER = 2.0;

    /**
     * Shared model client. Applications may override by declaring their own
     * {@link ModelClient} bean.
     */
    @Bean
    @ConditionalOnMissingBean(ModelClient.class)
    public ModelClient modelClient(AgentProperties properties) {
        ModelClient client = createBaseClient(properties);
        AgentProperties.CallTimeout callTimeout = properties.getCallTimeout();
        if (callTimeout.isEnabled()) {
            Duration duration = callTimeout.getDuration() != null
                    ? callTimeout.getDuration()
                    : Duration.ofSeconds(30);
            client = new TimeoutModelClient(client, duration);
        }
        AgentProperties.Retry retry = properties.getRetry();
        if (retry.isEnabled()) {
            client = new RetryModelClient(
                    client,
                    retry.getMaxAttempts(),
                    RETRY_INITIAL_BACKOFF,
                    RETRY_BACKOFF_MULTIPLIER);
        }
        return client;
    }

    /**
     * Factory for per-prompt {@code Agent} instances. Uses the {@code modelClient} bean.
     */
    @Bean
    @ConditionalOnBean(ModelClient.class)
    @ConditionalOnMissingBean(AgentFactory.class)
    public AgentFactory agentFactory(ModelClient modelClient) {
        return new AgentFactory(modelClient);
    }

    private static ModelClient createBaseClient(AgentProperties properties) {
        AgentProperties.Model model = properties.getModel();
        String provider = model.getProvider() == null ? "openai" : model.getProvider().trim().toLowerCase();
        ReasoningConfig reasoning = toReasoningConfig(model.getReasoning());
        Duration timeout = model.getTimeout() != null ? model.getTimeout() : Duration.ofSeconds(60);
        return switch (provider) {
            case "openai" -> new OpenAiModelClient(
                    model.getBaseUrl(),
                    model.getApiKey(),
                    model.getName(),
                    timeout,
                    // flavor auto-detected from baseUrl; explicit reasoning default
                    null,
                    reasoning,
                    model.getExtraBody());
            case "mock" -> MockModelClient.ruleBased();
            default -> throw new IllegalArgumentException(
                    "Unknown agent4j.model.provider '" + model.getProvider()
                            + "'. Supported values: openai, mock");
        };
    }

    /**
     * Translates the YAML block into the core {@link ReasoningConfig}.
     * {@code auto} with no effort hint collapses to null so the core default applies.
     */
    private static ReasoningConfig toReasoningConfig(AgentProperties.Model.Reasoning reasoning) {
        if (reasoning == null) {
            return null;
        }
        ReasoningConfig.Mode mode = switch (reasoning.getMode()) {
            case enabled -> ReasoningConfig.Mode.ENABLED;
            case disabled -> ReasoningConfig.Mode.DISABLED;
            default -> ReasoningConfig.Mode.AUTO;
        };
        boolean hasEffort = reasoning.getEffort() != null && !reasoning.getEffort().isBlank();
        if (mode == ReasoningConfig.Mode.AUTO && !hasEffort) {
            return null;
        }
        return new ReasoningConfig(mode, reasoning.getEffort());
    }
}
