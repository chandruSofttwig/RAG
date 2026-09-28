package com.localrag.search;

import com.localrag.config.QueryType;
import java.util.List;

public record SearchResult(List<Hit> hits, Diagnostics diagnostics) {

  public record Diagnostics(
      String query,
      SearchMode mode,
      QueryType queryType,
      Double bm25Weight,
      Double semanticWeight,
      int bm25Candidates,
      int semanticCandidates,
      int semanticFiltered,
      boolean semanticSkipped,
      String skipReason,
      double elapsedMs) {}
}
