package com.localrag;

import com.localrag.index.Tokens;
import com.localrag.llm.Embedder;
import com.localrag.llm.OllamaException;
import java.util.List;

/** Deterministic hashed bag-of-words embedder so pipeline tests run without Ollama. */
public final class FakeEmbedder implements Embedder {

  private static final int DIMS = 128;
  public volatile boolean failQueries;

  @Override
  public String model() {
    return "fake-embed";
  }

  @Override
  public float[][] embedDocuments(List<String> texts) {
    return texts.stream().map(FakeEmbedder::vector).toArray(float[][]::new);
  }

  @Override
  public float[] embedQuery(String text) {
    if (failQueries) {
      throw new OllamaException("Embedding request failed: connection refused");
    }
    return vector(text);
  }

  private static float[] vector(String text) {
    float[] v = new float[DIMS];
    for (String token : Tokens.analyze(text)) {
      v[Math.floorMod(token.hashCode(), DIMS)] += 1f;
    }
    v[0] += 0.01f; // never all-zero
    return Embedder.normalise(v);
  }
}
