package io.github.qwzhang01.agent.product.prompt;

import java.time.Instant;
import java.util.Objects;

/**
 * An immutable prompt version (Stage 13 M13.4, D4).
 * <p>
 * Publishing APPENDS to the history and never rewrites it - the version list
 * IS the audit trail. Rollback moves a channel pointer back; it does not
 * touch stored content.
 * <p>
 * Versions are monotonic integers per prompt name (v1 skips semver: prompts
 * have no compatibility contract to express with it - an honest simplification).
 *
 * @param name        prompt name (e.g. "support-system")
 * @param version     1-based monotonic sequence number
 * @param content     the prompt text
 * @param channel     the channel this version was published to (stable/canary)
 * @param publishedAt publication timestamp (audit)
 */
public record PromptVersion(String name, int version, String content, String channel,
                            Instant publishedAt) {

    public PromptVersion {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got " + version);
        }
    }

    @Override
    public String toString() {
        return name + "#v" + version + "(" + channel + ")";
    }
}
