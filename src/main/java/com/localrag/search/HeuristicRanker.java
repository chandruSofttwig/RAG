package com.localrag.search;

import com.localrag.config.QueryType;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Path / name heuristics applied to the final top-k (the source-agnostic part of andromedia's
 * HeuristicRanker): tests, build output and config files are demoted; an exact file or symbol
 * name match is boosted for identifier-style queries.
 */
public final class HeuristicRanker {

  static final double TEST_PENALTY = 0.75;
  static final double GENERATED_PENALTY = 0.5;
  static final double CONFIG_PENALTY = 0.85;
  static final double FILENAME_EXACT_BOOST = 1.6;
  static final double SYMBOL_EXACT_BOOST = 1.35;

  private static final List<String> TEST_MARKERS =
      List.of("/test/", "/tests/", "/__tests__/", "/spec/", "/test_", "_test.", ".test.", ".spec.", "test.java");
  private static final List<String> GENERATED_MARKERS =
      List.of("/generated/", "/build/", "/target/", "/node_modules/", "/dist/");
  private static final List<String> CONFIG_SUFFIXES =
      List.of(".yml", ".yaml", ".properties", ".xml", ".ini", ".toml", ".cfg", ".json");

  private HeuristicRanker() {}

  public static List<Hit> rank(String query, QueryType type, List<Hit> hits) {
    return hits.stream()
        .map(h -> h.withScore((float) (h.score() * factor(query, type, h))))
        .sorted(Comparator.comparingDouble(Hit::score).reversed())
        .toList();
  }

  static double factor(String query, QueryType type, Hit hit) {
    double factor = pathFactor(hit.chunk().path());
    if (type != QueryType.NATURAL_LANGUAGE) {
      factor *= exactMatchFactor(query, hit);
    }
    return factor;
  }

  private static double pathFactor(String path) {
    String p = "/" + path.toLowerCase(Locale.ROOT);
    if (TEST_MARKERS.stream().anyMatch(p::contains)) {
      return TEST_PENALTY;
    }
    if (GENERATED_MARKERS.stream().anyMatch(p::contains)) {
      return GENERATED_PENALTY;
    }
    if (CONFIG_SUFFIXES.stream().anyMatch(p::endsWith)) {
      return CONFIG_PENALTY;
    }
    return 1.0;
  }

  private static double exactMatchFactor(String query, Hit hit) {
    String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (q.isEmpty()) {
      return 1.0;
    }
    String path = hit.chunk().path().toLowerCase(Locale.ROOT);
    String name = path.substring(path.lastIndexOf('/') + 1);
    String stem = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
    double factor = 1.0;
    if (q.equals(name) || q.equals(stem) || (q.length() >= 4 && name.contains(q))) {
      factor *= FILENAME_EXACT_BOOST;
    }
    if (hit.chunk().symbol() != null && q.equals(hit.chunk().symbol().toLowerCase(Locale.ROOT))) {
      factor *= SYMBOL_EXACT_BOOST;
    }
    return factor;
  }
}
