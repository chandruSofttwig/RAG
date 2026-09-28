package com.localrag.llm;

import java.util.List;
import java.util.Map;

/** Embeds with a local Ollama embedding model, adding the task prefixes the model was trained with. */
public class OllamaEmbedder implements Embedder {

  /** base model name -> {document prefix, query prefix} for asymmetric retrieval models. */
  private static final Map<String, String[]> PREFIXES =
      Map.of(
          "nomic-embed-text", new String[] {"search_document: ", "search_query: "},
          "mxbai-embed-large",
              new String[] {"", "Represent this sentence for searching relevant passages: "},
          "snowflake-arctic-embed",
              new String[] {"", "Represent this sentence for searching relevant passages: "});

  private final OllamaClient client;
  private final String model;
  private final String docPrefix;
  private final String queryPrefix;

  public OllamaEmbedder(OllamaClient client, String model) {
    this.client = client;
    this.model = model;
    String[] prefixes = PREFIXES.getOrDefault(model.split(":")[0], new String[] {"", ""});
    this.docPrefix = prefixes[0];
    this.queryPrefix = prefixes[1];
  }

  @Override
  public String model() {
    return model;
  }

  @Override
  public float[][] embedDocuments(List<String> texts) {
    float[][] vectors = client.embed(model, texts.stream().map(t -> docPrefix + t).toList());
    for (int i = 0; i < vectors.length; i++) {
      vectors[i] = Embedder.normalise(vectors[i]);
    }
    return vectors;
  }

  @Override
  public float[] embedQuery(String text) {
    return Embedder.normalise(client.embed(model, List.of(queryPrefix + text))[0]);
  }
}
