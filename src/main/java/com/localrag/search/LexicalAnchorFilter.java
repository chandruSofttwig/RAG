package com.localrag.search;

import com.localrag.index.Tokens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Port of andromedia's LexicalAnchorFilter + QueryLexicalMatch. When BM25 has a strong relative
 * lead, competitive and query-aligned BM25 hits that fusion dropped are forced back to the top —
 * dense retrievers often miss exact-name matches. All thresholds are relative to the BM25
 * distribution; there are no absolute Lucene score floors.
 */
public final class LexicalAnchorFilter {

  static final float RELATIVE_MEMBER_FRACTION = 0.25f;
  static final float STRONG_VS_MEDIAN_FACTOR = 1.2f;
  static final int MAX_ANCHORS = 25;

  private LexicalAnchorFilter() {}

  public static List<Hit> apply(List<Hit> hybrid, List<Hit> bm25Hits, int limit, String query) {
    int max = Math.max(1, limit);
    if (bm25Hits == null || bm25Hits.isEmpty()) {
      return hybrid == null ? List.of() : hybrid.stream().limit(max).toList();
    }
    if (hybrid == null || hybrid.isEmpty()) {
      return bm25Hits.stream().limit(max).toList();
    }
    if (!hasStrongLexicalLead(bm25Hits)) {
      return hybrid.stream().limit(max).toList();
    }

    float memberFloor = bm25Hits.getFirst().score() * RELATIVE_MEMBER_FRACTION;
    List<String> tokens = Tokens.significant(query);
    Map<String, Hit> anchors = new LinkedHashMap<>();
    for (Hit hit : bm25Hits) {
      if (hit.score() < memberFloor || (!tokens.isEmpty() && !isAligned(hit, tokens))) {
        continue;
      }
      anchors.putIfAbsent(hit.id(), hit);
      if (anchors.size() >= MAX_ANCHORS) {
        break;
      }
    }

    Set<String> hybridIds = new HashSet<>();
    hybrid.forEach(h -> hybridIds.add(h.id()));
    float topScore = hybrid.getFirst().score() > 0f ? hybrid.getFirst().score() : 0.01f;
    List<Hit> promoted = new ArrayList<>();
    for (Hit anchor : anchors.values()) {
      if (hybridIds.contains(anchor.id())) {
        continue;
      }
      float scale = 1.05f - Math.min(0.3f, promoted.size() * 0.05f);
      promoted.add(new Hit(anchor.chunk(), topScore * scale, anchor.bm25Score(), null));
      if (promoted.size() >= max) {
        break;
      }
    }

    List<Hit> result = new ArrayList<>(max);
    Set<String> seen = new HashSet<>();
    for (List<Hit> source : List.of(promoted, hybrid)) {
      for (Hit hit : source) {
        if (result.size() >= max) {
          return List.copyOf(result);
        }
        if (seen.add(hit.id())) {
          result.add(hit);
        }
      }
    }
    return List.copyOf(result);
  }

  /** True when the top BM25 score beats the median of the BM25 head by {@link #STRONG_VS_MEDIAN_FACTOR}. */
  static boolean hasStrongLexicalLead(List<Hit> bm25Hits) {
    if (bm25Hits.isEmpty() || !(bm25Hits.getFirst().score() > 0f)) {
      return false;
    }
    int n = Math.min(bm25Hits.size(), 10);
    float[] scores = new float[n];
    for (int i = 0; i < n; i++) {
      scores[i] = bm25Hits.get(i).score();
    }
    Arrays.sort(scores);
    float median = n % 2 == 1 ? scores[n / 2] : (scores[n / 2 - 1] + scores[n / 2]) / 2f;
    return !(median > 0f) || bm25Hits.getFirst().score() >= median * STRONG_VS_MEDIAN_FACTOR;
  }

  /** Does the hit literally contain a distinctive (len >= 4, else any) query token? */
  static boolean isAligned(Hit hit, List<String> queryTokens) {
    String haystack =
        (hit.chunk().path() + " " + (hit.chunk().symbol() == null ? "" : hit.chunk().symbol()) + " " + hit.chunk().text())
            .toLowerCase(Locale.ROOT);
    List<String> distinctive = queryTokens.stream().filter(t -> t.length() >= 4).toList();
    for (String token : distinctive.isEmpty() ? queryTokens : distinctive) {
      if (haystack.contains(token)) {
        return true;
      }
    }
    return false;
  }
}
