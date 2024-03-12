/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jbellis.jvector.graph;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import io.github.jbellis.jvector.LuceneTestCase;
import io.github.jbellis.jvector.TestUtil;
import io.github.jbellis.jvector.disk.OnDiskGraphIndex;
import io.github.jbellis.jvector.disk.SimpleMappedReader;
import io.github.jbellis.jvector.pq.PQVectors;
import io.github.jbellis.jvector.pq.ProductQuantization;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.vector.VectorEncoding;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class Test2DThreshold extends LuceneTestCase {
    @Test
    public void testThreshold10k() throws IOException {
        testThreshold(10_000, 8);
    }

    @Test
    public void testThreshold20k() throws IOException {
        testThreshold(20_000, 16);
    }

    public void testThreshold(int graphSize, int maxDegree) throws IOException {
        var R = getRandom();
        // generate 2D vectors
        var vectors = TestFloatVectorGraph.createRandomFloatVectors(graphSize, 2, R);

        // build index
        var ravv = new ListRandomAccessVectorValues(List.of(vectors), 2);
        var builder = new GraphIndexBuilder<>(ravv, VectorEncoding.FLOAT32, VectorSimilarityFunction.EUCLIDEAN, maxDegree, 2 * maxDegree, 1.2f, 1.4f);
        var onHeapGraph = builder.build();

        // test raw vectors
        var searcher = new GraphSearcher.Builder<>(onHeapGraph.getView()).build();
        for (int i = 0; i < 10; i++) {
            TestParams tp = createTestParams(vectors);

            NodeSimilarity.ExactScoreFunction sf = j -> VectorSimilarityFunction.EUCLIDEAN.compare(tp.q, ravv.vectorValue(j));
            var result = searcher.search(sf, null, vectors.length, tp.th, Bits.ALL);
            // System.out.printf("visited %d to find %d/%d results for threshold %s%n", result.getVisitedCount(), result.getNodes().length, tp.exactCount, tp.th);
            assert result.getVisitedCount() < vectors.length : "visited all vectors for threshold " + tp.th;
            assert result.getNodes().length >= 0.9 * tp.exactCount : "returned " + result.getNodes().length + " nodes for threshold " + tp.th + " but should have returned at least " + tp.exactCount;
        }

        // test compressed
        Path outputPath = Files.createTempFile("graph", ".jvector");
        TestUtil.writeGraph(onHeapGraph, ravv, outputPath);
        var pq = ProductQuantization.compute(ravv, ravv.dimension(), false);
        var cv = new PQVectors(pq, pq.encodeAll(List.of(vectors)));

        try (var marr = new SimpleMappedReader(outputPath.toAbsolutePath().toString());
             var onDiskGraph = new OnDiskGraphIndex<float[]>(marr::duplicate, 0))
        {
            for (int i = 0; i < 10; i++) {
                TestParams tp = createTestParams(vectors);
                searcher = new GraphSearcher.Builder<>(onDiskGraph.getView()).build();
                NodeSimilarity.Reranker reranker = (j) -> VectorSimilarityFunction.EUCLIDEAN.compare(tp.q, ravv.vectorValue(j));
                var asf = cv.approximateScoreFunctionFor(tp.q, VectorSimilarityFunction.EUCLIDEAN);
                var result = searcher.search(asf, reranker, vectors.length, tp.th, Bits.ALL);

                // System.out.printf("visited %d to find %d/%d results for threshold %s%n", result.getVisitedCount(), result.getNodes().length, tp.exactCount, tp.th);
                assert result.getVisitedCount() < vectors.length : "visited all vectors for threshold " + tp.th;
                assert result.getNodes().length >= 0.9 * tp.exactCount : "returned " + result.getNodes().length + " nodes for threshold " + tp.th + " but should have returned at least " + tp.exactCount;
            }
        }
    }

    /**
     * Create "interesting" test parameters -- shouldn't match too many (we want to validate
     * that threshold code doesn't just crawl the entire graph) or too few (we might not find them)
     */
    private TestParams createTestParams(float[][] vectors) {
        var R = getRandom();

        // Generate a random query vector and threshold
        var q = TestUtil.randomVector(R, 2);
        float th = (float) (0.3 + 0.45 * R.nextDouble());

        // Count the number of vectors that have a similarity score greater than or equal to the threshold
        long exactCount = Arrays.stream(vectors).filter(v -> VectorSimilarityFunction.EUCLIDEAN.compare(q, v) >= th).count();

        return new TestParams(exactCount, q, th);
    }

    /**
     * Encapsulates a search vector q and a threshold th with the exact number of matches in the graph
     */
    private static class TestParams {
        public final long exactCount;
        public final float[] q;
        public final float th;

        public TestParams(long exactCount, float[] q, float th) {
            this.exactCount = exactCount;
            this.q = q;
            this.th = th;
        }
    }
}
