package com.localrag.index;

/**
 * A retrievable unit of text.
 *
 * @param kind code | markdown | text
 * @param contentHash hash of path+symbol+text; unchanged chunks reuse their embedding on re-index
 */
public record Chunk(
    String id,
    String path,
    int startLine,
    int endLine,
    String text,
    String symbol,
    String kind,
    String contentHash) {

  public String location() {
    return path + ":" + startLine + "-" + endLine;
  }

  /** Text fed to BM25 and the embedding model: path and symbol help both channels. */
  public String indexText() {
    String header = symbol == null || symbol.isBlank() ? path : path + " :: " + symbol;
    return header + "\n" + text;
  }
}
