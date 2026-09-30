package io.github.qwzhang01.agent.rag.generate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.rag.CitationVerifier;
import io.github.qwzhang01.agent.rag.internal.Texts;
import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link CitationVerifier} that asks a chat model whether each sentence is backed by the chunks it
 * cites, and only those.
 * <p>
 * Sentences are sent in batches of {@link Options#batchSize()}; each sentence carries its own
 * evidence capped at {@link Options#maxEvidenceChars()}. A sentence citing nothing, or citing only
 * chunks absent from {@code chunksById}, is {@code NOT_FOUND} without a model call. A failed batch
 * (model error, unparseable JSON, missing or invalid entries) leaves the affected sentences
 * {@code UNVERIFIED} and sets {@code degraded}; other batches are unaffected.
 */
public final class LlmCitationVerifier implements CitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(LlmCitationVerifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            你是事实核查员。对每条待核查的句子，只根据该句自己附带的证据判断，不得使用其他知识：
            - SUPPORTED：证据明确陈述了句子的内容（允许同义改写）。
            - CONTRADICTED：证据陈述的内容与句子不一致。
            - NOT_FOUND：证据没有提到句子的内容，或只部分覆盖。
            只输出 JSON，不要输出 markdown 或其他文字，格式：
            {"results":[{"i":0,"verdict":"SUPPORTED|CONTRADICTED|NOT_FOUND","reason":"不超过30字的理由"}]}
            每条句子都必须有一个结果，i 与输入编号一致。
            """;

    private final ModelClient modelClient;
    private final Options options;

    /**
     * @param model            model id, null for the client default
     * @param maxEvidenceChars evidence budget per sentence
     * @param batchSize        sentences per model call
     */
    public record Options(String model, int maxEvidenceChars, int batchSize) {

        public static final int DEFAULT_MAX_EVIDENCE_CHARS = 4_000;
        public static final int DEFAULT_BATCH_SIZE = 8;

        public Options {
            if (maxEvidenceChars <= 0) {
                throw new IllegalArgumentException("maxEvidenceChars must be > 0, got: " + maxEvidenceChars);
            }
            if (batchSize <= 0) {
                throw new IllegalArgumentException("batchSize must be > 0, got: " + batchSize);
            }
        }

        public static Options defaults() {
            return new Options(null, DEFAULT_MAX_EVIDENCE_CHARS, DEFAULT_BATCH_SIZE);
        }
    }

    public LlmCitationVerifier(ModelClient modelClient) {
        this(modelClient, Options.defaults());
    }

    public LlmCitationVerifier(ModelClient modelClient, Options options) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.options = Objects.requireNonNull(options, "options");
    }

    public Options options() {
        return options;
    }

    @Override
    public Verification verify(List<AnswerSentence> sentences, Map<String, Chunk> chunksById) {
        Objects.requireNonNull(sentences, "sentences");
        Map<String, Chunk> chunks = chunksById == null ? Map.of() : chunksById;
        AnswerSentence[] result = sentences.toArray(new AnswerSentence[0]);
        List<Integer> pending = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (int i = 0; i < result.length; i++) {
            AnswerSentence s = result[i];
            if (s.citedChunkIds().isEmpty()) {
                result[i] = s.withVerdict(SupportVerdict.NOT_FOUND, "no citation");
                continue;
            }
            String ev = evidence(s, chunks);
            if (ev.isEmpty()) {
                result[i] = s.withVerdict(SupportVerdict.NOT_FOUND, "cited chunks unavailable");
                continue;
            }
            pending.add(i);
            evidence.add(ev);
        }
        int promptTokens = 0;
        int completionTokens = 0;
        boolean degraded = false;
        for (int from = 0; from < pending.size(); from += options.batchSize()) {
            int to = Math.min(pending.size(), from + options.batchSize());
            List<Integer> idx = pending.subList(from, to);
            List<String> ev = evidence.subList(from, to);
            BatchOutcome outcome = verifyBatch(idx, ev, result);
            promptTokens += outcome.promptTokens;
            completionTokens += outcome.completionTokens;
            degraded |= outcome.degraded;
        }
        return new Verification(List.of(result), promptTokens, completionTokens, degraded);
    }

    private record BatchOutcome(int promptTokens, int completionTokens, boolean degraded) {
    }

    private BatchOutcome verifyBatch(List<Integer> idx, List<String> evidence, AnswerSentence[] result) {
        ModelResponse response;
        try {
            response = modelClient.chat(ModelRequest.builder()
                    .model(options.model())
                    .messages(List.of(
                            ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(userPrompt(idx, evidence, result))))
                    .responseFormat(ModelRequest.ResponseFormat.json())
                    .build());
        } catch (RuntimeException e) {
            log.warn("Citation verification batch of {} failed: {}", idx.size(), e.getMessage());
            markUnverified(idx, result, "verifier error");
            return new BatchOutcome(0, 0, true);
        }
        int pt = response == null || response.usage() == null ? 0 : response.usage().promptTokens();
        int ct = response == null || response.usage() == null ? 0 : response.usage().completionTokens();
        Map<Integer, AnswerSentence> verdicts = parseVerdicts(response == null ? null : response.content(), idx, result);
        if (verdicts == null) {
            log.warn("Citation verification batch of {} returned unparseable output", idx.size());
            markUnverified(idx, result, "unparseable verifier output");
            return new BatchOutcome(pt, ct, true);
        }
        boolean degraded = false;
        for (int local = 0; local < idx.size(); local++) {
            int global = idx.get(local);
            AnswerSentence v = verdicts.get(local);
            if (v == null) {
                result[global] = result[global].withVerdict(SupportVerdict.UNVERIFIED, "missing verifier result");
                degraded = true;
            } else {
                result[global] = v;
            }
        }
        if (degraded) {
            log.warn("Citation verification batch of {} returned incomplete results", idx.size());
        }
        return new BatchOutcome(pt, ct, degraded);
    }

    private static void markUnverified(List<Integer> idx, AnswerSentence[] result, String reason) {
        for (int i : idx) {
            result[i] = result[i].withVerdict(SupportVerdict.UNVERIFIED, reason);
        }
    }

    private static String userPrompt(List<Integer> idx, List<String> evidence, AnswerSentence[] result) {
        StringBuilder sb = new StringBuilder();
        for (int local = 0; local < idx.size(); local++) {
            sb.append("### i=").append(local).append('\n')
                    .append("句子：").append(result[idx.get(local)].text()).append('\n')
                    .append("证据：\n").append(evidence.get(local)).append("\n\n");
        }
        return sb.toString();
    }

    private String evidence(AnswerSentence s, Map<String, Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (String id : s.citedChunkIds()) {
            Chunk c = chunks.get(id);
            if (c == null) {
                continue;
            }
            sb.append('[').append(id).append("]\n").append(c.text()).append('\n');
        }
        return Texts.truncate(sb.toString().strip(), options.maxEvidenceChars());
    }

    /**
     * Local index to verdict-filled sentence; null when the output is not usable JSON. Numbering
     * exactly {@code 1..n} is read as 1-based, since the model sometimes ignores the 0-based input.
     */
    static Map<Integer, AnswerSentence> parseVerdicts(String raw, List<Integer> idx, AnswerSentence[] result) {
        JsonNode root = readJson(raw);
        if (root == null) {
            return null;
        }
        JsonNode array = root.isArray() ? root : root.get("results");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<JsonNode> entries = new ArrayList<>();
        Set<Integer> numbers = new HashSet<>();
        for (JsonNode node : array) {
            if (node != null && node.isObject()) {
                entries.add(node);
                numbers.add(index(node.get("i")));
            }
        }
        boolean oneBased = !numbers.contains(0) && numbers.contains(idx.size())
                && numbers.stream().allMatch(n -> n >= 1 && n <= idx.size());
        Map<Integer, AnswerSentence> out = new HashMap<>();
        for (JsonNode node : entries) {
            int local = index(node.get("i")) - (oneBased ? 1 : 0);
            if (local < 0 || local >= idx.size()) {
                continue;
            }
            SupportVerdict verdict = parseVerdict(node.path("verdict").asText(""));
            if (verdict == null) {
                continue;
            }
            out.put(local, result[idx.get(local)].withVerdict(verdict, node.path("reason").asText("").strip()));
        }
        return out;
    }

    /** Sentence index from a numeric or numeric-string node; -1 when absent or not an integer. */
    private static int index(JsonNode node) {
        if (node == null) {
            return -1;
        }
        if (node.isIntegralNumber() && node.canConvertToInt()) {
            return node.intValue();
        }
        if (node.isTextual() && node.asText().strip().matches("\\d{1,9}")) {
            return Integer.parseInt(node.asText().strip());
        }
        return -1;
    }

    private static SupportVerdict parseVerdict(String raw) {
        String v = raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return switch (v) {
            case "SUPPORTED" -> SupportVerdict.SUPPORTED;
            case "CONTRADICTED" -> SupportVerdict.CONTRADICTED;
            case "NOT_FOUND" -> SupportVerdict.NOT_FOUND;
            default -> null;
        };
    }

    static JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = stripFence(raw.strip());
        try {
            return MAPPER.readTree(s);
        } catch (Exception ignored) {
            boolean arrayFirst = s.indexOf('[') >= 0 && (s.indexOf('{') < 0 || s.indexOf('[') < s.indexOf('{'));
            JsonNode first = arrayFirst ? readBetween(s, '[', ']') : readBetween(s, '{', '}');
            return first != null ? first : (arrayFirst ? readBetween(s, '{', '}') : readBetween(s, '[', ']'));
        }
    }

    private static JsonNode readBetween(String s, char open, char close) {
        int start = s.indexOf(open);
        int end = s.lastIndexOf(close);
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return MAPPER.readTree(s.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFence(String raw) {
        int open = raw.indexOf("```");
        if (open < 0) {
            return raw;
        }
        int lineEnd = raw.indexOf('\n', open);
        if (lineEnd < 0) {
            return raw;
        }
        int close = raw.indexOf("```", lineEnd);
        return close < 0 ? raw.substring(lineEnd + 1) : raw.substring(lineEnd + 1, close).strip();
    }
}
