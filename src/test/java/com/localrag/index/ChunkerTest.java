package com.localrag.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

  @Test
  void splitsCodeAtDefinitionsAndRecordsSymbols() {
    String filler = "    int x = 0;\n".repeat(40);
    String code =
        "package demo;\n\npublic class Billing {\n\n  public int charge(int cents) {\n" + filler
            + "    return cents;\n  }\n\n  public void refund(String id) {\n" + filler + "  }\n}\n";
    List<Chunk> chunks = new Chunker(800, 2).chunk("Billing.java", code, "code");
    assertTrue(chunks.size() >= 2, "expected several chunks, got " + chunks.size());
    assertTrue(chunks.stream().anyMatch(c -> c.symbol() != null && c.symbol().contains("charge")), chunks.toString());
    assertTrue(chunks.stream().anyMatch(c -> c.symbol() != null && c.symbol().contains("refund")), chunks.toString());
    for (Chunk c : chunks) {
      assertTrue(c.startLine() >= 1 && c.endLine() >= c.startLine());
      assertTrue(c.text().length() <= 800 + 100);
    }
  }

  @Test
  void splitsMarkdownOnHeadingsButNotInsideFences() {
    String body = "text line\n".repeat(60);
    String md = "# Intro\n" + body + "## Setup\n```\n# not a heading\n```\n" + body;
    List<Chunk> chunks = new Chunker(700, 0).chunk("README.md", md, "markdown");
    assertTrue(chunks.stream().anyMatch(c -> c.symbol() != null && c.symbol().contains("Intro")));
    assertTrue(chunks.stream().anyMatch(c -> c.symbol() != null && c.symbol().contains("Setup")));
    assertTrue(chunks.stream().noneMatch(c -> c.symbol() != null && c.symbol().contains("not a heading")));
  }

  @Test
  void smallFileIsOneChunkWithAccurateLines() {
    List<Chunk> chunks = new Chunker(2000, 5).chunk("notes.txt", "\n\nhello\nworld\n\n", "text");
    assertEquals(1, chunks.size());
    assertEquals(3, chunks.getFirst().startLine());
    assertEquals(4, chunks.getFirst().endLine());
    assertEquals("notes.txt:3-4", chunks.getFirst().id());
  }

  @Test
  void oversizedSegmentIsWindowedWithOverlap() {
    String text = "line of prose that goes on\n".repeat(200);
    List<Chunk> chunks = new Chunker(500, 3).chunk("big.txt", text, "text");
    assertTrue(chunks.size() > 5);
    for (int i = 1; i < chunks.size(); i++) {
      assertTrue(chunks.get(i).startLine() <= chunks.get(i - 1).endLine(), "windows should overlap");
    }
  }

  @Test
  void blankFileYieldsNothing() {
    assertTrue(new Chunker(2000, 5).chunk("empty.md", "\n  \n", "markdown").isEmpty());
  }
}
