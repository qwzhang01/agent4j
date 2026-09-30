package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ParsedDocument;

import java.io.IOException;
import java.nio.file.Path;

/** Parses one file into ordered structural blocks. One implementation per format. */
public interface DocumentLoader {

    /** Whether this loader handles {@code file} (normally by extension). */
    boolean supports(Path file);

    /**
     * @param file  file to parse
     * @param docId identity assigned by the caller (corpus-relative path)
     * @return parsed document; {@code contentHash} is the SHA-256 of the file bytes
     * @throws IOException when the file cannot be read or parsed
     */
    ParsedDocument load(Path file, String docId) throws IOException;
}
