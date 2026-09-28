package com.localrag;

import com.localrag.config.Settings;
import com.localrag.index.IndexHandle;
import com.localrag.index.IndexMeta;
import com.localrag.index.LuceneIndexer;
import com.localrag.llm.Embedder;
import com.localrag.llm.OllamaClient;
import com.localrag.llm.OllamaEmbedder;
import com.localrag.rag.RagService;
import com.localrag.search.HybridRetriever;
import com.localrag.search.SearchMode;
import com.localrag.search.SearchResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/** Wires settings, local models and the index together; safe to share across server threads. */
public final class RagApp implements AutoCloseable {

  private final Settings settings;
  private final OllamaClient ollama;
  private final Embedder embedder;
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
  private IndexHandle index;

  public RagApp(Settings settings) {
    this(settings, new OllamaClient(settings.ollamaUrl, settings.requestTimeoutSeconds), null);
  }

  /** @param embedder override (tests); null uses Ollama with {@code settings.embedModel} */
  public RagApp(Settings settings, OllamaClient ollama, Embedder embedder) {
    this.settings = settings;
    this.ollama = ollama;
    this.embedder = embedder != null ? embedder : new OllamaEmbedder(ollama, settings.embedModel);
  }

  public Settings settings() {
    return settings;
  }

  public OllamaClient ollama() {
    return ollama;
  }

  public LuceneIndexer.IndexStats reindex(List<Path> roots, boolean embed, Consumer<String> progress)
      throws IOException {
    lock.writeLock().lock();
    try {
      closeIndex();
      return new LuceneIndexer(settings).build(roots, embed ? embedder : null, progress);
    } finally {
      lock.writeLock().unlock();
    }
  }

  public SearchResult search(String query, int topK, SearchMode mode) {
    lock.readLock().lock();
    try {
      return retriever().search(query, topK, mode);
    } finally {
      lock.readLock().unlock();
    }
  }

  public RagService.Answer ask(
      String question, int topK, SearchMode mode, Consumer<SearchResult> onRetrieved, Consumer<String> onToken) {
    lock.readLock().lock();
    try {
      return new RagService(retriever(), ollama, settings).ask(question, topK, mode, onRetrieved, onToken);
    } finally {
      lock.readLock().unlock();
    }
  }

  public boolean indexExists() {
    return IndexHandle.exists(settings.indexDir);
  }

  public IndexMeta indexMeta() throws IOException {
    return IndexMeta.read(settings.indexDir);
  }

  private HybridRetriever retriever() {
    return new HybridRetriever(openIndex(), embedder, settings);
  }

  private synchronized IndexHandle openIndex() {
    if (index == null) {
      try {
        index = IndexHandle.open(settings.indexDir);
      } catch (IOException ex) {
        throw new UncheckedIOException(ex);
      }
    }
    return index;
  }

  private synchronized void closeIndex() throws IOException {
    if (index != null) {
      index.close();
      index = null;
    }
  }

  @Override
  public void close() throws IOException {
    closeIndex();
  }
}
