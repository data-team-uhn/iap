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
 submitter uploads a file (attachDocument), or retries a failed parse (retryParse)
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
 the reading workflow (content, named by the schema version, e.g. readProposal)
   classifyDocument ── the gate: what kind of document is this?
   gateways ────────── act on the pick once it is settled (`decision` source, step 7)
   intakeAnswers ───── ask every remaining question in one model call
   markCompleteness ── tag the submission `incomplete`, or untag it
   finishReading ───── extractionStatus=done
```

Everything from `extractAnswers` down to the reading workflow's first user task, or its
end if it has none, runs inside one engine walk, on the job's thread, fired as
`iap-extraction`, and is saved in one commit. Either all of it lands, or none of it
does. What comes after a user task runs later, in another request (see step 6).

## Step 1: the upload, and sending it to be parsed

**Class:** `ParseDocumentsHandler` · **Handler name:** `parseDocuments` · **Called by:**
the `attachDocument` system workflow after every upload, and `retryParse`.

An upload is a `POST <submission>.attachDocument.json` with `requirement` and `file`.
The `attachDocument` system workflow (submissions module) runs three steps in one walk:

| Step | Handler | What it does |
| --- | --- | --- |
| `attach` | `AttachDocumentHandler` | Only the submitter, only while a draft (`SaveAnswersHandler.checkMayEdit`). Checks the type against the requirement's `acceptedFileTypes`, the size against `DocumentLimits.getMaxFileSize()`, and a PDF's page count, counted with PDFBox (`PdfPageCounter`), against `DocumentLimits.getMaxPdfPages()`. A PDF that PDFBox cannot open is stored anyway, for the daemon to refuse. Then stores the file as a new `sub:DocumentVersion`. |
| `mark` | `MarkCompletenessHandler` | Tags the submission `incomplete` while a form or document requirement is not fulfilled, and untags it otherwise |
| `parse` | `ParseDocumentsHandler` | Sends the upload to be parsed (below) |

The browser runs the same checks first, with the same limits, read from
`/system/documents/limits`; see [file-validation.md](file-validation.md).

A new upload for a document that already has one is a new version of it. Before it is
added, `DocumentReadings.dropSuggestions` deletes every answer that is still only the
model's untouched suggestion from the old versions. Reading never overwrites an answer
(step 8), so without that the new file would never be read for those questions. An
answer the submitter confirmed, changed or typed keeps its value.

Removing a document (`detachDocument`) drops every reading of it
(`DocumentReadings.dropAll`): untouched suggestions go whole, and other answers lose
only the extractions that cite it, since Oak refuses to delete a version still
referenced.
`DetachDocumentHandler` refuses while the document's parse is `queued` or the reading is
`running`: "Wait until the document has been read, or stop the reading first".

What `parseDocuments` does, in order:

1. Checks the caller with `SubmitterAccess.checkMayChange`: only the person who raised
   the submission, and only while it is a draft. Anyone else gets a 403, a submitted
   request gets a 409.
2. Reads `readingWorkflow` on the submission's schema version. **No reading workflow
   means nothing is parsed**, so a schema that never reads its documents costs nothing.
3. For each document, takes the latest upload (`SubmissionFiles.getCurrentFiles`). An
   upload is sent when its `parseStatus` is empty (never sent) or `failed`. One that is
   `queued` or `completed` is left alone.
4. For each upload sent:
   - `ParseService.stage` copies the bytes into a new folder on the shared volume,
     `/shared-docs/<uuid>/<name>`. The name is `fileName` on the `sub:File`, or the
     document's `title` when that is missing.
   - `ParseService.queue` creates a job node under `/var/documents/jobs/<jobId>` and a
     Sling job on topic `iap/documents/parse`. The job names the `sub:File` as its
     `target`.
   - The file records `parseStatus=queued`, `parseJobId`, `sharedPath`, and loses any
     old `parseError`.
   - If queueing fails, the staged folder is deleted at once. No job node names it, so
     no sweep would ever find it later.
5. If at least one upload was sent: the submission gets `extractionStatus=running`, and
   the claim (`extractionReadingClaimed`, `extractionReadingClaimedBy`) is removed,
   since a new document is a new reading.

Staging and queueing happen before the engine commits. That is on purpose: the job node
has to exist before the Sling job runs.

## Step 2: the daemon parses it

**Code:** `modules/documents/processing` (Python). **Called by:** `ParseJobConsumer` in
the documents module, which sends
`POST /parse?path=&job_id=&max_bytes=&max_pages=&max_expanded_bytes=` and moves the job
node from `queued` to `active`.

The last three are the configured `DocumentLimits`: file size, PDF pages, and a DOCX's
size once unzipped. The daemon applies each where it is lower than its own ceiling
(`IAP_MAX_INPUT_BYTES`, `IAP_MAX_INPUT_PAGES`, `IAP_MAX_EXPANDED_BYTES`), so a caller
can lower a ceiling but never raise it. It checks the document before it queues it
(`refuse_oversized_input` in `shared_docs.py`). A document over a limit gets a 400, and
`ParseJobConsumer` fails the job with the daemon's words, for example "The daemon
answered HTTP 400: document has 612 pages, over the 500-page limit (…)". The file then
ends `failed` with that `parseError` (step 4).

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
| Converting | Flags it, and no callback is sent. The PDF loop checks the flag every 2 s while it waits on page batches (`ABANDON_POLL_SECONDS`), drops the batches not started, and waits for the running ones to stop: up to Docling's `document_timeout` for one batch plus 120 s (`get_cancel_wait_seconds`). A batch still running after that marks the worker stuck, and `/health` starts failing. A DOCX converts in one call, so it cannot be cut short. | `running` |
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
staging folder. If the folder cannot be removed, the record is kept, since it is all
that names the folder, and the next sweep tries again. A day after the job settled
(`GIVE_UP_ON_FOLDER_MILLISECONDS`) the record is dropped anyway, with an `ERROR` log
line and the problem "A parse staging folder cannot be removed" for an administrator.

So the same outcome can reach the handlers more than once: offered again on a sweep, or
again after its record could not be deleted. A `ParseOutcomeHandler` must therefore be
idempotent, as its javadoc says. `ParseCompletionHandler` is: see its third check below.

A callback for a job whose record is gone answers 404, the same as for an unknown job. A
callback for a stopped job still finds its record, and overwrites the `Stopped` failure
with its own outcome (see "Stop").

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
| The Markdown file exists | If not: `parseStatus=failed`, with the same "The parse named …, which is not on the shared volume" message. |
| The PDF path is on the shared volume | If not: the PDF is skipped and a warning logged. |
| The upload is itself a PDF (`File.isUploadAPdf`, which reads `uploadedFile/jcr:content/jcr:mimeType`) | The PDF is not stored a second time. `File.getFilePdf()` falls back to the upload. |

The ingester also has a message for a success that names no Markdown, "The parse
finished without naming the text it produced", but nothing reaches it. The callback
refuses such a success with a 400, and `IngestParseHandler` would throw first ("A
successful parse names the Markdown it produced"). That reverts the walk,
`ParseCompletionHandler` does not take the outcome, and the sweep offers it again until
it drops the record at 30 minutes. The file stays `queued`.

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
| A job queued by Read again (job property `readAgain=true`) finds `extractionStatus` at `running` | The job returns `FAILED`, so Sling runs it again later. It was queued before the walk asking for it committed, and until that commit the old status and claim are still there. |
| No current file is still `queued` (`SubmissionFiles.isEveryParseSettled`) | The job returns `FAILED`, so Sling runs it again later. A parse that landed but has not committed yet looks "queued" for a moment. |
| The schema version's `readingWorkflow` points at a `wf:WorkflowVersion` that exists | `extractionStatus=failed`, "Nothing is set up to read this request's documents". Fired anyway, the reading would start nothing and leave `running` for good. |
| `extractionReadingClaimed` is not set, and setting it, with the job's Sling job id in `extractionReadingClaimedBy`, commits. A claim that already holds this job's own id counts as its own: Sling runs an unfinished job again, with the same id, after a restart. | A claim held by another id: another job has the reading, and this one is dropped. Two jobs that race write different ids, and the repository refuses the second commit. |

The `readingWorkflow` check only asks that the version exists, not that it is tagged
`active`. An inactive version passes it, the engine then refuses to start it, and the
job's give-up below records "The document could not be read".

Then it fires `extractAnswers` on the submission. If that throws, the job "gives up": in
a commit of its own it writes `extractionStatus=failed`, "The document could not be
read", and removes the claim. Without that, the submission would stay at `running`
forever.

`ReadingRuns.begin` registers the job's thread under the submission path, so Stop can
interrupt it (see "Stop" below). A stopped reading gives up the same way, and records
"Reading was stopped." instead.

## Step 6: starting the schema's reading workflow

**System workflow:** `extractAnswers`. One step: the built-in `startWorkflow` handler
with `workflowFrom: schemaVersion/readingWorkflow` and `replaceActive: true`
(`iap:replaceActive` in its diagram).

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

Why the reading is not a few steps in the submission's own workflow: nothing could start
them there. A reading begins when a parse lands, in a background job, not in a request
aimed at that instance. And nothing delivers a message to a running instance: the engine
refuses a free-standing catching event rather than park it, and a boundary event fires
only when its timer runs out (see "Known gaps" in [workflows.md](workflows.md)). So the
reading gets an instance of its own, started for the submission when its parse lands.

What runs after the reading workflow's first user task runs in the request that
completes the task, with that person as the actor, and is saved in that request's
commit. The job is long over by then: its give-up does not cover that part, and the
extraction queue does not hold it to one at a time (the LLM call gate still does).

In the demo's reading workflows that request is the submitter pressing "Continue once
this is confirmed". The `finishReading` before that task already set `extractionStatus`
to `done`, and nothing sets it back to `running`. So the intake runs inside that request
with the status at `done`: the view shows no progress bar, the button waits for the
model call, and a Stop does nothing, since it acts only while the status is `running`.
Classify and intake steps do register the request's thread with `ReadingRuns`, so a
reading workflow that pauses without a `finishReading` first could still be stopped
there.

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
| `confidenceThreshold` | The confidence a pick needs to be saved. A less sure pick is not saved, and the question waits for the submitter. | 0.7 |
| `template` (or `template.md`) child | Reference text shown to the model, for example the usual structure of a research protocol | none |

What the step does:

1. Collects the "open" classifications. It skips one whose condition does not hold, one
   with no question, and one whose question already has an answer (same rule as the
   intake below: an empty field the form saved does not count). A second upload does not
   pay again for a decision already made or confirmed, and a newer version of the
   document does not change it either.
2. Groups them by `document`. For each document:
   - No upload: skipped.
   - Parse `failed`: `extractionStatus=failed` with the parse error, and the step stops.
   - Parse not `completed` yet: skipped.
   - Otherwise one model call for all of that document's classifications.
3. The call goes through `AnswerIntakeService`, the same code as the intake (step 8).
   Each template is added in front of the system prompt under `# Reference for
   "<label>"`. Each field's rules are the requirement's `prompt`, followed by the list
   of allowed values.
4. A pick the model found, with a confidence at or above that requirement's
   `confidenceThreshold`, is written like any extracted answer (see "Writing an
   answer"): pre-filled, with confidence, reasoning and quotes. A less sure pick is not
   written at all, so the question stays empty for the submitter. The gate must not
   guess.
5. If the model could not be asked, or answered unreadably twice:
   `extractionStatus=failed` and the step stops.

Whether the workflow acts on the pick is not this step's call: that is left to the
reading workflow's gateways, through the `decision` condition source
(`DecisionOperandResolver`, submissions module). Its operand names the question by
its path inside the submission's schema version, such as `is_proposal/decision`. It
finds the submission by walking up from where the condition is evaluated, so it works in
a gateway, whose conditions live in the workflow, not in the schema. It gives the
answer's value only once the answer is settled, and no value otherwise:

| The answer | Settled? |
| --- | --- |
| No extraction under it, or one with no suggested value (the submitter typed it) | Yes |
| An extraction is `reviewed` (the submitter confirmed it) | Yes |
| Its value differs from the model's suggestion (the submitter changed it) | Yes |
| The model's untouched suggestion, with the surest extraction's confidence at or above the threshold | Yes |
| The model's untouched suggestion, below the threshold | No: no value |
| No answer at all | No: no value |

The threshold is the `confidenceThreshold` of the classification requirement the
question sits in (walking up, sections included), or 0.7
(`ClassificationRequirement.DEFAULT_CONFIDENCE_THRESHOLD`) for any other question. The
gate never writes a pick below its own threshold, so a pick it wrote counts at once. The
threshold check matters for an answer an intake wrote, or for a threshold raised after
the pick was written. A pick the gate did not write is "no answer", and the gateway's
default arc takes it: in the demo, a pause for the submitter (see "Writing a reading
workflow").

## Step 8: reading the answers (the intake)

**Class:** `IntakeAnswersHandler` · **Handler name:** `intakeAnswers`.

Activity properties a reading workflow can set on this step:

| Property | Meaning | When absent |
| --- | --- | --- |
| `requirement` | One requirement name, or several (a list, or comma-separated). All are asked in one call, in that order. | Every question the schema asks |
| `documents` | Document requirement names to read, in order. Each one's text goes under its own heading (`# Preamble` for `preamble`), and together they are one document to the model. | The first upload with a completed parse |
| `promptFrom` | Extra system-prompt files from the bundle's `/prompts` folder, added in front of the intake prompt | No extra prompt |
| `recordWhen` | `path=value`, the path being the question's path inside the schema version. The model is still asked everything, but answers are only saved when that field holds that value: the model's reply matched to the question's options, ignoring case, or, when the field already had an answer and was not asked again, that answer. | Always save |

In a diagram these are the service task's `iap:requirement`, `iap:documents`,
`iap:promptFrom` and `iap:recordWhen` attributes. `documents` and `promptFrom` are
lists, written comma-separated. `requirement` is one string, which the handler splits on
commas itself. The `properties` list of `ServiceTask.json` maps those four, with
`iap:message` (step 9) and `iap:replaceActive` (step 6). `UserTask.json` maps
`iap:requirement` on a user task, which only places the task in the form (see "What the
submitter sees"). See
[What an executable graph carries](workflows.md#what-an-executable-graph-carries) for
how that list works.

What the step does:

1. **Which documents.** With `documents`: each named one with a completed parse is read.
   A named one never uploaded is skipped. A named one whose parse failed fails the step
   with its parse error. If none is left: fail with the reason
   (`describeNothingToRead`). Without `documents`: the first completed upload, or fail
   with the reason.
2. **Which questions.** Questions of the named requirements that apply (conditions
   resolved), have an `extractionPrompt`, and have **no answer yet**. A question that
   holds a value, or that the model already suggested an answer for, is not asked again:
   reading again fills gaps. An empty answer the form saved (a field somebody clicked
   through) is a gap; a suggestion the submitter cleared is not. The first reading of a
   newer version asks every question, answered or not (see "A newer version").
   Questions are keyed by their path inside the schema version, so two sections may both
   have a `title`.
3. **Nothing to ask.** If every question already has an answer: `done`. If the whole
   schema asks nothing: `done`, "The schema asks nothing of the document". If a named
   requirement asks nothing (its condition did not hold): no status change.
4. **Ask** the model (see the next section).
5. **Unreadable or unreachable:** `extractionStatus=failed` with the reason, and nothing
   is written.
6. **`recordWhen` does not hold:** nothing is written, and `extractionStatus=done`
   unless an earlier step of the reading already wrote `failed`.
7. **Otherwise** every field the model found is written (see "Writing an answer"), and
   `extractionStatus=done` unless an earlier step of the reading already wrote `failed`.

A model that cannot be reached is recorded as `failed`, not thrown as a workflow error.
Throwing would revert the answers earlier steps of the reading wrote, and the job's
give-up would replace the real reason with the generic "The document could not be read".

## Step 9: ending the reading

**Class:** `FinishReadingHandler` · **Handler name:** `finishReading`.

Moves `extractionStatus` from `running` to `done`, with the step's `message`
(`iap:message` in a diagram) if it has one, for example to say the reading waits for the
submitter. Any other status is left alone, because a step that already wrote `failed`
said something this one does not know. A reading workflow puts this at the end of every
path, so the view never spins forever.

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

The part that does not change goes first and stays in the system prompt. A call and its
re-ask (see "Two attempts") send the same system prompt, `promptFrom` files included, and
the correction is added to the user message. So both open with the same bytes, which is
what a hosted provider's prefix cache matches on and what a local model reuses its KV
cache for. Anything that changes per document belongs after it.

### How much of the document fits

`maxOutputTokens` = 500 + 400 per field. The document gets what is left of the model's
context window (`CallBudget.calculateDocumentTokenBudget`):

```
document budget = window − 15% safety margin − maxOutputTokens − (system prompt + question block)
```

This is the only place the size is decided; there is no separate maximum to keep in step
with it.

| Term | Why |
| --- | --- |
| window | The active model's `contextLimitTokens`. When the model does not say, or the settings cannot be read, 131072 (`DEFAULT_CONTEXT_LIMIT_TOKENS`), the smallest window of any model in the catalogue. |
| 15% safety margin | Tokens are estimated at 4 characters each (`CHARS_PER_TOKEN`), which undercounts dense Markdown, and the provider's chat template adds tokens of its own. |
| `maxOutputTokens` | A vLLM-style provider refuses a request whose prompt plus requested output would not fit. It does not stop generating early. |

A document that does not fit keeps its opening (75% of the room) and its end (25%). The
opening holds the cover page, the synopsis and the objectives; the end holds the
appendices, the site list and the signature page. The opening stops at the last
paragraph break before its limit, and the end starts at the first paragraph break after
its own (`CallBudget.startOfTail`), so neither half starts or stops mid-sentence. What
the opening gives up goes to the end. A text with no paragraph breaks is cut where the
limits fall. The two halves are joined by `CallBudget.OMISSION_MARKER`, a bracketed line
saying the middle was left out, and the intake prompt tells the model that the text on
either side does not follow on.

`DocumentBudget` logs every cut as `Document cut to fit`, with the stage, the file, and
how many characters were sent and left out. If no room is left at all, it returns empty
text and `AnswerIntakeService` does not ask the model: the step fails with "The questions
leave no room to show the model the document". A blank document fails with "The document
has no text that can be read".

This is a real limit: an answer that is only in the middle of a very long protocol is
not found, and the confidence says nothing about it. Sending the whole document instead
would get the call refused, and answer nothing at all.

### Two attempts, no more

`ModelCall.askWithCorrection` sends the call. If the reply holds no readable JSON object
(`ModelReplies.readJsonObject` takes the text between the first `{` and the last `}`),
it asks once more with a `# Correction` section saying what was wrong: empty, no JSON,
never closed (cut off), or not one object with a key per field. A second bad reply means
"degraded": nothing is saved, and the step fails with "The model's answer could not be
read". Each attempt resends the whole document, so a third would pay for it a third
time.

### One call at a time

The LLM module allows one model call at a time across the instance (`LLMCallGate`,
implemented by `CallGateImpl`, a fair semaphore with one slot). There is nothing to
configure. The extraction queue (`iap-extraction`, topic `iap/extraction/extract`) also
runs one submission at a time. A wider queue would not read anything sooner; it would
only move the waiting from the queue, where a job waits as long as needed, to the gate,
where a call fails when its timeout runs out.

Why one: the provider serves a proposal-sized call on its own whatever else it is asked.
Measured against prompter on 2026-09-21, model `Qwen3.8-27B`, 200,000-token prompts, each
call carrying a document the provider had not seen:

| Calls at once | Wall (s) | Per call (s) |
| --- | --- | --- |
| 1 | 39.3 | 39.3 |
| 2 | 77.3 | 38.6 |
| 4 | 152.8 | 38.2 |
| 6 | 228.4 | 38.1 |
| 8 | 304.0 | 38.0 |
| 12 | 455.1 | 37.9 |

Nothing failed, and the wall time grows in a straight line with the number of calls. The
provider does not refuse extra requests, it queues them. So sending more than one buys no
throughput: the extra calls wait in the provider's queue against prompter's 600 s
`timeoutSeconds`. The last call of the 12 came back after 455 s, most of that budget.
Waiting at the gate keeps the wait where the caller can see it.

A caller waits three calls' worth of `timeoutSeconds` for the slot (`QUEUED_CALLS` in
`OpenAIClient`), not one. Waiting exactly as long as one call may take would give up just
as the slot is about to come free, and for a reading that is a failure the submitter
sees.

Extraction makes one call per document to classify, then one intake call per
`intakeAnswers` step, so the limit bites across submissions more than within one. At
about 39 s a call, ten queued proposals mean the last one waits some six and a half
minutes.

If this is ever measured again, give every call its own document. The same run with one
identical body for every call reported 3.8 s per call and made a limit of 8 look right.
That was the provider's prefix cache answering, not the model working: a document it had
not seen took 47.4 s, against 3.8 s for a repeat.

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
| Confidence is held to 0–1 | `ModelReplies.readConfidence` | A number outside 0–1 is clamped. A missing value or a non-number reads as 0. |
| A question that already has an answer is not overwritten | `ExtractionFields.getAnswered` | Reading again fills gaps only. An answer counts when it holds a value or has an extraction under it, so a suggestion the submitter cleared stays cleared. An empty answer with no extraction is the form saving a field somebody clicked through, and is filled. The one exception is the first reading of a newer version (see "A newer version"). |

How a quote is matched (`QuoteVerifier`): both texts are lower-cased, page markers
removed, whitespace collapsed. An exact match wins. Otherwise, for quotes longer than 24
characters (`ANCHOR_LENGTH`) and at most 1000 (`MAX_FUZZY_QUOTE`), it looks for the
quote's first, middle and last 24 characters in the text, takes up to 4 candidate places
(`MAX_CANDIDATES`, at most 2 per anchor), and finds the stretch of nearby text with the
fewest edits to the quote. The best stretch must reach 0.85 and agree on numbers and
negations. A longer quote has to be there exactly.

The anchors are there because comparing a quote against a whole protocol is quadratic.
The three anchors are found cheaply, and only a window where each hit says the quote may
start (1.5 times the quote's length, `WINDOW_FACTOR`) is compared edit by edit. A model
that changed all three anchors did not copy the passage. A quote past 1000 characters
that is not there word for word was built, not copied.

A quote is checked against the whole document, not only the part that fitted in the call.
Where the token budget fell says nothing about whether a passage is real.

Both sides are normalized by the same function, `QuoteVerifier.normalize`.
`DocumentScan` normalizes the document line by line with it, so the document comes out
the same as the quote would. If the two differed (say, two spaces at a paragraph break
against one in the quote), a faithful quote across that break would never be found. The
document is normalized once, not once per quote. For a document read alone,
`ParsedDocuments` keeps the result (see "Who calls whom"), so the classify and intake
steps share it. An intake that names its `documents` joins their texts into one scan
itself (`IntakeAnswersHandler.getScan`, `DocumentScan.join`), and neither uses nor fills
that cache.

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
        ├── replacedValue   = what the answer held before (only from a newer version)
        └── <uuid> (sub:Evidence), one per kept quote
            ├── quote, header, page
            └── source → REFERENCE to the version it came from
                          (only when the step names its `documents`)
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

### A newer version

A submitter never deletes a document. A new upload for the same requirement adds a
`sub:DocumentVersion`, and every older version stays.

The first reading of a newer version asks every question of the step, answered or not.
`ExtractionFields.isNewRevision` says when that is: a file the step reads is not its
document's first version, and no answer to the step's questions cites it yet. For each
answer, N is what the model read now, C the answer's value, and P the last suggestion
(`Answer.getSuggestedValues`):

| Case | What happens |
| --- | --- |
| The question has no answer yet | Written as usual |
| N equals C, or N equals P | Nothing changes, and no extraction is added |
| N differs from both | `value` becomes N. The new extraction keeps C in `replacedValue`, unless C was empty. |
| The model found nothing | Nothing changes |

This holds whether the submitter typed the answer, confirmed it, or left the suggestion
as it was. The gate (step 7) is left out: a classification already answered is never
asked again. Read again on the same version only fills gaps, since the step's answers
now cite it.

`Answer.getSurestExtraction` passes over a run when another run of the same answer read
a newer version of one of its documents. So the form shows what the newest file said,
and a version nobody has read yet changes nothing.

## What the submitter sees

The form projection, `GET <submission>.form.json` (`SubmissionFormServlet`, submissions
module), carries the reading to the page:

| Key | Where | What it says |
| --- | --- | --- |
| `readsDocuments` | top level | The schema version names a `readingWorkflow` |
| `extraction` | top level, once `extractionStatus` is set | `ReadingProjection.describe`: `status`, `message`, `retryable` (a current upload's parse `failed`), `parsed` (no current upload is still `queued`), `reading` (`extractionReadingClaimed` is set) |
| `extracted` | each form requirement | `ReadingProjection.isExtracted`: a classification, or a form with a question that has an `extractionPrompt` |
| `provenance` | each question a model answered | `ProvenanceProjection`, from the surest extraction: `suggested`, `confidence`, `reasoning`, `reviewed`, `evidenceRejected`, and `passages`, each with `quote`, `cite` and `source` |

A passage's `cite` is "p. N", its heading, or both. Its `source` is the path of the PDF
the run read (`File.getFilePdf()`), plus `#page=N` when the quote has a page. A quote
that names its own source (a step with `documents`) links to that document's PDF
instead. A source with no PDF gives no link.

The frontend, in `modules/submissions/impl/src/main/frontend/src/`:

| File | What it shows |
| --- | --- |
| `SubmissionView.tsx` | Asks for the form again every 4 s while `status` is `running`, for up to 45 minutes. Shows the reading bar while `running` or `failed`, and a `done` reading's `message` (a paused reading says what it waits for). Beside the page's actions: **Abort** while `running`, and **Try again** when `failed` and the form is `editable`. Holds the step that sends the request while the reading runs: "Wait until the uploaded document has been read." |
| `readingProgress.ts` | The bar's four steps: Parsing until `parsed`, Digesting until `reading`, then Extraction for 45 s and Validation after, on a clock, since the model call says nothing until it ends. A failed reading stops on Parsing when a parse failed (`retryable`, or not `parsed`), on Extraction otherwise. |
| `submissionForm.ts` | The posts: `stopProcessing` for Abort; `readAgain(…)` posts `retryParse` when `retryable`, `readAgain` otherwise; `reviewExtraction` |
| `SubmissionEditor.tsx` | A form that `readsDocuments` is three pages: the upload and the questions answered by hand, the classifications, then the other `extracted` requirements. A requirement's panel offers the user tasks that name it. |
| `SubmissionTasks.tsx` | Offers a task only when its `@events` lists `complete`, and places it by the task's `requirement` (copied from the `bpmn:userTask`'s `iap:requirement` onto the `wf:TaskInstance`), so the demo's "Continue once this is confirmed" sits under `is_proposal` |
| `AnswerProvenance.tsx`, `provenance.ts` | Per answer: a badge ("AI found:", "Matches your protocol", "You corrected this"), a "Low confidence" chip under 0.75 (`LOW_CONFIDENCE_FLOOR`) while the suggestion is untouched, and the passages. **Confirm answer** posts `confirmed=true`; "This passage doesn't support the answer" posts `evidenceRejected`. Opening a passage shows the PDF in the shared `PdfViewer` (frontend-commons, `pdfViewer/`) at its page, with the quote marked as the assistant's. |

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
| `retryParse` | `ParseDocumentsHandler` | Sends every upload that was never parsed or whose parse failed |
| `reviewExtraction` | `ReviewExtractionHandler` | Records what the submitter thinks of a pre-filled answer |

### Stop

1. If `extractionStatus` is not `running`, nothing happens. A late Stop must not undo a
   finished reading.
2. If a thread is reading this submission (`ReadingRuns`), it is interrupted. The model
   call checks for that before and after each request (`ModelCall.chat`), so a reply
   that arrives after a Stop is thrown away. A stop that lands after the model answered,
   while the step still checks quotes or writes, is caught when the step ends
   (`ReadingRuns.end`): `classifyDocument` and `intakeAnswers` then throw, and the
   engine reverts the walk, so nothing that reading wrote is kept.
3. Readings still waiting in the job queue for this submission are removed.
4. For each current file:
   - Parse `queued`: `ParseControl.abandon` (see below). The file gets
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

`ParseControl.abandon` (`ParseAbandonment`, documents module) sends `/cancel` to the
daemon without waiting for the answer, then reads the job record:

| Job record | What `abandon` does | Returns |
| --- | --- | --- |
| Missing, or no valid job id | Nothing more | `true`: no record names the staging folder, so `StopProcessingHandler` wipes it (`sharedPath`) |
| Already `completed` or `failed` | Leaves it as it is | `false` |
| `queued` or `active` | Marks it `failed` with error `Stopped`, sets `finished`, removes the outputs, keeps `target` and the staging path, and commits in a session of its own. If that commit conflicts (a callback or the sweep changed the record first), it reverts and logs at `INFO`. | `false` |

It never wipes the staging folder or deletes the record itself. What then becomes of a
stopped record depends on the Stop's own walk:

- **The walk commits.** The file no longer names the job. The sweep offers the `Stopped`
  record to the handlers, `ParseCompletionHandler` does not take it (the job id does not
  match), and 30 minutes after it settled the sweep drops the record with its folder.
- **The walk reverts.** The file still waits on the job. The first sweep at least 2
  minutes after the stop hands the `Stopped` failure to `ParseCompletionHandler`, and
  the file settles as `failed`.
- **The daemon calls back late.** Its outcome overwrites the `Stopped` record.
  `ParseCompletionHandler` takes it only if the file still waits on that job.

### Read again

Refused with a 409 while `extractionStatus` is `running` ("This request is already being
read"). Otherwise it sets `running`, removes the claim, and queues the same job a parse
queues (step 5), with the job property `readAgain=true`. The job is queued before this
walk commits, so that property tells it to wait until it sees `running`. Questions
already answered are not asked again, unless the reading is the first one of a newer
version.

### Retry a failed parse

The `parseDocuments` step. Only uploads with no `parseStatus` or with
`failed` are sent. Usually the daemon was down, not the document broken.

### Review a pre-filled answer

Payload: `question` (the path inside the schema version, as the save endpoint uses), and
either or both of `confirmed` and `evidenceRejected` (`true`/`false`, as booleans or
strings). It finds the run the form showed, `Answer.getSurestExtraction()` (the highest
confidence among runs not passed over for a newer version; on a tie, the earliest), and:

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
| `done` | Every answer that could be read was saved; `extractionMessage` may say more | `intakeAnswers` (unless an earlier step already wrote `failed`), `finishReading` |
| `failed` | Parsing or reading failed; `extractionMessage` says why | `classifyDocument`, `intakeAnswers`, the job's give-up, `stopProcessing` |

`parseStatus` on each `sub:File`:

| Value | Meaning |
| --- | --- |
| (none) | Never sent to be parsed |
| `queued` | Sent, no answer yet |
| `completed` | Parsed and read in; `markdownFile` is there |
| `failed` | Failed, lost, or stopped; `parseError` says which |

Only the job node under `/var/documents/jobs` is ever `active`. It goes `queued` →
`active` → `completed` or `failed`, and is deleted once a handler takes the outcome. A
Stop marks a `queued` or `active` one `failed`, with error `Stopped`.

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
| The document could not be read | The reading workflow threw, or its version is not tagged `active` |
| Reading was stopped. | Stop |
| Nothing is set up to read this request's documents | The schema names no reading workflow, or it no longer exists |
| The schema asks nothing of the document | No extractable question in the whole schema |

## What a reading leaves in the log

Every step a reading passes logs one line at `INFO`, so a slow reading can be accounted
for without adding anything. In order, for one submission:

| Line | Where | What it carries |
| --- | --- | --- |
| `Parse staged` / `Parse queued` | documents (`ParseJobService`) | The staged path and bytes; the job, path and target. This is the start time. |
| `Document sent to be parsed` | `ParseDocumentsHandler` | The `sub:File`, the job and the file name |
| `Reading started` | `ParseDocumentsHandler` | The submission and how many parses this step queued (`parsesQueued`) |
| `Parse dispatched` | documents (`ParseJobConsumer`) | `acceptedInMs`: how long the daemon took to accept the work, not to do it |
| `parse job=... done` | the daemon | `waitMs` for the parse slot, `parseMs` for the conversion |
| `Parse completed` / `Parse failed` | documents (`ParseCallbackServlet`) | `parseMs` measured from the dispatch, and `tokens` |
| `Parse came back` | `ParseCompletionHandler` | The job, the file, the submission, whether it succeeded, `tokens` |
| `Parse read in` | `ParseResultIngester` | The Markdown and PDF bytes stored, `tokens`, and the milliseconds to store them |
| `Reading queued` | `QueueExtractionHandler` | The submission, once per parse that lands; the job waits until every parse has settled |
| `Reading run started` / `Reading run done` | `ExtractAnswersJobConsumer` | Brackets the whole reading; `done` carries the status and `ms` |
| `Reading step started` / `Reading step done` | `ClassifyDocumentHandler`, `IntakeAnswersHandler` | `step=classify` with the file and the number of classifications, or `step=intake` with the requirement and the number of questions; `ms` on `done` |
| `Document cut to fit` | `DocumentBudget` | The stage, the file, and how many characters were sent and left out |
| `LLM request served` | `OpenAIClient` (llm) | The model, `gateWaitMs` and `callMs`, split |
| `LLM call done` | `DefaultLLMClient` (llm) | The stage, the prompt size, `maxOutputTokens`, the milliseconds, the reply size |

The two LLM lines are split on purpose. The call gate holds calls to one at a time, so a
slow reading is usually a reading that waited, and one total would not say which it was.
The stage on `LLM call done` is the response schema's name, `iap_intake`, for both
classify and intake calls; the step line beside it says which step and which file or
requirement the call was for.

Nothing here can be measured from the job records. A job queued for a node is deleted as
soon as its outcome is taken, so the log is the only place a finished parse is recorded.
A job queued without a target (the polling endpoint's), or one whose outcome no handler
took (a stopped one, once the Stop has committed), keeps its record until
`StaleParseJobSweeper` drops it with its staging folder, 30 minutes after it settled, or
later while the folder cannot be removed.

## Data the module reads and writes

On `sub:Submission`:

| Property | Type | Meaning |
| --- | --- | --- |
| `extractionStatus` | String | See "Statuses" |
| `extractionMessage` | String | A short message for the person looking |
| `extractionReadingClaimed` | Boolean | A job has taken this reading; removed when new documents are sent, on Stop, on Read again, and when a reading gives up |
| `extractionReadingClaimedBy` | String | The claiming job's Sling job id. Two jobs racing would both write `true`, which the repository merges silently; their ids differ, so the second commit is refused. A job Sling runs again after a restart keeps its id, and finds the claim still its own. Removed with the claim. |

On `sub:File`:

| Property / child | Meaning |
| --- | --- |
| `fileName` | The upload's name, read when staging (its extension tells the daemon the file type); the document's `title` is used when it is missing |
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
| `ClassifyDocumentHandler` | handler `classifyDocument` | a reading workflow | `ReadingRuns`, `AnswerIntakeService`, `ParsedDocuments`, `ExtractionFields.write` |
| `IntakeAnswersHandler` | handler `intakeAnswers` | a reading workflow | `ReadingRuns`, `AnswerIntakeService`, `ParsedDocuments` or `DocumentScan.join`, `ExtractionFields`, `Prompts` |
| `FinishReadingHandler` | handler `finishReading` | a reading workflow | `ExtractionStatus` |
| `ReadAgainHandler` | handler `readAgain` | `readAgain` | `JobManager.addJob` |
| `StopProcessingHandler` | handler `stopProcessing` | `stopProcessing` | `ReadingRuns.getFiles`, `.stop`, `JobManager.findJobs`, `.removeJobById`, `ParseControl.abandon`, `ParseService.discardStaging`, `ParsedDocuments.forget` |
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

## What commits when

The ingester and every handler write through the engine's session, and the engine
commits the whole run at once, so a submission never shows half a parse or half a
reading. These writes happen outside that commit:

| Step | What it writes on its own | Why |
| --- | --- | --- |
| `parseDocuments` | The staged copy on the shared volume, and the job node, committed by `ParseService.queue` in a session of its own | The job node must be there before the Sling job runs. It is also why a failure to queue deletes what was staged: no job node names that folder, so no sweep would find it. |
| `queueExtraction`, `readAgain` | A Sling job | The JobManager commits the job node in its own session |
| `stopProcessing` | Removes queued reading jobs; `ParseControl.abandon` marks the parse job record failed in its own session; the staging folder is deleted only when no job record names it | A Stop has to reach work that is already outside the submission |

The claim and the give-up in step 5, and the staging cleanup in step 4, also commit on
their own; those steps say why.

## Configuration

| Where | Setting | Default | Meaning |
| --- | --- | --- | --- |
| `modules/extraction/.../feature.json` | service user `iap-extraction` | | `jcr:read` and `jcr:modifyProperties` on `/Submissions`: it only sets the claim and the status; the engine writes everything else as its own user. `jcr:read` on `/Workflows`, to check the reading workflow exists |
| same | queue `iap-extraction` | `maxparallel` 1 | One submission read at a time |
| same | bundle start order | 29 | After the LLM client, the engine and the models |
| documents `feature.json` | `daemonUrl` on PID `io.uhndata.iap.documents.internal.DaemonConnection` | `${docling.url}` | Where the daemon is. The one PID is read by both `ParseJobConsumer` (dispatch) and `ParseAbandonment` (Stop). |
| same PID | `responseTimeout` | 30 s | How long to wait for the daemon to answer a dispatch or a `/cancel`. A Stop does not wait for `/cancel`; only the log hears the answer. |
| same PID | `daemonToken` | `IAP_DOCLING_TOKEN` | Overrides the environment's daemon token |
| PIDs of `ParseJobConsumer` and `ParseCallbackServlet` | `callbackToken` | `IAP_DOCLING_CALLBACK_JWT` | Overrides the environment's callback token |
| documents `feature.json` | `StaleParseJobSweeper.maxAgeMinutes` | 30 | When an unanswered parse is given up |
| environment | `IAP_DOCLING_CALLBACK_JWT` | | The shared token for callbacks; without it no parse is dispatched |
| environment | `IAP_DOCLING_TOKEN` | | Optional bearer token for `/parse` and `/cancel` |
| environment | `IAP_SHARED_DOCS` | `/shared-docs` | The shared volume |
| documents `feature.json` | `maxFileSize`, `maxPdfPages`, `maxUnzippedSize` on PID `io.uhndata.iap.documents.internal.ConfiguredDocumentLimits` | 50 MB, 500, 512 MB | The `DocumentLimits`: checked on upload, served at `/system/documents/limits`, and sent with each `/parse`. Read from `IAP_UPLOAD_MAX_BYTES`, `IAP_UPLOAD_MAX_PDF_PAGES` and `IAP_UPLOAD_MAX_UNZIPPED_BYTES`. Zero or less means the default. |
| daemon environment | `IAP_MAX_INPUT_BYTES`, `IAP_MAX_INPUT_PAGES`, `IAP_MAX_EXPANDED_BYTES` | 64 MiB, 500, 512 MiB | The daemon's own ceilings. The limits Java sends apply under them; 0 turns one off. |
| LLM settings | the active model and its context window | 131072 tokens if unset | See the `llm` module |

## Writing a reading workflow

A reading workflow is ordinary workflow content under `/Workflows`, named by
`readingWorkflow` on the schema version (`jcr:reference:readingWorkflow` in the schema's
JSON). Its service tasks use the handlers above.

The worked example is the research-proposal demo's `readProposal`, named by
`/Schemas/researchProposal`. Its content is `Workflows/readProposal.json`, with the
diagram in `Workflows/readProposal/v1/bpmn.xml`, under
`demos/research-proposal/src/main/resources/SLING-INF/content/`:

1. `classify` (`classifyDocument`). The schema's two classifications, `is_proposal`
   (with a `template.md` child: the usual protocol structure) and `proposal_category`
   (options from `/Categories/Proposal`), both read `proposal`, so they go in one call.
2. Gateway `isProposal`:
   - `property` `extractionStatus` equals `failed`: end. The gate already wrote why.
   - `decision` `is_proposal/decision` equals `yes`: on to the study type.
   - equals `no`: `markCompleteness`, `finishReading`, end ("Not a research proposal").
   - default, not settled: `markCompleteness`, then `finishReading` with the `message`
     "The reading waits for you to confirm whether this is a research proposal.", then
     the user task `confirmProposalUnsettled` (performer `@creator`, `requirement`
     `is_proposal`), which leads back to the gateway. A pick still not settled pauses
     again.
3. Gateway `studyType`, on `decision` `proposal_category/decision`:
   - `includes any` of the prospective category paths: `intakeAnswers` with
     `requirement` `[common, prospective]`.
   - `includes any` of the retrospective ones: `[common, retrospective]`.
   - `is not empty`, another category: `[common]`.
   - default, not settled: the same pause, with `confirmCategoryUnsettled` under
     `proposal_category`.
4. Every intake path: `markCompleteness`, then `finishReading`.

The demo has two more. `readPfq` (`/Schemas/pfq`) has the same shape with one
classification, and one intake that names `documents` `[preamble, questionnaire]`, so a
preamble uploaded later is read with the questionnaire. `ethicsReviewDemo` has no gate:
one intake for `ethicsReview`, with `promptFrom` `tcps2_reference.md`.

Keep in mind:

- Every path must end at `finishReading`, or the submission stays `running`. The one
  exception is a path taken only after a step wrote `failed`, such as `readProposal`'s
  `endReadingFailed`.
- Run `markCompleteness` before `finishReading`. The reading writes answers, and the
  `incomplete` tag was last worked out before it, at the upload or the last save.
- A user task that waits for the submitter should come after a `finishReading` with a
  `message`, so the view stops spinning while it waits. Give it a `requirement`, so the
  form offers it under the classification it waits on.
- Up to its first user task the reading runs as `iap-extraction`, in the job. After it,
  the rest runs in the request that completes the task, as that person (see step 6).
- Tag the reading workflow's version `active`. The job only checks that the version
  exists; an inactive one fails the reading with "The document could not be read".
- Route on a pick with the `decision` source (step 7), which names the question by its
  path and counts only a settled answer. An `answer` operand in a gateway still cannot
  name a question by its path (see "Known gaps" in [workflows.md](workflows.md)): it
  needs the question's UUID, which ties the guard to one schema version.
- `recordWhen` on `intakeAnswers` names its question by the path inside the schema
  version, so it can gate what is saved without that tie.

## What is still missing

| Gap | Effect |
| --- | --- |
| A `recordWhen` gate on a question the same `intakeAnswers` step asks has no confidence threshold | An unsure intake answer can still open the gate |
| After a pause, the rest of the reading runs in the request that completes the user task, with `extractionStatus` at `done` | The view shows no progress while the intake runs, the button waits for the model call, and Stop cannot reach it |
| Only the start and end of a long document are read | The middle of a document past the context window is never read, and the confidence does not say so |
| Only the first parsed upload is read unless `documents` is set | Satellite files, such as a consent form or a covering letter, are ignored by default |
| The category does not choose the schema | It is recorded next to the schema the submitter already chose |
| `editDistance` on `sub:Extraction` is declared but never written | Nothing measures how far the submitter's value moved from the model's |
| No chat over the document (two calls: a router, then an answer) | The submitter cannot ask the document a question of their own |
| Nothing better than asking the submitter when the model cannot place a study | The reading waits, and only goes on when somebody answers the category and completes the task |

## Files

Paths are under `modules/extraction/`, except the packaging file, whose path is from the
repository root. Classes are in
`src/main/java/io/uhndata/iap/extraction/internal/`, content in
`src/main/resources/SLING-INF/content/`, prompts in `src/main/resources/prompts/`.

### Wiring

| File | What it does |
| --- | --- |
| `pom.xml` | The bundle. Depends on `iap-llm`, `iap-documents-api`, `iap-workflows`, `iap-submissions-api`, `iap-schemas-api`, `iap-data-content-api`, `iap-error-tracking-api`, `iap-java-utils`, and `iap-tags-api` for tests. Sets a 0.99 coverage floor and says why. |
| `src/main/features/feature.json` | Declares the bundle at start order 29, after the LLM client, the workflow engine, and the submissions and schemas models. Creates the `iap-extraction` service user and runs the extraction job queue one submission at a time (see "Configuration"). |
| `packaging/slingfeature/src/main/features/core/extraction.json` | Deploys the bundle. Without it the module builds and is never installed. |

### Reading a parse in

| File | What it does |
| --- | --- |
| `ParseResultIngester` | Copies a finished parse onto the `sub:File` node, under the child names the `File` model defines (`MARKDOWN_CHILD`, `PDF_CHILD`). Its `discardStaging` is called by `ParseCompletionHandler` once the engine has committed; the ingester clears nothing itself. |
| `ParsePropertyNames` | Property names and status values for a parse, as stored on a `sub:File` |
| `ParsedDocuments` | Reads a parsed document once per reading rather than once per step, when it is read alone |
| `DocumentScan` | A parsed document ready to have quotes checked against it: normalized once, with its headings |
| `DocumentBudget` | How much of a document one model call sends, and the log line when it is not all of it |

### Shared by every call

| File | What it does |
| --- | --- |
| `DocumentText` | Reads a parsed document's text, and tells whether a line is a heading. Finding the heading above a quote is `DocumentScan.getHeading`. |
| `ModelReplies` | Reads a JSON object and its fields out of a model reply, tolerantly |
| `ModelCall` | Asks once, and once more with a correction when the reply could not be read |
| `Prompts` | Loads prompts from the bundle, cached |

### Choosing what to ask

| File | What it does |
| --- | --- |
| `ExtractionField` | One answer to read out of a document, read from a `sch:Question` |
| `ExtractionFields` | Which questions a step asks, how they are keyed, and what becomes of the answers |

### The intake

| File | What it does |
| --- | --- |
| `AnswerIntakeService` | Asks the model every field in one call, and checks the quotes against the whole document |
| `IntakePayload` | What the model is shown (`SCHEMA`, then `DOCUMENT`) and the JSON schema its reply must match |
| `FieldResult` | One field's answer after its quotes were checked: found or not, confidence, value, reasoning, quotes |
| `QuoteVerifier` | Whether a quote is really in the text the model was shown |
| `prompts/intake_system.md` | System prompt for the intake: the blocks, the evidence rules, the output rules |
| `prompts/tcps2_reference.md` | A reference text a step can add with `promptFrom`. The demo's `ethicsReviewDemo` names it. |

### Running it

| File | What it does |
| --- | --- |
| `ParseDocumentsHandler` | Service task `parseDocuments`: stages every upload still wanting a parse and queues it |
| `ParseCompletionHandler` | Takes a finished parse from the documents module and fires `documentParsed` on the submission |
| `IngestParseHandler` | Service task `ingestParse`: reads the parse onto the file, or records that it failed |
| `QueueExtractionHandler` | Service task `queueExtraction`: queues the reading job; the job reads once every parse has settled |
| `ReadAgainHandler` | Service task `readAgain`: the submitter asks for the reading again; queues the same job |
| `ExtractAnswersJobConsumer` | The background job that decides whose turn it is to read and fires `extractAnswers` |
| `ClassifyDocumentHandler` | Service task `classifyDocument`: one call per document for the classification requirements that point at it; picks sure enough are written as pre-filled answers |
| `IntakeAnswersHandler` | Service task `intakeAnswers`: the intake for one or more requirements, over the named documents (see step 8) |
| `FinishReadingHandler` | Service task `finishReading`: moves a still-`running` submission to `done` |
| `ReviewExtractionHandler` | Service task `reviewExtraction`: the submitter's verdict on a pre-filled answer |
| `StopProcessingHandler` | Service task `stopProcessing`: stops a parse or a reading that is still going |
| `ReadingRuns` | Which thread is reading each submission, so a Stop can interrupt it |
| `SubmitterAccess` | Lets only the submitter, and only while the request is a draft, change how it is read |
| `ExtractionStatus` | The `extractionStatus` and `extractionMessage` the view reads, and the values they take |
| `SubmissionFiles` | A submission's latest uploads, the first one parsed, and whether every parse has settled |
| `ExtractedAnswers` | Writes an answer, its extraction and its evidence. References are set with `ReferenceUtils` from `iap-java-utils`. |
| `SystemWorkflows/documentParsed` (`.json`, `bpmn.xml`) | Reads a finished parse in, then queues the reading. Performer: `iap-extraction` only. |
| `SystemWorkflows/extractAnswers` (`.json`, `bpmn.xml`) | Starts the reading workflow the schema names, ending an earlier one still open. Performer: `iap-extraction` only. |
| `SystemWorkflows/readAgain` (`.json`, `bpmn.xml`) | Queues the reading again for the submitter |
| `SystemWorkflows/retryParse` (`.json`, `bpmn.xml`) | Sends again the uploads whose parse failed, and any never sent. The other workflow that runs `parseDocuments` is the submissions module's `attachDocument`. |
| `SystemWorkflows/stopProcessing` (`.json`, `bpmn.xml`) | Stops a parse or a reading that is still going |
| `SystemWorkflows/reviewExtraction` (`.json`, `bpmn.xml`) | Records what the submitter made of a pre-filled answer |

### Outside this module

Java classes are named alone; each module's are under its `src/main/java`. Other paths
are under these folders:

| Module | Folder |
| --- | --- |
| submissions, workflow content | `modules/submissions/api/src/main/resources/SLING-INF/content/` |
| submissions, frontend | `modules/submissions/impl/src/main/frontend/src/` |
| frontend-commons | `modules/frontend-commons/src/main/frontend/src/` |
| workflows | `modules/workflows/src/main/resources/SLING-INF/content/WorkflowTypes/WorkflowTypes/` |
| demo | `demos/research-proposal/src/main/resources/SLING-INF/content/` |

| Module | File | What it does for the reading |
| --- | --- | --- |
| documents | `ParseService`, `ParseControl`, `ParseOutcomeHandler` | Stage and queue a parse for a repository node; abandon one; be told how it ended |
| documents | `DocumentLimits`, `ConfiguredDocumentLimits` | The upload limits, configured once and sent with each `/parse` |
| documents | `DocumentLimitsServlet` | Serves the limits to the browser at `/system/documents/limits` |
| submissions | `SystemWorkflows/attachDocument` (`.json`, `bpmn.xml`) | `attachDocument`, `markCompleteness`, then `parseDocuments` (step 1) |
| submissions | `AttachDocumentHandler`, `PdfPageCounter` | Checks and stores an upload in the versioned document layout; counts a PDF's pages with PDFBox |
| submissions | `DetachDocumentHandler` | Removes a document; refused while it is parsed or read |
| submissions | `DocumentReadings` | Drops the readings of a document replaced or removed |
| submissions | `MarkCompletenessHandler` | Service task `markCompleteness`: the `incomplete` tag |
| submissions | `DecisionOperandResolver` | The `decision` condition source the gateways route on (step 7) |
| submissions | `SubmissionFormServlet`, `ReadingProjection`, `ParseStatus`, `ProvenanceProjection` | The form projection's reading parts (see "What the submitter sees") |
| submissions | `SubmissionView.tsx`, `readingProgress.ts`, `submissionForm.ts`, `SubmissionEditor.tsx`, `SubmissionTasks.tsx`, `AnswerProvenance.tsx`, `provenance.ts` | The page (see "What the submitter sees") |
| frontend-commons | `pdfViewer/PdfViewer.tsx` | Opens a cited passage in its PDF |
| workflows | `ServiceTask.json`, `UserTask.json` | Map the `iap:` attributes the reading uses, including `iap:requirement` on a user task |
| demo | `Schemas/researchProposal.json`, `Schemas/researchProposal/v1/is_proposal/template.md` | The schema that names `readProposal`, with two classifications |
| demo | `Workflows/readProposal.json`, `Workflows/readProposal/v1/bpmn.xml` | The worked example (see "Writing a reading workflow") |
| demo | `Schemas/pfq.json`, `Workflows/readPfq.json`, `Workflows/readPfq/v1/bpmn.xml` | A questionnaire, read with its preamble |
| demo | `Schemas/ethicsReviewDemo.json`, `Workflows/ethicsReviewDemo.json`, `Workflows/ethicsReviewDemo/v1/bpmn.xml` | One intake with `promptFrom`, no gate |
