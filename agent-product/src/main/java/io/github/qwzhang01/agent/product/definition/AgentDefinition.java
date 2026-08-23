package io.github.qwzhang01.agent.product.definition;

import java.util.List;
import java.util.Objects;

/**
 * Declarative agent definition parsed from YAML/JSON (Stage 13 M13.1, D1: "names in the
 * definition, implementations in the registry").
 * <p>
 * Every non-trivial value in this record is a <b>name reference</b> resolved against
 * {@link io.github.qwzhang01.agent.product.ProductContext} at bind time:
 * <ul>
 *   <li>{@code spec.model.provider} -&gt; a registered {@code ModelClient}</li>
 *   <li>{@code spec.tools[].ref} -&gt; a registered {@code Tool}</li>
 *   <li>{@code spec.memory.contextBuilder} -&gt; a registered {@code ContextBuilder}</li>
 * </ul>
 * The declarative format holds names and parameters only - never logic. That is the
 * mechanical guarantee that YAML stays non-Turing-complete and serializable.
 * <p>
 * M13.1 schema subset (later milestones extend it):
 * <pre>{@code
 * apiVersion: v1
 * kind: Agent
 * metadata:
 *   name: support-bot          # required
 *   tenant: acme               # optional
 * spec:
 *   persona:
 *     systemPrompt: "..."      # required (promptRef arrives in M13.4)
 *     temperature: 0.3         # optional, 0-2
 *   model:
 *     provider: openai         # required, registered model name
 *     fallback: deepseek       # optional, registered model name
 *   tools:
 *     - ref: order-query       # optional, registered tool names (subset semantics)
 *   memory:
 *     shortTerm: { strategy: window, maxMessages: 20 }   # built-in windowing
 *     # or
 *     contextBuilder: rich-memory                          # named ContextBuilder
 * }</pre>
 *
 * @param apiVersion schema version envelope, must be "v1"
 * @param kind       resource kind envelope, must be "Agent"
 * @param metadata   definition identity (name + optional tenant)
 * @param spec       the four-section agent spec
 */
public record AgentDefinition(String apiVersion, String kind, Metadata metadata, Spec spec) {

    public AgentDefinition {
        if (!"v1".equals(apiVersion)) {
            throw new IllegalArgumentException(
                    "apiVersion must be 'v1', got " + (apiVersion == null ? "null" : "'" + apiVersion + "'"));
        }
        if (!"Agent".equals(kind)) {
            throw new IllegalArgumentException(
                    "kind must be 'Agent', got " + (kind == null ? "null" : "'" + kind + "'"));
        }
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        if (metadata.name == null || metadata.name.isBlank()) {
            throw new IllegalArgumentException("metadata.name must not be blank");
        }
        if (spec.tools != null) {
            spec = new Spec(spec.persona, spec.model, List.copyOf(spec.tools), spec.memory,
                    spec.workflow, spec.ambient == null ? null : List.copyOf(spec.ambient));
        }
    }

    /**
     * Definition identity.
     *
     * @param name   unique agent name (registry key)
     * @param tenant optional tenant id (isolation/partitioning, Stage 13 D7)
     */
    public record Metadata(String name, String tenant) {
    }

    /**
     * The agent spec.
     *
     * @param persona        personality: inline system prompt + sampling defaults
     * @param model          model wiring: primary provider + optional fallback
     * @param tools          tool references (subset of the registry; null = no tools)
     * @param memory         context/memory wiring; null = passthrough (Stage 1-7 behavior)
     * @param workflow       optional registered workflow name (DAG export target)
     * @param ambient        optional standing instructions (Stage 12 wiring, M13.5)
     */
    public record Spec(Persona persona, Model model, List<ToolRef> tools, Memory memory,
                       String workflow, List<AmbientDecl> ambient) {
    }

    /**
     * Personality: exactly one of inline prompt or managed prompt reference
     * (validated by DefinitionValidator).
     *
     * @param systemPrompt inline system prompt (simple agents)
     * @param promptRef    reference into the PromptManager (versioned asset, M13.4)
     * @param temperature  sampling temperature default, 0-2, null = provider default
     */
    public record Persona(String systemPrompt, PromptRef promptRef, Double temperature) {
    }

    /**
     * Model wiring.
     *
     * @param provider primary model name (registered)
     * @param fallback fallback model name (registered), null = no fallback
     */
    public record Model(String provider, String fallback) {
    }

    /**
     * A tool entry: EITHER a reference to a registered tool ({@code ref}) OR an
     * inline HTTP API declaration ({@code http}, M13.3). Exactly one must be set.
     *
     * @param ref  registered tool name (D1 reference indirection)
     * @param http inline HTTP API tool declaration (M13.3)
     */
    public record ToolRef(String ref, HttpApiDecl http) {

        public ToolRef {
            boolean hasRef = ref != null && !ref.isBlank();
            boolean hasHttp = http != null;
            if (hasRef == hasHttp) {
                throw new IllegalArgumentException(
                        "each tools[] entry needs exactly one of 'ref' or 'http'");
            }
        }

        /**
         * Backward-compatible single-arg form (registered tool reference).
         */
        public ToolRef(String ref) {
            this(ref, null);
        }

        /**
         * The name this entry contributes to the agent's tool registry:
         * the referenced tool's name, or the inline declaration's name.
         */
        public String toolName() {
            return ref != null && !ref.isBlank() ? ref : http.name();
        }
    }

    /**
     * Memory/context wiring. {@code shortTerm} and {@code contextBuilder} are mutually
     * exclusive (validated); long-term memory configuration arrives in later milestones.
     *
     * @param shortTerm      built-in windowing strategy
     * @param contextBuilder named ContextBuilder (rich strategies stay in Java, D1)
     */
    public record Memory(ShortTerm shortTerm, String contextBuilder) {

        /**
         * Built-in short-term strategy.
         *
         * @param strategy     only "window" is supported in v1
         * @param maxMessages  messages kept verbatim (system prompt excluded), &gt; 0
         */
        public record ShortTerm(String strategy, Integer maxMessages) {
        }
    }

    /**
     * A declarative standing instruction (M13.5, Stage 12 wiring): trigger +
     * importance + message template. The CONDITION predicate stays a Java
     * extension point - YAML declares when to check, not how to judge
     * (declaring judgment in YAML is how you invent a DSL you will regret).
     *
     * @param instructionId   unique instruction id
     * @param description     human-readable description
     * @param trigger         {@code {onEvent: key}} or {@code {schedule: PT10M}}
     * @param importance      INFO / WARN / CRITICAL (Stage 12 D7 noise tiers)
     * @param messageTemplate {@code {$.path}} template against the event payload
     */
    public record AmbientDecl(String instructionId, String description,
                              TriggerDecl trigger, String importance, String messageTemplate) {

        /**
         * Exactly one of onEvent / schedule.
         *
         * @param onEvent  event key to react to
         * @param schedule ISO-8601 duration, e.g. "PT10M" (every 10 minutes)
         */
        public record TriggerDecl(String onEvent, String schedule) {
        }
    }
}
