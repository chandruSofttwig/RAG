package com.localrag.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokensTest {

  @Test
  void keepsWholeIdentifierAndItsParts() {
    List<String> terms = Tokens.analyze("getUserById(user_id)");
    assertTrue(terms.contains("getuserbyid"), terms.toString());
    assertTrue(terms.contains("user"), terms.toString());
    assertTrue(terms.contains("id"), terms.toString());
    assertTrue(terms.contains("user_id") || terms.contains("userid"), terms.toString());
  }

  @Test
  void dropsStopwords() {
    List<String> terms = Tokens.analyze("how does the retry logic work");
    assertFalse(terms.contains("the"));
    assertFalse(terms.contains("how"));
    assertTrue(terms.contains("retry"));
  }

  @Test
  void primaryTermsAreWholeWordsOnly() {
    assertEquals(List.of("hybridsearchservice"), Tokens.primaryTerms("HybridSearchService"));
  }
}
