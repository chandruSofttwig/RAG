package com.localrag.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Contents of {@code meta.json} next to the Lucene index. */
public record IndexMeta(
    List<String> roots,
    String createdAt,
    int files,
    int chunks,
    String embeddingModel,
    Integer dimensions) {

  private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
  static final String FILE_NAME = "meta.json";

  public boolean hasEmbeddings() {
    return embeddingModel != null && dimensions != null && dimensions > 0;
  }

  public void write(Path indexDir) throws IOException {
    Files.createDirectories(indexDir);
    JSON.writeValue(indexDir.resolve(FILE_NAME).toFile(), this);
  }

  public static IndexMeta read(Path indexDir) throws IOException {
    return JSON.readValue(indexDir.resolve(FILE_NAME).toFile(), IndexMeta.class);
  }
}
