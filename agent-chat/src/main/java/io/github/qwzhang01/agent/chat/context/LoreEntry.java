package io.github.qwzhang01.agent.chat.context;

import java.util.Objects;

/**
 * One host-supplied lore slice. Content is injected verbatim when the
 * trigger hits; the engine does not parse card formats or titles.
 *
 * @param content  text to inject; blank content is skipped even on a hit
 * @param trigger  keyword and/or regex; required
 */
public record LoreEntry(String content, LoreTrigger trigger) {

    public LoreEntry {
        content = content == null ? "" : content;
        Objects.requireNonNull(trigger, "trigger");
    }

    public static LoreEntry of(String content, LoreTrigger trigger) {
        return new LoreEntry(content, trigger);
    }
}
