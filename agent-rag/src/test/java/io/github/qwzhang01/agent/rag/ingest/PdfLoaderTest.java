package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfLoaderTest {

    private final PdfLoader loader = new PdfLoader();

    @Test
    void supportsPdfOnly() {
        assertTrue(loader.supports(Path.of("x/Manual.PDF")));
        assertFalse(loader.supports(Path.of("x/manual.md")));
    }

    @Test
    void roundTripsPagesAndParagraphs(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("manual.pdf");
        writePdf(file, "Operator Manual", List.of(
                List.of("Timeout is set to 30 seconds.", "Retries default to three."),
                List.of("Page two paragraph.")));

        ParsedDocument doc = loader.load(file, "manual.pdf");

        assertEquals("Operator Manual", doc.title());
        assertEquals(64, doc.contentHash().length());
        List<DocumentBlock> blocks = doc.blocks();
        assertEquals(3, blocks.size(), blocks::toString);
        assertEquals("Timeout is set to 30 seconds.", blocks.get(0).text());
        assertEquals("Retries default to three.", blocks.get(1).text());
        assertEquals("Page two paragraph.", blocks.get(2).text());
        assertEquals(List.of(1, 1, 2), blocks.stream().map(DocumentBlock::page).toList());
        for (DocumentBlock b : blocks) {
            assertEquals(BlockType.PARAGRAPH, b.type());
            assertEquals(0, b.startLine());
            assertEquals(0, b.endLine());
            assertTrue(b.sectionPath().isEmpty());
        }

        List<Chunk> chunks = new StructureAwareChunker().chunk(doc);
        assertEquals(List.of(1, 2), chunks.stream().map(Chunk::page).toList());
        assertEquals("Timeout is set to 30 seconds.\n\nRetries default to three.", chunks.get(0).text());
        assertEquals("manual.pdf#1", chunks.get(1).chunkId());
    }

    @Test
    void titleFallsBackToFileName(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("untitled.pdf");
        writePdf(file, null, List.of(List.of("Hello.")));
        assertEquals("untitled", loader.load(file, "untitled.pdf").title());
    }

    @Test
    void corruptPdfThrowsIOException(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.pdf");
        Files.writeString(file, "not a pdf");
        assertThrows(IOException.class, () -> loader.load(file, "broken.pdf"));
    }

    static void writePdf(Path file, String title, List<List<String>> pages) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (List<String> paragraphs : pages) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                    float y = 700;
                    for (String paragraph : paragraphs) {
                        cs.beginText();
                        cs.setFont(font, 12);
                        cs.newLineAtOffset(72, y);
                        cs.showText(paragraph);
                        cs.endText();
                        y -= 120;
                    }
                }
            }
            if (title != null) {
                PDDocumentInformation info = new PDDocumentInformation();
                info.setTitle(title);
                pdf.setDocumentInformation(info);
            }
            pdf.save(file.toFile());
        }
    }
}
