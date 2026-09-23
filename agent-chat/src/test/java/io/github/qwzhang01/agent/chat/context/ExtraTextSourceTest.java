package io.github.qwzhang01.agent.chat.context;

import io.github.qwzhang01.agent.chat.model.ChatPersona;
import io.github.qwzhang01.agent.chat.model.Room;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtraTextSourceTest {

    private static final ChatPersona LUNA = ChatPersona.of("luna", "You are Luna.");
    private static final Room ROOM = new Room("r", List.of(LUNA));

    // No-budget (backward-compat)

    @Test
    void noLimit_returnsFullText() {
        String text = "x".repeat(10_000);
        String result = content(new ExtraTextSource(text));
        assertEquals(text, result);
    }

    @Test
    void blankText_contributesNothing() {
        assertTrue(new ExtraTextSource("   ").contribute(ROOM, LUNA, "hi").isEmpty());
        assertTrue(new ExtraTextSource(null).contribute(ROOM, LUNA, "hi").isEmpty());
    }

    // Budget enforced

    /**
     * With 10 000-char input and maxTokens=500, the result must:
     *   - not exceed 500 chars (the budget);
     *   - start with the very first line (head is never dropped);
     *   - not contain the last line (tail is dropped).
     */
    @Test
    void truncatesFromTail_keepingHead() {
        // 100 lines, each "line-NNN: " + 90 x's = ~100 chars → ~10 000 chars total
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 100; i++) {
            sb.append(String.format("line-%03d: ", i)).append("x".repeat(90)).append('\n');
        }
        String text = sb.toString().stripTrailing();

        String result = content(new ExtraTextSource(text, 500));

        assertTrue(result.length() <= 500,
                "result (" + result.length() + " chars) must be within the 500-token budget");
        assertTrue(result.startsWith("line-001:"),
                "head must not be truncated");
        assertFalse(result.contains("line-100:"),
                "tail must be dropped");
    }

    @Test
    void withinBudget_returnsFullText() {
        String text = "hello\nworld";
        assertEquals(text, content(new ExtraTextSource(text, 1000)));
    }

    @Test
    void multiLine_dropsTrailingSections() {
        // 5 lines of 20 chars each; budget = 45 → fits 2 lines (20 + 1 + 20 = 41 ≤ 45)
        String text = "aaaaaaaaaaaaaaaaaaaaa\n"   // 21 chars
                    + "bbbbbbbbbbbbbbbbbbbbb\n"   // 21 chars
                    + "ccccccccccccccccccccc\n"   // 21 chars
                    + "ddddddddddddddddddddd\n"   // 21 chars
                    + "eeeeeeeeeeeeeeeeeeeee";    // 21 chars (no trailing \n)
        // Each "line" = 21 chars; budget = 45 → first 2 lines cost 21 + 1 + 21 = 43 ≤ 45
        // Third line would cost 43 + 1 + 21 = 65 > 45 → dropped

        String result = content(new ExtraTextSource(text, 45));

        assertTrue(result.contains("aaa"), "first section must be kept");
        assertTrue(result.contains("bbb"), "second section must be kept");
        assertFalse(result.contains("ccc"), "third section and beyond must be dropped");
    }

    /** First segment larger than budget: always kept to avoid zeroing out context. */
    @Test
    void firstSegmentExceedsBudget_keptAnyway() {
        String text = "this-long-first-line-exceeds-budget\nsecond";  // 35 + 1 + 6 = 42 chars
        String result = content(new ExtraTextSource(text, 10));

        assertTrue(result.contains("this-long-first-line-exceeds-budget"),
                "first segment must be kept even when it exceeds the budget");
        assertFalse(result.contains("second"),
                "second segment must be dropped");
    }

    @Test
    void invalidMaxTokens_throws() {
        assertThrows(IllegalArgumentException.class, () -> new ExtraTextSource("x", 0));
        assertThrows(IllegalArgumentException.class, () -> new ExtraTextSource("x", -2));
    }

    private static String content(ExtraTextSource source) {
        List<ChatMessage> msgs = source.contribute(ROOM, LUNA, "hi");
        if (msgs.isEmpty()) return "";
        return msgs.get(0).content();
    }
}
