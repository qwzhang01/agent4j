package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Delegates each file to the first loader that supports it. */
public final class CompositeLoader implements DocumentLoader {

    private final List<DocumentLoader> loaders;

    public CompositeLoader(List<DocumentLoader> loaders) {
        Objects.requireNonNull(loaders, "loaders");
        this.loaders = List.copyOf(loaders);
    }

    /** Markdown then PDF. */
    public static CompositeLoader defaults() {
        return new CompositeLoader(List.of(new MarkdownLoader(), new PdfLoader()));
    }

    public List<DocumentLoader> loaders() {
        return loaders;
    }

    @Override
    public boolean supports(Path file) {
        return loaders.stream().anyMatch(l -> l.supports(file));
    }

    /** @throws IOException also when no loader supports {@code file} */
    @Override
    public ParsedDocument load(Path file, String docId) throws IOException {
        for (DocumentLoader loader : loaders) {
            if (loader.supports(file)) {
                return loader.load(file, docId);
            }
        }
        throw new IOException("No loader supports " + file);
    }
}
