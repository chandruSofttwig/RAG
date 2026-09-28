package com.localrag.config;

import java.nio.file.Path;
import java.util.Map;

/**
 * Runtime settings. Each field can be overridden by an environment variable {@code RAG_<NAME>}
 * (e.g. {@code RAG_CHAT_MODEL=qwen2.5:0.5b}) and then by CLI flags.
 */
public final class Settings {

  public record ChannelWeights(double bm25, double semantic) {}

  // Local models (Ollama).
  public String ollamaUrl = "http://localhost:11434";
  public String embedModel = "nomic-embed-text";
  public String chatModel = "qwen2.5:3b";
  public int requestTimeoutSeconds = 300;

  // Index and chunking.
  public Path indexDir = Path.of(".rag_index");
  public int maxChunkChars = 2000;
  public int chunkOverlapLines = 5;
  public long maxFileBytes = 1_000_000;
  public int embedBatchSize = 16;

  // Retrieval — defaults ported from andromedia's HybridSearchService / RankingHeuristicsProperties.
  public int topK = 6;
  public int rrfK = 60;
  public int candidateMultiplier = 5;
  public int minCandidatePool = 50;
  /** Minimum cosine similarity (-1..1) for a semantic hit to enter fusion. */
  public double semanticMinScore = 0.2;
  public double relativeScoreFloor = 0.08;

  // Prompt / generation.
  public int maxPromptChars = 12_000;
  public double temperature = 0.1;
  public int numCtx = 8192;

  public final Map<QueryType, ChannelWeights> weights =
      Map.of(
          QueryType.IDENTIFIER, new ChannelWeights(1.0, 0.3),
          QueryType.NATURAL_LANGUAGE, new ChannelWeights(0.6, 1.0),
          QueryType.MIXED, new ChannelWeights(0.85, 0.85));

  public static Settings fromEnv() {
    Settings s = new Settings();
    s.ollamaUrl = env("OLLAMA_URL", s.ollamaUrl);
    s.embedModel = env("EMBED_MODEL", s.embedModel);
    s.chatModel = env("CHAT_MODEL", s.chatModel);
    s.requestTimeoutSeconds = Integer.parseInt(env("REQUEST_TIMEOUT_SECONDS", "" + s.requestTimeoutSeconds));
    s.indexDir = Path.of(env("INDEX_DIR", s.indexDir.toString()));
    s.maxChunkChars = Integer.parseInt(env("MAX_CHUNK_CHARS", "" + s.maxChunkChars));
    s.chunkOverlapLines = Integer.parseInt(env("CHUNK_OVERLAP_LINES", "" + s.chunkOverlapLines));
    s.maxFileBytes = Long.parseLong(env("MAX_FILE_BYTES", "" + s.maxFileBytes));
    s.embedBatchSize = Integer.parseInt(env("EMBED_BATCH_SIZE", "" + s.embedBatchSize));
    s.topK = Integer.parseInt(env("TOP_K", "" + s.topK));
    s.rrfK = Integer.parseInt(env("RRF_K", "" + s.rrfK));
    s.semanticMinScore = Double.parseDouble(env("SEMANTIC_MIN_SCORE", "" + s.semanticMinScore));
    s.maxPromptChars = Integer.parseInt(env("MAX_PROMPT_CHARS", "" + s.maxPromptChars));
    s.temperature = Double.parseDouble(env("TEMPERATURE", "" + s.temperature));
    s.numCtx = Integer.parseInt(env("NUM_CTX", "" + s.numCtx));
    return s;
  }

  private static String env(String name, String fallback) {
    String value = System.getenv("RAG_" + name);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
