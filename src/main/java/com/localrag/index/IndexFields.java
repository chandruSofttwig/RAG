package com.localrag.index;

/** Lucene field names. */
public final class IndexFields {
  public static final String ID = "id";
  public static final String PATH = "path";
  public static final String SYMBOL = "symbol";
  public static final String KIND = "kind";
  public static final String START_LINE = "start_line";
  public static final String END_LINE = "end_line";
  public static final String HASH = "content_hash";
  /** Stored raw chunk text (returned to callers and put in prompts). */
  public static final String TEXT = "text";
  /** Analyzed path + symbol + text: the BM25 field. */
  public static final String CONTENT = "content";
  /** Normalised dense vector (HNSW, cosine). */
  public static final String EMBEDDING = "embedding";

  private IndexFields() {}
}
