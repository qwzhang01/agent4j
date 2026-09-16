package io.github.qwzhang01.agent.spring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 8.2 six-face health check: aggregation, exception capture, and
 * the overall verdict.
 */
class AgentHealthIndicatorTest {

    @Test
    void upFacesReportUp() {
        List<AgentHealthIndicator.FaceStatus> report = AgentHealthIndicator.report(List.of(
                healthy("model"),
                healthy("store"),
                healthy("scheduler"),
                healthy("mcp"),
                healthy("a2a"),
                healthy("sandbox")));
        assertThat(report).hasSize(6);
        assertThat(report).allMatch(s -> s.status() == AgentHealthIndicator.FaceStatus.Status.UP);
        assertThat(AgentHealthIndicator.asMap(report))
                .containsEntry("overall", "UP")
                .containsEntry("model", "UP");
    }

    @Test
    void throwingFaceIsCapturedAsDownNotPropagated() {
        List<AgentHealthIndicator.FaceStatus> report = AgentHealthIndicator.report(List.of(
                healthy("model"),
                throwing("mcp")));
        assertThat(report).hasSize(2);
        assertThat(report.get(1).status()).isEqualTo(AgentHealthIndicator.FaceStatus.Status.DOWN);
        assertThat(report.get(1).detail()).contains("boom");
        assertThat(AgentHealthIndicator.asMap(report)).containsEntry("overall", "DOWN");
    }

    @Test
    void falseReturnIsDown() {
        List<AgentHealthIndicator.FaceStatus> report = AgentHealthIndicator.report(List.of(
                healthy("model"),
                unhealthy("store")));
        assertThat(report.get(1).status()).isEqualTo(AgentHealthIndicator.FaceStatus.Status.DOWN);
        assertThat(report.get(1).detail()).isEqualTo("check returned false");
    }

    @Test
    void notConfiguredDoesNotFailOverall() {
        List<AgentHealthIndicator.FaceStatus> report = AgentHealthIndicator.report(List.of(
                healthy("model"),
                notConfigured("a2a")));
        assertThat(report.get(1).status())
                .isEqualTo(AgentHealthIndicator.FaceStatus.Status.NOT_CONFIGURED);
        // NOT_CONFIGURED is not DOWN: absent is not unhealthy
        assertThat(AgentHealthIndicator.asMap(report)).containsEntry("overall", "UP");
    }

    // ---- helpers ----

    private static AgentHealthIndicator healthy(String face) {
        return indicator(face, () -> true);
    }

    private static AgentHealthIndicator unhealthy(String face) {
        return indicator(face, () -> false);
    }

    private static AgentHealthIndicator throwing(String face) {
        return indicator(face, () -> {
            throw new IllegalStateException("boom");
        });
    }

    private static AgentHealthIndicator notConfigured(String face) {
        return new AgentHealthIndicator() {
            @Override
            public String face() {
                return face;
            }

            @Override
            public boolean isConfigured() {
                return false;
            }

            @Override
            public boolean healthy() {
                return false;
            }
        };
    }

    private interface HealthCall {
        boolean call() throws Exception;
    }

    private static AgentHealthIndicator indicator(String face, HealthCall call) {
        return new AgentHealthIndicator() {
            @Override
            public String face() {
                return face;
            }

            @Override
            public boolean healthy() throws Exception {
                return call.call();
            }
        };
    }
}
