package com.localrag.search;

import com.localrag.config.QueryType;
import com.localrag.config.Settings;
import com.localrag.index.IndexFields;
import com.localrag.index.IndexHandle;
import com.localrag.index.Tokens;
import com.localrag.llm.Embedder;
import com.localrag.llm.OllamaException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

/**
 * Hybrid retrieval pipeline (port of andromedia's HybridSearchService):
 *
 * <pre>
 * query ─┬─ BM25 over the candidate pool ─────────────────────────┐
 *        └─ embed query → HNSW KNN (cosine) → min-score filter ───┤
 *                                                                 ▼
 *      weighted RRF (weights chosen by query type) → relative-score filter
 *      → lexical anchoring (rescue strong exact BM25 matches) → top-k → path heuristics
 * </pre>
 *
 * If the index has no vectors or the local embedding model is unavailable, the semantic channel
 * is skipped and the answer is BM25-only; the reason is reported in the diagnostics.
 */
public final class HybridRetriever {

  private static final int MAX_QUERY_TERMS = 64;

  private record SemanticCandidates(List<Hit> hits, String skipReason) {}

  private final IndexHandle index;
  private final Embedder embedder;
  private final Settings settings;

  public HybridRetriever(IndexHandle index, Embedder embedder, Settings settings) {
    this.index = index;
    this.embedder = embedder;
    this.settings = settings;
  }

  public SearchResult search(String query, int topK, SearchMode mode) {
    long started = System.nanoTime();
    int k = Math.max(1, topK);
    QueryType type = QueryTypeClassifier.classify(query);
    Settings.ChannelWeights weights = settings.weights.get(type);
    int pool = Math.max(k * settings.candidateMultiplier, settings.minCandidatePool);
    // Fuse on a larger pool so BM25-strong / KNN-weak hits survive; truncate last.
    int fusePool = Math.min(Math.max(k * 4, 25), pool);

    List<Hit> bm25 = List.of();
    List<Hit> semantic = List.of();
    String skipReason = null;

    switch (mode) {
      case BM25 -> {
        bm25 = bm25Search(query, pool, type);
        skipReason = "bm25 mode";
      }
      case SEMANTIC -> semantic = semanticSearch(query, pool); // errors surface in semantic-only mode
      case HYBRID -> {
        CompletableFuture<List<Hit>> bm25Future = CompletableFuture.supplyAsync(() -> bm25Search(query, pool, type));
        CompletableFuture<SemanticCandidates> semanticFuture =
            CompletableFuture.supplyAsync(
                () -> {
                  try {
                    return new SemanticCandidates(semanticSearch(query, pool), null);
                  } catch (IllegalStateException | OllamaException ex) {
                    return new SemanticCandidates(List.of(), ex.getMessage());
                  }
                });
        try {
          bm25 = bm25Future.join();
          SemanticCandidates candidates = semanticFuture.join();
          semantic = candidates.hits();
          skipReason = candidates.skipReason();
        } catch (CompletionException ex) {
          throw ex.getCause() instanceof RuntimeException re ? re : ex;
        }
      }
    }

    int before = semantic.size();
    semantic =
        semantic.stream()
            .filter(h -> h.semanticScore() != null && h.semanticScore() >= settings.semanticMinScore)
            .toList();
    int semanticFiltered = before - semantic.size();

    List<Hit> fused;
    if (mode == SearchMode.BM25 || semantic.isEmpty()) {
      fused = bm25.stream().limit(fusePool).toList();
    } else if (mode == SearchMode.SEMANTIC || bm25.isEmpty()) {
      fused = semantic.stream().limit(fusePool).toList();
    } else {
      fused =
          ReciprocalRankFusion.fuse(bm25, semantic, fusePool, settings.rrfK, weights.bm25(), weights.semantic());
      fused = RelativeScoreFilter.apply(fused, fusePool, settings.relativeScoreFloor);
      // Anchor against heuristically ranked BM25 so path/name boosts match what BM25-only surfaces.
      fused = LexicalAnchorFilter.apply(fused, HeuristicRanker.rank(query, type, bm25), fusePool, query);
    }
    List<Hit> ranked = HeuristicRanker.rank(query, type, fused.stream().limit(k).toList());

    boolean hybrid = mode == SearchMode.HYBRID;
    return new SearchResult(
        ranked,
        new SearchResult.Diagnostics(
            query,
            mode,
            type,
            hybrid ? weights.bm25() : null,
            hybrid ? weights.semantic() : null,
            bm25.size(),
            semantic.size(),
            semanticFiltered,
            mode == SearchMode.BM25 || skipReason != null,
            skipReason,
            Math.round((System.nanoTime() - started) / 1e5) / 10.0));
  }

  /** BM25 over the analyzed content field. Identifier queries require every whole word (AND), falling back to OR. */
  List<Hit> bm25Search(String query, int topK, QueryType type) {
    List<String> terms = Tokens.analyze(query);
    if (terms.isEmpty()) {
      return List.of();
    }
    terms = terms.subList(0, Math.min(terms.size(), MAX_QUERY_TERMS));
    if (type == QueryType.IDENTIFIER) {
      List<String> required = Tokens.primaryTerms(query).stream().filter(terms::contains).toList();
      if (!required.isEmpty()) {
        List<Hit> strict = runBm25(buildQuery(terms, required), topK);
        if (!strict.isEmpty()) {
          return strict;
        }
      }
    }
    return runBm25(buildQuery(terms, List.of()), topK);
  }

  private static Query buildQuery(List<String> should, List<String> must) {
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    for (String term : should) {
      builder.add(new TermQuery(new Term(IndexFields.CONTENT, term)), BooleanClause.Occur.SHOULD);
    }
    for (String term : must) {
      builder.add(new TermQuery(new Term(IndexFields.CONTENT, term)), BooleanClause.Occur.FILTER);
    }
    return builder.build();
  }

  private List<Hit> runBm25(Query query, int topK) {
    try {
      TopDocs top = index.searcher().search(query, topK);
      List<Hit> hits = new ArrayList<>(top.scoreDocs.length);
      for (ScoreDoc sd : top.scoreDocs) {
        hits.add(new Hit(index.chunk(sd.doc), sd.score, sd.score, null));
      }
      return hits;
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Cosine KNN over the HNSW graph. Throws IllegalStateException when the channel is unusable. */
  List<Hit> semanticSearch(String query, int topK) {
    if (!index.meta().hasEmbeddings()) {
      throw new IllegalStateException("index has no embeddings (re-index without --no-embed)");
    }
    if (embedder == null) {
      throw new IllegalStateException("no embedding model configured");
    }
    if (!embedder.model().equals(index.meta().embeddingModel())) {
      throw new IllegalStateException(
          "index was embedded with '" + index.meta().embeddingModel() + "' but the query model is '"
              + embedder.model() + "' — re-index or pass --embed-model " + index.meta().embeddingModel());
    }
    float[] vector = embedder.embedQuery(query);
    if (vector.length != index.meta().dimensions()) {
      throw new IllegalStateException("query embedding dimension " + vector.length + " does not match index");
    }
    try {
      TopDocs top = index.searcher().search(new KnnFloatVectorQuery(IndexFields.EMBEDDING, vector, topK), topK);
      List<Hit> hits = new ArrayList<>(top.scoreDocs.length);
      for (ScoreDoc sd : top.scoreDocs) {
        float cosine = 2f * sd.score - 1f; // Lucene COSINE score is (1 + cos) / 2
        hits.add(new Hit(index.chunk(sd.doc), cosine, null, cosine));
      }
      return hits;
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
