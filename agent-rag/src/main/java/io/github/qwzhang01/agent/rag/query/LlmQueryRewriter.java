package io.github.qwzhang01.agent.rag.query;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.rag.QueryRewriter;
import io.github.qwzhang01.agent.rag.internal.Texts;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Rewrites a follow-up question into one standalone retrieval query with a single model call.
 * <p>
 * No call is made when there is no history. Any model failure or implausible output
 * (blank, far longer than the question) returns the original question with {@code degraded=true}.
 */
public final class LlmQueryRewriter implements QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryRewriter.class);

    static final String SYSTEM_PROMPT = """
            你是检索查询改写器。根据对话历史，把用户的最后一个问题改写成一条可以独立检索的查询。
            要求：
            1. 用历史补全代词和省略（如"它""那个""那……呢"），使查询脱离上下文也能理解；
            2. 专有名词、技术术语、代码标识、版本号保持原样，不翻译、不改写；
            3. 不添加历史和问题中没有的事实，不回答问题；
            4. 使用问题本身的语言（中文问题输出中文，English question → English query）；
            5. 问题已经独立时原样输出；
            6. 只输出改写后的查询文本，一行，不加引号、解释或前缀。
            You rewrite the last question into ONE standalone search query. Output only the query.""";

    private static final Pattern PREFIX = Pattern.compile(
            "^(?:改写后的查询|改写后查询|改写后|改写结果|改写|查询|检索查询|独立查询"
                    + "|rewritten query|standalone query|search query|rewritten|query|output|answer)\\s*[:：]\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern THINK_BLOCK = Pattern.compile("(?s)<think>.*?(?:</think>|$)");
    // Only a pair wrapping the whole line is stripped; 《》 is kept because it marks titles.
    private static final String QUOTE_OPEN = "\"'`“‘「『";
    private static final String QUOTE_CLOSE = "\"'`”’」』";

    private final ModelClient client;
    private final String model;
    private final int maxHistoryTurns;
    private final int maxHistoryCharsPerTurn;

    /** Provider default model, 6 history turns, 500 chars per turn. */
    public LlmQueryRewriter(ModelClient client) {
        this(client, null, 6, 500);
    }

    /**
     * @param client                 model port
     * @param model                  model name, null for the provider default
     * @param maxHistoryTurns        most recent turns included in the prompt
     * @param maxHistoryCharsPerTurn each turn is truncated to this many chars
     */
    public LlmQueryRewriter(ModelClient client, String model, int maxHistoryTurns, int maxHistoryCharsPerTurn) {
        this.client = Objects.requireNonNull(client, "client");
        if (maxHistoryTurns <= 0 || maxHistoryCharsPerTurn <= 0) {
            throw new IllegalArgumentException("maxHistoryTurns and maxHistoryCharsPerTurn must be positive");
        }
        this.model = model;
        this.maxHistoryTurns = maxHistoryTurns;
        this.maxHistoryCharsPerTurn = maxHistoryCharsPerTurn;
    }

    @Override
    public Rewrite rewrite(String question, List<ConversationTurn> history) {
        Objects.requireNonNull(question, "question");
        if (history == null || history.isEmpty() || question.isBlank()) {
            return Rewrite.unchanged(question);
        }
        ModelRequest request = ModelRequest.builder()
                .model(model)
                .addMessage(ChatMessage.system(SYSTEM_PROMPT))
                .addMessage(ChatMessage.user(userPrompt(question, history)))
                .temperature(0.0)
                .build();
        ModelResponse response;
        try {
            response = client.chat(request);
        } catch (RuntimeException e) {
            log.warn("query rewrite failed, using original question: {}", e.getMessage());
            return new Rewrite(question, 0, 0, true);
        }
        int promptTokens = response == null || response.usage() == null ? 0 : response.usage().promptTokens();
        int completionTokens = response == null || response.usage() == null ? 0 : response.usage().completionTokens();
        String rewritten = clean(response == null ? null : response.content());
        if (rewritten.isEmpty() || rewritten.length() > question.length() * 3 + 100) {
            log.warn("query rewrite returned unusable output ({} chars), using original question",
                    rewritten.length());
            return new Rewrite(question, promptTokens, completionTokens, true);
        }
        log.debug("query rewritten: '{}' -> '{}'", question, rewritten);
        return new Rewrite(rewritten, promptTokens, completionTokens, false);
    }

    String userPrompt(String question, List<ConversationTurn> history) {
        List<ConversationTurn> recent = history.size() <= maxHistoryTurns
                ? history : history.subList(history.size() - maxHistoryTurns, history.size());
        StringBuilder sb = new StringBuilder("对话历史（从早到晚）：\n");
        for (ConversationTurn turn : recent) {
            sb.append("assistant".equalsIgnoreCase(turn.role()) ? "助手" : "用户")
                    .append(": ")
                    .append(truncate(turn.content().replaceAll("\\s+", " ").strip()))
                    .append('\n');
        }
        return sb.append("\n最后一个问题：").append(question.strip())
                .append("\n\n改写后的独立检索查询：").toString();
    }

    private String truncate(String text) {
        return text.length() <= maxHistoryCharsPerTurn ? text : Texts.truncate(text, maxHistoryCharsPerTurn) + "…";
    }

    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        for (String line : THINK_BLOCK.matcher(raw).replaceAll("").strip().split("\\R")) {
            String cleaned = cleanLine(line.strip());
            if (!cleaned.isEmpty()) {
                return cleaned;
            }
        }
        return "";
    }

    private static String cleanLine(String line) {
        String previous;
        do {
            previous = line;
            line = PREFIX.matcher(line).replaceFirst("").strip();
            line = stripQuotes(line);
        } while (!line.equals(previous));
        return line;
    }

    private static String stripQuotes(String s) {
        if (s.length() < 2) {
            return s;
        }
        int open = QUOTE_OPEN.indexOf(s.charAt(0));
        if (open >= 0 && QUOTE_CLOSE.charAt(open) == s.charAt(s.length() - 1)) {
            return s.substring(1, s.length() - 1).strip();
        }
        return s;
    }
}
