package com.localrag.search;

import java.util.Locale;

public enum SearchMode {
  HYBRID,
  BM25,
  SEMANTIC;

  public static SearchMode parse(String value) {
    if (value == null || value.isBlank()) {
      return HYBRID;
    }
    return SearchMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
