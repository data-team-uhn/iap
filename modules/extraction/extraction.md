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
- **Parse** -- the daemon writes `<stem>.md` and `<stem>.pdf` beside the staged file, and reports
  back how large it measured the document to be. See
  [processing.md](../documents/processing/processing.md).
- **Read the parse in** -- the daemon's callback hands the outcome to `ParseCompletionHandler`,
  which fires `documentParsed` on the submission. That system workflow runs `ingestParse`
  (`ParseResultIngester` copies everything onto the `sub:File`, or records the failure) and
  `queueExtraction`. Once the engine has committed, `ParseCompletionHandler` has the documents
  module clear the volume: until then it holds the only copy of what the parse produced, and a
  delete before the commit would lose the parse for good if the commit failed. The parse job record
  is deleted as soon as the outcome was taken.
  - the size the daemon reported becomes `tokens`
  - `<stem>.md` becomes the `markdownFile` child; `<stem>.pdf` the `pdfFile` child, unless
    the upload is itself a PDF and so already holds it
  - the whole staging folder comes off the volume, not the outputs one by one
- **Whose turn it is to read** -- every parse that lands queues a reading job, and the job decides
  whether its turn has come. It has to be the job: `queueExtraction` runs inside a transaction that
  has not committed, so two parses landing at once could each see the other as still going and
  neither would queue anything, leaving the submission at `running` with nothing to move it on. The
  job's own session sees committed state, so it can answer the question, and it claims the reading
  on the submission (`extractionReadingClaimed`) so two jobs cannot both pay a model to read the
  same document. Sending new documents takes the claim back down.
- **Read the answers** -- a background job fires `extractAnswers` on the submission. That system
  workflow does no reading itself: it has one step, `startWorkflow`, which follows
  `schemaVersion/readingWorkflow` and puts the submission through the workflow its schema names.
  A schema version naming none simply has none. The steps below are that workflow's, and the
  demo's is `/Workflows/readProposal`:
  - **One call for the common questions** (`intakeAnswers`, `requirement: common`) -- the whole
    document goes once. "Is this a research proposal?" (`common/isProposal`) and "What kind of study
    is this?" (`common/category`) are ordinary choice questions in that requirement, asked with the
    rest. `promptFrom` adds `protocol_structure.md` and `is_proposal_system.md` to the system prompt
    for this step only.
  - **Not a proposal writes nothing** -- the same call still asks every common question.
    `recordWhen: common/isProposal=proposal` means those answers are stored only when the verdict
    is `proposal`. Anything else, an unanswered verdict included, leaves the form untouched and
    ends the reading. Completeness is not judged: nothing was written.
  - **Then the questions the category brought in** -- an exclusive gateway on the `common/category`
    answer routes to one `intakeAnswers` step naming the prospective or the retrospective requirement.
    Each answer found becomes a `sub:Answer` with a `sub:Extraction` (value, confidence,
    reasoning, source version) and `sub:Evidence` children (quote, page, heading). A question that
    takes more than one answer is asked for them as one comma-separated string and stored as one
    value per name; the extraction keeps the model's answer as it came, and the form splits it the
    same way when it asks whether the submitter has changed anything (`Answer.splitAnswer`, one rule
    so the two lists cannot disagree). A question that already carries an answer is left alone.
  - **A person answers when the model could not** -- the default arc. A category the model was not
    sure of comes back `found_answer: false` and is not guessed at. Completeness is not judged:
    category is still empty, so `incomplete` cannot come off. The reading is marked finished, so
    the view stops spinning, then waits at `chooseCategory`, a plain user task offered under the
    common questions. The submitter answers the category in the form and completes the task, and
    execution comes back to the gateway. Still unanswered means it waits again.
  - **Completeness** (`markCompleteness`, from the submissions module) -- the `incomplete` tag is
    judged again after the study-type answers have landed. The not-a-proposal and unclassified
    paths skip this.
  - **The end** (`finishReading`) -- moves a still-`running` submission to `done`. A reading can get
    this far having asked nothing, and one waiting for the category passes it too. Without this the
    view would spin for good. A status that already says how the reading ended is left alone.
  - `extractionStatus` ends `done` or `failed`, with a message beside a failure; the view stops the
    spinner and shows the message.
- The reading can be asked for again at any time: `POST <submission>.extractAnswers.json`.
- **Why the reading is a workflow of its own, not steps in the submission's.** A system workflow
  runs straight through with no instance behind it, so it cannot branch, wait or fork. The
  submission's own instance could hold all of that, but it is parked at a user task while the
  reading happens -- the researcher is looking at the form -- and an instance cannot be in two
  places. A message cannot wake a parked token either: the engine's door opens onto a homepage or
  a task, and a free-standing catching event is refused rather than parked (see
  [workflows.md](../../docs/workflows.md)). So the reading gets an instance of its own, started
  for the submission when its parse lands.

## Rules worth knowing

- **Nothing is guessed.** An unreachable model or an unreadable reply records `failed`, and the
  view offers to try again. A question the model is not sure of comes back unanswered, which is
  what the verdict and the category do too: the reading does not pick the likeliest.
- **Options can come from a content path.** A choice question with `optionsFrom` (for example
  `/Categories`) offers what that path holds today instead of child `sch:AnswerOption` nodes. A path
  that documents itself (`AutoDocumentable`) gives its live items, each with a label and a
  description; any other path gives its labeled leaves. The form, the model prompt and the answer
  matching all read the same list, through `Question.getOfferedOptions()`.
- **The category is decided in the common call, not in a call of its own.** It is the same kind
  of judgement as the verdict, from the same material. Each option goes to the model as
  `value (label) -- description`, and a second call would re-pay the whole prefill of the document.
- **Domain knowledge goes only where it is needed.** `promptFrom` on an `intakeAnswers` step names
  extra prompt files from this bundle's `prompts/` folder, put in the system prompt of that step only.
  The ~4k tokens of ICH-GCP reference in `protocol_structure.md` go with the proposal's common call
  and nowhere else.
- **A document too long for one call is cut in the middle, not at the end.** How much text fits is
  worked out per call by `CallBudget`, from the active model's `contextLimitTokens`: the window, less
  a 15% safety margin, less the room the answer is given, less the tokens the prompt itself takes.
  That is the only place the size is decided -- there is no separate maximum to keep in step with it.
  A model that does not say what its window is, or settings that cannot be read at all, fall back to
  the smallest window any model in the catalogue has.

  What does not fit comes out of the middle. The call carries the document's opening and its end,
  joined by `CallBudget.OMISSION_MARKER`, a bracketed line saying in the document's own voice that
  the middle was left out. Three quarters of the room goes to the opening, where the cover page, the
  synopsis and the objectives are; the rest reaches the appendices, the site list and the signature
  page. Cutting at the end alone made exactly those unreadable for the documents most likely to hold
  them. Both cuts move back to a paragraph break, so neither half starts or stops mid-sentence, and
  the marker is there so the model is told it is reading across a gap rather than left to treat the
  join as continuous text -- all three prompts say what it means.

  It is still a real limit worth knowing: an answer that appears only in the middle of a very long
  protocol is not found, and the confidence says nothing about it. The alternative -- sending a
  document the provider refuses outright -- answers nothing at all.

  **A cut says so.** `DocumentBudget` is the one place the three stages work the budget out, fit the
  document to it and log `Document cut to fit` with the stage, the file, and how many characters went
  and how many did not. It is also the one place that answers a prompt leaving no room at all: the
  call is not made, because an intake asking every
  field over nothing can only invent. Written out three times, one stage checked that and two did not.

  The answer's room comes out of the window because a vLLM-style provider refuses a request whose
  prompt plus requested output would not fit; it does not stop generating early. The margin covers
  what the `chars / 4` token estimate undercounts and the tokens the chat template adds.
- **The constant part of a prompt goes first, and stays in the system prompt.** A call and its
  re-ask open with the same system prompt, `promptFrom` files included, so the two share a
  byte-identical opening. Keeping it first and out of the user message is what a hosted provider's
  prefix cache matches on and what a local model reuses its KV cache for. Anything that varies per
  document belongs after it.
- **What the intake is shown**: its instructions in the system prompt, then two blocks in the
  user message. `SCHEMA` is one entry per field -- question, purpose, rules, answer shape,
  whether several values are allowed -- built from the `sch:Question` nodes, so there is no list
  of field names in the code. `DOCUMENT` is the document's Markdown. The questions come first so
  the model reads what it is looking for before the text it looks in.
- **The answer's shape is enforced, not hoped for.** The response schema is built from the same
  fields as `SCHEMA` -- one object per field with `found_answer`, `confidence`, `value`,
  `reasoning`, `evidence[]` -- all closed to extra properties, and handed
  to the client as the JSON schema the reply must match. The model is asked once, and once more
  if the first reply was not readable. `value` is always a string; the field's `responseShape`
  is guidance in `SCHEMA`, not the wire type. A single-choice question's `value` is also closed
  to its allowed values (plus `null`), so a provider that honours the schema cannot answer off-list.
- **A quote has to be in the document.** Every passage is checked against the whole parsed document,
  with case, whitespace and the page markers set aside -- against the whole of it, not against the
  part that fitted in the call. A quote is evidence when it is in the document the submitter
  attached; where the token budget happened to fall is this pipeline's business and says nothing
  about whether the passage is real. The match is fuzzy: a dropped word or a changed hyphen still
  counts, a made-up sentence does not. A quote nobody can find is dropped rather than shown as
  evidence, and each one kept records the heading it sits under. The field's confidence is multiplied
  by how many of its quotes held up, from 1.0 when all did to 0.5 when none did. A field is `found`
  only when the model said so and gave a value.

  **The fuzzy part runs in a window, not over the whole document.** Comparing a quote against a whole
  protocol is quadratic, so it is anchored first: three stretches of the quote -- its opening, its
  middle, its end -- are looked for cheaply, and only a stretch of text around each hit is compared
  properly. A model that changed all three did not copy the passage. A quote longer than 1,000
  normalized characters is only ever checked for exact containment, since a passage that long which
  is not there verbatim was built rather than copied.

  **Both sides are normalized the same way.** A quote is put through `QuoteVerifier.normalize` whole,
  so the document has to come out of `DocumentScan` as that same function would leave it. It did not:
  a blank line contributed a separator of its own, so the document carried two spaces wherever it had
  a paragraph break while the quote carried one, and no quote reaching across a break could be found
  however faithfully it had been copied. A long one was dropped outright, since past 1,000 characters
  only exact containment is tried. The tests drive `DocumentScan`, not the verifier, for that reason:
  asking the verifier directly compares against a document prepared some other way, and the two can
  disagree with nothing to notice it.

  The document is normalized **once per reading**, not once per quote: normalizing means two regular
  expressions and a lowercasing over the whole of it, and a reading with fifteen fields and three
  quotes each used to walk an 800 kB document about ninety times. `ParsedDocuments` keeps the most
  recent one, keyed by the stored file and when it was last written, so the four steps that want the
  same Markdown read it once between them and a re-parse is never served the text it replaced.
- **The intake does not guess either.** Nothing to read, or a reply that cannot be read twice
  over, gives a `degraded` result: every field unanswered. A model that cannot be reached is an
  `IOException` to the caller. Neither is ever turned into an answer.

- **Extraction is content, not code.** `documentParsed` and `extractAnswers` are `/SystemWorkflows`
  definitions shipped by this module, and the handlers are the engine's extension point: a
  deployment adds a step or drops one by editing the definition. Branching on what was read
  happens in the reading workflow, with gateways on the answers (`source: answer`).
- **Who may fire what.** `documentParsed` names this module's service user, `iap-extraction`, as
  its only performer: its payload is paths on the shared volume, and reading files from there
  into the repository is nobody else's to ask for. `extractAnswers` admits `everyone`; it reads
  nothing but the repository, and a submitter may well want to ask for it again.
- **Nothing here commits, with one exception.** The ingester and every handler write through the
  engine's session, and the engine commits the whole run at once, so a submission never shows half a
  parse or half a reading. The exception is `parseDocuments`: staging writes to the shared volume and
  queueing commits a job node through a session of its own, both before the walk that asked for them
  has committed. It has to be that way round -- the job node must be visible to the consumer before
  the Sling job is queued -- and it is why a failure there discards what it staged, rather than
  leaving a folder on the volume that no job node names and no sweep can find.
- **A reading that fails outright still says so.** Everything above is about a model that answered
  badly. A genuine failure -- a malformed definition, a repository that refuses -- throws, and the
  engine reverts the whole walk, which leaves the `running` the parse step committed earlier standing
  and the reading claimed by a job that is about to end. `ExtractAnswersJobConsumer` writes `failed`
  and takes the claim back down in a commit of its own before letting the failure out, so the view
  stops spinning and a later parse, or somebody asking again, can take the reading. Without it that
  submission could never be read again by anything.
- **A model that cannot be reached is recorded, not thrown.** Because the engine commits the whole
  run at once, it also reverts the whole run when a handler throws -- and the `running` the parse step
  committed earlier survives that revert. Throwing on a timeout or a 503 would therefore leave the
  submission at `running` with no step left to move it on, and the view blocking sending for good. So
  a failed call records `failed` and returns; only a genuine repository failure throws.

## What a reading leaves in the log

Every step a reading passes through logs one line at `INFO`, so a slow reading can be accounted for
without instrumenting anything. In order, for one submission:

| Line | Where | What it carries |
| --- | --- | --- |
| `Parse staged` / `Parse queued` | documents | the staged path, the bytes, the job id; this is T0 |
| `Document sent to be parsed` | `ParseDocumentsHandler` | the `sub:File`, the job and the file name |
| `Reading started` | `ParseDocumentsHandler` | the submission and how many parses are outstanding |
| `Parse dispatched` | documents | how long the daemon took to accept the work, not to do it |
| `parse job=... done` | the daemon | `waitMs` for the parse slot, `parseMs` for the conversion |
| `Parse completed` / `Parse failed` | documents | `parseMs` measured from the dispatch, and `tokens` |
| `Parse came back` | `ParseCompletionHandler` | the job, the file, the submission, the size |
| `Parse read in` | `ParseResultIngester` | the bytes stored and the milliseconds to store them |
| `Reading queued` | `QueueExtractionHandler` | the submission, once every parse has settled |
| `Reading run started` / `Reading run done` | `ExtractAnswersJobConsumer` | brackets the whole reading |
| `Reading step started` / `done` | each handler | `step=intake` and the requirement, with `ms` |
| `Document cut to fit` | `DocumentBudget` | the stage, the file, and how much of it went and did not |
| `LLM request served` | `OpenAIClient` | the model, `gateWaitMs` and `callMs`, split |
| `LLM call done` | `DefaultLLMClient` | the stage, the prompt size, the milliseconds, the reply size |

The two LLM lines are split on purpose. The call gate holds calls to one at a time, so a slow reading
is usually a reading that waited, and one total would not say which of the two it was. The stage is
the response schema's name -- `iap_intake` -- and the step line beside it says which requirement
the call was for.

Nothing here is measured from the job records: a job queued for a node is deleted as soon as its
outcome is taken, so the log is the only place a finished parse survives. A job queued without one --
the polling endpoint's -- keeps its record until `StaleParseJobSweeper` drops it, together with the
staging folder it worked in, once it has been settled longer than a poller would wait. So does the
record of a job whose handler refused the outcome. Neither used to be dropped at all: the sweep only
looked at jobs that had not finished, so both the node and the folder stayed for good.

## Calls in flight

Every model call in the system goes through the LLM module's `LLMCallGate`, which holds them to **one
at a time**. There is nothing to configure: the provider serves a proposal-sized call on its own
whatever else is asked of it, so one is the only number worth sending.

Measured against prompter on 2026-09-21, model `Qwen3.8-27B`, 200,000-token prompts, each call
carrying a document the provider had not seen:

| calls at once | wall (s) | per call (s) |
| --- | --- | --- |
| 1 | 39.3 | 39.3 |
| 2 | 77.3 | 38.6 |
| 4 | 152.8 | 38.2 |
| 6 | 228.4 | 38.1 |
| 8 | 304.0 | 38.0 |
| 12 | 455.1 | 37.9 |

Nothing failed, and the wall time is flatly linear in the number of calls. The provider does not
refuse the extra requests, it queues them, so sending more than one buys no throughput: it only parks
calls in that queue where they spend the 600s `timeoutSeconds` waiting. The last call of the wave of
12 came back after 455s, which is most of that budget. Waiting on the gate instead keeps the wait
where the caller can see it.

A caller waits three calls' worth of `timeoutSeconds` for a slot, not one. Waiting exactly as long as
a call may take means giving up at the moment the slot is about to come free, and for a reading giving
up is a failure the submitter sees, for no reason but that another submission was being read.

**If this is ever measured again, beware the prefix cache.** The same run with one identical body sent
to every call reported 3.8s per call and looked like a cap of 8 was right. That was the cache
answering, not the model working: a document the provider had not seen took 47.4s against 3.8s for a
repeat of one it had. Any such measurement has to give every call its own document.

Extraction makes one call per requirement it reads -- common, then one of the study types -- so the
one-at-a-time rule bites across submissions rather than within one. At about 39s a call, ten queued
proposals mean the last waits some six and a half minutes. The extraction job queue
(`iap-extraction`, topic `iap/extraction/extract`) runs **one submission at a time**, matching the
gate. A wider queue reads nothing sooner, because the gate holds the calls to one whatever the queue
says; all it does is move the waiting from the job queue, where a job waits as long as it takes, to
the gate, where a job waits against `timeoutSeconds` and fails when it runs out.

## Not built yet

Stage 1 is complete. What is left is Stage 2 and a few things Stage 1 decided not to do.

- **Anything past the token budget.** A document longer than what is left of the active model's
  context window keeps its opening and its end, and what was in the middle is never read. Covering a
  very long protocol whole would mean splitting it again, which is what this pipeline deliberately
  does not do.
- **Chat over the document.** Two calls, a router and an answer. Not built in cards either.
- **More than one document.** Every upload is parsed, but only the first parsed one is read for
  answers. A proposal with satellite files, a consent form or a covering letter, needs the fields
  asked across all of them.
- **The category choosing the schema**, instead of being recorded beside one the submitter already
  chose.
- **A better answer than asking, when the model cannot place a study.** Asking the submitter is the
  right thing to do and not a cheap one: the reading waits, and it only goes on when somebody
  answers the category and completes the step.

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
| `ParseResultIngester` | Copies a finished parse onto the `sub:File` node, then has the volume cleared. |
| `ParsePropertyNames` | The names shared between the daemon's JSON and JCR. |
| `ParsedDocuments` | The parsed text of a file, read and prepared once per reading rather than per step. |
| `DocumentScan` | A document ready to have quotes checked against it: normalized once, with its headings. |
| `DocumentBudget` | How much of a document one stage sends, and the log line when it is not all of it. |

### Shared by every call

| File | What it does |
| --- | --- |
| `DocumentText` | Reads the parsed text, sizes it, and finds the heading a quote sits under. |
| `ModelReplies` | Reads a JSON object out of a model reply and its fields, tolerantly. |
| `ModelCall` | Ask once, and once more with a correction when the answer could not be read. |
| `Prompts` | Loads prompts from the bundle, cached. |
| `prompts/is_proposal_system.md` | Extra system prompt, named by `promptFrom`, for judging what a research proposal is and what kind of study it describes. |
| `prompts/protocol_structure.md` | The ICH-GCP rubrics B.1-B.17 a proposal's sections map onto. Named by `promptFrom` on the proposal's common call, about 16k characters of it. |

### Choosing what to ask, and where

| File | What it does |
| --- | --- |
| `ExtractionField` | One answer to read out of a document, read from a `sch:Question`. |
| `ExtractionFields` | Which questions a step asks, how they are keyed, and what becomes of the answers. |

### The intake

| File | What it does |
| --- | --- |
| `AnswerIntakeService` | Asks the model every field in one call over the whole document, and checks its quotes. |
| `IntakePayload` | What the model is shown -- `SCHEMA`, then `DOCUMENT` -- and the JSON schema its reply must match. |
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
| `FinishReadingHandler` | Service task `finishReading`: moves a still-`running` submission to `done`. |
| `ReviewExtractionHandler` | Service task `reviewExtraction`: the submitter's verdict on a suggestion. |
| `IntakeAnswersHandler` | Service task `intakeAnswers`: the intake for one requirement (`requirement`), over the named documents (`documents`), with extra system prompt files (`promptFrom`). `recordWhen` (`path=value`) writes the answers only when that field came back as the value. |
| `ExtractionStatus` | The `extractionStatus` / `extractionMessage` the view reads, and the states they take. |
| `SubmissionFiles` | A submission's latest uploads, the first one parsed, and whether every parse has settled. |
| `ExtractedAnswers` | Writes an answer, its extraction and its evidence. |
| `SystemWorkflows/documentParsed(.json, bpmn.xml)` | Read a finished parse in, then queue the reading. Performer: `iap-extraction` only. |
| `SystemWorkflows/extractAnswers(.json, bpmn.xml)` | Start the reading workflow the schema names. Performer: `everyone`. |
| `SystemWorkflows/reviewExtraction(.json, bpmn.xml)` | Record what the submitter made of a pre-filled answer. |
| `src/main/features/feature.json` | Also creates the `iap-extraction` service user, with read on `/Submissions`, and runs the extraction job queue one submission at a time. |

Outside this module: the documents module's `ParseService` (stage and queue a parse for a repository
node) and `ParseOutcomeHandler` (be told how it ended), and the submissions module's
`attachDocument` handler writing the versioned layout and its form servlet serving `extraction`.
