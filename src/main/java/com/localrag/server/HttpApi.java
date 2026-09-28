package com.localrag.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localrag.RagApp;
import com.localrag.index.LuceneIndexer;
import com.localrag.search.SearchMode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Small JSON API + single-page UI on the JDK's built-in HTTP server.
 *
 * <pre>
 * GET  /             web UI
 * GET  /api/status   index + model info
 * POST /api/search   {"query", "k"?, "mode"?}          -> {"hits", "diagnostics"}
 * POST /api/ask      {"question", "k"?, "mode"?}       -> NDJSON stream: sources, token*, done | error
 * POST /api/index    {"paths": [...], "embed"?: true}  -> index stats
 * </pre>
 */
public final class HttpApi {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final RagApp app;
  private final HttpServer server;

  public HttpApi(RagApp app, String host, int port) throws IOException {
    this.app = app;
    this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handleUi);
    server.createContext("/api/status", ex -> handle(ex, "GET", this::status));
    server.createContext("/api/search", ex -> handle(ex, "POST", this::search));
    server.createContext("/api/index", ex -> handle(ex, "POST", this::index));
    server.createContext("/api/ask", this::ask);
  }

  public void start() {
    server.start();
  }

  public void stop() {
    server.stop(0);
  }

  public int port() {
    return server.getAddress().getPort();
  }

  // --- handlers ---------------------------------------------------------------------------

  private Object status(JsonNode body) throws IOException {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("indexDir", app.settings().indexDir.toAbsolutePath().toString());
    m.put("indexExists", app.indexExists());
    m.put("index", app.indexExists() ? app.indexMeta() : null);
    m.put("ollamaUrl", app.settings().ollamaUrl);
    m.put("embedModel", app.settings().embedModel);
    m.put("chatModel", app.settings().chatModel);
    try {
      m.put("ollamaModels", app.ollama().listModels());
    } catch (RuntimeException ex) {
      m.put("ollamaError", ex.getMessage());
    }
    return m;
  }

  private Object search(JsonNode body) {
    String query = requireText(body, "query");
    int k = body.path("k").asInt(app.settings().topK);
    SearchMode mode = SearchMode.parse(body.path("mode").asText(null));
    return Views.result(app.search(query, k, mode), true);
  }

  private Object index(JsonNode body) throws IOException {
    List<Path> paths = new ArrayList<>();
    body.path("paths").forEach(p -> paths.add(Path.of(p.asText())));
    if (paths.isEmpty()) {
      throw new IllegalArgumentException("'paths' must be a non-empty array");
    }
    LuceneIndexer.IndexStats stats =
        app.reindex(paths, body.path("embed").asBoolean(true), msg -> System.err.println("[index] " + msg));
    return stats;
  }

  private void ask(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!"POST".equals(exchange.getRequestMethod())) {
        sendJson(exchange, 405, Map.of("error", "use POST"));
        return;
      }
      JsonNode body;
      String question;
      try {
        body = readBody(exchange);
        question = requireText(body, "question");
      } catch (IllegalArgumentException ex) {
        sendJson(exchange, 400, Map.of("error", ex.getMessage()));
        return;
      }
      int k = body.path("k").asInt(app.settings().topK);
      SearchMode mode = SearchMode.parse(body.path("mode").asText(null));

      exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
      exchange.getResponseHeaders().set("Cache-Control", "no-cache");
      exchange.sendResponseHeaders(200, 0);
      OutputStream out = exchange.getResponseBody();
      try {
        app.ask(
            question,
            k,
            mode,
            retrieval -> {
              Map<String, Object> event = new LinkedHashMap<>(Views.result(retrieval, true));
              event.put("type", "sources");
              writeEvent(out, event);
            },
            token -> writeEvent(out, Map.of("type", "token", "text", token)));
        writeEvent(out, Map.of("type", "done"));
      } catch (RuntimeException ex) {
        writeEvent(out, Map.of("type", "error", "error", String.valueOf(ex.getMessage())));
      }
    }
  }

  private void handleUi(HttpExchange exchange) throws IOException {
    try (exchange) {
      String path = exchange.getRequestURI().getPath();
      if (!"/".equals(path) && !"/index.html".equals(path)) {
        sendJson(exchange, 404, Map.of("error", "not found"));
        return;
      }
      try (InputStream in = HttpApi.class.getResourceAsStream("/static/index.html")) {
        byte[] html = in == null ? "<h1>UI missing</h1>".getBytes(StandardCharsets.UTF_8) : in.readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, html.length);
        exchange.getResponseBody().write(html);
      }
    }
  }

  // --- plumbing ---------------------------------------------------------------------------

  @FunctionalInterface
  private interface JsonHandler {
    Object handle(JsonNode body) throws Exception;
  }

  private static void handle(HttpExchange exchange, String method, JsonHandler handler) throws IOException {
    try (exchange) {
      if (!method.equals(exchange.getRequestMethod())) {
        sendJson(exchange, 405, Map.of("error", "use " + method));
        return;
      }
      try {
        sendJson(exchange, 200, handler.handle(readBody(exchange)));
      } catch (IllegalArgumentException ex) {
        sendJson(exchange, 400, Map.of("error", String.valueOf(ex.getMessage())));
      } catch (Exception ex) {
        Throwable cause = ex instanceof UncheckedIOException u ? u.getCause() : ex;
        sendJson(exchange, 500, Map.of("error", String.valueOf(cause.getMessage())));
      }
    }
  }

  private static JsonNode readBody(HttpExchange exchange) throws IOException {
    byte[] bytes = exchange.getRequestBody().readAllBytes();
    if (bytes.length == 0) {
      return JSON.createObjectNode();
    }
    try {
      return JSON.readTree(bytes);
    } catch (IOException ex) {
      throw new IllegalArgumentException("request body is not valid JSON");
    }
  }

  private static String requireText(JsonNode body, String field) {
    String value = body.path(field).asText("").strip();
    if (value.isEmpty()) {
      throw new IllegalArgumentException("'" + field + "' is required");
    }
    return value;
  }

  private static void sendJson(HttpExchange exchange, int status, Object payload) throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(payload);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  private static void writeEvent(OutputStream out, Object event) {
    try {
      out.write(JSON.writeValueAsBytes(event));
      out.write('\n');
      out.flush();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
