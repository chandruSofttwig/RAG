package com.localrag.index;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** Walks a directory tree and returns indexable text files. */
public final class FileScanner {

  /** Directories never worth indexing (mirrors andromedia's SkippedDirectories). */
  static final Set<String> SKIP_DIRS =
      Set.of(
          ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out", ".next",
          ".venv", "venv", "__pycache__", ".pytest_cache", ".mypy_cache", ".idea", ".vscode",
          ".gradle", ".rag_index", "coverage", ".tox", ".cache", "vendor");

  static final Set<String> CODE_EXTS =
      Set.of(
          "java", "kt", "scala", "groovy", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs", "go", "rs",
          "rb", "php", "c", "h", "cpp", "hpp", "cc", "cs", "swift", "sh", "bash", "sql", "vue",
          "svelte", "lua", "dart");
  static final Set<String> MARKDOWN_EXTS = Set.of("md", "mdx", "markdown", "rst");
  static final Set<String> TEXT_EXTS =
      Set.of(
          "txt", "json", "yaml", "yml", "toml", "ini", "cfg", "xml", "html", "css", "scss",
          "properties", "gradle", "csv", "adoc");
  static final Set<String> TEXT_FILENAMES =
      Set.of("Dockerfile", "Makefile", "README", "LICENSE", "Jenkinsfile");

  private FileScanner() {}

  public static Optional<String> kindOf(Path path) {
    String name = path.getFileName().toString();
    if (TEXT_FILENAMES.contains(name)) {
      return Optional.of("text");
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0) {
      return Optional.empty();
    }
    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (CODE_EXTS.contains(ext)) {
      return Optional.of("code");
    }
    if (MARKDOWN_EXTS.contains(ext)) {
      return Optional.of("markdown");
    }
    if (TEXT_EXTS.contains(ext)) {
      return Optional.of("text");
    }
    return Optional.empty();
  }

  public static List<Path> scan(Path root, long maxBytes) throws IOException {
    Path start = root.toAbsolutePath().normalize();
    List<Path> found = new ArrayList<>();
    if (Files.isRegularFile(start)) {
      if (kindOf(start).isPresent()) {
        found.add(start);
      }
      return found;
    }
    Files.walkFileTree(
        start,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
            if (!dir.equals(start) && (SKIP_DIRS.contains(name) || name.startsWith("."))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile() && attrs.size() <= maxBytes && kindOf(file).isPresent()) {
              found.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    found.sort(null);
    return found;
  }

  /** Reads a file as UTF-8 text; empty if it looks binary or cannot be read. */
  public static Optional<String> readText(Path path) {
    try {
      byte[] bytes = Files.readAllBytes(path);
      int probe = Math.min(bytes.length, 8192);
      for (int i = 0; i < probe; i++) {
        if (bytes[i] == 0) {
          return Optional.empty();
        }
      }
      try {
        return Optional.of(
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes))
                .toString());
      } catch (CharacterCodingException ex) {
        return Optional.empty();
      }
    } catch (IOException ex) {
      return Optional.empty();
    }
  }
}
