package com.localrag.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.core.FlattenGraphFilter;
import org.apache.lucene.analysis.miscellaneous.LengthFilter;
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter;
import org.apache.lucene.analysis.util.CharTokenizer;

/**
 * BM25 analyzer for mixed code and prose. Keeps each whole word (so exact identifiers still match
 * strongly) and also emits camelCase / snake_case parts, so {@code getUserById} is found by
 * "user id". Lowercases and removes English stopwords.
 */
public final class CodeAnalyzer extends Analyzer {

  private static final int WORD_DELIMITER_FLAGS =
      WordDelimiterGraphFilter.GENERATE_WORD_PARTS
          | WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
          | WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
          | WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
          | WordDelimiterGraphFilter.PRESERVE_ORIGINAL
          | WordDelimiterGraphFilter.STEM_ENGLISH_POSSESSIVE;

  private final boolean indexTime;

  /** @param indexTime flatten the token graph (required when writing to the index) */
  public CodeAnalyzer(boolean indexTime) {
    this.indexTime = indexTime;
  }

  @Override
  protected TokenStreamComponents createComponents(String fieldName) {
    Tokenizer tokenizer =
        CharTokenizer.fromTokenCharPredicate(c -> Character.isLetterOrDigit(c) || c == '_');
    TokenStream stream = new WordDelimiterGraphFilter(tokenizer, WORD_DELIMITER_FLAGS, null);
    if (indexTime) {
      stream = new FlattenGraphFilter(stream);
    }
    stream = new LowerCaseFilter(stream);
    stream = new StopFilter(stream, Tokens.STOPWORDS);
    stream = new LengthFilter(stream, 2, 64);
    return new TokenStreamComponents(tokenizer, stream);
  }
}
