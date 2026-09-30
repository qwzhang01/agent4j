package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompositeLoaderTest {

    @Test
    void defaultsRouteMarkdownAndPdf(@TempDir Path dir) throws Exception {
        CompositeLoader loader = CompositeLoader.defaults();
        assertEquals(2, loader.loaders().size());
        assertInstanceOf(MarkdownLoader.class, loader.loaders().get(0));
        assertInstanceOf(PdfLoader.class, loader.loaders().get(1));

        Path md = dir.resolve("a.md");
        Files.writeString(md, "# Title\n\nbody\n");
        assertEquals("Title", loader.load(md, "a.md").title());

        Path pdf = dir.resolve("b.pdf");
        PdfLoaderTest.writePdf(pdf, "PDF Title", List.of(List.of("Body.")));
        assertEquals("PDF Title", loader.load(pdf, "b.pdf").title());

        assertTrue(loader.supports(md));
        assertFalse(loader.supports(dir.resolve("c.txt")));
        assertThrows(IOException.class, () -> loader.load(dir.resolve("c.txt"), "c.txt"));
    }

    @Test
    void firstSupportingLoaderWins() throws Exception {
        ParsedDocument expected = new ParsedDocument("x", "first", "src", "h", List.of(), null);
        DocumentLoader first = new StubLoader(expected);
        DocumentLoader second = new StubLoader(new ParsedDocument("x", "second", "src", "h", List.of(), null));
        CompositeLoader loader = new CompositeLoader(List.of(first, second));
        assertEquals(expected, loader.load(Path.of("any.txt"), "x"));
        assertThrows(NullPointerException.class, () -> new CompositeLoader(null));
    }

    private record StubLoader(ParsedDocument result) implements DocumentLoader {

        @Override
        public boolean supports(Path file) {
            return true;
        }

        @Override
        public ParsedDocument load(Path file, String docId) {
            return result;
        }
    }
}
