# Answer Extraction — reading answers out of a submitted document

A submitted document is parsed, read into the repository, checked, and mined for the answers
the schema asks for. Everything after parsing runs in Java, in this module.

## The pipeline

- **Upload** -- the submitter attaches the file through the submissions module's `attachDocument`
  system workflow. It lands as `sub:Document` -> `v1` (`sub:DocumentVersion`) -> `file` (`sub:File`)
  -> `uploadedFile`; a replacement is the next version of the same document, not a new one.
- **Send to parse** -- the process's `parseDocuments` service task, right after the upload step:
  every latest upload not parsed yet is staged on the volume shared with the Docling daemon and
  queued as a parse job naming the `sub:File` it is for. The file records `parseStatus: queued`,
  `parseJobId` and `sharedPath`; the submission records `extractionStatus: running`, which is what
  the submission view shows a spinner for.
- **Parse** -- the daemon writes `<stem>.md`, `<stem>.pdf` and a `Chunks/` tree beside the staged
  file. See [processing.md](../documents/processing/processing.md).
- **Read the parse in** -- the daemon's callback hands the outcome to `ParseCompletionHandler`,
  which fires `documentParsed` on the submission. That system workflow runs `ingestParse`
  (`ParseResultIngester` copies everything onto the `sub:File` and deletes the files, or records
  the failure) and `queueExtraction` (once no parse is still going, queue the reading). The parse
  job record is deleted as soon as the outcome was taken.
  - `outline.json` becomes `tokens`, `bookmarks`, `chunked`, `unchunkedReason`
  - `catalog.json` becomes `sub:Chunk` children, in document order
  - each `Chunk-N.md` becomes that chunk's `content`
  - `<stem>.md` becomes the `markdownFile` child; `<stem>.pdf` the `pdfFile` child, unless
    the upload is itself a PDF and so already holds it
  - `unchunkedOverLimit` is computed here too, once -- see the rule below
- **Read the answers** -- a background job fires `extractAnswers` on the submission. That system
  workflow is a straight line of service tasks, each skipping itself when the gate did not let
  the document through:
  - **Gate** (`gateProposal`) -- one model call, three questions: is this a research proposal,
    which category is it, what is each chunk about (a rubric tag). The tags are written with the
    confidence the model gave them; the verdict and the category go on the submission.
  - **Second look** (`classifyProposal`) -- only when the gate picked no category or was not sure
    enough of it.
  - **Intake** (`intakeAnswers`) -- every question with an `extractionPrompt`, in one call, over
    the whole document when it fits and the selected chunks when it does not. Each answer found
    becomes a `sub:Answer` with a `sub:Extraction` (value, confidence, reasoning, source version)
    and `sub:Evidence` children (quote, page, chunk). A question the submitter already answered
    is left alone. The chunks the model read come back retagged, replacing the gate's guess.
  - **Completeness** (`markCompleteness`, from the submissions module) -- the `incomplete` tag is
    judged again, now that answers may have landed.
  - `extractionStatus` ends `done`, `undetermined`, `not-proposal` or `failed`, with a message
    beside it; the view stops the spinner and shows the message.
  - **Step 2** (`secondPass`) -- ask again for every field the intake left open: nothing found, an
    answer under 0.75, or a quote that could not be found in the text. Fields are grouped by their
    rubric tags and each group gets its own call over the chunks it has not read. Whatever is still
    unanswered then gets one sweep over whatever is still unread, ignoring tags. Higher confidence
    wins, and a call never sees the previous answer. Capped at six calls.
- The reading can be asked for again at any time: `POST <submission>.extractAnswers.json`.

## Rules worth knowing

- **The gate does not guess.** The verdict is one of three: `PROPOSAL`, `NOT_PROPOSAL`, or
  `UNDETERMINED`. If it cannot reach the model, cannot read two answers, or has nothing to
  show, the verdict is `UNDETERMINED` -- not a yes. `UNDETERMINED` skips extraction. The
  category is held to the same rule: no pick is recorded as no pick, never as the likeliest.
- **What the gate is shown**: four blocks, named as the prompt names them.
  `PROTOCOL_STRUCTURE` is the ICH-GCP rubric reference; `CATEGORIES` is the live leaves of
  `/Categories` -- id, label, description, one per line -- left out when there are none. Both
  live in the **system prompt**. `INPUT` is the document, in up to two parts: its own
  bookmarks (a table of contents) when it has them, then its text -- the whole `markdownFile`
  for a document the parse left unchunked, or the first 10k tokens' worth of chunks (its
  opening). The text always goes along: an outline says what shape a document has, not what
  kind of study it is, and a proposal says what it is early or not at all. `CATALOG` is one
  heading per chunk, the tagging targets, and is left out when the document has no chunks.
  `INPUT` and `CATALOG` live in the **user message**.
- **The category is decided in the gate call, not in a call of its own.** It is the same kind
  of judgement as `is_proposal`, from the same material. Adding it costs the catalogue (~1k
  tokens) and ~80 tokens of answer, a few percent of the call; a separate call would re-pay
  the whole prefill of the document and roughly double the stage. The answer is one category
  id, validated in Java against the live leaves -- by path, or by label when the model copied
  that half of the line instead. Anything else is no pick, not the nearest one.
- **A second look when the gate was unsure.** Below a category confidence of 0.75, or with no
  pick at all, `ProposalCategoryService` asks once more with more to read: the chunks tagged
  B.1--B.3 (background, objectives, design), packed to the model's `wholeDocumentTokenLimit`,
  or the whole document when it fits. Its system prompt opens with the glossary, then
  `CATEGORIES`, then the instructions. Same rule on the answer: off-list or null is no pick.
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
  of the size, and is what placing a single chunk needs -- that is what the intake opens with,
  and what the retagging and step 2 passes will open with too. The gate call happens once per
  document; the intake and its follow-ups repeat, so that is where the smaller size pays off.
  The two prompts are different byte-for-byte, so an intake call does not share the gate's
  cached prefix -- it starts its own, shared across every call that opens with the glossary.
- **A chunk that opens mid-prose inherits the last heading of the chunk before it**:
  a run of several headless chunks in a row all inherit from the same last chunk that had one.
- **Selection errs towards sending.** A chunk is only left out when something placed it somewhere
  the fields do not ask about. A chunk carrying no tags is a wildcard: it matches every field and
  stays eligible for every later pass. Ruling one out on a tag nobody assigned is how this would
  silently go fail-closed, reporting a field absent from text nobody ever searched.
- **What the intake is shown**: the glossary in the system prompt, then three blocks in the
  user message. `SCHEMA` is one entry per field -- question, purpose, rules, answer shape,
  whether several values are allowed -- built from the `sch:Question` nodes, so there is no
  list of field names in the code. `CATALOG` is one line per chunk: id, heading, and for a
  chunk not sent in full, a 150-character snippet of its opening. `CHUNK` is the full text of
  the sent chunks, each opened by a `[chunk:<id>]` marker so a quote can say where it came from.
  A document with no chunks is sent whole under the id `document`.
- **How much the intake sends** is the active model's `wholeDocumentTokenLimit`. The whole
  document goes when it fits under it. When it does not, `ChunkSelection` picks the candidates
  and they are packed in document order until the budget is spent; the first candidate always
  goes, however large. Reference lists are never sent -- a chunk headed References,
  Bibliography or the like in the last 40% of the document is dropped before either step.
- **The answer's shape is enforced, not hoped for.** The response schema is built from the same
  fields as `SCHEMA` -- one object per field with `found_answer`, `confidence`, `value`,
  `reasoning`, `evidence[]` -- plus `chunk_tags[]`, all closed to extra properties, and handed
  to the client as the JSON schema the reply must match. The model is asked once, and once more
  if the first reply was not readable. `value` is always a string; the field's `responseShape`
  is guidance in `SCHEMA`, not the wire type.
- **A quote has to be in what was sent.** Every passage is checked against the chunk it names,
  with case, whitespace and the page and chunk markers set aside. The match is fuzzy: a dropped
  word or a changed hyphen still counts, a made-up sentence does not. Each passage records whether
  it was found and how closely it matched. The field's confidence is multiplied by how many of its
  quotes held up, from 1.0 when all did to 0.5 when none did. A field is `found` only when the model
  said so and gave a value.

- **Every call is recorded.** `llmCallTracker`, a JSONL file beside the chunks, holds one line per
  call: which call it was, what step, which fields it asked about, which chunks it read in full, how
  long it took (`durationMs`) and how it ended (`outcome`: `ok`, `degraded` when the answer could not
  be read, `failed` when the call never came back). Step 2 reads it to know what is left. Coverage is
  counted per field: if one field has read a chunk and another has not, that chunk still goes to the
  second field. A failed call read nothing, so it does not count.
- **The intake does not guess either.** Nothing to read, or a reply that cannot be read twice
  over, gives a `degraded` result: every field unanswered, no tags. A model that cannot be
  reached is an `IOException` to the caller. Neither is ever turned into an answer.
- **Intake tags are the model's word on the full text.** `applyTags` writes what the model gave,
  with its confidence, replacing the gate's guess. There is no cap on how many tags a chunk carries:
  a section really can be background and objectives at once. A tag off the B.1-B.17 vocabulary is
  dropped, on both the gate and the intake path. A chunk that came back with no usable tag keeps
  what it had, rather than being cleared, because a cleared chunk would become a wildcard. A
  degraded result writes nothing.

- **Extraction is content, not code.** `documentParsed` and `extractAnswers` are `/SystemWorkflows`
  definitions shipped by this module, and the handlers are the engine's extension point: a
  deployment adds a step or drops one by editing the definition. A system workflow cannot branch
  on what a step recorded, so the steps after the gate read its verdict from the execution's
  variables and skip themselves instead.
- **Who may fire what.** `documentParsed` names this module's service user, `iap-extraction`, as
  its only performer: its payload is paths on the shared volume, and reading files from there
  into the repository is nobody else's to ask for. `extractAnswers` admits `everyone`; it reads
  nothing but the repository, and a submitter may well want to ask for it again.
- **Nothing here commits.** The gate's and the intake's `applyTags`, the ingester and every
  handler write through the engine's session, and the engine commits the whole run at once, so a
  submission never shows half a parse or half a reading.

## Calls in parallel

Step 2's targeted calls do not depend on each other, so they run at the same time. The sweep needs
their merged answers, so it runs after them. The gate, classify and intake calls are one call each;
there is nothing to run in parallel there.

Three things keep this safe:

- **One cap for the whole instance.** The LLM module's `LLMCallGate` is a semaphore every model call
  goes through: gate, classify, intake, Step 2, the chat servlet. Its size is the OSGi configuration
  `IAP LLM - Calls in flight` (`io.uhndata.iap.llm.internal.CallGateImpl`, property `maxCallsInFlight`,
  default 4). A call that waits longer than the provider's timeout for a slot fails instead of
  queueing. Step 2 runs its calls on a pool of that many threads.
- **Writes stay on the handler thread.** The handler's resolver is a JCR session, and a session is
  single-threaded. Only the model calls fan out. Their results are written down in batch order,
  whatever order they finished in, so the call record reads the same on every run.
- **A stuck call is cut off.** Each call may take at most three times the provider's timeout (one
  wait for a slot, one attempt, one retry). The client retries once, not the LangChain4j default of
  twice. Past the cap the call is written down as `failed` and the run goes on without it.

The extraction job queue (`iap-extraction`, topic `iap/extraction/extract`) is capped at 4
submissions at once. The gate already caps the calls; this stops fifteen submissions from all
waiting on it, which would make every recorded duration mostly queue time.

**Why 4.** The provider (prompter) publishes no rate limit. It queues rather than refuses, so past
its limit calls do not fail, they slow down, and the only signal is the read timeout. Four 20k-token
calls in flight is about 80k tokens of prefill at once, a comfortable load for one instance of a
70B-class model. The number has **not yet been measured** against prompter. To measure it:

```bash
PROMPTER_API_KEY=... python3 tools/dev/llm-bench/bench_prompter.py --model GPT-OSS-120B --document proposal.md
```

It fires waves of 1, 2, 4, 6, 8 and 12 identical Step-2-shaped calls and prints the time per call
for each wave. The right cap is the largest wave still clearly cheaper per call than the one before.
Write the result and the model here, and set `maxCallsInFlight` to it. Re-measure when the model or
the provider changes. `durationMs` in the call record shows the same thing in production: if it
climbs while the load does, the cap is too high.

## Not built yet

Stage 1 is complete. What is left is Stage 2 and a few things Stage 1 decided not to do.

- **Chunk summaries.** `sub:Chunk` has a `summary` property that the parse always writes empty, and
  nothing fills it. In cards a background pass summarises each chunk after extraction finishes. It
  is what a chat over the document would route on.
- **A deep retagging pass.** The gate tags a chunk from its heading; the intake corrects the chunks
  it read. A chunk no call ever read in full keeps a heading-level guess. A background pass would
  read those and merge summary, tags and routing into one call per chunk.
- **Chat over the document.** Two calls, a router and an answer. Not built in cards either.
- **More than one document.** Every upload is parsed, but only the first parsed one is read for
  answers. A proposal with satellite files, a consent form or a covering letter, needs the fields
  asked across all of them.
- **The category choosing the schema**, instead of being recorded beside one the submitter already
  chose.

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
| `ProposalGateService` | Decides whether the document is a proposal, which category it is, and tags its chunks, in one call. |
| `CategoryCatalog` | The live leaves of `/Categories` as the model is shown them, and the lookup that reads its answer back. |
| `CategoryPick` | A category the model chose, by path, with its confidence. |
| `ProposalCategoryService` | The second look at the category, over the parts that say what the study does. |
| `ChunkContent` | Reads a chunk's text; derives its headings and token estimate. |
| `ChunkCatalog` | Every chunk with its text and headings, headless chunks inheriting from the one before. Both the gate and the intake read it. |
| `ModelReplies` | Reads a JSON object out of a model reply and its fields, tolerantly. |
| `Prompts` | Loads prompts and schemas from the bundle, cached. |
| `prompts/is_proposal_system.md` | System prompt to answer the `is_proposal` gate question, pick the category and tag the chunks. |
| `prompts/is_proposal_schema.json` | The shape its answer must take. |
| `prompts/classify_system.md` | System prompt for the second look at the category. |
| `prompts/classify_schema.json` | The shape its answer must take. |
| `prompts/protocol_structure.md` | The ICH-GCP rubrics B.1-B.17 the tags come from. Sent whole in the gate's system prompt, about 16k characters of it. |
| `prompts/protocol_structure_glossary.md` | One line per rubric, for the calls that only place a chunk. The intake opens with it. |

### Choosing what to ask, and where

| File | What it does |
| --- | --- |
| `ExtractionField` | One answer to read out of a document, read from a `sch:Question`. |
| `ChunkSelection` | Which chunks could hold an answer, by tag. |
| `FullTextSelection` | Which of those go to the model in full within a token budget: the whole document when it fits, else the candidates in document order; reference lists never. Shared by the intake and the second look. |

### The intake

| File | What it does |
| --- | --- |
| `AnswerIntakeService` | Asks the model every field in one call, checks its quotes, and writes the tags it gave. |
| `IntakePayload` | What the model is shown -- `SCHEMA`, `CATALOG`, `CHUNK` -- which chunks go in full, and the JSON schema its reply must match. |
| `FieldResult` | One field's answer as the model gave it: found or not, confidence, value, reasoning, quotes. |
| `QuoteVerifier` | Whether a quote is really in the text it claims to come from. |
| `prompts/intake_system.md` | System prompt for the intake: the blocks, the evidence rules, the output rules. |

### Running it

| File | What it does |
| --- | --- |
| `ParseDocumentsHandler` | Service task `parseDocuments`: stage every unparsed upload for the daemon and queue its parse. |
| `ParseCompletionHandler` | Takes a finished parse from the documents module and fires `documentParsed` on the submission. |
| `IngestParseHandler` | Service task `ingestParse`: read the parse onto the file, or record that it failed. |
| `QueueExtractionHandler` | Service task `queueExtraction`: once every parse has settled, queue the reading. |
| `ExtractAnswersJobConsumer` | The background job that fires `extractAnswers`. |
| `GateProposalHandler` | Service task `gateProposal`: the gate, its tags, and the verdict and category on the submission. |
| `ClassifyProposalHandler` | Service task `classifyProposal`: the second look at the category. |
| `IntakeAnswersHandler` | Service task `intakeAnswers`: the intake, and the answers, extractions and evidence it writes. |
| `ExtractionStatus` | The `extractionStatus` / `extractionMessage` the view reads, and the states they take. |
| `SubmissionFiles` | A submission's latest uploads, the first one parsed, and whether every parse has settled. |
| `SecondPassHandler` | Service task `secondPass`: ask again for the fields the first pass left open. |
| `SecondPassService` | Runs the Step 2 calls the planner plans, the targeted ones at the same time, and keeps the surer of two answers. |
| `SecondPassPlanner` | Which fields go again, grouped per call, and over which chunks. |
| `LlmCallTracker` | One line per model call: what it asked, what it read, how long it took and how it ended. |
| `RubricTags` | The B.1-B.17 vocabulary, used as a schema enum and as the filter on every reply. |
| `ResponseSchemas` | Closes the gate and classify schemas over the rubrics and the live categories. |
| `ExtractedAnswers` | Writes an answer, its extraction and its evidence. Shared by both passes. |
| `SystemWorkflows/documentParsed(.json, bpmn.xml)` | Read a finished parse in, then queue the reading. Performer: `iap-extraction` only. |
| `SystemWorkflows/extractAnswers(.json, bpmn.xml)` | Gate, second look, intake, completeness. Performer: `everyone`. |
| `src/main/features/feature.json` | Also creates the `iap-extraction` service user, with read on `/Submissions`, and caps the extraction job queue at 4 submissions at once. |

Outside this module: the documents module's `ParseService` (stage and queue a parse for a repository
node) and `ParseOutcomeHandler` (be told how it ended), and the submissions module's
`attachDocument` handler writing the versioned layout and its form servlet serving `extraction`.
