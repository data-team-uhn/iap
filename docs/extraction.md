# Answer extraction

**Module:** `modules/extraction` · **Bundle:** `iap-extraction` · **Package:**
`…extraction.internal` (nothing exported). **Leans on:** `iap-documents-api` (parsing),
`iap-llm` (the model), `iap-workflows` (the engine), `iap-submissions-api` and
`iap-schemas-api` (the models it reads and writes).

A submitter uploads a document, such as a research protocol. The platform turns it into
text, asks a language model the questions the schema marks as answerable from a
document, checks every quote the model gives against the text, and saves what it found
as pre-filled answers. The submitter then confirms, changes or ignores each one. Nothing
the model says is trusted on its own: an answer needs a quote that is really in the
document, and a question the model is unsure about stays for a person to answer.

This page walks the whole path, from the upload to the saved answer, and says at each
step which class does the work, who calls it, and what it checks.

## The whole path

```
 submitter uploads a file
        │
        ▼
 parseDocuments ──── stage the file on the shared volume, queue a parse job
 (service task)       file: parseStatus=queued     submission: extractionStatus=running
        │
        ▼
 Docling daemon ──── converts the file to Markdown (+ a PDF for office files)
 (Python, port 18765) POSTs the outcome to /system/documents/parseCallback
        │
        ▼
 documents module ── records the outcome on the job node, hands it to every
 ParseOutcomeDispatcher ParseOutcomeHandler
        │
        ▼
 ParseCompletionHandler ── fires `documentParsed` on the submission
        │
        ▼
 documentParsed (system workflow)
   ingestParse ─────── copy the Markdown and PDF onto the file node
   queueExtraction ─── add a Sling job on topic iap/extraction/extract
        │
        ▼  (engine commits, then the staging folder is deleted)
 ExtractAnswersJobConsumer (background, one at a time)
   every parse settled? ── no: try again later
   claim the reading ───── another job has it: stop
   fire `extractAnswers`
        │
        ▼
 extractAnswers (system workflow)
   startWorkflow ── follow schemaVersion/readingWorkflow, cancel an older
                    reading still open, start the schema's reading workflow
        │
        ▼
 the reading workflow (content, one per schema; for example readProposal)
   classifyDocument ── the gate: what kind of document is this?
   gateways ────────── go on only on a settled pick
   intakeAnswers ───── ask every remaining question in one model call
   finishReading ───── extractionStatus=done
```

Everything from `extractAnswers` down to the end of the reading workflow runs inside one
engine walk, on the job's thread, and is saved in one commit. Either the whole reading
lands, or none of it does.

## Step 1: sending a document to be parsed

**Class:** `ParseDocumentsHandler` · **Handler name:** `parseDocuments` · **Called by:**
the `parse` step of the submissions module's `attachDocument` system workflow, and by
the `retryParse` system workflow.

What it does, in order:

1. Checks the caller with `SubmitterAccess.checkMayChange`: only the person who raised
   the submission, and only while it is a draft. Anyone else gets a 403, a submitted
   request gets a 409.
2. Reads `readingWorkflow` on the submission's schema version. **No reading workflow
   means nothing is parsed**, so a schema that never reads its documents costs nothing.
3. For each document, takes the latest upload (`SubmissionFiles.currentFiles`). An
   upload is sent when its `parseStatus` is empty (never sent) or `failed`. One that is
   `queued` or `completed` is left alone.
4. For each upload sent:
   - `ParseService.stage` copies the bytes into a new folder on the shared volume,
     `/shared-docs/<uuid>/<name>`.
   - `ParseService.queue` creates a job node under `/var/documents/jobs/<jobId>` and a
     Sling job on topic `iap/documents/parse`. The job names the `sub:File` as its
     `target`.
   - The file records `parseStatus=queued`, `parseJobId`, `sharedPath`, and loses any
     old `parseError`.
   - If queueing fails, the staged folder is deleted at once. No job node names it, so
     no sweep would ever find it later.
5. If at least one upload was sent: the submission gets `extractionStatus=running`, and
   `extractionReadingClaimed` is removed, since a new document is a new reading.

Staging and queueing happen before the engine commits. That is on purpose: the job node
has to exist before the Sling job runs.

## Step 2: the daemon parses it

**Code:** `modules/documents/processing` (Python). **Called by:** `ParseJobConsumer` in
the documents module, which sends `POST /parse?path=&job_id=` and moves the job node
from `queued` to `active`.

The daemon answers "queued" at once and converts in the background. One conversion runs
at a time (`MAX_CONCURRENT_PARSES` = 1). It writes `<stem>.md` beside the staged file,
plus `<stem>.pdf` when LibreOffice made a PDF out of an office file. Then it POSTs the
outcome to `/system/documents/parseCallback`, with the shared token from
`IAP_DOCLING_CALLBACK_JWT`. See
[processing.md](../modules/documents/processing/processing.md) for the conversion
itself.

The daemon also serves `POST /cancel?job_id=`, used by Stop (see "What the submitter can
do"):

| State of the parse | What `/cancel` does | Reply `status` |
| --- | --- | --- |
| Waiting in the daemon's queue | Drops it; it never starts | `cancelled` |
| Converting | Flags it. The PDF loop checks the flag between page batches (`ABANDON_POLL_SECONDS` = 2.0) and gives up. No callback is sent. A DOCX converts in one call, so it cannot be cut short. | `running` |
| Not known to this daemon | Nothing | `unknown` |

## Step 3: the parse comes back

**Class:** `ParseCallbackServlet` (documents module). It checks the bearer token,
records the outcome on the job node (`completed` with outputs, or `failed` with an
error), commits, then calls `ParseOutcomeDispatcher.settle`.

`settle` hands the outcome to every registered `ParseOutcomeHandler`. If one of them
takes it, the job node is deleted. If none does, the node stays.

The same `settle` is also called by:

| Caller | When |
| --- | --- |
| `ParseJobConsumer` | The daemon refused the dispatch or could not be reached. The job is marked failed. |
| `StaleParseJobSweeper` | A job got no answer within `maxAgeMinutes` (30). It is marked failed: "No outcome arrived within 30 minutes; the parse was given up on". |
| `StaleParseJobSweeper` | A job settled at least 2 minutes ago (`RETRY_AFTER_MILLISECONDS`) and no handler took it. It is offered again on every sweep. |

The sweep runs every 5 minutes (`DEFAULT_PERIOD_SECONDS` = 300), on the cluster leader
only. A settled record older than 30 minutes that still nobody took is deleted with its
staging folder.

When a job node vanished while the callback was writing it (a Stop deleted it), the
callback answers 404, the same as for an unknown job.

## Step 4: reading the parse into the repository

**Class:** `ParseCompletionHandler` (a `ParseOutcomeHandler`). **Called by:**
`ParseOutcomeDispatcher.settle`.

It opens a session as the `iap-extraction` service user and checks:

| Check | If it fails |
| --- | --- |
| The `target` file still exists and sits under a submission | Returns "not taken". The file may simply not be committed yet; the sweep offers it again. |
| The file's `parseJobId` equals this outcome's job id | Returns "not taken". This is an old parse the file no longer waits for. |
| The file's `parseStatus` is still `queued` | Returns "taken" without doing anything. This is the same outcome delivered twice. |

Then it fires `documentParsed` on the submission, as the `iap-extraction` user. The
payload carries the file path, `succeeded`, `error`, `markdown`, `tokens`, and `pdf`
when a `<stem>.pdf` exists beside the Markdown.

The `documentParsed` system workflow runs two steps:

1. **`ingestParse`** (`IngestParseHandler`, using `ParseResultIngester`).
   - On a failed parse: the file gets `parseStatus=failed` and `parseError` (or "The
     parse failed without details").
   - On a success: the Markdown and the PDF are copied onto the file node. Details
     below.
2. **`queueExtraction`** (`QueueExtractionHandler`): adds a Sling job on
   `iap/extraction/extract` naming the submission. It does this for failed parses too,
   so the reading can report the failure.

Only after the engine has committed does `ParseCompletionHandler` ask the documents
module to delete the staging folder (`ParseService.discardStaging`). Until that commit,
the volume holds the only copy of the parse.

What `ParseResultIngester.ingest` checks and writes:

| Check | Result |
| --- | --- |
| The Markdown path is on the shared volume (`ParseService.isStagedPath`) | If not: `parseStatus=failed`, "The parse named …, which is not on the shared volume". A file outside the volume is never read. |
| The Markdown file exists | If not: `parseStatus=failed`, "The parse finished without naming the text it produced" (or the same "not on the shared volume" message). |
| The PDF path is on the shared volume | If not: the PDF is skipped and a warning logged. |
| The upload is itself a PDF (`uploadedFile/jcr:content/jcr:mimeType`) | The PDF is not stored a second time. `File.getFilePdf()` falls back to the upload. |

On success the file gets `parseStatus=completed`, `tokens` (the daemon's estimate), a
`markdownFile` child (`text/markdown`) and, for office uploads, a `pdfFile` child
(`application/pdf`). A re-parse deletes the old child before writing the new one. Files
are streamed into the repository, not loaded into memory first. Anything else the
pipeline left on the volume (a `.docx` made from a `.doc`, temp files) is deleted with
the folder.

## Step 5: whose turn it is to read

**Class:** `ExtractAnswersJobConsumer` · **Job topic:** `iap/extraction/extract` ·
**Queue:** `iap-extraction`, one job at a time (`queue.maxparallel` = 1).

Every parse that lands queues a job, so a submission with three uploads queues three.
The job, not the workflow step, decides who reads. A workflow step runs before its
commit and cannot see what other requests are doing; the job's own session sees
committed state.

| Check | If it fails |
| --- | --- |
| The job names a submission, and it still exists | The job is dropped. |
| No current file is still `queued` (`SubmissionFiles.allParsesSettled`) | The job returns `FAILED`, so Sling runs it again later. A parse that landed but has not committed yet looks "queued" for a moment. |
| The schema version's `readingWorkflow` points at a `wf:WorkflowVersion` that exists | `extractionStatus=failed`, "Nothing is set up to read this request's documents". Fired anyway, the reading would start nothing and leave `running` for good. |
| `extractionReadingClaimed` is not set, and setting it (with a value of the job's own in `extractionReadingClaimedBy`) commits | Another job has the reading; this one is dropped. Two jobs that race write different values, and the repository refuses the second commit. |

Then it fires `extractAnswers` on the submission. If that throws, the job "gives up": in
a commit of its own it writes `extractionStatus=failed`, "The document could not be
read", and removes the claim. Without that, the submission would stay at `running`
forever.

`ReadingRuns.begin` registers the job's thread under the submission path, so Stop can
interrupt it (see "Stop" below).

## Step 6: starting the schema's reading workflow

**System workflow:** `extractAnswers`. One step: the built-in `startWorkflow` handler
with `workflowFrom: schemaVersion/readingWorkflow` and `replaceActive: true`.

- It follows the submission's `schemaVersion` reference, then the schema version's
  `readingWorkflow` reference, to a `wf:WorkflowVersion`. If the chain does not end at a
  workflow version, nothing happens.
- `replaceActive` cancels any instance of the same workflow still active on the
  submission (any version of it), with its open tasks, before starting the new one. A
  reading that was waiting for the submitter to confirm a category is replaced, not run
  twice.
- The reading workflow is a normal content workflow with a persisted instance, so it can
  branch and wait for a person. A system workflow cannot do either, which is why the
  reading is not written into `extractAnswers` itself.

Only the `iap-extraction` user may fire `documentParsed` and `extractAnswers`: their
start events name it as the only performer, and administrators, who pass every performer
check. The payload of `documentParsed` carries paths on the shared volume, and no
ordinary user should be able to have files read from there.

## Step 7: the gate (classifying the document)

**Class:** `ClassifyDocumentHandler` · **Handler name:** `classifyDocument` · **Schema
type:** `sch:ClassificationRequirement`.

A classification requirement is a form requirement with one question (found by walking
its children, sections included). The question's options are the categories to pick
from. It also carries:

| Property | Meaning | Default |
| --- | --- | --- |
| `prompt` | What the model must decide, and how to tell | required |
| `document` | The name of the document requirement whose upload is classified | required |
| `confidenceThreshold` | Below this, the pick waits for the submitter | 0.7 |
| `template` (or `template.md`) child | Reference text shown to the model, for example the usual structure of a research protocol | none |

What the step does:

1. Collects the "open" classifications. It skips one whose condition does not hold, one
   with no question, and one whose question already has an answer (same rule as the
   intake below: an empty field the form saved does not count). A second upload does not
   pay again for a decision already made or confirmed.
2. Groups them by `document`. For each document:
   - No upload: skipped.
   - Parse `failed`: `extractionStatus=failed` with the parse error, and the step stops.
   - Parse not `completed` yet: skipped.
   - Otherwise one model call for all of that document's classifications.
3. The call goes through `AnswerIntakeService`, the same code as the intake (step 8).
   Each template is added in front of the system prompt under `# Reference for
   "<label>"`. Each field's rules are the requirement's `prompt`, followed by the list
   of allowed values.
4. The pick is written like any extracted answer (see "Writing an answer"): pre-filled,
   with confidence, reasoning and quotes.
5. If the model could not be asked, or answered unreadably twice:
   `extractionStatus=failed` and the step stops.

Whether the workflow acts on the pick is not this step's call. The reading workflow's
gateways decide, through the `decision` condition source, which counts an answer only
once it is settled: confirmed or typed by the submitter, or picked by the model at or
above `confidenceThreshold`. Below that, the reading waits at a user task until the
submitter confirms.

## Step 8: reading the answers (the intake)

**Class:** `IntakeAnswersHandler` · **Handler name:** `intakeAnswers`.

Activity properties a reading workflow can set on this step:

| Property | Meaning | When absent |
| --- | --- | --- |
| `requirement` | One requirement name, or several (a list, or comma-separated). All are asked in one call, in that order. | Every question the schema asks |
| `documents` | Document requirement names to read, in order. Each one's text goes under its own heading (`# Preamble` for `preamble`), and together they are one document to the model. | The first upload with a completed parse |
| `promptFrom` | Extra system-prompt files from the bundle's `/prompts` folder, added in front of the intake prompt | No extra prompt |
| `recordWhen` | `path=value`. The model is still asked everything, but answers are only saved when that field holds that value: the model's reply matched to the question's options, ignoring case, or, when the field already had an answer and was not asked again, that answer. | Always save |

What the step does:

1. **Which documents.** With `documents`: each named one with a completed parse is read.
   A named one never uploaded is skipped. A named one whose parse failed fails the step
   with its parse error. If none is left: fail with the reason (`whyNothingToRead`).
   Without `documents`: the first completed upload, or fail with the reason.
2. **Which questions.** Questions of the named requirements that apply (conditions
   resolved), have an `extractionPrompt`, and have **no answer yet**. A question that
   holds a value, or that the model already suggested an answer for, is never asked
   again: reading again fills gaps, it never overwrites. An empty answer the form saved
   (a field somebody clicked through) is a gap; a suggestion the submitter cleared is
   not. Questions are keyed by their path inside the schema version, so two sections may
   both have a `title`.
3. **Nothing to ask.** If every question already has an answer: `done`. If the whole
   schema asks nothing: `done`, "The schema asks nothing of the document". If a named
   requirement asks nothing (its condition did not hold): no status change.
4. **Ask** the model (see the next section).
5. **Unreadable or unreachable:** `extractionStatus=failed` with the reason, and nothing
   is written.
6. **`recordWhen` does not hold:** nothing is written, `extractionStatus=done`.
7. **Otherwise** every field the model found is written (see "Writing an answer"), and
   `extractionStatus=done`.

A model that cannot be reached is not thrown as a workflow error. Throwing would roll
back the whole walk and leave the committed `running` behind with nothing to move it on.

## Step 9: ending the reading

**Class:** `FinishReadingHandler` · **Handler name:** `finishReading`.

Moves `extractionStatus` from `running` to `done`, with the step's `message` if it has
one (for example to say the reading waits for the submitter). Any other status is left
alone, because a step that already wrote `failed` said something this one does not know.
A reading workflow puts this at the end of every path, so the view never spins forever.

## Asking the model

**Classes:** `AnswerIntakeService`, `IntakePayload`, `ModelCall`, `ModelReplies`,
`DocumentBudget`, `Prompts`. Used by both the gate and the intake.

### What is sent

- **System prompt:** the step's extra text (`promptFrom` files, or classification
  templates), then `prompts/intake_system.md`. That file sets the rules: the document is
  untrusted data and instructions inside it are ignored; every quote must be verbatim
  and must not cross a `<!-- page: N -->` marker; no quote means `found_answer=false`;
  every field must be answered.
- **User message:** a `## SCHEMA` block, then `## DOCUMENT (untrusted data)`, then the
  document text. The questions come first, so the model knows what to look for before it
  reads. Each field lists `question`, `purpose`, `rules` (the `extractionPrompt`, plus
  the allowed values as `value (label) -- description`), `answer shape`
  (`responseShape`), and "multiple values: yes, as one comma-separated string" when
  `maxAnswers` is not 1.
- **Reply schema** (strict JSON schema, name `iap_intake`): one object per field, each
  with `found_answer` (boolean), `value` (string or null), `confidence` (number),
  `reasoning`, and `evidence` (a list of `{quote, page}`). No extra keys are allowed. A
  single-choice field limits `value` to its option values plus null. A multi-choice
  field cannot, since "a, b" is not itself an option.

### How much of the document fits

`maxOutputTokens` = 500 + 400 per field. The document gets what is left of the model's
context window (`CallBudget.calculateDocumentTokenBudget`):

```
document budget = window − 15% safety margin − maxOutputTokens − (system prompt + question block)
```

The window comes from the LLM settings, or 131072 tokens when they do not say. Tokens
are estimated at 4 characters each. A document that does not fit keeps its opening (75%
of the room) and its end (25%), with a note in the middle saying the middle was left
out; a log line says how much was cut. If no room is left at all, the model is not
asked: the step fails with "The questions leave no room to show the model the document".
A blank document fails with "The document has no text that can be read".

### Two attempts, no more

`ModelCall.askWithCorrection` sends the call. If the reply holds no readable JSON object
(`ModelReplies.readJsonObject` takes the text between the first `{` and the last `}`),
it asks once more with a `# Correction` section saying what was wrong: empty, no JSON,
never closed (cut off), or not one object with a key per field. A second bad reply means
"degraded": nothing is saved, and the step fails with "The model's answer could not be
read". Each attempt resends the whole document, so a third would pay for it a third
time.

### One call at a time

The LLM module allows one model call at a time across the instance (`CallGateImpl`, a
fair semaphore with one slot). The extraction queue is also one job at a time. A wider
queue would not read anything sooner; it would only move the waiting from the queue,
where a job waits as long as needed, to the gate, where a call fails when its timeout
runs out.

## The checks that keep answers honest

| Rule | Where | What happens |
| --- | --- | --- |
| A question without an `extractionPrompt` is never sent | `ExtractionField.isExtractable` | Questions meant only for people stay out of the model's way |
| A field with no `value` is "not found", whatever `found_answer` says | `AnswerIntakeService.readField` | Nothing is written for it |
| A quote must be really in the document | `QuoteVerifier` via `DocumentScan.locate` | Quotes that are not found are dropped, never stored |
| Quotes shorter than 6 characters do not count | `MIN_QUOTE_LENGTH` = 6 | Too easy to match by chance |
| A near-match must be at least 85% right | `MATCH_FLOOR` = 0.85 | Edit distance against the best-matching stretch |
| Numbers and negations must agree exactly | `NEGATIONS` (not, no, never, without, none, nor, neither, cannot) | "was not approved" never matches "was approved" |
| A quote may not join two documents sent together | `isAcrossParts` | Those words were never next to each other |
| Confidence drops when quotes fail | `getEvidenceFactor` | See below |
| Confidence is held to 0–1 | `ModelReplies.readConfidence` | Anything else, or a missing value, reads as 0 |
| A question that already has an answer is not overwritten | `ExtractionFields.getAnswered` | Reading again fills gaps only. An answer counts when it holds a value or has an extraction under it, so a suggestion the submitter cleared stays cleared. An empty answer with no extraction is the form saving a field somebody clicked through, and is filled. |

How a quote is matched (`QuoteVerifier`): both texts are lower-cased, page markers
removed, whitespace collapsed. An exact match wins. Otherwise, for quotes longer than 24
characters (`ANCHOR_LENGTH`) and at most 1000 (`MAX_FUZZY_QUOTE`), it looks for the
quote's first, middle and last 24 characters in the text, takes up to 4 candidate places
(`MAX_CANDIDATES`, at most 2 per anchor), and finds the stretch of nearby text with the
fewest edits to the quote. The best stretch must reach 0.85 and agree on numbers and
negations. A longer quote has to be there exactly.

How confidence is adjusted: for a found answer, `confidence × (0.5 + 0.5 × kept quotes ÷
distinct quotes offered)`. All quotes found keeps it, half found takes off a quarter,
none found (or no quotes at all) halves it (`UNVERIFIED_PENALTY` = 0.5). The answer can
still be right, so it is not set to zero.

Each kept quote is placed under the nearest Markdown heading above it (`DocumentScan`,
lines matching `#` to `######`), so the reviewer can see which section it came from.

## Writing an answer

**Classes:** `ExtractionFields.write`, `ExtractedAnswers.write`. Every found field gets:

```
sub:Submission
└── <uuid> (sub:Answer)
    ├── question  → REFERENCE to the sch:Question
    ├── value     = the values (see below)
    └── <uuid> (sub:Extraction)
        ├── extractedAnswer = the model's value, as it came
        ├── confidence      = after the evidence adjustment
        ├── reasoning
        ├── sources         → REFERENCE to every sub:DocumentVersion read
        └── <uuid> (sub:Evidence), one per kept quote
            ├── quote, header, page
            └── source → REFERENCE to the version it came from (only when several were read)
```

How the value is stored (`Answer.readAnswer`):

- A single-value question keeps the reply as one value, trimmed.
- A multi-value question splits the reply on commas.
- For a question with options, each part is matched to an option, first by value, then
  by label, ignoring case. "English" becomes `english`; a category label becomes its
  path, such as `/Categories/...`. If no option matches, or two options match equally,
  the model's own words are kept, so a person can see what it said and fix it.

The form uses the same rule to show the suggestion (`Answer.getSuggestedValues`), so an
answer nobody touched never looks "changed".

The answer is written straight into `value`, so the form shows it pre-filled.
`sub:Extraction.reviewed` tells a suggestion nobody has looked at from one the submitter
settled. When the form already saved an empty answer for the question, that node is
filled rather than a second one created; if its extraction cannot be written, its value
is put back as it was. Each field is written on its own: one that fails to write (for
example its source file vanished) is logged and skipped, and the others are still saved.

## What the submitter can do

Each of these is a system workflow on `sub/Submission`, fired with `POST
<submission>.<event>.json` (see "Events over HTTP" in [workflows.md](workflows.md)).
Their start events admit `everyone`, and then the handler checks `SubmitterAccess`: only
the person who raised the submission, only while it is a draft. An administrator who is
not the submitter is refused too.

| Event | Handler | What it does |
| --- | --- | --- |
| `stopProcessing` | `StopProcessingHandler` | Stops a parse or a reading that is going |
| `readAgain` | `ReadAgainHandler` | Runs the reading again over the documents already parsed |
| `retryParse` | `ParseDocumentsHandler` | Sends uploads whose parse failed to be parsed again |
| `reviewExtraction` | `ReviewExtractionHandler` | Records what the submitter thinks of a pre-filled answer |

### Stop

1. If `extractionStatus` is not `running`, nothing happens. A late Stop must not undo a
   finished reading.
2. If a job thread is reading this submission (`ReadingRuns`), it is interrupted. The
   model call checks for that before and after each request (`ModelCall.chat`), so a
   reply that arrives after a Stop is thrown away.
3. Readings still waiting in the job queue for this submission are removed.
4. For each current file:
   - Parse `queued`: `ParseControl.abandon` tells the daemon `/cancel`, deletes the job
     node and wipes the staging folder. If the daemon already finished, the record is
     left for its callback, and the folder is not touched. The file gets
     `parseStatus=failed`, "Stopped before the document was read", and loses
     `parseJobId`, `sharedPath` and `tokens`.
   - Parse `completed` and a model call on this instance was reading it (the step named
     it in `ReadingRuns`): its `markdownFile` is deleted and the file is marked "Stopped
     while the document was being read". The next try parses it again rather than
     reading text the submitter just threw away. A claim with no reading thread is left
     over from a reading that ended or a restart, and the text is kept.
5. The submission gets `extractionStatus=failed`, "Reading was stopped.", and the claim
   is removed. The cached document text (`ParsedDocuments`) is dropped.

The interrupted job sees the stop, writes the same "Reading was stopped." status in its
own commit, and ends.

### Read again

Refused with a 409 while `extractionStatus` is `running` ("This request is already being
read"). Otherwise it sets `running`, removes the claim, and queues the same job a parse
queues (step 5). Questions already answered are not asked again.

### Retry a failed parse

The same `parseDocuments` step as an upload. Only uploads with no `parseStatus` or with
`failed` are sent. Usually the daemon was down, not the document broken.

### Review a pre-filled answer

Payload: `question` (the path inside the schema version, as the save endpoint uses), and
either or both of `confirmed` and `evidenceRejected` (`true`/`false`, as booleans or
strings). It finds the run the form showed, `Answer.getSurestExtraction()` (the highest
confidence; on a tie, the earliest), and:

- `confirmed=true`: sets `reviewed=true` and `confidence=1.0`. The person vouched for
  it.
- `evidenceRejected`: stored as given. It says the quote does not back the answer. It is
  a report about the extraction, not about the answer, so it settles nothing.

A missing `question` gives a 400, and so does a question nothing was extracted for.

## Statuses

`extractionStatus` on the submission (`ExtractionStatus`):

| Value | Meaning | Written by |
| --- | --- | --- |
| `running` | Documents are being parsed or read | `parseDocuments`, `readAgain` |
| `done` | Every answer that could be read was saved; `extractionMessage` may say more | `intakeAnswers`, `finishReading` |
| `failed` | Parsing or reading failed; `extractionMessage` says why | `classifyDocument`, `intakeAnswers`, the job's give-up, `stopProcessing` |

`parseStatus` on each `sub:File`:

| Value | Meaning |
| --- | --- |
| (none) | Never sent to be parsed |
| `queued` | Sent, no answer yet |
| `completed` | Parsed and read in; `markdownFile` is there |
| `failed` | Failed, lost, or stopped; `parseError` says which |

The job node under `/var/documents/jobs` goes `queued` → `active` → `completed` or
`failed`, and is deleted once a handler takes the outcome.

Messages the submitter can see in `extractionMessage`:

| Message | Cause |
| --- | --- |
| No document has been uploaded | Nothing to read |
| The document has not been read yet | A parse is still queued |
| The document could not be read. `<parseError>` | The parse failed |
| No uploaded document could be read | Uploads exist, none parsed |
| The document has no text that can be read | The Markdown is blank |
| The questions leave no room to show the model the document | The prompt fills the window |
| The model's answer could not be read | Two unreadable replies |
| The model could not be reached | The call failed: the model is down, has no active settings, or the call slot did not come free in time |
| The document's text could not be read | The stored Markdown could not be read back out of the repository |
| The document could not be read | The reading workflow threw |
| Reading was stopped. | Stop |
| Nothing is set up to read this request's documents | The schema names no reading workflow, or it no longer exists |
| The schema asks nothing of the document | No extractable question in the whole schema |

## Data the module reads and writes

On `sub:Submission`:

| Property | Type | Meaning |
| --- | --- | --- |
| `extractionStatus` | String | See "Statuses" |
| `extractionMessage` | String | A short message for the person looking |
| `extractionReadingClaimed` | Boolean | A job has taken this reading; removed when new documents are sent, on Stop, and on Read again |
| `extractionReadingClaimedBy` | String | A value of the claiming job's own. Two jobs racing would both write `true`, which the repository merges silently; their own values differ, so the second commit is refused. Removed with the claim. |

On `sub:File`:

| Property / child | Meaning |
| --- | --- |
| `fileName` | The upload's name, read when staging (its extension tells the daemon the file type) |
| `uploadedFile` | The upload, as received |
| `parseStatus`, `parseError` | See "Statuses" |
| `parseJobId` | The job it was queued under; an outcome for another job is ignored |
| `sharedPath` | Where it was staged; used to clean up after a failure or a Stop |
| `tokens` | The daemon's size estimate |
| `markdownFile` | The text the model reads |
| `pdfFile` | The PDF the text came from, only for office uploads |

On the schema side: `readingWorkflow` on `sch:SchemaVersion` (a reference to the reading
workflow version), and on `sch:Question` the `extractionPrompt`, `purpose`,
`responseShape`, `maxAnswers`, option children or `optionsFrom`. `optionsFrom` names a
content path; for a catalogue such as `/Categories` the live leaves are the options,
with their descriptions (`OptionCatalog`).

## Who calls whom

| Class | Kind | Called by | Calls |
| --- | --- | --- | --- |
| `ParseDocumentsHandler` | handler `parseDocuments` | `attachDocument`, `retryParse` | `ParseService.stage`, `.queue`, `.discardStaging` |
| `ParseCompletionHandler` | `ParseOutcomeHandler` | `ParseOutcomeDispatcher` | engine (`documentParsed`), `ParseResultIngester.discardStaging` |
| `IngestParseHandler` | handler `ingestParse` | `documentParsed` | `ParseResultIngester.ingest` |
| `ParseResultIngester` | component | the two above | `ParseService.isStagedPath`, `.discardStaging` |
| `QueueExtractionHandler` | handler `queueExtraction` | `documentParsed` | `JobManager.addJob` |
| `ExtractAnswersJobConsumer` | Sling job consumer | the `iap-extraction` queue | `ReadingRuns`, engine (`extractAnswers`) |
| `ClassifyDocumentHandler` | handler `classifyDocument` | a reading workflow | `AnswerIntakeService`, `ParsedDocuments`, `ExtractionFields.write` |
| `IntakeAnswersHandler` | handler `intakeAnswers` | a reading workflow | `AnswerIntakeService`, `ParsedDocuments`, `ExtractionFields`, `Prompts` |
| `FinishReadingHandler` | handler `finishReading` | a reading workflow | `ExtractionStatus` |
| `ReadAgainHandler` | handler `readAgain` | `readAgain` | `JobManager.addJob` |
| `StopProcessingHandler` | handler `stopProcessing` | `stopProcessing` | `ReadingRuns.stop`, `JobManager`, `ParseControl.abandon`, `ParsedDocuments.forget` |
| `ReviewExtractionHandler` | handler `reviewExtraction` | `reviewExtraction` | `Answer.getSurestExtraction` |
| `AnswerIntakeService` | component | classify, intake | `LLMClientFactory`, `DocumentBudget`, `IntakePayload`, `ModelCall`, `QuoteVerifier` |
| `ParsedDocuments` | component | classify, intake, stop | `DocumentText.readText`, `DocumentScan.of` |
| `ReadingRuns` | component | job, classify, intake, stop | (thread registry) |

Helpers with no state: `SubmissionFiles` (current uploads, reasons, settled check),
`SubmitterAccess`, `ExtractionStatus`, `ParsePropertyNames`, `ExtractionField(s)`,
`ExtractedAnswers`, `FieldResult`, `IntakePayload`, `ModelCall`, `ModelReplies`,
`DocumentBudget`, `DocumentScan`, `DocumentText`, `QuoteVerifier`, `Prompts`.

`ParsedDocuments` keeps only the last document read, keyed by the Markdown's path and
its `jcr:lastModified`. A re-parse changes that time, so stale text is never served; a
file with no recorded time is never reused.

## Configuration

| Where | Setting | Default | Meaning |
| --- | --- | --- | --- |
| `modules/extraction/.../feature.json` | service user `iap-extraction` | | `jcr:read` and `jcr:modifyProperties` on `/Submissions`: it only sets the claim and the status; the engine writes everything else as its own user. `jcr:read` on `/Workflows`, to check the reading workflow exists |
| same | queue `iap-extraction` | `maxparallel` 1 | One submission read at a time |
| same | bundle start order | 29 | After the LLM client, the engine and the models |
| documents `feature.json` | `ParseJobConsumer.daemonUrl`, `ParseAbandonment.daemonUrl` | `${docling.url}` | Where the daemon is |
| same | `StaleParseJobSweeper.maxAgeMinutes` | 30 | When an unanswered parse is given up |
| `ParseAbandonment` | `responseTimeout` | 30 s | How long to wait for `/cancel` |
| environment | `IAP_DOCLING_CALLBACK_JWT` | | The shared token for callbacks; without it no parse is dispatched |
| environment | `IAP_DOCLING_TOKEN` | | Optional bearer token for `/parse` and `/cancel` |
| environment | `IAP_SHARED_DOCS` | `/shared-docs` | The shared volume |
| LLM settings | the active model and its context window | 131072 tokens if unset | See the `llm` module |

## Writing a reading workflow

A reading workflow is ordinary workflow content under `/Workflows`, named by
`readingWorkflow` on the schema version. Its service tasks use the handlers above. The
research-proposal demo's `readProposal` is the example to copy:

1. `classifyDocument`: is this a proposal, and which kind?
2. A gateway on the `is_proposal` decision. A settled "no" ends the reading. An
   unsettled one waits at a user task for the submitter.
3. A gateway on the category, leading to one `intakeAnswers` step per kind, each naming
   the requirements to ask (for example `[common, prospective]`).
4. `finishReading` at the end of every path.

Keep in mind:

- Every path must end at `finishReading`, or the submission stays `running`.
- A user task that waits for the submitter should come after a `finishReading` with a
  `message`, so the view stops spinning while it waits.
- The reading runs as `iap-extraction`, not as the submitter.

## What is still missing

| Gap | Effect |
| --- | --- |
| The `attachDocument` system workflow and `AttachDocumentHandler` (IAP-135) are not merged | Nothing starts a parse yet; this module is installed but idle |
| The `decision` condition source and `markCompleteness` are not merged | Reading workflows cannot route on a settled pick yet |
| The submission view that shows `extractionStatus` and the provenance of answers is not merged | Nobody sees the status yet |
| Only the start and end of a long document are read | The middle of a document past the context window is never read, and the confidence does not say so |
| Only the first parsed upload is read unless `documents` is set | Satellite files are ignored by default |
| The category does not choose the schema | It is recorded next to the schema the submitter already chose |
| `editDistance` on `sub:Extraction` is declared but never written | Nothing measures how far the submitter's value moved from the model's |
