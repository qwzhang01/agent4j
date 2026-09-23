package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentEvent;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auto-configuration integration: profile property flows into
 * the factory, the check/health/shutdown beans exist, and legacy
 * behaviour (mock provider, factory.create) keeps working.
 */
class Stage82AutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));

    @Test
    void defaultProfileIsSecureAndAllStage82BeansExist() {
        contextRunner
                .withPropertyValues("agent4j.model.provider=mock")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context).hasSingleBean(AgentHealthIndicator.class);
                    assertThat(context).hasSingleBean(GracefulShutdownCoordinator.class);
                    assertThat(context).hasSingleBean(HighRiskConfigCheck.class);
                    assertThat(context.getBean(AgentFactory.class).profile())
                            .isEqualTo(AgentProfile.SECURE);

                    // read-only registry: governance does not break the loop.
                    Agent agent = context.getBean(AgentFactory.class)
                            .create("secure-mock", "you are a test");
                    List<AgentEvent> events = new ArrayList<>();
                    agent.stream("hi", events::add);
                    assertThat(events).isNotEmpty();
                });
    }

    @Test
    void testProfilePropertyFlowsThrough() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=mock",
                        "agent4j.profile=test")
                .run(context -> {
                    assertThat(context.getBean(AgentFactory.class).profile())
                            .isEqualTo(AgentProfile.TEST);
                    // mock provider + test profile + auto-approve on:
                    // HIGH check must NOT block a test boot.
                    assertThat(context).hasSingleBean(AgentFactory.class);
                });
    }

    @Test
    void unsafeProfilePropertyFlowsThroughAndSkipsCheckFindings() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=mock",
                        "agent4j.profile=unsafe")
                .run(context -> {
                    assertThat(context.getBean(AgentFactory.class).profile())
                            .isEqualTo(AgentProfile.UNSAFE);
                    // UNSAFE skips findings: the check bean exists but has
                    // nothing to report.
                    HighRiskConfigCheck check = context.getBean(HighRiskConfigCheck.class);
                    assertThat(check.inspect(true, true)).isEmpty();
                });
    }

    @Test
    void secureProfileWithAutoApproveFailsTheBoot() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=mock",
                        "agent4j.profile=secure",
                        "agent4j.approval.auto-approve=true")
                .run(context -> {
                    // The HIGH finding (SECURE + auto-approve) blocks boot.
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("high-risk configuration check FAILED");
                });
    }

    @Test
    void configVersionPropertyIsBound() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=mock",
                        "agent4j.config-version.value=2026.09.16-1")
                .run(context -> {
                    assertThat(context.getBean(AgentProperties.class)
                            .getConfigVersion().getValue()).isEqualTo("2026.09.16-1");
                });
    }

    @Test
    void shutdownDrainTimeoutPropertyIsBound() {
        contextRunner
                .withPropertyValues(
                        "agent4j.model.provider=mock",
                        "agent4j.shutdown.drain-timeout=45s")
                .run(context -> {
                    assertThat(context.getBean(AgentProperties.class)
                            .getShutdown().getDrainTimeout().toSeconds()).isEqualTo(45);
                });
    }
}
