package com.localrag.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localrag.FakeEmbedder;
import com.localrag.config.Settings;
import com.localrag.index.IndexHandle;
import com.localrag.index.LuceneIndexer;
import com.localrag.rag.PromptBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HybridRetrieverTest {

  @TempDir Path tmp;
  Settings settings;
  FakeEmbedder embedder;

  @BeforeEach
  void buildIndex() throws Exception {
    Path docs = tmp.resolve("docs");
    Files.createDirectories(docs.resolve("src"));
    Files.createDirectories(docs.resolve("node_modules/junk"));
    Files.writeString(docs.resolve("src/PaymentRetryPolicy.java"),
        """
        package pay;

        public class PaymentRetryPolicy {
          public int backoffMillis(int attempt) {
            return Math.min(30_000, 500 * (1 << attempt));
          }
        }
        """);
    Files.writeString(docs.resolve("src/user_store.py"),
        """
        def load_user(user_id):
            return db.fetch("users", user_id)

        def delete_user(user_id):
            db.delete("users", user_id)
        """);
    Files.writeString(docs.resolve("README.md"),
        """
        # Payments service

        Failed card payments are retried with exponential backoff, capped at thirty seconds.

        # Deployment

        The service is deployed with docker compose on a single host.
        """);
    Files.writeString(docs.resolve("node_modules/junk/index.js"), "function paymentRetry() {}");

    settings = new Settings();
    settings.indexDir = tmp.resolve("index");
    embedder = new FakeEmbedder();
    LuceneIndexer.IndexStats stats = new LuceneIndexer(settings).build(List.of(docs), embedder, m -> {});
    assertEquals(3, stats.files(), "node_modules must be skipped");
    assertTrue(stats.chunks() >= 3);
    assertEquals(stats.chunks(), stats.embedded());
  }

  @Test
  void hybridFindsIdentifierByExactName() throws Exception {
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      SearchResult r = new HybridRetriever(index, embedder, settings).search("PaymentRetryPolicy", 3, SearchMode.HYBRID);
      assertEquals("src/PaymentRetryPolicy.java", r.hits().getFirst().chunk().path());
      assertFalse(r.diagnostics().semanticSkipped());
    }
  }

  @Test
  void bm25MatchesIdentifierParts() throws Exception {
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      SearchResult r = new HybridRetriever(index, embedder, settings).search("delete user", 3, SearchMode.BM25);
      assertEquals("src/user_store.py", r.hits().getFirst().chunk().path());
      assertNotNull(r.hits().getFirst().bm25Score());
    }
  }

  @Test
  void naturalLanguageQueryUsesBothChannels() throws Exception {
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      SearchResult r =
          new HybridRetriever(index, embedder, settings).search("how are failed payments retried", 3, SearchMode.HYBRID);
      assertTrue(r.diagnostics().bm25Candidates() > 0);
      assertTrue(r.diagnostics().semanticCandidates() > 0);
      assertTrue(r.hits().stream().anyMatch(h -> h.chunk().path().equals("README.md")));
    }
  }

  @Test
  void fallsBackToBm25WhenEmbeddingModelIsDown() throws Exception {
    embedder.failQueries = true;
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      SearchResult r = new HybridRetriever(index, embedder, settings).search("backoff", 3, SearchMode.HYBRID);
      assertTrue(r.diagnostics().semanticSkipped());
      assertTrue(r.diagnostics().skipReason().contains("connection refused"));
      assertFalse(r.hits().isEmpty());
      assertThrows(RuntimeException.class,
          () -> new HybridRetriever(index, embedder, settings).search("backoff", 3, SearchMode.SEMANTIC));
    }
  }

  @Test
  void reindexReusesUnchangedEmbeddings() throws Exception {
    LuceneIndexer.IndexStats again =
        new LuceneIndexer(settings).build(List.of(tmp.resolve("docs")), embedder, m -> {});
    assertEquals(0, again.embedded());
    assertEquals(again.chunks(), again.reusedEmbeddings());
  }

  @Test
  void bm25OnlyIndexStillAnswersHybridQueries() throws Exception {
    new LuceneIndexer(settings).build(List.of(tmp.resolve("docs")), null, m -> {});
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      SearchResult r = new HybridRetriever(index, embedder, settings).search("docker compose", 3, SearchMode.HYBRID);
      assertEquals("README.md", r.hits().getFirst().chunk().path());
      assertTrue(r.diagnostics().skipReason().contains("no embeddings"));
    }
  }

  @Test
  void promptNumbersSourcesAndRespectsBudget() throws Exception {
    try (IndexHandle index = IndexHandle.open(settings.indexDir)) {
      List<Hit> hits = new HybridRetriever(index, embedder, settings).search("payments", 5, SearchMode.HYBRID).hits();
      PromptBuilder.Prompt big = new PromptBuilder(100_000).build("payments?", hits);
      assertEquals(hits.size(), big.sources().size());
      assertTrue(big.user().contains("[1] "));
      PromptBuilder.Prompt small = new PromptBuilder(900).build("payments?", hits);
      assertTrue(small.sources().size() < hits.size() || hits.size() == 1);
      assertFalse(small.sources().isEmpty());
    }
  }
}
