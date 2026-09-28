package com.localrag.rag;

import com.localrag.config.Settings;
import com.localrag.llm.OllamaClient;
import com.localrag.search.HybridRetriever;
import com.localrag.search.SearchMode;
import com.localrag.search.SearchResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Retrieve → build grounded prompt → stream an answer from the local chat model. */
public final class RagService {

  public record Answer(String text, PromptBuilder.Prompt prompt, SearchResult retrieval) {}

  static final String NO_CONTEXT_ANSWER =
      "I couldn't find anything relevant in the index for that question.";

  private final HybridRetriever retriever;
  private final OllamaClient ollama;
  private final Settings settings;
  private final PromptBuilder promptBuilder;

  public RagService(HybridRetriever retriever, OllamaClient ollama, Settings settings) {
    this.retriever = retriever;
    this.ollama = ollama;
    this.settings = settings;
    this.promptBuilder = new PromptBuilder(settings.maxPromptChars);
  }

  /**
   * @param onRetrieved called once with the retrieval result, before generation starts
   * @param onToken called for each streamed token of the answer
   */
  public Answer ask(
      String question,
      int topK,
      SearchMode mode,
      Consumer<SearchResult> onRetrieved,
      Consumer<String> onToken) {
    SearchResult retrieval = retriever.search(question, topK, mode);
    PromptBuilder.Prompt prompt = promptBuilder.build(question, retrieval.hits());
    if (onRetrieved != null) {
      onRetrieved.accept(retrieval);
    }
    if (prompt.sources().isEmpty()) {
      if (onToken != null) {
        onToken.accept(NO_CONTEXT_ANSWER);
      }
      return new Answer(NO_CONTEXT_ANSWER, prompt, retrieval);
    }
    String text =
        ollama.chat(
            settings.chatModel,
            List.of(
                new OllamaClient.Message("system", prompt.system()),
                new OllamaClient.Message("user", prompt.user())),
            Map.of("temperature", settings.temperature, "num_ctx", settings.numCtx),
            onToken);
    return new Answer(text, prompt, retrieval);
  }
}
