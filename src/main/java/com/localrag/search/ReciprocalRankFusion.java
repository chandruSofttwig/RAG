package com.localrag.search;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted Reciprocal Rank Fusion (port of andromedia's ReciprocalRankFusion):
 * {@code score(d) = Σ_channel weight / (k + rank_channel(d))}. Raw channel scores are carried over.
 */
public final class ReciprocalRankFusion {

  public static final int DEFAULT_K = 60;

  private ReciprocalRankFusion() {}

  public static List<Hit> fuse(
      List<Hit> bm25Hits, List<Hit> semanticHits, int limit, int k, double bm25Weight, double semanticWeight) {
    Map<String, Fused> fused = new LinkedHashMap<>();
    accumulate(fused, bm25Hits, k, bm25Weight, true);
    accumulate(fused, semanticHits, k, semanticWeight, false);
    return fused.values().stream()
        .sorted(Comparator.comparingDouble((Fused f) -> f.score).reversed())
        .limit(Math.max(1, limit))
        .map(f -> new Hit(f.template.chunk(), (float) f.score, f.bm25, f.semantic))
        .toList();
  }

  private static void accumulate(Map<String, Fused> fused, List<Hit> hits, int k, double weight, boolean bm25) {
    int rank = 1;
    for (Hit hit : hits) {
      Fused entry = fused.computeIfAbsent(hit.id(), id -> new Fused(hit));
      entry.score += weight / (k + rank);
      if (bm25) {
        entry.bm25 = hit.bm25Score() != null ? hit.bm25Score() : hit.score();
      } else {
        entry.semantic = hit.semanticScore() != null ? hit.semanticScore() : hit.score();
      }
      rank++;
    }
  }

  private static final class Fused {
    final Hit template;
    double score;
    Float bm25;
    Float semantic;

    Fused(Hit template) {
      this.template = template;
    }
  }
}
