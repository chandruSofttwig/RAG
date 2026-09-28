package com.localrag.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localrag.config.QueryType;
import com.localrag.index.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class FusionTest {

  static Hit hit(String id, float score, String text) {
    return new Hit(new Chunk(id, id, 1, 1, text, null, "text", id), score, null, null);
  }

  @Test
  void rrfRanksDocumentsFoundByBothChannelsFirst() {
    List<Hit> bm25 = List.of(hit("a", 9, ""), hit("b", 5, ""), hit("c", 1, ""));
    List<Hit> sem = List.of(hit("b", 0.8f, ""), hit("d", 0.7f, ""), hit("e", 0.6f, ""));
    List<Hit> fused = ReciprocalRankFusion.fuse(bm25, sem, 10, 60, 1.0, 1.0);
    assertEquals("b", fused.getFirst().id());
    assertEquals(5, fused.size());
    assertEquals(5f, fused.getFirst().bm25Score());
    assertEquals(0.8f, fused.getFirst().semanticScore());
  }

  @Test
  void rrfChannelWeightsShiftTheOrder() {
    List<Hit> bm25 = List.of(hit("lex", 9, ""));
    List<Hit> sem = List.of(hit("dense", 0.9f, ""));
    assertEquals("lex", ReciprocalRankFusion.fuse(bm25, sem, 2, 60, 1.0, 0.3).getFirst().id());
    assertEquals("dense", ReciprocalRankFusion.fuse(bm25, sem, 2, 60, 0.6, 1.0).getFirst().id());
  }

  @Test
  void relativeFilterDropsFarWeakerHits() {
    List<Hit> ranked = List.of(hit("a", 10, ""), hit("b", 5, ""), hit("c", 0.1f, ""));
    assertEquals(List.of("a", "b"), RelativeScoreFilter.apply(ranked, 10, 0.08).stream().map(Hit::id).toList());
  }

  @Test
  void lexicalAnchorRescuesStrongExactMatchMissingFromFusion() {
    List<Hit> bm25 =
        List.of(
            hit("Invoice.java", 20, "class InvoiceCalculator"),
            hit("x", 3, "misc"), hit("y", 2.5f, "misc"), hit("z", 2, "misc"));
    List<Hit> hybrid = List.of(hit("other", 0.03f, "unrelated"), hit("x", 0.02f, "misc"));
    List<Hit> out = LexicalAnchorFilter.apply(hybrid, bm25, 5, "InvoiceCalculator");
    assertEquals("Invoice.java", out.getFirst().id());
    assertNull(out.getFirst().semanticScore());
  }

  @Test
  void lexicalAnchorLeavesFlatBm25Alone() {
    List<Hit> bm25 = List.of(hit("x", 3, "q"), hit("y", 3, "q"), hit("z", 3, "q"));
    List<Hit> hybrid = List.of(hit("h", 0.03f, "q"));
    assertEquals(List.of("h"), LexicalAnchorFilter.apply(hybrid, bm25, 5, "q").stream().map(Hit::id).toList());
  }

  @Test
  void classifierMatchesAndromediaRules() {
    assertEquals(QueryType.IDENTIFIER, QueryTypeClassifier.classify("HybridSearchService"));
    assertEquals(QueryType.IDENTIFIER, QueryTypeClassifier.classify("src/main/App.java"));
    assertEquals(QueryType.NATURAL_LANGUAGE, QueryTypeClassifier.classify("how does retry work here"));
    assertEquals(QueryType.MIXED, QueryTypeClassifier.classify("where is getUser"));
  }

  @Test
  void heuristicsDemoteTestsAndBoostExactFileName() {
    Hit test = new Hit(new Chunk("t", "src/test/FooTest.java", 1, 1, "", null, "code", "t"), 1f, null, null);
    Hit main = new Hit(new Chunk("m", "src/main/Foo.java", 1, 1, "", null, "code", "m"), 1f, null, null);
    List<Hit> ranked = HeuristicRanker.rank("Foo", QueryType.IDENTIFIER, List.of(test, main));
    assertEquals("m", ranked.getFirst().id());
    assertTrue(ranked.getFirst().score() > 1f);
  }
}
