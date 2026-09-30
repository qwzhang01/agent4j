package io.github.qwzhang01.agent.rag.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Loads evaluation cases from JSONL, one case per line:
 * <pre>{@code
 * {"id":"q001","type":"single_hop","question":"...","history":[{"role":"user","content":"..."}],
 *  "relevant":[{"docId":"a.md","section":"召回"}],"answer":"...","answerable":true}
 * }</pre>
 * Blank lines and lines starting with {@code //} are skipped. {@code history}, {@code relevant},
 * {@code answer} are optional; a missing {@code answerable} defaults to {@code type != unanswerable}.
 */
public final class EvalDataset {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalDataset() {
    }

    /**
     * @throws IOException              when the file cannot be read
     * @throws IllegalArgumentException on malformed JSON, missing required fields or duplicate ids;
     *                                  the message names the file and 1-based line number
     */
    public static List<EvalCase> load(Path jsonl) throws IOException {
        Objects.requireNonNull(jsonl, "jsonl");
        return parse(Files.readAllLines(jsonl, StandardCharsets.UTF_8), jsonl.toString());
    }

    /** Same as {@link #load} over already-read lines; {@code source} is used in error messages. */
    public static List<EvalCase> parse(List<String> lines, String source) {
        List<EvalCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n).replace("\uFEFF", "").strip();
            if (line.isEmpty() || line.startsWith("//")) {
                continue;
            }
            String where = source + ":" + (n + 1);
            EvalCase c = parseLine(line, where);
            if (!ids.add(c.id())) {
                throw new IllegalArgumentException(where + ": duplicate case id '" + c.id() + "'");
            }
            cases.add(c);
        }
        return List.copyOf(cases);
    }

    private static EvalCase parseLine(String line, String where) {
        JsonNode node;
        try {
            node = MAPPER.readTree(line);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(where + ": invalid JSON: " + e.getOriginalMessage(), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(where + ": expected a JSON object");
        }
        String id = requiredText(node, "id", where);
        String question = requiredText(node, "question", where);
        String type = text(node, "type");
        List<ConversationTurn> history = new ArrayList<>();
        for (JsonNode turn : array(node, "history", where)) {
            String role = text(turn, "role");
            if (role.isBlank()) {
                throw new IllegalArgumentException(where + ": history entry without role");
            }
            history.add(new ConversationTurn(role, text(turn, "content")));
        }
        List<GoldRef> relevant = new ArrayList<>();
        for (JsonNode ref : array(node, "relevant", where)) {
            String docId = text(ref, "docId");
            if (docId.isBlank()) {
                throw new IllegalArgumentException(where + ": relevant entry without docId");
            }
            String section = text(ref, "section");
            relevant.add(new GoldRef(docId, section.isBlank() ? null : section));
        }
        JsonNode answerable = node.get("answerable");
        boolean isAnswerable = answerable == null || answerable.isNull()
                ? !EvalCase.UNANSWERABLE.equals(type)
                : answerable.asBoolean();
        return new EvalCase(id, type, question, history, relevant, text(node, "answer"), isAnswerable);
    }

    private static Iterable<JsonNode> array(JsonNode node, String field, String where) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(where + ": '" + field + "' must be an array");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field, String where) {
        String value = text(node, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(where + ": missing required field '" + field + "'");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").strip();
    }
}
