package com.localrag.index;

import com.localrag.config.Settings;
import com.localrag.llm.Embedder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;

/** Builds the Lucene index (BM25 text field + optional HNSW vector field) from files on disk. */
public final class LuceneIndexer {

  /** Lucene 9's default HNSW format rejects vectors wider than this. */
  static final int MAX_VECTOR_DIMENSIONS = 1024;
  static final String LUCENE_DIR = "lucene";

  public record IndexStats(
      int files, int chunks, int embedded, int reusedEmbeddings, String embeddingModel, double seconds) {}

  private final Settings settings;

  public LuceneIndexer(Settings settings) {
    this.settings = settings;
  }

  /**
   * Re-indexes {@code roots} from scratch into {@code settings.indexDir}.
   *
   * @param embedder null to build a BM25-only index
   */
  public IndexStats build(List<Path> roots, Embedder embedder, Consumer<String> progress) throws IOException {
    long started = System.nanoTime();
    Path indexDir = settings.indexDir.toAbsolutePath();
    Map<String, float[]> reuse = embedder == null ? Map.of() : previousVectors(indexDir, embedder.model());

    Chunker chunker = new Chunker(settings.maxChunkChars, settings.chunkOverlapLines);
    List<Chunk> chunks = new ArrayList<>();
    int files = 0;
    for (Path root : roots) {
      Path absRoot = root.toAbsolutePath().normalize();
      if (!Files.exists(absRoot)) {
        throw new IOException("Path does not exist: " + absRoot);
      }
      Path base = Files.isDirectory(absRoot) ? absRoot : absRoot.getParent();
      List<Path> found = FileScanner.scan(absRoot, settings.maxFileBytes);
      progress.accept("Scanning " + absRoot + ": " + found.size() + " files");
      for (Path file : found) {
        Optional<String> content = FileScanner.readText(file);
        Optional<String> kind = FileScanner.kindOf(file);
        if (content.isEmpty() || kind.isEmpty()) {
          continue;
        }
        String rel = base.relativize(file).toString().replace('\\', '/');
        if (roots.size() > 1) {
          rel = base.getFileName() + "/" + rel;
        }
        chunks.addAll(chunker.chunk(rel, content.get(), kind.get()));
        files++;
      }
    }
    progress.accept("Chunked " + files + " files into " + chunks.size() + " chunks");

    float[][] vectors = null;
    int embedded = 0;
    int reused = 0;
    if (embedder != null && !chunks.isEmpty()) {
      vectors = new float[chunks.size()][];
      List<Integer> todo = new ArrayList<>();
      for (int i = 0; i < chunks.size(); i++) {
        float[] previous = reuse.get(chunks.get(i).contentHash());
        if (previous != null) {
          vectors[i] = previous;
          reused++;
        } else {
          todo.add(i);
        }
      }
      if (reused > 0) {
        progress.accept("Reusing " + reused + " embeddings from the previous index");
      }
      int batch = Math.max(1, settings.embedBatchSize);
      for (int start = 0; start < todo.size(); start += batch) {
        List<Integer> ids = todo.subList(start, Math.min(todo.size(), start + batch));
        float[][] out = embedder.embedDocuments(ids.stream().map(i -> chunks.get(i).indexText()).toList());
        for (int j = 0; j < ids.size(); j++) {
          vectors[ids.get(j)] = out[j];
        }
        embedded += ids.size();
        progress.accept("Embedded " + embedded + "/" + todo.size() + " chunks with " + embedder.model());
      }
      int dims = vectors[0].length;
      if (dims > MAX_VECTOR_DIMENSIONS) {
        throw new IOException(
            "Embedding model '" + embedder.model() + "' produces " + dims + "-dim vectors; Lucene's HNSW"
                + " format supports up to " + MAX_VECTOR_DIMENSIONS + ". Use e.g. nomic-embed-text (768),"
                + " mxbai-embed-large (1024) or all-minilm (384).");
      }
    }

    Path tmp = indexDir.resolve(LUCENE_DIR + ".tmp");
    deleteRecursively(tmp);
    Files.createDirectories(tmp);
    IndexWriterConfig config =
        new IndexWriterConfig(new CodeAnalyzer(true)).setOpenMode(IndexWriterConfig.OpenMode.CREATE);
    try (FSDirectory dir = FSDirectory.open(tmp);
        IndexWriter writer = new IndexWriter(dir, config)) {
      for (int i = 0; i < chunks.size(); i++) {
        writer.addDocument(toDocument(chunks.get(i), vectors == null ? null : vectors[i]));
      }
      writer.commit();
    }
    Path live = indexDir.resolve(LUCENE_DIR);
    deleteRecursively(live);
    Files.move(tmp, live, StandardCopyOption.ATOMIC_MOVE);

    new IndexMeta(
            roots.stream().map(r -> r.toAbsolutePath().normalize().toString()).toList(),
            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            files,
            chunks.size(),
            vectors == null ? null : embedder.model(),
            vectors == null ? null : vectors[0].length)
        .write(indexDir);

    double seconds = Math.round((System.nanoTime() - started) / 1e7) / 100.0;
    return new IndexStats(files, chunks.size(), embedded, reused, vectors == null ? null : embedder.model(), seconds);
  }

  private static Document toDocument(Chunk chunk, float[] vector) {
    Document doc = new Document();
    doc.add(new StringField(IndexFields.ID, chunk.id(), Field.Store.YES));
    doc.add(new StringField(IndexFields.PATH, chunk.path(), Field.Store.YES));
    doc.add(new StringField(IndexFields.KIND, chunk.kind(), Field.Store.YES));
    doc.add(new StringField(IndexFields.HASH, chunk.contentHash(), Field.Store.YES));
    if (chunk.symbol() != null) {
      doc.add(new StoredField(IndexFields.SYMBOL, chunk.symbol()));
    }
    doc.add(new StoredField(IndexFields.START_LINE, chunk.startLine()));
    doc.add(new StoredField(IndexFields.END_LINE, chunk.endLine()));
    doc.add(new StoredField(IndexFields.TEXT, chunk.text()));
    doc.add(new TextField(IndexFields.CONTENT, chunk.indexText(), Field.Store.NO));
    if (vector != null) {
      doc.add(new KnnFloatVectorField(IndexFields.EMBEDDING, vector, VectorSimilarityFunction.COSINE));
    }
    return doc;
  }

  /** content_hash -> vector from the existing index, so unchanged chunks are not re-embedded. */
  private static Map<String, float[]> previousVectors(Path indexDir, String model) {
    Path live = indexDir.resolve(LUCENE_DIR);
    try {
      if (!Files.isDirectory(live) || !model.equals(IndexMeta.read(indexDir).embeddingModel())) {
        return Map.of();
      }
      Map<String, float[]> out = new HashMap<>();
      try (FSDirectory dir = FSDirectory.open(live);
          DirectoryReader reader = DirectoryReader.open(dir)) {
        for (LeafReaderContext ctx : reader.leaves()) {
          LeafReader leaf = ctx.reader();
          FloatVectorValues values = leaf.getFloatVectorValues(IndexFields.EMBEDDING);
          if (values == null) {
            continue;
          }
          StoredFields stored = leaf.storedFields();
          for (int doc = values.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = values.nextDoc()) {
            String hash = stored.document(doc, Set.of(IndexFields.HASH)).get(IndexFields.HASH);
            out.put(hash, values.vectorValue().clone());
          }
        }
      }
      return out;
    } catch (IOException | RuntimeException ex) {
      return Map.of();
    }
  }

  static void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(path)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
