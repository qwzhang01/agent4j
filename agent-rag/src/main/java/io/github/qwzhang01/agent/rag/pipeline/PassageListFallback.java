package io.github.qwzhang01.agent.rag.pipeline;

import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;

/** Default generation fallback: lists the passages by title and section, each with its citation marker. */
final class PassageListFallback {

    private PassageListFallback() {
    }

    static String render(List<ScoredChunk> contexts) {
        if (contexts.isEmpty()) {
            return "回答服务暂时不可用，也没有检索到相关资料。";
        }
        StringBuilder sb = new StringBuilder("回答服务暂时不可用。以下是检索到的相关资料：");
        for (ScoredChunk c : contexts) {
            Chunk chunk = c.chunk();
            sb.append("\n- 《").append(chunk.docTitle()).append('》');
            if (!chunk.sectionPath().isEmpty()) {
                sb.append(' ').append(chunk.sectionLabel());
            }
            sb.append(" [").append(chunk.chunkId()).append(']');
        }
        return sb.toString();
    }
}
