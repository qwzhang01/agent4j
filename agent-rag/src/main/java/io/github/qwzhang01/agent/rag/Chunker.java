package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;

import java.util.List;

/** Splits a parsed document into chunks. Must be deterministic: same input, same chunk ids and texts. */
public interface Chunker {

    List<Chunk> chunk(ParsedDocument document);
}
