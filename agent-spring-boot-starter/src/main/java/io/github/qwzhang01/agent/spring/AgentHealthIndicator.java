package io.github.qwzhang01.agent.spring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight six-face health check (Stage 8.2): Model, Store, Scheduler,
 * MCP, A2A, Sandbox — the roadmap's "提供健康检查" bar.
 * <p>
 * Deliberately NOT a Spring Boot actuator {@code HealthIndicator}: the
 * starter must not force the actuator onto every consumer's classpath.
 * Instead this is a plain interface plus one aggregated reporter; apps
 * that DO use the actuator can adapt it with a three-line lambda
 * ({@code HealthIndicator} delegating to {@link #report()}), apps that
 * don't can expose it as a plain controller endpoint.
 * <p>
 * Each face is optional: a deployment without MCP simply reports
 * {@code NOT_CONFIGURED} — absent is not unhealthy. A face reports
 * {@code DOWN} only when it is configured and its check throws or
 * returns false. Checks run sequentially in face order; a thrown
 * exception is captured as DOWN with the message, never propagated.
 */
public interface AgentHealthIndicator {

    /** Face name: model / store / scheduler / mcp / a2a / sandbox. */
    String face();

    /**
     * Probe this face. Return true for healthy, false for configured-but-
     * failing. Throwing is allowed and treated as DOWN. A face that is
     * not configured at all should return {@code false} from
     * {@link #isConfigured()} and will report NOT_CONFIGURED.
     */
    boolean healthy() throws Exception;

    /**
     * Whether this face is configured at all. Default true; a face whose
     * owning module/bean is absent overrides this to report
     * NOT_CONFIGURED (absent is not unhealthy).
     */
    default boolean isConfigured() {
        return true;
    }

    /** Aggregated status record for one face. */
    record FaceStatus(String face, Status status, String detail) {

        public enum Status { UP, DOWN, NOT_CONFIGURED }
    }

    /** Collect one FaceStatus per indicator, exceptions never propagate. */
    static List<FaceStatus> report(java.util.Collection<? extends AgentHealthIndicator> indicators) {
        List<FaceStatus> out = new java.util.ArrayList<>();
        for (AgentHealthIndicator indicator : indicators) {
            String face = indicator.face();
            if (!indicator.isConfigured()) {
                out.add(new FaceStatus(face, FaceStatus.Status.NOT_CONFIGURED, "absent"));
                continue;
            }
            try {
                boolean up = indicator.healthy();
                out.add(up
                        ? new FaceStatus(face, FaceStatus.Status.UP, "ok")
                        : new FaceStatus(face, FaceStatus.Status.DOWN, "check returned false"));
            } catch (Exception e) {
                out.add(new FaceStatus(face, FaceStatus.Status.DOWN, e.toString()));
            }
        }
        return out;
    }

    /** Render the report as a flat map (handy for JSON endpoints). */
    static Map<String, Object> asMap(List<FaceStatus> statuses) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("overall", statuses.stream().allMatch(s -> s.status() != FaceStatus.Status.DOWN)
                ? "UP" : "DOWN");
        for (FaceStatus s : statuses) {
            map.put(s.face(), s.status() + (s.detail() == null || s.detail().equals("ok")
                    ? "" : " (" + s.detail() + ")"));
        }
        return map;
    }
}
