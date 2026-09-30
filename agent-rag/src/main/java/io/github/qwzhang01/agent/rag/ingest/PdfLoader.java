package io.github.qwzhang01.agent.rag.ingest;

import io.github.qwzhang01.agent.rag.DocumentLoader;
import io.github.qwzhang01.agent.rag.model.BlockType;
import io.github.qwzhang01.agent.rag.model.DocumentBlock;
import io.github.qwzhang01.agent.rag.model.ParsedDocument;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Loads {@code .pdf} files with PDFBox: one {@link BlockType#PARAGRAPH} block per blank-line
 * separated paragraph of each page's extracted text. Blocks carry {@code page}; line numbers are 0.
 * No heading detection, so every block has an empty section path. The title is the PDF info title,
 * else the file name.
 */
public final class PdfLoader implements DocumentLoader {

    private static final Pattern PARAGRAPH_BREAK = Pattern.compile("\\n\\s*\\n");

    @Override
    public boolean supports(Path file) {
        return FileSupport.hasExtension(file, ".pdf");
    }

    @Override
    public ParsedDocument load(Path file, String docId) throws IOException {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(docId, "docId");
        byte[] bytes = Files.readAllBytes(file);
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            // PDFBox marks paragraph ends it detects from layout; turn them into blank lines.
            stripper.setParagraphEnd("\n");
            List<DocumentBlock> blocks = new ArrayList<>();
            int pages = pdf.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf).replace("\r\n", "\n");
                for (String paragraph : PARAGRAPH_BREAK.split(text)) {
                    String body = paragraph.strip();
                    if (!body.isEmpty()) {
                        blocks.add(new DocumentBlock(BlockType.PARAGRAPH, body, 0, List.of(), 0, 0, page));
                    }
                }
            }
            return new ParsedDocument(docId, titleOf(pdf, file), file.toAbsolutePath().toString(),
                    FileSupport.sha256Hex(bytes), blocks, Map.of());
        }
    }

    private static String titleOf(PDDocument pdf, Path file) {
        PDDocumentInformation info = pdf.getDocumentInformation();
        String title = info == null ? null : info.getTitle();
        return title == null || title.isBlank() ? FileSupport.baseName(file) : title.strip();
    }
}
