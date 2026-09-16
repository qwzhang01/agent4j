package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.RetryModelClient;
import io.github.qwzhang01.agent.core.client.TimeoutModelClient;
import io.github.qwzhang01.agent.core.model.ReasoningConfig;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.model.openai.OpenAiModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;

/**
 * Auto-configures a {@link ModelClient}, a profile-aware
 * {@link AgentFactory} (Stage 8.2), the boot-time high-risk config check,
 * the six-face health indicator, and the graceful shutdown coordinator.
 * <p>
 * Does not register an {@code Agent} bean — inject {@link AgentFactory}
 * and create agents with per-character system prompts.
 * <p>
 * Bean names: {@code modelClient}, {@code agentFactory},
 * {@code agentHealthIndicator}, {@code gracefulShutdownCoordinator}.
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentProperties.class)
@ConditionalOnProperty(prefix = "agent4j", name = "enabled", matchIfMissing = true)
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

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
     * Stage 8.2 profile-aware factory. SECURE/TEST assemble governed
     * agents via SecureAgentBuilder; UNSAFE is the explicit raw path.
     */
    @Bean
    @ConditionalOnBean(ModelClient.class)
    @ConditionalOnMissingBean(AgentFactory.class)
    public AgentFactory agentFactory(ModelClient modelClient, AgentProperties properties) {
        return new AgentFactory(modelClient, properties.getProfile(), null);
    }

    /**
     * Six-face health indicator (Stage 8.2): model face probes the
     * client's presence; store/scheduler/mcp/a2a/sandbox faces are
     * contributed by their owning modules' beans when present (via
     * {@code AgentHealthIndicator} beans the app or module starters
     * declare), aggregated here.
     */
    @Bean
    public AgentHealthIndicator modelHealthIndicator(ModelClient modelClient) {
        return new AgentHealthIndicator() {
            @Override
            public String face() {
                return "model";
            }

            @Override
            public boolean healthy() {
                return modelClient != null;
            }
        };
    }

    /**
     * Graceful shutdown coordinator (Stage 8.2): gate + bounded drain +
     * straggler cancel. Apps bind it to their runtime's stop hooks.
     */
    @Bean
    public GracefulShutdownCoordinator gracefulShutdownCoordinator() {
        return new GracefulShutdownCoordinator();
    }

    /**
     * Boot-time high-risk config check (Stage 8.2). Runs when the factory
     * bean exists; SECURE blocks the boot on HIGH findings, TEST warns.
     * The check result is logged as one structured block either way.
     * <p>
     * Store/sandbox presence is probed by class NAME via reflection, so
     * this starter stays compile-time independent of agent-workflow /
     * agent-sandbox (they are optional at runtime).
     */
    @Bean
    public HighRiskConfigCheck highRiskConfigCheck(AgentProperties properties,
                                                   org.springframework.context.ApplicationContext appContext) {
        AgentProfile profile = properties.getProfile();
        HighRiskConfigCheck check = new HighRiskConfigCheck(profile, properties);
        boolean storePresent = beanPresent(appContext,
                "io.github.qwzhang01.agent.workflow.runtime.durable.RunStore");
        boolean sandboxPresent = beanPresent(appContext,
                "io.github.qwzhang01.agent.sandbox.Sandbox");
        List<HighRiskConfigCheck.Finding> findings = check.inspect(storePresent, sandboxPresent);
        if (!findings.isEmpty()) {
            log.warn("{}", check.renderLog(findings));
        }
        if (check.shouldBlockBoot(findings)) {
            throw new IllegalStateException(
                    "agent4j high-risk configuration check FAILED under profile=" + profile
                            + " - fix the HIGH findings above or switch profile=test explicitly");
        }
        return check;
    }

    /** True when at least one bean of the named class type exists. */
    private static boolean beanPresent(org.springframework.context.ApplicationContext appContext,
                                        String className) {
        try {
            Class<?> type = Class.forName(className);
            return appContext.getBeanNamesForType(type).length > 0;
        } catch (ClassNotFoundException e) {
            return false; // module not on the classpath at all
        }
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
