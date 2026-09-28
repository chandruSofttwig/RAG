package com.localrag.search;

import java.util.List;

/** Drops hits far weaker than the top hit (port of andromedia's RelativeScoreFilter). */
public final class RelativeScoreFilter {

  private RelativeScoreFilter() {}

  public static List<Hit> apply(List<Hit> ranked, int limit, double relativeFloor) {
    if (ranked == null || ranked.isEmpty()) {
      return List.of();
    }
    float top = ranked.getFirst().score();
    if (!(top > 0f) || relativeFloor <= 0) {
      return ranked.stream().limit(limit).toList();
    }
    double floor = top * relativeFloor;
    List<Hit> kept = ranked.stream().filter(h -> h.score() >= floor).limit(limit).toList();
    return kept.isEmpty() ? ranked.stream().limit(Math.min(limit, 3)).toList() : kept;
  }
}
