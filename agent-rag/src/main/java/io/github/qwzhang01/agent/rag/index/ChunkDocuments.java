package io.github.qwzhang01.agent.rag.index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.rag.model.Chunk;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Lucene document layout of {@link LuceneChunkIndex}: one marker document per indexed document
 * (content hash) and one document per chunk. Both carry {@link #F_DOC_ID}, the replace/delete key.
 */
final class ChunkDocuments {

    private static final Logger log = LoggerFactory.getLogger(ChunkDocuments.class);

    static final String F_KIND = "kind";
    static final String KIND_CHUNK = "chunk";
    static final String KIND_DOC = "doc";
    static final String F_DOC_ID = "docId";
    static final String F_CHUNK_ID = "chunkId";
    static final String F_HASH = "contentHash";
    static final String F_TITLE = "docTitle";
    static final String F_SOURCE = "source";
    static final String F_SECTION = "sectionPath";
    static final String F_TEXT = "text";
    static final String F_START = "startLine";
    static final String F_END = "endLine";
    static final String F_PAGE = "page";
    static final String F_META = "metadata";
    static final String F_CONTENT = "content";
    static final String F_VECTOR = "vector";
    static final String META_PREFIX = "meta.";

    static final Query CHUNKS = new TermQuery(new Term(F_KIND, KIND_CHUNK));
    static final Query MARKERS = new TermQuery(new Term(F_KIND, KIND_DOC));

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {
    };

    private ChunkDocuments() {
    }

    static Term docTerm(String docId) {
        return new Term(F_DOC_ID, docId);
    }

    static Query metadataFilter(String key, String value) {
        return new TermQuery(new Term(META_PREFIX + key, value));
    }

    static Document marker(String docId, String contentHash) {
        Document doc = new Document();
        doc.add(new StringField(F_KIND, KIND_DOC, Field.Store.NO));
        doc.add(new StringField(F_DOC_ID, docId, Field.Store.YES));
        doc.add(new StoredField(F_HASH, contentHash));
        return doc;
    }

    static Document chunk(Chunk chunk, float[] vector) {
        Document doc = new Document();
        doc.add(new StringField(F_KIND, KIND_CHUNK, Field.Store.NO));
        doc.add(new StringField(F_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
        doc.add(new StringField(F_DOC_ID, chunk.docId(), Field.Store.YES));
        doc.add(new StoredField(F_TITLE, chunk.docTitle()));
        if (chunk.source() != null) {
            doc.add(new StoredField(F_SOURCE, chunk.source()));
        }
        doc.add(new StoredField(F_SECTION, writeJson(chunk.sectionPath())));
        doc.add(new StoredField(F_TEXT, chunk.text()));
        doc.add(new StoredField(F_START, chunk.startLine()));
        doc.add(new StoredField(F_END, chunk.endLine()));
        if (chunk.page() != null) {
            doc.add(new StoredField(F_PAGE, chunk.page()));
        }
        doc.add(new StoredField(F_META, writeJson(chunk.metadata())));
        doc.add(new TextField(F_CONTENT, chunk.contextualText(), Field.Store.NO));
        chunk.metadata().forEach((key, value) ->
                doc.add(new StringField(META_PREFIX + key, value, Field.Store.NO)));
        if (vector != null) {
            if (isZero(vector)) {
                log.debug("Zero vector for chunk {}; indexing keyword-only", chunk.chunkId());
            } else {
                doc.add(new KnnFloatVectorField(F_VECTOR, vector.clone(), VectorSimilarityFunction.COSINE));
            }
        }
        return doc;
    }

    static Chunk toChunk(Document doc) {
        IndexableField page = doc.getField(F_PAGE);
        return new Chunk(
                doc.get(F_CHUNK_ID),
                doc.get(F_DOC_ID),
                doc.get(F_TITLE),
                doc.get(F_SOURCE),
                readJson(doc.get(F_SECTION), STRING_LIST),
                doc.get(F_TEXT),
                doc.getField(F_START).numericValue().intValue(),
                doc.getField(F_END).numericValue().intValue(),
                page == null ? null : page.numericValue().intValue(),
                readJson(doc.get(F_META), STRING_MAP));
    }

    static boolean isZero(float[] vector) {
        for (float v : vector) {
            if (v != 0f) {
                return false;
            }
        }
        return true;
    }

    private static String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + value, e);
        }
    }

    private static <T> T readJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored field: " + json, e);
        }
    }
}
