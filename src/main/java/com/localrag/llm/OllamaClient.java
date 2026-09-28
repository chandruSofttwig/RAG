package com.localrag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Minimal client for a local Ollama server: model list, embeddings and streaming chat. */
public class OllamaClient {

  public record Message(String role, String content) {}

  private static final ObjectMapper JSON = new ObjectMapper();

  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;

  public OllamaClient(String baseUrl, int timeoutSeconds) {
    this.baseUrl = baseUrl.replaceAll("/+$", "");
    this.timeout = Duration.ofSeconds(timeoutSeconds);
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  public String baseUrl() {
    return baseUrl;
  }

  public List<String> listModels() {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags")).timeout(timeout).GET().build();
    JsonNode body;
    try {
      HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new OllamaException("Ollama /api/tags returned " + response.statusCode());
      }
      body = JSON.readTree(response.body());
    } catch (IOException ex) {
      throw new OllamaException("Cannot reach Ollama at " + baseUrl + ": " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new OllamaException("Interrupted", ex);
    }
    List<String> names = new ArrayList<>();
    body.path("models").forEach(m -> names.add(m.path("name").asText()));
    return names;
  }

  /** Embeds a batch of texts. Returns one vector per input, in order. */
  public float[][] embed(String model, List<String> inputs) {
    Map<String, Object> payload = Map.of("model", model, "input", inputs, "truncate", true);
    JsonNode body;
    try {
      HttpResponse<String> response =
          http.send(post("/api/embed", payload), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new OllamaException(
            "Embedding with '" + model + "' failed (" + response.statusCode() + "): " + abbreviate(response.body()));
      }
      body = JSON.readTree(response.body());
    } catch (IOException ex) {
      throw new OllamaException("Embedding request to " + baseUrl + " failed: " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new OllamaException("Interrupted", ex);
    }
    JsonNode embeddings = body.path("embeddings");
    if (embeddings.size() != inputs.size()) {
      throw new OllamaException("Expected " + inputs.size() + " embeddings, got " + embeddings.size());
    }
    float[][] out = new float[inputs.size()][];
    for (int i = 0; i < out.length; i++) {
      JsonNode vector = embeddings.get(i);
      out[i] = new float[vector.size()];
      for (int d = 0; d < vector.size(); d++) {
        out[i][d] = (float) vector.get(d).asDouble();
      }
    }
    return out;
  }

  /** Streams a chat completion; each content delta is passed to {@code onToken}. Returns the full text. */
  public String chat(String model, List<Message> messages, Map<String, Object> options, Consumer<String> onToken) {
    Map<String, Object> payload =
        Map.of("model", model, "messages", messages, "stream", true, "options", options == null ? Map.of() : options);
    StringBuilder full = new StringBuilder();
    try {
      HttpResponse<InputStream> response =
          http.send(post("/api/chat", payload), HttpResponse.BodyHandlers.ofInputStream());
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
        if (response.statusCode() != 200) {
          StringBuilder error = new StringBuilder();
          reader.lines().forEach(error::append);
          throw new OllamaException(
              "Chat with '" + model + "' failed (" + response.statusCode() + "): " + abbreviate(error.toString()));
        }
        String line;
        while ((line = reader.readLine()) != null) {
          if (line.isBlank()) {
            continue;
          }
          JsonNode event = JSON.readTree(line);
          if (event.hasNonNull("error")) {
            throw new OllamaException(event.get("error").asText());
          }
          String piece = event.path("message").path("content").asText("");
          if (!piece.isEmpty()) {
            full.append(piece);
            if (onToken != null) {
              onToken.accept(piece);
            }
          }
          if (event.path("done").asBoolean(false)) {
            break;
          }
        }
      }
    } catch (IOException ex) {
      throw new OllamaException("Chat request to " + baseUrl + " failed: " + ex.getMessage(), ex);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new OllamaException("Interrupted", ex);
    }
    return full.toString();
  }

  private HttpRequest post(String path, Object payload) throws IOException {
    return HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(payload)))
        .build();
  }

  private static String abbreviate(String text) {
    return text == null ? "" : text.length() <= 300 ? text : text.substring(0, 300) + "…";
  }
}
