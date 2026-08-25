package io.github.qwzhang01.agent.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

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
