package io.github.qwzhang01.agent.product.template;

import java.util.Objects;

/**
 * Template identity (Stage 13 M13.2).
 *
 * @param name        template name - the registry key (instance names are given at
 *                    instantiate time, not taken from here)
 * @param version     template version, informational in v1 (registry indexes by
 *                    name only; version selection is v2)
 * @param description human-readable description of what this template produces
 */
public record TemplateMetadata(String name, String version, String description) {

    public TemplateMetadata {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("metadata.name must not be blank");
        }
    }
}
