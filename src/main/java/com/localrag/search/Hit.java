package com.localrag.search;

import com.localrag.index.Chunk;

/**
 * A retrieved chunk. {@code score} is the pipeline's current ranking score; the per-channel raw
 * scores are kept for diagnostics (bm25 = Lucene BM25, semantic = cosine similarity).
 */
public record Hit(Chunk chunk, float score, Float bm25Score, Float semanticScore) {

  public String id() {
    return chunk.id();
  }

  public Hit withScore(float newScore) {
    return new Hit(chunk, newScore, bm25Score, semanticScore);
  }
}
