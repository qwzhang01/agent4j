package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.rag.AnswerGenerator;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import io.github.qwzhang01.agent.rag.model.GeneratedAnswer;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link AnswerGenerator} that prompts a chat model to answer only from the given chunks and to
 * end every sentence with {@code [chunkId]} markers.
 * <p>
 * Contexts are packed in rank order into {@link Options#maxContextChars()}; lower-ranked chunks
 * that do not fit are dropped whole (the top chunk is truncated if it alone exceeds the budget).
 * Only ids of packed chunks are accepted as citations. With no contexts the refusal text is
 * returned without a model call. An output starting with the refusal text is {@code refused} and
 * carries no sentences. Model exceptions propagate unchanged; the caller decides how to degrade.
 */
public final class LlmAnswerGenerator implements AnswerGenerator {

    private static final Logger log = LoggerFactory.getLogger(LlmAnswerGenerator.class);

    private final ModelClient modelClient;
    private final Options options;

    /**
     * @param model           model id, null for the client default
     * @param maxContextChars character budget for rendered contexts
     * @param refusalText     exact text the model must output when the material is insufficient
     * @param maxHistoryTurns most recent history turns sent to the model
     */
    public record Options(String model, int maxContextChars, String refusalText, int maxHistoryTurns) {

        public static final int DEFAULT_MAX_CONTEXT_CHARS = 12_000;
        public static final String DEFAULT_REFUSAL_TEXT = "资料中没有相关内容";
        public static final int DEFAULT_MAX_HISTORY_TURNS = 6;

        public Options {
            if (maxContextChars <= 0) {
                throw new IllegalArgumentException("maxContextChars must be > 0, got: " + maxContextChars);
            }
            if (maxHistoryTurns < 0) {
                throw new IllegalArgumentException("maxHistoryTurns must be >= 0, got: " + maxHistoryTurns);
            }
            refusalText = refusalText == null || refusalText.isBlank() ? DEFAULT_REFUSAL_TEXT : refusalText.strip();
        }

        public static Options defaults() {
            return new Options(null, DEFAULT_MAX_CONTEXT_CHARS, DEFAULT_REFUSAL_TEXT, DEFAULT_MAX_HISTORY_TURNS);
        }
    }

    public LlmAnswerGenerator(ModelClient modelClient) {
        this(modelClient, Options.defaults());
    }

    public LlmAnswerGenerator(ModelClient modelClient, Options options) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.options = Objects.requireNonNull(options, "options");
    }

    public Options options() {
        return options;
    }

    /**
     * @throws RuntimeException whatever the model client throws; nothing is swallowed here
     */
    @Override
    public GeneratedAnswer generate(String question, List<ConversationTurn> history, List<ScoredChunk> contexts) {
        Objects.requireNonNull(question, "question");
        List<Chunk> packed = pack(contexts == null ? List.of() : contexts, options.maxContextChars());
        if (packed.isEmpty()) {
            return new GeneratedAnswer(options.refusalText(), List.of(), true, 0, 0);
        }
        ModelResponse response = modelClient.chat(buildRequest(question, history, packed));
        String raw = response == null || response.content() == null ? "" : response.content();
        int promptTokens = response == null || response.usage() == null ? 0 : response.usage().promptTokens();
        int completionTokens = response == null || response.usage() == null ? 0 : response.usage().completionTokens();
        if (isRefusal(raw)) {
            return new GeneratedAnswer(raw, List.of(), true, promptTokens, completionTokens);
        }
        Set<String> allowed = new LinkedHashSet<>();
        packed.forEach(c -> allowed.add(c.chunkId()));
        CitationParser.ParseResult parsed = CitationParser.parse(raw, allowed);
        if (parsed.unknownCitations() > 0) {
            log.warn("Generator cited {} id(s) not present in the context; dropped", parsed.unknownCitations());
        }
        return new GeneratedAnswer(raw, parsed.sentences(), false, promptTokens, completionTokens);
    }

    boolean isRefusal(String raw) {
        return raw.strip().startsWith(options.refusalText());
    }

    ModelRequest buildRequest(String question, List<ConversationTurn> history, List<Chunk> packed) {
        ModelRequest.Builder builder = ModelRequest.builder()
                .model(options.model())
                .addMessage(ChatMessage.system(systemPrompt()));
        for (ConversationTurn turn : recentHistory(history)) {
            builder.addMessage("assistant".equalsIgnoreCase(turn.role())
                    ? ChatMessage.assistant(turn.content())
                    : ChatMessage.user(turn.content()));
        }
        return builder.addMessage(ChatMessage.user(userPrompt(question, packed))).build();
    }

    private List<ConversationTurn> recentHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty() || options.maxHistoryTurns() == 0) {
            return List.of();
        }
        int from = Math.max(0, history.size() - options.maxHistoryTurns());
        return history.subList(from, history.size());
    }

    private String systemPrompt() {
        return """
                你是一个严格基于资料回答问题的助手。规则：
                1. 只能使用【资料】中的内容回答，不得使用资料以外的知识，不得猜测。
                2. 每一句话的末尾都必须标注一个或多个来源标记，格式为 [chunkId]，chunkId 必须原样取自资料每段开头的方括号；多个来源写成 [id1][id2]。
                3. 绝不能编造资料中不存在的 chunkId。
                4. 如果资料不足以回答问题，只输出这句话，不要输出任何其他内容：%s
                5. 代码、配置、命令、参数名保持原样，放在代码块中照抄，不要改写。
                6. 使用与问题相同的语言回答，简洁准确。
                """.formatted(options.refusalText());
    }

    private static String userPrompt(String question, List<Chunk> packed) {
        StringBuilder sb = new StringBuilder("【资料】\n");
        for (Chunk c : packed) {
            sb.append(render(c)).append("\n\n");
        }
        return sb.append("【问题】\n").append(question.strip()).toString();
    }

    static String header(Chunk c) {
        StringBuilder sb = new StringBuilder("[").append(c.chunkId()).append("] 《").append(c.docTitle()).append('》');
        String section = c.sectionLabel();
        if (!section.isBlank()) {
            sb.append(" section: ").append(section);
        }
        return sb.toString();
    }

    private static String render(Chunk c) {
        return header(c) + "\n" + c.text();
    }

    static List<Chunk> pack(List<ScoredChunk> contexts, int maxChars) {
        List<Chunk> packed = new ArrayList<>();
        int used = 0;
        for (ScoredChunk sc : contexts) {
            Chunk c = sc.chunk();
            int size = render(c).length() + 2;
            if (used + size <= maxChars) {
                packed.add(c);
                used += size;
            } else if (packed.isEmpty()) {
                int room = Math.max(0, maxChars - header(c).length() - 3);
                packed.add(new Chunk(c.chunkId(), c.docId(), c.docTitle(), c.source(), c.sectionPath(),
                        c.text().substring(0, Math.min(room, c.text().length())),
                        c.startLine(), c.endLine(), c.page(), c.metadata()));
                break;
            } else {
                break;
            }
        }
        return packed;
    }
}
