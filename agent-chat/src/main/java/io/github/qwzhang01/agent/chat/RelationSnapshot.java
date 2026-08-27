package io.github.qwzhang01.agent.chat;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Host-supplied relationship view for one turn.
 * <p>
 * {@code stage} and slot keys are free-form. The engine injects them and
 * does not score, clamp, or interpret product vocabularies (tiers, intimacy
 * formulas, tavern matrices stay with the host).
 *
 * @param stage  optional phase label; blank omitted
 * @param slots  optional gauges / labels; null values dropped
 * @param note   optional prose already rendered by the host
 */
public record RelationSnapshot(String stage, Map<String, String> slots, String note) {

    public RelationSnapshot {
        stage = blankToEmpty(stage);
        slots = copySlots(slots);
        note = blankToEmpty(note);
    }

    public static RelationSnapshot empty() {
        return new RelationSnapshot("", Map.of(), "");
    }

    public static RelationSnapshot note(String note) {
        return new RelationSnapshot("", Map.of(), note);
    }

    public static RelationSnapshot of(String stage, Map<String, String> slots) {
        return new RelationSnapshot(stage, slots, "");
    }

    public static RelationSnapshot of(String stage, Map<String, String> slots, String note) {
        return new RelationSnapshot(stage, slots, note);
    }

    public boolean isEmpty() {
        return stage.isEmpty() && slots.isEmpty() && note.isEmpty();
    }

    private static String blankToEmpty(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static Map<String, String> copySlots(Map<String, String> slots) {
        if (slots == null || slots.isEmpty()) {
            return Map.of();
        }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey().trim(), entry.getValue());
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }
}
