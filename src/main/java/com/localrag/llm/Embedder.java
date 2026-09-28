package com.localrag.llm;

import java.util.List;

/** Produces L2-normalised embeddings for documents and queries. */
public interface Embedder {

  String model();

  float[][] embedDocuments(List<String> texts);

  float[] embedQuery(String text);

  static float[] normalise(float[] vector) {
    double sum = 0;
    for (float v : vector) {
      sum += v * v;
    }
    double norm = Math.sqrt(sum);
    if (norm == 0) {
      return vector;
    }
    float[] out = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      out[i] = (float) (vector[i] / norm);
    }
    return out;
  }
}
