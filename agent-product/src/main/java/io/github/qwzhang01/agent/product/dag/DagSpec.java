package io.github.qwzhang01.agent.product.dag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

/**
 * A JSON-serializable description of a Workflow (Stage 13 M13.5, D5):
 * what a frontend (React Flow etc.) renders and what a visual editor
 * would submit back.
 * <p>
 * The spec carries STRUCTURE, not behavior: node ids and types, edges,
 * and condition <b>names</b> (resolved through a {@link ConditionRegistry}).
 * A predicate that was never registered cannot be exported - the codec
 * fails fast instead of silently dropping a routing branch (D5 honest
 * boundary: an unregistered lambda is invisible to any serializer).
 * <p>
 * START/END are control-flow sentinels: they appear as edge endpoints,
 * never as nodes.
 *
 * @param version    spec version, "v1"
 * @param name       workflow name
 * @param nodes      node id + type (implementation class simple name)
 * @param edges      normal routing edges
 * @param errorEdges onError edges
 */
public record DagSpec(
        String version,
        String name,
        List<NodeSpec> nodes,
        List<EdgeSpec> edges,
        List<EdgeSpec> errorEdges) {

    public DagSpec {
        if (!"v1".equals(version)) {
            throw new IllegalArgumentException("DagSpec version must be 'v1', got " + version);
        }
        Objects.requireNonNull(name, "name must not be null");
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        errorEdges = errorEdges == null ? List.of() : List.copyOf(errorEdges);
    }

    /**
     * A node: id plus its implementation type name (e.g. "AgentNode").
     *
     * @param id   node id
     * @param type implementation class simple name
     */
    public record NodeSpec(String id, String type) {
    }

    /**
     * An edge. {@code when} is the registered condition NAME; null means
     * unconditional.
     *
     * @param from source node id or "__START__"
     * @param to   target node id or "__END__"
     * @param when condition name, null = unconditional
     */
    public record EdgeSpec(String from, String to, String when) {

        /** Unconditional edge convenience constructor. */
        public EdgeSpec(String from, String to) {
            this(from, to, null);
        }
    }

    /**
     * Render as JSON (what the frontend consumes).
     */
    public String toJson() {
        try {
            return new ObjectMapper().writeValueAsString(this);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("DagSpec serialization failed", e);
        }
    }

    /**
     * Parse from JSON (what the frontend submits).
     */
    public static DagSpec fromJson(String json) {
        try {
            return new ObjectMapper().readValue(json, DagSpec.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot parse DagSpec: " + e.getOriginalMessage(), e);
        }
    }
}
