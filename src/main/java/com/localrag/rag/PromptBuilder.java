package com.localrag.rag;

import com.localrag.search.Hit;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the grounded prompt (port of andromedia's CodeRagPromptBuilder): numbered excerpts the
 * model must cite as [n], trimmed from the tail until the whole prompt fits the char budget.
 */
public final class PromptBuilder {

  public record Prompt(String system, String user, List<Hit> sources) {}

  static final String SYSTEM_PROMPT =
      """
      You are a retrieval-augmented assistant. Answer the user's question using ONLY the provided excerpts.
      Cite sources with bracket references like [1], [2] that match the excerpt numbers.
      If the excerpts do not contain enough information, say so clearly. Do not invent files, functions or facts.
      Be concise and specific. When the answer lives in code, name the exact file and function.
      """
          .strip();

  private final int maxPromptChars;

  public PromptBuilder(int maxPromptChars) {
    this.maxPromptChars = maxPromptChars;
  }

  public Prompt build(String question, List<Hit> hits) {
    String q = question == null ? "" : question.strip();
    List<Hit> selected = new ArrayList<>(hits);
    while (!selected.isEmpty()
        && SYSTEM_PROMPT.length() + userMessage(q, selected, Integer.MAX_VALUE).length() > maxPromptChars) {
      selected.removeLast();
    }
    if (selected.isEmpty() && !hits.isEmpty()) {
      // Even the best excerpt alone is too big: keep it, truncated to fit.
      int room = Math.max(500, maxPromptChars - SYSTEM_PROMPT.length() - q.length() - 200);
      List<Hit> best = List.of(hits.getFirst());
      return new Prompt(SYSTEM_PROMPT, userMessage(q, best, room), best);
    }
    return new Prompt(SYSTEM_PROMPT, userMessage(q, selected, Integer.MAX_VALUE), List.copyOf(selected));
  }

  static String userMessage(String question, List<Hit> hits, int maxExcerptChars) {
    StringBuilder sb = new StringBuilder("Question:\n").append(question).append("\n\nExcerpts:\n");
    if (hits.isEmpty()) {
      sb.append("(no relevant excerpts were found)\n");
    }
    for (int i = 0; i < hits.size(); i++) {
      Hit hit = hits.get(i);
      sb.append("\n[").append(i + 1).append("] ").append(hit.chunk().location());
      if (hit.chunk().symbol() != null) {
        sb.append(" (").append(hit.chunk().symbol()).append(')');
      }
      String text = hit.chunk().text();
      sb.append("\n```\n").append(text, 0, Math.min(text.length(), maxExcerptChars)).append("\n```\n");
    }
    return sb.toString();
  }
}
