package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.qwzhang01.agent.rag.index.TestChunks.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuceneChunkIndexContractTest extends ChunkIndexContractTest {

    @TempDir
    Path dir;

    @Override
    protected ChunkIndex open(int dimensions) {
        return LuceneChunkIndex.open(dir, dimensions);
    }

    @Test
    void prefixCountAboveLimitIsRejected() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "h", List.of(chunk("a.md", 0, "text")), List.<float[]>of(new float[]{1, 0, 0}));
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i <= LuceneChunkIndex.MAX_DOC_ID_PREFIXES; i++) {
            prefixes.add("p" + i + "/");
        }
        SearchFilter tooMany = SearchFilter.none().withDocIdPrefixes(prefixes);

        assertThrows(IllegalArgumentException.class, () -> index.keywordSearch("text", 5, tooMany));
        assertThrows(IllegalArgumentException.class, () -> index.vectorSearch(new float[]{1, 0, 0}, 5, tooMany));
    }

    @Test
    void maxPrefixesWithManyMetadataFiltersAndLongQueryStayUnderClauseLimit() {
        ChunkIndex index = index(3);
        Map<String, String> metadata = new java.util.HashMap<>();
        for (int i = 0; i < 40; i++) {
            metadata.put("k" + i, "v");
        }
        index.upsert("keep/a.md", "h", List.of(chunk("keep/a.md", 0, "字 foo", metadata)), List.<float[]>of(new float[]{1, 0, 0}));
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < LuceneChunkIndex.MAX_DOC_ID_PREFIXES - 1; i++) {
            prefixes.add("p" + i + "/");
        }
        prefixes.add("keep/");
        SearchFilter filter = SearchFilter.of(metadata).withDocIdPrefixes(prefixes);
        StringBuilder query = new StringBuilder("foo ");
        for (int i = 0; i < 3000; i++) {
            query.append((char) ('一' + i));
        }

        assertEquals(1, index.keywordSearch(query.toString(), 5, filter).size());
        assertEquals(1, index.vectorSearch(new float[]{1, 0, 0}, 5, filter).size());
    }
}
