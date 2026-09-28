package com.localrag.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware chunking. Files are cut at natural boundaries first (definitions in code,
 * headings in Markdown, blank-line paragraphs in prose); consecutive segments are then packed up
 * to {@code maxChars}. A segment too large on its own is split into overlapping line windows.
 */
public final class Chunker {

  /** A line that starts a definition; the symbol name lands in one of the named groups. */
  private static final Pattern CODE_BOUNDARY =
      Pattern.compile(
          "^\\s*(?:export\\s+)?(?:default\\s+)?(?:(?:public|private|protected|internal|static|final|abstract|async|override|sealed|open|data)\\s+)*"
              + "(?:def|class|interface|enum|record|struct|trait|impl|fn|func|function|module|object|type)\\s+(?<name>[A-Za-z_$][\\w$]*)"
              + "|^\\s*(?:export\\s+)?(?:const|let|var)\\s+(?<arrow>[A-Za-z_$][\\w$]*)\\s*=\\s*(?:async\\s*)?\\("
              + "|^\\s*(?:(?:public|private|protected)\\s+)(?:(?:static|final|abstract|synchronized)\\s+)*[\\w<>\\[\\],.?\\s]+?\\s+(?<method>[A-Za-z_]\\w*)\\s*\\(");

  private static final Pattern MD_HEADING = Pattern.compile("^#{1,6}\\s+(?<name>.+?)\\s*#*\\s*$");

  private record Segment(int start, int end, String symbol) {}

  private final int maxChars;
  private final int overlapLines;

  public Chunker(int maxChars, int overlapLines) {
    this.maxChars = Math.max(200, maxChars);
    this.overlapLines = Math.max(0, overlapLines);
  }

  public List<Chunk> chunk(String relPath, String content, String kind) {
    String[] lines = content.split("\\R", -1);
    boolean anyContent = false;
    for (String line : lines) {
      if (!line.isBlank()) {
        anyContent = true;
        break;
      }
    }
    if (!anyContent) {
      return List.of();
    }

    List<Segment> segments = segments(lines, kind);
    List<Segment> pieces = new ArrayList<>();
    Segment current = null;
    for (Segment seg : segments) {
      if (chars(lines, seg.start, seg.end) > maxChars) {
        if (current != null) {
          pieces.add(current);
          current = null;
        }
        pieces.addAll(window(lines, seg));
      } else if (current == null) {
        current = seg;
      } else if (chars(lines, current.start, seg.end) <= maxChars) {
        current = new Segment(current.start, seg.end, joinSymbols(current.symbol, seg.symbol));
      } else {
        pieces.add(current);
        current = seg;
      }
    }
    if (current != null) {
      pieces.add(current);
    }

    List<Chunk> chunks = new ArrayList<>();
    for (Segment piece : pieces) {
      int start = piece.start;
      int end = piece.end;
      while (start < end && lines[start].isBlank()) {
        start++;
      }
      while (end > start && lines[end - 1].isBlank()) {
        end--;
      }
      if (end <= start) {
        continue;
      }
      String body = String.join("\n", Arrays.copyOfRange(lines, start, end));
      chunks.add(
          new Chunk(
              relPath + ":" + (start + 1) + "-" + end,
              relPath,
              start + 1,
              end,
              body,
              piece.symbol,
              kind,
              sha1(relPath + "\n" + piece.symbol + "\n" + body)));
    }
    return chunks;
  }

  private static List<Segment> segments(String[] lines, String kind) {
    List<Integer> starts = new ArrayList<>();
    List<String> symbols = new ArrayList<>();
    starts.add(0);
    symbols.add(null);
    boolean inFence = false;
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String symbol = null;
      boolean boundary = false;
      switch (kind) {
        case "code" -> {
          Matcher m = CODE_BOUNDARY.matcher(line);
          if (m.find()) {
            boundary = true;
            symbol = firstNonNull(m.group("name"), m.group("arrow"), m.group("method"));
          }
        }
        case "markdown" -> {
          if (line.stripLeading().startsWith("```")) {
            inFence = !inFence;
          }
          Matcher m = MD_HEADING.matcher(line);
          if (!inFence && m.matches()) {
            boundary = true;
            symbol = m.group("name");
          }
        }
        default -> boundary = i > 0 && lines[i - 1].isBlank() && !line.isBlank();
      }
      if (!boundary) {
        continue;
      }
      if (i == 0) {
        symbols.set(0, symbol);
      } else {
        starts.add(i);
        symbols.add(symbol);
      }
    }
    List<Segment> segments = new ArrayList<>();
    for (int idx = 0; idx < starts.size(); idx++) {
      int start = starts.get(idx);
      int end = idx + 1 < starts.size() ? starts.get(idx + 1) : lines.length;
      if (end > start) {
        segments.add(new Segment(start, end, symbols.get(idx)));
      }
    }
    return segments;
  }

  private List<Segment> window(String[] lines, Segment seg) {
    List<Segment> out = new ArrayList<>();
    int i = seg.start;
    while (i < seg.end) {
      int j = i;
      int size = 0;
      while (j < seg.end && (size + lines[j].length() + 1 <= maxChars || j == i)) {
        size += lines[j].length() + 1;
        j++;
      }
      out.add(new Segment(i, j, seg.symbol));
      if (j >= seg.end) {
        break;
      }
      i = Math.max(i + 1, j - overlapLines);
    }
    return out;
  }

  private static int chars(String[] lines, int start, int end) {
    int total = 0;
    for (int i = start; i < end; i++) {
      total += lines[i].length() + 1;
    }
    return total;
  }

  /** Packed chunks list every symbol they contain (capped) so BM25 and citations see all of them. */
  private static String joinSymbols(String a, String b) {
    if (a == null) {
      return b;
    }
    if (b == null || a.split(", ").length >= 4) {
      return a;
    }
    return a + ", " + b;
  }

  private static String firstNonNull(String... values) {
    for (String v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }

  static String sha1(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
