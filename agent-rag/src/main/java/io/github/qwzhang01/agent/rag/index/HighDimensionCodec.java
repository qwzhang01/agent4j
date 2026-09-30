package io.github.qwzhang01.agent.rag.index;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;

import java.io.IOException;

/**
 * Default codec with a higher vector dimension limit. Keeps the delegate's name, so the
 * index stays readable by a stock Lucene codec; the limit only applies at write time.
 */
final class HighDimensionCodec extends FilterCodec {

    private final KnnVectorsFormat vectors;

    HighDimensionCodec(Codec delegate, int maxDimensions) {
        super(delegate.getName(), delegate);
        this.vectors = new MaxDimensionsFormat(delegate.knnVectorsFormat(), maxDimensions);
    }

    @Override
    public KnnVectorsFormat knnVectorsFormat() {
        return vectors;
    }

    private static final class MaxDimensionsFormat extends KnnVectorsFormat {

        private final KnnVectorsFormat delegate;
        private final int maxDimensions;

        MaxDimensionsFormat(KnnVectorsFormat delegate, int maxDimensions) {
            super(delegate.getName());
            this.delegate = delegate;
            this.maxDimensions = maxDimensions;
        }

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            return delegate.fieldsWriter(state);
        }

        @Override
        public KnnVectorsReader fieldsReader(SegmentReadState state) throws IOException {
            return delegate.fieldsReader(state);
        }

        @Override
        public int getMaxDimensions(String fieldName) {
            return maxDimensions;
        }
    }
}
