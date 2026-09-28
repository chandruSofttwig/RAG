package com.localrag.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/** Token helpers shared by indexing, BM25 query building and lexical alignment. */
public final class Tokens {

  /** English function words (same spirit as andromedia's QueryLexicalMatch stopwords). */
  public static final CharArraySet STOPWORDS =
      CharArraySet.unmodifiableSet(
          new CharArraySet(
              List.of(
                  ("a an the and or for of to in on at is are was were be been being what whats which"
                          + " who whom whose how when where why do does did should would could can our"
                          + " we i me my your with from about into over after before than then that this"
                          + " these those any all some not no yes as by if so too very just also have has"
                          + " had will shall may might must need please tell give get got make made it its"
                          + " there their them they he she his her you us")
                      .split(" ")),
              true));

  private static final Analyzer QUERY_ANALYZER = new CodeAnalyzer(false);

  private Tokens() {}

  /** Distinct analyzed terms (whole words + identifier parts) for a BM25 OR query. */
  public static List<String> analyze(String text) {
    Set<String> terms = new LinkedHashSet<>();
    try (TokenStream stream = QUERY_ANALYZER.tokenStream(IndexFields.CONTENT, text == null ? "" : text)) {
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        terms.add(term.toString());
      }
      stream.end();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return new ArrayList<>(terms);
  }

  /** Whole words only (no sub-parts) — the strict AND clause for identifier queries. */
  public static List<String> primaryTerms(String text) {
    Set<String> terms = new LinkedHashSet<>();
    for (String word : split(text, "[^A-Za-z0-9_]+")) {
      String lower = word.toLowerCase(Locale.ROOT);
      if (lower.length() > 1 && !STOPWORDS.contains(lower)) {
        terms.add(lower);
      }
    }
    return new ArrayList<>(terms);
  }

  /** Distinctive query tokens (length >= 3, not stopwords) for lexical alignment checks. */
  public static List<String> significant(String text) {
    List<String> out = new ArrayList<>();
    for (String token : split(text == null ? "" : text.toLowerCase(Locale.ROOT), "[^a-z0-9]+")) {
      if (token.length() >= 3 && !STOPWORDS.contains(token)) {
        out.add(token);
      }
    }
    return out;
  }

  private static List<String> split(String text, String regex) {
    List<String> out = new ArrayList<>();
    if (text == null) {
      return out;
    }
    for (String piece : text.split(regex)) {
      if (!piece.isEmpty()) {
        out.add(piece);
      }
    }
    return out;
  }
}
