# Answer Extraction — reading answers out of a submitted document

A submitted document is parsed, read into the repository, checked, and mined for the answers
the schema asks for. Everything after parsing runs in Java, in this module.

## The pipeline

- **Upload** — a file lands on a `sub:File` node.
- **Parse** — the Docling daemon writes `<stem>.md`, `<stem>.pdf` and a `Chunks/` tree to the
  shared volume. See [processing.md](../documents/processing/processing.md).
- **Ingest** — `ParseResultIngester` copies all of it onto the node and deletes the files:
  - `outline.json` becomes `tokens`, `bookmarks`, `chunked`, `unchunkedReason`
  - `catalog.json` becomes `sub:Chunk` children, in document order
  - each `Chunk-N.md` becomes that chunk's `content`
  - `<stem>.md` becomes the `markdownFile` child; `<stem>.pdf` the `pdfFile` child, unless
    the upload is itself a PDF and so already holds it
  - `unchunkedOverLimit` is computed here too, once, and is what the gate reads instead of
    re-deriving it -- see the rule below
- **Gate** — `ProposalGateService` asks one model call two things at once:
  - is this a research proposal?
  - what is each chunk about (a rubric tag)?
- **Tag** — `applyTags` writes those tags onto the chunks. A separate step, so a document
  turned away leaves nothing behind.
- **Select** — `ChunkSelection` picks the chunks worth sending for a given set of questions.
- **Extract** — put the questions to the model over the selected chunks. *Not built yet.*
- **Write** — record the answers as `sub:Answer` with `sub:Evidence`. *Not built yet.*

## Rules worth knowing

- **The gate does not guess.** The verdict is one of three: `PROPOSAL`, `NOT_PROPOSAL`, or
  `UNDETERMINED`. If it cannot reach the model, cannot read two answers, or has nothing to
  show, the verdict is `UNDETERMINED` -- not a yes. `UNDETERMINED` skips extraction.
- **What the gate is shown**: three blocks, named as the prompt names them.
  `PROTOCOL_STRUCTURE` is the ICH-GCP rubric reference and lives in the **system prompt**.
  `INPUT` is the document, in whichever of three forms it has -- the document's own bookmarks
  (a table of contents), the whole `markdownFile` (a document the parse left unchunked), or the
  first 10k tokens' worth of chunks (its opening). A proposal says what it is early or not at
  all. `CATALOG` is one heading per chunk, the tagging targets, and is left out when the
  document has no chunks. `INPUT` and `CATALOG` live in the **user message**.
- **Whether a document can be processed at all is decided once, at ingest, not re-derived by
  the gate.** `chunked: false` has three possible reasons, and only one of them,
  `below_min_structure_tokens`, says anything about size. The other two -- a deliberate skip
  (`chunking_not_requested`) or a splitter that found nothing to cut
  (`splitter_returned_no_parts`) -- could be any size. `ParseResultIngester` checks the
  document's own token count against the active model's `wholeDocumentTokenLimit` -- the same
  threshold that decides whether a document is small enough to leave whole in the first place
  -- and when it does not fit, sets `unchunkedOverLimit` on the node and logs an error, at the
  one place every parse passes through, rather than leaving it to surface later as a quiet
  "cannot tell" from the gate. None of the Markdown, the PDF or a chunk tree is stored in that
  case either.  This should
  not happen -- the parser is expected to leave every non-trivial document either chunked,
  bookmarked, or small enough to send whole.
  `ProposalGateService` only reads the flag; `describeInput` returns blank when it is set, and
  the existing "nothing could be shown" path in `evaluate` logs and returns `UNDETERMINED`,
  same as any other document with nothing to offer.
- **The constant part of a prompt goes first, and stays in the system prompt.** The gate call
  and its re-ask open with the same `PROTOCOL_STRUCTURE`, so the two share a byte-identical
  opening. Keeping it first and out of the user message is what a hosted provider's prefix
  cache matches on and what a local model reuses its KV cache for. Put the per-call
  instructions first instead and the shared part stops being a prefix, so nothing can cache
  it. Anything that varies per document belongs after it.
- **Repeated calls get the glossary, not the full reference.** `protocol_structure.md` is ~4k
  tokens of ICH-GCP prose, which is what judging a whole document's shape needs -- that is what
  the gate opens with. `protocol_structure_glossary.md` is one line per rubric, about a ninth
  of the size, and is what placing a single chunk needs -- that is what the retagging and
  extraction passes will open with instead, once built. The gate call happens once per
  document; the retagging and extraction calls repeat, once per batch of chunks, so that is
  where the smaller size actually pays off. The two prompts are different byte-for-byte, so a
  retagging call does not share the gate's cached prefix -- it starts its own, shared only
  across the retagging/extraction calls that repeat it.
- **A chunk that opens mid-prose inherits the last heading of the chunk before it**:
  a run of several headless chunks in a row all inherit from the same last chunk that had one.
- **Selection errs towards sending.** A chunk is only left out when a model read it and
  confidently placed it elsewhere (`tagBasis` of `fulltext` or `deep`). Untagged, uncertain,
  heading-tagged and unplaced chunks all still go.

## Not built yet

- Nothing calls the ingester. The parse job records a shared-volume path with no link back to
  a `sub:File`, so the job needs to carry its target.
- Intake and step 2 (the extraction calls themselves). When they are built: batch the chunks
  per call rather than one call per chunk, and read the glossary instead of the full reference.
  The preamble is paid once per call, not once per chunk, so batching saves more than any
  caching does.
- `POST /Submissions/<id>.extract`, writing `sub:Extraction`, `sub:Answer` and `sub:Evidence`.

## Files

### Wiring

| File | What it does |
| --- | --- |
| `pom.xml` | The bundle. Depends on `iap-llm`, `iap-submissions-api`, `iap-schemas-api`. Carries a 0.99 coverage floor and why. |
| `src/main/features/feature.json` | Declares the bundle, start-order 27 — after the LLM client and the submissions model. |
| `packaging/slingfeature/.../core/extraction.json` | Deploys the bundle. Without it the module builds and is never installed. |

### Reading a parse in

| File | What it does |
| --- | --- |
| `ParseResultIngester` | Copies a finished parse onto the `sub:File` node, then deletes it from the volume. |
| `ParsePropertyNames` | The names shared between the daemon's JSON and JCR. |

### The gate

| File | What it does |
| --- | --- |
| `ProposalGateService` | Decides whether the document is a proposal, and tags its chunks, in one call. |
| `ChunkContent` | Reads a chunk's text; derives its heading and token estimate. |
| `Prompts` | Loads prompts and schemas from the bundle, cached. |
| `prompts/is_proposal_system.md` | System prompt to answer the `is_proposal` gate question. |
| `prompts/is_proposal_schema.json` | The shape its answer must take. |
| `prompts/protocol_structure.md` | The ICH-GCP rubrics B.1-B.17 the tags come from. Sent whole in the gate's system prompt, about 16k characters of it. |
| `prompts/protocol_structure_glossary.md` | One line per rubric, for the calls that only place a chunk. Nothing reads it yet -- the retagging pass is not built. |

### Choosing what to ask, and where

| File | What it does |
| --- | --- |
| `ExtractionField` | One answer to read out of a document, read from a `sch:Question`. |
| `ChunkSelection` | Which chunks could hold an answer, by tag. |
