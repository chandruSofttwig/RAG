package com.localrag.search;

import com.localrag.config.QueryType;
import java.util.regex.Pattern;

/**
 * Port of andromedia's QueryTypeClassifier. The type selects the RRF channel weights:
 * identifier-looking queries trust BM25, natural-language questions trust embeddings.
 */
public final class QueryTypeClassifier {

  private static final Pattern CAMEL_CASE = Pattern.compile(".*[a-z][A-Z].*");
  private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
  private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(_[a-z0-9]+)+$");
  private static final Pattern PATH_LIKE = Pattern.compile("[/\\\\]");
  private static final Pattern FILE_EXTENSION = Pattern.compile(".*\\.[a-zA-Z]{2,5}(\\b|$)");
  private static final Pattern NATURAL_LANGUAGE =
      Pattern.compile("\\b(the|and|how|what|where|when|why|logic|validation|handler|flow)\\b", Pattern.CASE_INSENSITIVE);

  private QueryTypeClassifier() {}

  public static QueryType classify(String query) {
    if (query == null || query.isBlank()) {
      return QueryType.MIXED;
    }
    String q = query.trim();
    int signals = identifierSignals(q);
    boolean natural = q.chars().filter(c -> c == ' ').count() >= 2 || NATURAL_LANGUAGE.matcher(q).find();
    if (signals >= 2 || (signals == 1 && !natural)) {
      return QueryType.IDENTIFIER;
    }
    if (natural && signals == 0) {
      return QueryType.NATURAL_LANGUAGE;
    }
    return QueryType.MIXED;
  }

  private static int identifierSignals(String q) {
    int signals = 0;
    if (FILE_EXTENSION.matcher(q).matches()) signals++;
    if (PATH_LIKE.matcher(q).find()) signals++;
    if (CAMEL_CASE.matcher(q).matches()) signals++;
    if (PASCAL_CASE.matcher(q).matches()) signals++;
    if (SNAKE_CASE.matcher(q).matches()) signals++;
    if (q.contains(".") && !q.contains(" ")) signals++;
    return signals;
  }
}
