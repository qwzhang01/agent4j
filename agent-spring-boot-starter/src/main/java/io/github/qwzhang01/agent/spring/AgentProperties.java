package io.github.qwzhang01.agent.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration properties for the optional agent4j Spring Boot starter.
 * <p>
 * Prefix: {@code agent4j}. Example:
 * <pre>
 * agent4j:
 *   enabled: true
 *   model:
 *     provider: openai   # openai | mock
 *     api-key:
 *     base-url: https://api.openai.com/v1
 *     name: gpt-4o-mini
 *     timeout: 60s
 *     reasoning:            # provider-neutral reasoning intent
 *       mode: disabled      # auto | enabled | disabled
 *       effort: medium      # optional: low | medium | high
 *     extra-body:           # escape hatch for vendor-specific fields
 *       thinking:
 *         budget_tokens: 8000
 *   retry:
 *     enabled: false
 *     max-attempts: 3
 *   call-timeout:
 *     enabled: false
 *     duration: 30s
 * </pre>
 * Core runtime modules stay Spring-free. This starter only wires a
 * {@link io.github.qwzhang01.agent.core.client.ModelClient} and an
 * {@link AgentFactory}; it does not auto-create an {@code Agent} bean.
 */
@ConfigurationProperties(prefix = "agent4j")
public class AgentProperties {

    /**
     * Whether auto-configuration is enabled. Defaults to {@code true}.
     */
    private boolean enabled = true;

    /**
     * Model client settings (provider, endpoint, default model).
     */
    private final Model model = new Model();

    /**
     * Optional {@code RetryModelClient} wrapper around the base client.
     */
    private final Retry retry = new Retry();

    /**
     * Optional {@code TimeoutModelClient} wrapper around the base client.
     */
    private final CallTimeout callTimeout = new CallTimeout();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Model getModel() {
        return model;
    }

    public Retry getRetry() {
        return retry;
    }

    public CallTimeout getCallTimeout() {
        return callTimeout;
    }

    /**
     * Which {@code ModelClient} implementation to construct.
     */
    public static class Model {

        /**
         * Provider id: {@code openai} (OpenAI-compatible HTTP) or {@code mock}
         * (rule-based {@code MockModelClient}, no network).
         */
        private String provider = "openai";

        /**
         * API key for OpenAI-compatible endpoints. Ignored by the mock provider.
         */
        private String apiKey = "";

        /**
         * Base URL of the OpenAI-compatible API (no trailing slash required).
         */
        private String baseUrl = "https://api.openai.com/v1";

        /**
         * Default model name passed to {@code OpenAiModelClient}.
         */
        private String name = "gpt-4o-mini";

        /**
         * Constructor timeout passed to {@code OpenAiModelClient}.
         * Call-level timeout is configured separately under {@code agent4j.call-timeout}.
         */
        private Duration timeout = Duration.ofSeconds(60);

        /**
         * Reasoning ("thinking") control applied to every request unless the
         * request itself carries a {@code ReasoningConfig}.
         */
        private final Reasoning reasoning = new Reasoning();

        /**
         * Vendor-specific fields merged verbatim into every request body.
         * <p>
         * The escape hatch for anything the provider-neutral {@code reasoning}
         * block cannot express, and for endpoints this framework has never
         * heard of. Example — a thinking budget (Anthropic-only knob):
         * <pre>
         * extra-body:
         *   thinking:
         *     budget_tokens: 8000
         * </pre>
         * Standard request fields win on collision, so this can never corrupt
         * the protocol itself.
         */
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Reasoning getReasoning() {
            return reasoning;
        }

        public Map<String, Object> getExtraBody() {
            return extraBody;
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<>() : extraBody;
        }

        /**
         * Maps to {@code ReasoningConfig}, shared by the OpenAI-compatible and
         * Anthropic clients. {@code mode} defaults to {@code auto} — no switch
         * is sent and the model default applies.
         * <p>
         * Only provider-neutral intent lives here. Vendor-specific knobs belong
         * in {@link Model#getExtraBody()}.
         */
        public static class Reasoning {

            /**
             * {@code auto} | {@code enabled} | {@code disabled}.
             */
            private ReasoningMode mode = ReasoningMode.auto;

            /**
             * Effort hint: {@code low} | {@code medium} | {@code high}
             * (OpenAI o-series, OpenRouter). Optional. Providers that ignore it
             * log a warning rather than failing.
             */
            private String effort;

            public ReasoningMode getMode() {
                return mode;
            }

            public void setMode(ReasoningMode mode) {
                this.mode = mode;
            }

            public String getEffort() {
                return effort;
            }

            public void setEffort(String effort) {
                this.effort = effort;
            }

            /**
             * Relaxed YAML enum: accepts any case.
             * <p>
             * Unknown values deliberately do <strong>not</strong> fall back to
             * {@code auto} — a typo like {@code disabeld} would then silently
             * ship the wrong behaviour. Spring Boot's binder fails fast with a
             * clear message listing the valid values instead.
             */
            public enum ReasoningMode {
                auto, enabled, disabled
            }
        }
    }

    /**
     * Maps to {@code RetryModelClient(delegate, maxAttempts, ...)}.
     * {@code max-attempts} is passed as {@code RetryModelClient}'s {@code maxRetries}
     * (retries after the first attempt; default 3 matches the client).
     */
    public static class Retry {

        /**
         * Wrap the base client with {@code RetryModelClient} when {@code true}.
         */
        private boolean enabled = false;

        /**
         * Passed as {@code RetryModelClient}'s {@code maxRetries} constructor argument.
         */
        private int maxAttempts = 3;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /**
     * Maps to {@code TimeoutModelClient(delegate, duration)} when enabled.
     */
    public static class CallTimeout {

        /**
         * Wrap the base client with {@code TimeoutModelClient} when {@code true}.
         */
        private boolean enabled = false;

        /**
         * Max duration for a single model call (and stream connection).
         */
        private Duration duration = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }
    }
}
