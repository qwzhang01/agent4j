package io.github.qwzhang01.agent.memory.context;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Two-tier context assembly for the memory block (roadmap step 3):
 * core at the head, archival paged in beside the current turn.
 * <p>
 * Placement physics (why the tiers sit where they sit):
 * <ul>
 *   <li><b>Core head placement is prefix-cache property.</b> Core is stable
 *       across turns (it ignores the query), so pinning it at the head keeps
 *       the prompt's longest-common-prefix long. Archival flips every turn
 *       (it follows the query), so it must sit LATE — next to the user
 *       message — to avoid re-paying the cache-write premium every turn.
 *       This is the E3 finding applied to memory: query-drifting content
 *       belongs at the tail (decision 26).</li>
 *   <li><b>Archival beside-the-turn placement is semantic adjacency.</b> A
 *       memory the model just paged in reads as part of the current question,
 *       not as part of the agent's stable identity.</li>
 * </ul>
 * <p>
 * Token budget order: core first (never dropped), then archival until the
 * budget runs out. When the budget is exceeded, a warn is logged; correctness
 * (identity facts present) never degrades — volume does.
 * <p>
 * Rendering stays one block per tier: at most TWO injected messages
 * (core block + archival block). The {@code [Known memories]} string is a
 * stable contract — downstream replay/debug tooling matches on it. The legacy
 * single-block rendering (one {@code [Known memories]} USER message holding
 * everything) remains what {@link MemoryContextBuilder}'s six-arg constructor
 * produces; the layered path is opt-in through the seven-arg constructor.
 */
final class LayeredMemoryAssembler {

    private static final Logger log = LoggerFactory.getLogger(LayeredMemoryAssembler.class);

    private static final String CORE_BLOCK_HEADER = "[Core memories]";
    private static final String KNOWN_BLOCK_HEADER = "[Known memories]";

    /**
     * Assemble the final message list: [core block] + history + [archival block],
     * with the archival block inserted immediately before the last USER message
     * (a multimodal USER message counts as the anchor; messages after it stay
     * after). Both blocks are optional (empty tier = no block).
     *
     * @param core     core-tier entries (already ordered), may be empty
     * @param history  the conversation history as built so far
     * @param archival archival-tier entries (already ranked), may be empty
     * @param policy   layering policy (token budget), never null
     * @return the assembled message list, never null
     */
    static List<ChatMessage> assemble(List<MemoryEntry> core,
                                      List<ChatMessage> history,
                                      List<MemoryEntry> archival,
                                      MemoryLayering policy) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(policy, "policy");

        List<MemoryEntry> coreKept = keepCore(core, policy);
        List<MemoryEntry> archivalKept = keepArchival(archival, policy, tokensOf(coreKept));

        boolean hasCore = !coreKept.isEmpty();
        boolean hasArchival = !archivalKept.isEmpty();
        if (!hasCore && !hasArchival) {
            return new ArrayList<>(history);
        }

        int anchor = lastUserIndex(history);
        List<ChatMessage> assembled = new ArrayList<>(history.size() + 2);
        if (hasCore) {
            assembled.add(ChatMessage.user(CORE_BLOCK_HEADER + "\n" + render(coreKept)));
        }
        if (anchor >= 0) {
            assembled.addAll(history.subList(0, anchor));
            if (hasArchival) {
                assembled.add(ChatMessage.user(KNOWN_BLOCK_HEADER + "\n" + render(archivalKept)));
            }
            assembled.addAll(history.subList(anchor, history.size()));
        } else {
            // No USER anchor in history: archival follows the core block at the
            // head, before all history. Deterministic either way; the anchor
            // path above is the normal case (a turn always ends with USER).
            if (hasArchival) {
                assembled.add(ChatMessage.user(KNOWN_BLOCK_HEADER + "\n" + render(archivalKept)));
            }
            assembled.addAll(history);
        }
        return assembled;
    }

    /**
     * Core is never dropped by the budget; a warn fires when core alone
     * exceeds it (the host gated what enters the core tier at write time).
     */
    private static List<MemoryEntry> keepCore(List<MemoryEntry> core, MemoryLayering policy) {
        if (core.isEmpty() || policy.memoryTokenBudget() == MemoryLayering.NO_TOKEN_BUDGET) {
            return core;
        }
        int used = tokensOf(core);
        if (used > policy.memoryTokenBudget()) {
            log.warn("Core memories alone exceed the memory token budget: {} > {} - "
                            + "injected anyway; hosts gate the core tier at write time",
                    used, policy.memoryTokenBudget());
        }
        return core;
    }

    /**
     * Archival fills the remaining budget (budget minus core's usage); entries
     * beyond it are dropped with a debug log. No budget ({@code 0}) keeps all.
     */
    private static List<MemoryEntry> keepArchival(List<MemoryEntry> archival,
                                                  MemoryLayering policy,
                                                  int coreTokens) {
        if (archival.isEmpty() || policy.memoryTokenBudget() == MemoryLayering.NO_TOKEN_BUDGET) {
            return archival;
        }
        int remaining = Math.max(0, policy.memoryTokenBudget() - coreTokens);
        List<MemoryEntry> kept = new ArrayList<>(archival.size());
        int used = 0;
        for (MemoryEntry e : archival) {
            int cost = tokensOf(List.of(e));
            if (used + cost > remaining) {
                log.debug("Archival memory '{}' dropped by token budget: {} + {} > {}",
                        e.subject(), used, cost, remaining);
                continue;
            }
            kept.add(e);
            used += cost;
        }
        return kept;
    }

    /**
     * Index of the last USER message (any USER message, multimodal included),
     * or {@code -1} when history has none.
     */
    private static int lastUserIndex(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).role() == ChatRole.USER) {
                return i;
            }
        }
        return -1;
    }

    private static String render(List<MemoryEntry> memories) {
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry m : memories) {
            sb.append("- [").append(m.type()).append("] ");
            if (m.subject() != null) {
                sb.append(m.subject()).append(": ");
            }
            sb.append(m.content()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Chars/4 token estimate over the rendered lines (the {@link ContextBudget}
     * heuristic applied per entry). One definition for budget checks.
     */
    private static int tokensOf(List<MemoryEntry> entries) {
        int chars = 0;
        for (MemoryEntry e : entries) {
            chars += (e.subject() == null ? 0 : e.subject().length() + 2)
                    + (e.content() == null ? 0 : e.content().length());
        }
        return chars / 4;
    }
}
