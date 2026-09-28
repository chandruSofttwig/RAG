package com.localrag.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.localrag.RagApp;
import com.localrag.config.Settings;
import com.localrag.index.IndexMeta;
import com.localrag.index.LuceneIndexer;
import com.localrag.rag.RagService;
import com.localrag.search.Hit;
import com.localrag.search.SearchMode;
import com.localrag.search.SearchResult;
import com.localrag.server.HttpApi;
import com.localrag.server.Views;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public final class Main {

  private static final String USAGE =
      """
      local-rag — hybrid BM25 + vector RAG on local models (Ollama)

      Usage: rag <command> [options]

      Commands:
        index <path>...            Index files/directories (replaces the existing index)
            --no-embed             BM25 only, skip embeddings
        search "<query>"           Retrieve chunks, no LLM
            -k <n>                 Number of results (default 6)
            --mode <m>             hybrid | bm25 | semantic (default hybrid)
            --json                 Print JSON
        ask "<question>"           Retrieve and answer with the local chat model
            -k <n>, --mode <m>     As for search
            --show-context         Print the retrieved excerpts first
        chat                       Interactive question loop (same options as ask)
        serve                      Web UI + JSON API
            --host <h>             Default 127.0.0.1
            --port <p>             Default 8080
        status                     Show index and model info

      Global options:
        --index-dir <dir>          Default .rag_index   (env RAG_INDEX_DIR)
        --ollama-url <url>         Default http://localhost:11434 (env RAG_OLLAMA_URL)
        --embed-model <name>       Default nomic-embed-text (env RAG_EMBED_MODEL)
        --chat-model <name>        Default qwen2.5:3b (env RAG_CHAT_MODEL)
      """;

  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
  private static final PrintStream ERR = new PrintStream(System.err, true, StandardCharsets.UTF_8);

  private Main() {}

  public static void main(String[] argv) {
    try {
      System.exit(run(argv));
    } catch (UncheckedIOException ex) {
      ERR.println("error: " + ex.getCause().getMessage());
      System.exit(1);
    } catch (Exception ex) {
      ERR.println("error: " + ex.getMessage());
      System.exit(1);
    }
  }

  static int run(String[] argv) throws Exception {
    Args args = Args.parse(argv);
    if (args.command == null || args.flag("help") || "help".equals(args.command)) {
      OUT.print(USAGE);
      return args.command == null ? 1 : 0;
    }
    Settings settings = Settings.fromEnv();
    args.option("index-dir").ifPresent(v -> settings.indexDir = Path.of(v));
    args.option("ollama-url").ifPresent(v -> settings.ollamaUrl = v);
    args.option("embed-model").ifPresent(v -> settings.embedModel = v);
    args.option("chat-model").ifPresent(v -> settings.chatModel = v);
    int k = args.option("k").map(Integer::parseInt).orElse(settings.topK);
    SearchMode mode = SearchMode.parse(args.option("mode").orElse("hybrid"));

    try (RagApp app = new RagApp(settings)) {
      return switch (args.command) {
        case "index" -> index(app, args);
        case "search" -> search(app, args.joinedPositionals("query"), k, mode, args.flag("json"));
        case "ask" -> {
          ask(app, args.joinedPositionals("question"), k, mode, args.flag("show-context"));
          yield 0;
        }
        case "chat" -> chat(app, k, mode, args.flag("show-context"));
        case "serve" -> serve(app, args.option("host").orElse("127.0.0.1"),
            args.option("port").map(Integer::parseInt).orElse(8080));
        case "status" -> status(app);
        default -> {
          ERR.println("Unknown command: " + args.command + "\n");
          OUT.print(USAGE);
          yield 1;
        }
      };
    }
  }

  private static int index(RagApp app, Args args) throws Exception {
    if (args.positionals.isEmpty()) {
      throw new IllegalArgumentException("index needs at least one path");
    }
    List<Path> roots = args.positionals.stream().map(Path::of).toList();
    LuceneIndexer.IndexStats stats = app.reindex(roots, !args.flag("no-embed"), msg -> ERR.println("  " + msg));
    OUT.printf(
        "Indexed %d files → %d chunks in %.1fs (embedded %d, reused %d, model: %s)%nIndex: %s%n",
        stats.files(), stats.chunks(), stats.seconds(), stats.embedded(), stats.reusedEmbeddings(),
        stats.embeddingModel() == null ? "none — BM25 only" : stats.embeddingModel(),
        app.settings().indexDir.toAbsolutePath());
    return 0;
  }

  private static int search(RagApp app, String query, int k, SearchMode mode, boolean json) throws Exception {
    SearchResult result = app.search(query, k, mode);
    if (json) {
      OUT.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
          .writeValueAsString(Views.result(result, true)));
      return 0;
    }
    printDiagnostics(result.diagnostics());
    if (result.hits().isEmpty()) {
      OUT.println("No results.");
    }
    printHits(result.hits(), 6);
    return 0;
  }

  private static void ask(RagApp app, String question, int k, SearchMode mode, boolean showContext) {
    RagService.Answer answer =
        app.ask(
            question,
            k,
            mode,
            retrieval -> {
              printDiagnostics(retrieval.diagnostics());
              if (showContext) {
                printHits(retrieval.hits(), 12);
              }
              OUT.println();
            },
            OUT::print);
    OUT.println("\n");
    List<Hit> sources = answer.prompt().sources();
    if (!sources.isEmpty()) {
      OUT.println("Sources:");
      for (int i = 0; i < sources.size(); i++) {
        Hit h = sources.get(i);
        OUT.printf("  [%d] %s%s%n", i + 1, h.chunk().location(),
            h.chunk().symbol() == null ? "" : " (" + h.chunk().symbol() + ")");
      }
    }
  }

  private static int chat(RagApp app, int k, SearchMode mode, boolean showContext) throws Exception {
    OUT.println("Ask about your indexed files. Empty line or 'exit' to quit.");
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    while (true) {
      OUT.print("\n> ");
      String line = in.readLine();
      if (line == null || line.isBlank() || line.strip().equals("exit")) {
        return 0;
      }
      try {
        ask(app, line.strip(), k, mode, showContext);
      } catch (RuntimeException ex) {
        ERR.println("error: " + ex.getMessage());
      }
    }
  }

  private static int serve(RagApp app, String host, int port) throws Exception {
    HttpApi api = new HttpApi(app, host, port);
    api.start();
    OUT.printf("local-rag UI on http://%s:%d  (chat: %s, embeddings: %s) — Ctrl+C to stop%n",
        host, api.port(), app.settings().chatModel, app.settings().embedModel);
    CountDownLatch forever = new CountDownLatch(1);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      api.stop();
      forever.countDown();
    }));
    forever.await();
    return 0;
  }

  private static int status(RagApp app) throws Exception {
    Settings s = app.settings();
    OUT.println("Index dir:    " + s.indexDir.toAbsolutePath());
    if (app.indexExists()) {
      IndexMeta meta = app.indexMeta();
      OUT.println("Roots:        " + String.join(", ", meta.roots()));
      OUT.println("Built:        " + meta.createdAt());
      OUT.println("Files/chunks: " + meta.files() + " / " + meta.chunks());
      OUT.println("Embeddings:   " + (meta.hasEmbeddings() ? meta.embeddingModel() + " (" + meta.dimensions() + " dims)" : "none (BM25 only)"));
    } else {
      OUT.println("Index:        (none — run `rag index <path>`)");
    }
    OUT.println("Ollama:       " + s.ollamaUrl);
    OUT.println("Embed model:  " + s.embedModel);
    OUT.println("Chat model:   " + s.chatModel);
    try {
      List<String> models = app.ollama().listModels();
      OUT.println("Installed:    " + String.join(", ", models));
      for (String needed : List.of(s.embedModel, s.chatModel)) {
        boolean present = models.stream().anyMatch(m -> m.equals(needed) || m.equals(needed + ":latest"));
        if (!present) {
          OUT.println("  ! '" + needed + "' is not pulled — run: ollama pull " + needed);
        }
      }
    } catch (RuntimeException ex) {
      OUT.println("Ollama error: " + ex.getMessage());
    }
    return 0;
  }

  private static void printDiagnostics(SearchResult.Diagnostics d) {
    StringBuilder sb = new StringBuilder()
        .append("[").append(d.mode()).append(" | ").append(d.queryType())
        .append(" | bm25 ").append(d.bm25Candidates())
        .append(" / semantic ").append(d.semanticCandidates());
    if (d.bm25Weight() != null) {
      sb.append(" | weights ").append(d.bm25Weight()).append('/').append(d.semanticWeight());
    }
    sb.append(" | ").append(d.elapsedMs()).append(" ms]");
    if (d.skipReason() != null && d.mode() == SearchMode.HYBRID) {
      sb.append("\n  semantic channel skipped: ").append(d.skipReason()).append(" — BM25 only");
    }
    ERR.println(sb);
  }

  private static void printHits(List<Hit> hits, int previewLines) {
    for (int i = 0; i < hits.size(); i++) {
      Hit h = hits.get(i);
      OUT.printf("%n[%d] %s%s  score=%.4f  bm25=%s  cos=%s%n", i + 1, h.chunk().location(),
          h.chunk().symbol() == null ? "" : " (" + h.chunk().symbol() + ")", h.score(),
          h.bm25Score() == null ? "-" : String.format("%.2f", h.bm25Score()),
          h.semanticScore() == null ? "-" : String.format("%.3f", h.semanticScore()));
      String[] lines = h.chunk().text().split("\n");
      for (int j = 0; j < Math.min(previewLines, lines.length); j++) {
        OUT.println("    " + lines[j]);
      }
      if (lines.length > previewLines) {
        OUT.println("    …");
      }
    }
  }

  /** Tiny argv parser: first bare word is the command, --name value / --flag / -k n options. */
  static final class Args {
    String command;
    final List<String> positionals = new ArrayList<>();
    final Map<String, String> options = new HashMap<>();

    private static final Set<String> FLAGS =
        Set.of("no-embed", "json", "show-context", "help");

    static Args parse(String[] argv) {
      Args a = new Args();
      for (int i = 0; i < argv.length; i++) {
        String arg = argv[i];
        if (arg.equals("-h")) {
          a.options.put("help", "true");
        } else if (arg.startsWith("--") || arg.equals("-k")) {
          String name = arg.replaceFirst("^-+", "");
          if (name.contains("=")) {
            a.options.put(name.substring(0, name.indexOf('=')), name.substring(name.indexOf('=') + 1));
          } else if (FLAGS.contains(name)) {
            a.options.put(name, "true");
          } else {
            if (i + 1 >= argv.length) {
              throw new IllegalArgumentException("missing value for " + arg);
            }
            a.options.put(name, argv[++i]);
          }
        } else if (a.command == null) {
          a.command = arg;
        } else {
          a.positionals.add(arg);
        }
      }
      return a;
    }

    boolean flag(String name) {
      return options.containsKey(name);
    }

    Optional<String> option(String name) {
      return Optional.ofNullable(options.get(name));
    }

    String joinedPositionals(String what) {
      String joined = String.join(" ", positionals).strip();
      if (joined.isEmpty()) {
        throw new IllegalArgumentException("missing " + what);
      }
      return joined;
    }
  }
}
