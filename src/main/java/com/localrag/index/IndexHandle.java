package com.localrag.index;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.FSDirectory;

/** An open, read-only view of the index. */
public final class IndexHandle implements AutoCloseable {

  private final FSDirectory directory;
  private final DirectoryReader reader;
  private final IndexSearcher searcher;
  private final IndexMeta meta;

  private IndexHandle(FSDirectory directory, DirectoryReader reader, IndexMeta meta) {
    this.directory = directory;
    this.reader = reader;
    this.searcher = new IndexSearcher(reader);
    this.meta = meta;
  }

  public static boolean exists(Path indexDir) {
    return Files.isRegularFile(indexDir.resolve(IndexMeta.FILE_NAME))
        && Files.isDirectory(indexDir.resolve(LuceneIndexer.LUCENE_DIR));
  }

  public static IndexHandle open(Path indexDir) throws IOException {
    if (!exists(indexDir)) {
      throw new IOException("No index at " + indexDir.toAbsolutePath() + ". Run `rag index <path>` first.");
    }
    FSDirectory dir = FSDirectory.open(indexDir.resolve(LuceneIndexer.LUCENE_DIR));
    return new IndexHandle(dir, DirectoryReader.open(dir), IndexMeta.read(indexDir));
  }

  public IndexSearcher searcher() {
    return searcher;
  }

  public IndexMeta meta() {
    return meta;
  }

  public int size() {
    return reader.numDocs();
  }

  public Chunk chunk(int docId) throws IOException {
    Document doc = searcher.storedFields().document(docId);
    return new Chunk(
        doc.get(IndexFields.ID),
        doc.get(IndexFields.PATH),
        doc.getField(IndexFields.START_LINE).numericValue().intValue(),
        doc.getField(IndexFields.END_LINE).numericValue().intValue(),
        doc.get(IndexFields.TEXT),
        doc.get(IndexFields.SYMBOL),
        doc.get(IndexFields.KIND),
        doc.get(IndexFields.HASH));
  }

  @Override
  public void close() throws IOException {
    reader.close();
    directory.close();
  }
}
