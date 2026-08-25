package io.github.qwzhang01.agent.observability.version;

import java.util.Objects;

/**
 * One atom of the version triple (Stage 18 D8): which PROMPT, which MODEL,
 * which TOOL set served a run - the reproducibility precondition.
 * <p>
 * The standard postmortem question - "yesterday's bad batch of runs, which
 * prompt version / model / tool combination was that?" - is unanswerable
 * without version records, and every answer without them is a guess.
 * {@code RunRecord} aggregates the triple; this record is its atom.
 * <p>
 * Sources are deliberately heterogeneous (assembly declares, it does not
 * discover):
 * <ul>
 *   <li>PROMPT - the Stage 13 {@code PromptManager} version at bind time
 *       (assembly reads {@code PromptManager.resolve(...)} and translates;
 *       the module itself never imports product - D5's numbers-not-identities
 *       discipline, here versions-not-managers)</li>
 *   <li>MODEL - the deployment's declared model id ("premium", "gpt-4o")</li>
 *   <li>TOOL - the tool-set fingerprint the assembly chooses (e.g. name@f1
 *       for the registered set)</li>
 * </ul>
 * {@code channel} is the Stage 13 stable/canary channel for prompts, null
 * when the kind has no channel concept (models/tools) - absence is honest.
 *
 * @param kind    which third of the triple this is
 * @param name    component name (prompt name / model key / tool-set label)
 * @param version version string ("v3", "2026-08-25.1", a fingerprint)
 * @param channel release channel for prompts (stable/canary), null otherwise
 */
public record ComponentVersion(Kind kind, String name, String version, String channel) {

    public ComponentVersion {
        Objects.requireNonNull(kind, "kind");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be null or blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank (component " + name + ")");
        }
    }

    /** Which third of the version triple. */
    public enum Kind {PROMPT, MODEL, TOOL}

    /** A component without a channel concept (models, tool sets). */
    public static ComponentVersion of(Kind kind, String name, String version) {
        return new ComponentVersion(kind, name, version, null);
    }
}
