# local-rag

Hybrid **BM25 + vector** retrieval-augmented generation in Java, running **entirely on local models**
through [Ollama](https://ollama.com). No API keys, and no data leaves your machine.

It is a standalone extraction of the retrieval stack in
andromedia (`andromedia/search`, `andromedia/llm`, `andromedia/indexing`). The
same pipeline and tuning constants are kept, the multi-source/CRM-specific parts are removed, and
the cloud embedding/LLM calls (OpenRouter) are replaced with local Ollama models.

```
             ┌───────────── index ─────────────┐        ┌──────────────── query ────────────────┐
 files ──▶ structure-aware chunker ──┬─▶ Lucene BM25 (code-aware analyzer) ──┐
                                     └─▶ nomic-embed-text ─▶ Lucene HNSW     ├─▶ weighted RRF ─▶ filters ─▶ top-k
                                                                             │                                  │
                                                     query ──▶ classify ─────┘                                  ▼
                                                                                  numbered excerpts ─▶ qwen2.5 ─▶ answer [1][2]
```

## Quick start

Requirements: Java 21+, Maven 3.8+, and Ollama running locally.

```bash
ollama pull nomic-embed-text      # embedding model (274 MB, 768 dims)
ollama pull qwen2.5:3b            # chat model (1.9 GB); qwen2.5:0.5b works on low-RAM machines

./rag status                      # builds the jar on first run, checks models
./rag index ~/code/my-project     # chunk + BM25 + embeddings  → ./.rag_index
./rag search "PaymentRetryPolicy"
./rag ask "how are failed payments retried?"
./rag chat                        # interactive
./rag serve --port 8080           # web UI at http://127.0.0.1:8080
```

`./rag` is a thin wrapper around `java -jar target/local-rag.jar`. You can also run
`mvn package` and then call the jar directly.

## Commands

| Command | What it does |
|---|---|
| `index <path>... [--no-embed]` | Rebuilds the index from files/directories. `--no-embed` builds BM25 only. Unchanged chunks reuse their previous embeddings. |
| `search "<q>" [-k 6] [--mode hybrid\|bm25\|semantic] [--json]` | Retrieval only (no LLM), with per-channel scores and diagnostics. |
| `ask "<q>" [-k 6] [--mode …] [--show-context]` | Retrieves, then streams a grounded answer with `[n]` citations and a source list. |
| `chat` | Same as `ask`, in a loop. |
| `serve [--host 127.0.0.1] [--port 8080]` | Web UI plus JSON API. |
| `status` | Index info, configured models, and a warning for any model that isn't pulled. |

Global options are `--index-dir`, `--ollama-url`, `--embed-model` and `--chat-model`. Every
setting can also be set with an environment variable `RAG_<NAME>`, e.g. `RAG_CHAT_MODEL=qwen2.5:0.5b` or
`RAG_TOP_K=8`. See [`Settings.java`](src/main/java/com/localrag/config/Settings.java).

### HTTP API (`rag serve`)

```
GET  /api/status
POST /api/search  {"query": "...", "k": 6, "mode": "hybrid"}   → {"hits": [...], "diagnostics": {...}}
POST /api/ask     {"question": "...", "k": 6}                   → NDJSON: {"type":"sources"} {"type":"token"}… {"type":"done"}
POST /api/index   {"paths": ["/abs/path"], "embed": true}      → index stats
```

## How retrieval works

This section is an analysis of andromedia's design and how it was carried over.

**1. Chunking** ([`Chunker`](src/main/java/com/localrag/index/Chunker.java)).
Andromedia chunks Java per method and TS/JS per symbol (using tree-sitter metadata). Here a
single language-agnostic chunker cuts at definition lines (`class`, `def`, `function`,
`public … foo(`, `const x = (`), at Markdown headings (ignoring code fences), or at blank-line
paragraphs. It then packs adjacent segments up to `maxChunkChars` (2000). An oversized segment is
split into overlapping line windows. Every chunk records its path, line range and symbol names.
The text that gets indexed is `path :: symbols` followed by the body, so both channels can match
on file and function names.

**2. BM25 channel** ([`CodeAnalyzer`](src/main/java/com/localrag/index/CodeAnalyzer.java), `HybridRetriever.bm25Search`).
Lucene BM25 (k1 = 1.2, b = 0.75). Andromedia uses `StandardAnalyzer`, which leaves `getUserById`
as a single token. This project uses a code-aware analyzer instead: it keeps the whole identifier
*and* its camelCase/snake_case parts (`getuserbyid`, `get`, `user`, `id`) and drops English
stopwords. Queries are built as term queries rather than parsed with `QueryParser`, so user input
can't cause a syntax error. As in andromedia, identifier-style queries require every whole word
(AND) and fall back to OR if that finds nothing; natural-language queries use OR.

**3. Semantic channel** (`HybridRetriever.semanticSearch`).
Chunks are embedded locally (default `nomic-embed-text`, with its `search_document:` /
`search_query:` prefixes applied automatically), L2-normalised, and stored in a Lucene HNSW
field with cosine similarity. Queries run KNN over that graph. Hits below
`semanticMinScore` (cosine 0.2) are dropped before fusion.

**4. Query-type-weighted RRF** ([`QueryTypeClassifier`](src/main/java/com/localrag/search/QueryTypeClassifier.java), [`ReciprocalRankFusion`](src/main/java/com/localrag/search/ReciprocalRankFusion.java)).
Both channels run in parallel over a candidate pool of `max(5k, 50)`. The scores are fused with
`Σ weight / (60 + rank)`. The weights come straight from andromedia's `RankingHeuristicsProperties`:

| query type | example | BM25 | semantic |
|---|---|---|---|
| IDENTIFIER | `HybridSearchService`, `src/app.ts` | 1.0 | 0.3 |
| NATURAL_LANGUAGE | `how does retry work` | 0.6 | 1.0 |
| MIXED | `where is getUser` | 0.85 | 0.85 |

**5. Post-fusion repair** ([`RelativeScoreFilter`](src/main/java/com/localrag/search/RelativeScoreFilter.java), [`LexicalAnchorFilter`](src/main/java/com/localrag/search/LexicalAnchorFilter.java)).
Fusion runs on a larger pool (`max(4k, 25)`) and truncation happens last, so a hit that is strong
on BM25 but weak on KNN can survive. When BM25 has a *strong relative lead* (top score ≥ 1.2 × the
median of the top 10), BM25 hits that are competitive (≥ 25 % of the top score) and query-aligned
(contain a distinctive query token) are forced back to the top of the list. Dense models often
miss exact-name matches, and this step catches them. All thresholds are relative to the score
distribution, so they don't depend on corpus size.

**6. Heuristics and generation** ([`HeuristicRanker`](src/main/java/com/localrag/search/HeuristicRanker.java), [`PromptBuilder`](src/main/java/com/localrag/rag/PromptBuilder.java)).
Tests are demoted (×0.75), as are build output (×0.5) and config files (×0.85). For identifier
queries, an exact file-name match is boosted (×1.6), and so is an exact symbol match (×1.35). The
final top-k go into a prompt as numbered excerpts, trimmed from the tail to fit `maxPromptChars`.
The system prompt (adapted from andromedia's `CodeRagPromptBuilder`) requires `[n]` citations and
tells the model to say so when the excerpts don't contain the answer.

**Graceful degradation.** If the index has no vectors, the embedding model isn't pulled, or Ollama
is down, the semantic channel is skipped. Hybrid search then returns BM25-only results and reports
why in the diagnostics. This is the same behaviour as andromedia's
`SEMANTIC_EMBEDDING_FAILURE_WARNING`.

### Not carried over from andromedia

- Source-specific logic: Slack, Jira, Linear, Notion, Zoho and git history, including
  `SourceCoverageMerger`, intent detectors and per-source boosts. It only matters when several
  heterogeneous sources are indexed.
- The symbol graph, impact analysis and the tree-sitter metadata extractors.
- The `Reranker` interface. Andromedia ships only a pass-through implementation.

## Choosing local models

| Role | Default | Alternatives |
|---|---|---|
| Embeddings | `nomic-embed-text` (768d) | `mxbai-embed-large` (1024d, prefixes handled), `all-minilm` (384d, fastest), `bge-m3` (1024d, multilingual) |
| Chat | `qwen2.5:3b` | `qwen2.5:0.5b` (≈1 GB RAM), `llama3.2:3b`, `qwen2.5:7b` or larger if you have the RAM/GPU |

Changing the embedding model requires a re-index. The index records which model it was built
with, and search refuses to mix models. Lucene's HNSW format is limited to 1024 dimensions.

## Development

```bash
mvn test        # 22 tests; pipeline tests use a deterministic fake embedder (no Ollama needed)
mvn package     # target/local-rag.jar (shaded, multi-release)
```

Layout: `config/` settings · `index/` scanning, chunking, analyzer, Lucene writer ·
`search/` channels, fusion, filters, heuristics · `llm/` Ollama clients · `rag/` prompt + answer ·
`server/` HTTP API + UI · `cli/` entry point.
