package com.localrag.server;

import com.localrag.search.Hit;
import com.localrag.search.SearchResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** JSON-friendly views of search results. */
public final class Views {

  private Views() {}

  public static Map<String, Object> hit(Hit hit, int rank, boolean includeText) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("rank", rank);
    m.put("id", hit.id());
    m.put("path", hit.chunk().path());
    m.put("startLine", hit.chunk().startLine());
    m.put("endLine", hit.chunk().endLine());
    m.put("symbol", hit.chunk().symbol());
    m.put("score", round(hit.score(), 6));
    m.put("bm25Score", hit.bm25Score() == null ? null : round(hit.bm25Score(), 4));
    m.put("semanticScore", hit.semanticScore() == null ? null : round(hit.semanticScore(), 4));
    if (includeText) {
      m.put("text", hit.chunk().text());
    }
    return m;
  }

  public static List<Map<String, Object>> hits(List<Hit> hits, boolean includeText) {
    return IntStream.range(0, hits.size())
        .mapToObj(i -> hit(hits.get(i), i + 1, includeText))
        .toList();
  }

  public static Map<String, Object> result(SearchResult result, boolean includeText) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("hits", hits(result.hits(), includeText));
    m.put("diagnostics", result.diagnostics());
    return m;
  }

  private static double round(double value, int places) {
    double scale = Math.pow(10, places);
    return Math.round(value * scale) / scale;
  }
}
