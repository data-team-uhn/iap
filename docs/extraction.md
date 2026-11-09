# Answer extraction

**Module:** `modules/extraction`. **Bundle:** `iap-extraction`. **Package:**
`io.uhndata.iap.extraction.internal` (nothing exported). **Uses:** `iap-documents-api`
(parsing), `iap-llm` (the model), `iap-workflows` (the engine), `iap-submissions-api`
and `iap-schemas-api` (the models it reads and writes).

A submitter uploads a document, such as a research protocol. The platform turns it into
text and asks a language model the questions that the schema marks as answerable from a
document. It checks every quote the model gives against the text, and saves what it
found as suggestions next to the answers. A suggestion is never an answer: the answer's
`value` stays empty until the submitter accepts the suggestion, edits it, or picks
something else. The model is never trusted on its own word. A quote counts only if it is
really in the document, and a question the model is unsure about is left for a person to
answer.

This page follows the whole path, from the upload to the saved answer. For each step it
says which class does the work, who calls it, and what it checks.

## The whole path

```
 submitter asks for a parse (retryParse; no upload step starts one yet)
        |
        v
 parseDocuments ------ stage the file on the shared volume, queue a parse job
 (service task)        file: parseStatus=queued    submission: extractionStatus=running
        |
        v
 Docling daemon ------ converts the file to Markdown (+ a PDF for office files)
 (Python, port 18765)  POSTs the outcome to /system/documents/parseCallback
        |
        v
 documents module ---- records the outcome on the job node; ParseOutcomeDispatcher
                       hands it to every ParseOutcomeHandler
        |
        v
 ParseCompletionHandler -- fires documentParsed on the submission
        |
        v
 documentParsed (system workflow)
   ingestParse -------- copy the Markdown and the PDF onto the file node
   queueExtraction ---- add a Sling job on topic iap/extraction/extract
        |
        v  (the engine commits, then the staging folder is deleted)
 ExtractAnswersJobConsumer (background, one job at a time)
   every parse settled? -- no: try again later
   claim the reading ----- another job has it: stop
   fire extractAnswers
        |
        v
 extractAnswers (system workflow)
   startWorkflow -- follow schemaVersion/readingWorkflow, cancel an older reading
                    still open, start the schema's reading workflow
        |
        v
 the reading workflow (content, named by the schema version; none ships on this branch)
   classifyDocument -- the gate: what kind of document is this?
   gateways ---------- act on the pick (see step 7)
   intakeAnswers ----- ask the remaining questions, ten per call at most
   finishReading ----- extractionStatus=done
```

Everything from `extractAnswers` to the reading workflow's first user task (or to its
end, if it has none) runs in one engine walk. It runs on the job's thread, as the
`iap-extraction` user, and is saved in one commit: either all of it is saved, or none of
it. What comes after a user task runs later, in another request (see step 6).

## Step 1: sending a document to be parsed

**Class:** `ParseDocumentsHandler`. **Handler name:** `parseDocuments`. **Called by:**
the `retryParse` system workflow. No upload step calls it yet: `attachDocument` only
stores the file.

What it does, in order:

1. Checks the caller with `SubmitterAccess.checkMayChange`: only the person who created
   the submission, and only while it is a draft. Anyone else gets a 403. A submitted
   request gets a 409.
2. Checks `readingWorkflow` on the submission's schema version
   (`SubmissionFiles.hasReadingWorkflow`: it must lead to a workflow version that exists
   and is tagged `active`). **No reading workflow that can run means nothing is parsed**,
   so a schema that never reads its documents costs nothing. One that is named but cannot
   run is also reported to the administrator.
3. Takes the latest upload of each document (`SubmissionFiles.getFilesToParse`). A
   document whose requirement sets `skipParsing` is left out, so its uploads are never
   parsed and no reading uses them. An upload is sent when its `parseStatus` is empty
   (never sent) or `failed`. One that is `queued` or `completed` is left alone. An
   upload with no content is skipped, and the problem "An uploaded file holds no content
   to parse" is recorded.
4. For each upload it sends:
   - `ParseService.stage` copies the bytes into a new folder on the shared volume,
     `/shared-docs/<uuid>/<name>`. The name is `fileName` on the `sub:File`, or the
     document's `title` when that is missing (the upload handler keeps the uploaded name
     there). Characters that are not safe in a file name become `_`.
   - `ParseService.queue` creates a job node under `/var/documents/jobs/<jobId>` and a
     Sling job on topic `iap/documents/parse`. The job names the `sub:File` as its
     `target`.
   - The file gets `parseStatus=queued`, `parseJobId` and `sharedPath`, and loses any
     old `parseError`.
   - If queueing fails, the staged folder is deleted at once, because usually no job
     node names it and no sweep would find it. If staging itself fails partway, the
     half-written folder stays on the volume.
5. If it sent at least one upload, the submission gets `extractionStatus=running` and
   loses `extractionMessage` and `extractionBlocked`. The claim
   (`extractionReadingClaimed`, `extractionReadingClaimedBy`) is removed too, since a
   new document means a new reading.

Staging and queueing happen inside the walk, before it commits. `queue` commits the job
node in a session of its own, so the Sling job can see it. This means an outcome can
arrive before the file's `parseJobId` is committed, most often when the daemon cannot be
reached. Step 4 handles this by looking at the file again for a few seconds.

## Step 2: the daemon parses it

**Code:** `modules/documents/processing` (Python). **Called by:** `ParseJobConsumer` in
the documents module. It marks the job node `active` (only if it is still `queued`),
then sends `POST /parse?path=&job_id=`.

The daemon answers "queued" at once and converts in the background. It runs one
conversion at a time (`MAX_CONCURRENT_PARSES` = 1). It writes `<stem>.md` next to the
staged file, plus `<stem>.pdf` when LibreOffice made a PDF out of an office file. Then
it POSTs the outcome to `/system/documents/parseCallback`, with the shared token from
`IAP_DOCLING_CALLBACK_JWT`. See
[processing.md](../modules/documents/processing/processing.md) for the conversion
itself.

The daemon also serves `POST /cancel?job_id=`, which Stop uses (see "What the submitter
can do"):

| State of the parse | What `/cancel` does | Reply `status` |
| --- | --- | --- |
| Waiting in the daemon's queue | Drops it; it never starts | `cancelled` |
| Converting | Flags it, and no callback is sent. While it waits on page batches, the PDF loop checks the flag every 2 s (`ABANDON_POLL_SECONDS`). It drops the batches not started yet, and waits for the running ones to stop: up to Docling's `document_timeout` for one batch, plus 120 s (`get_cancel_wait_seconds`). A batch still running after that marks the worker as stuck, and `/health` starts failing. A DOCX converts in one call, so it cannot be cut short. | `running` |
| Not known to this daemon | Nothing | `unknown` |

## Step 3: the parse comes back

**Class:** `ParseCallbackServlet` (documents module). It checks the bearer token and
records the outcome on the job node: `completed` with the outputs, or `failed` with an
error. Then it commits and calls `ParseOutcomeDispatcher.settle`.

`settle` gives the outcome to every registered `ParseOutcomeHandler`. If one of them
takes it, the job node is deleted. If none does, the node stays.

Other callers of `settle`:

| Caller | When |
| --- | --- |
| `ParseJobConsumer` | The daemon refused the dispatch or could not be reached, or no callback token is set. The job is marked failed. |
| `StaleParseJobSweeper` | A job got no outcome within `maxAgeMinutes` (30). It is marked failed with "No outcome arrived within 30 minutes; the parse was given up on", and its staging folder is deleted at once. |
| `StaleParseJobSweeper` | A job with a `target` settled at least 2 minutes ago (`RETRY_AFTER_MILLISECONDS`) and no handler took it. It is offered again on every sweep until it is 30 minutes old. |

The sweep runs every 5 minutes (`DEFAULT_PERIOD_SECONDS` = 300), and only on the cluster
leader. A settled record older than 30 minutes is deleted together with its staging
folder. If the folder cannot be removed, the record is kept, because it is the only
thing that names the folder, and the next sweep tries again. A day after the job settled
(`GIVE_UP_ON_FOLDER_MILLISECONDS`), the record is dropped anyway, with an `ERROR` log
line and the problem "A parse staging folder cannot be removed" for an administrator.

A record that names a `target` and is still there at 30 minutes is one that no handler
ever took. Whatever waited on it may now wait forever, so dropping it logs a `WARN` and
records the problem "A parse outcome was never taken in, so its file may stay waiting
for it". A file left `queued` this way keeps the reading waiting until the submitter
presses Stop.

So the same outcome can reach the handlers more than once: offered again by a sweep, or
offered again because its record could not be deleted. A `ParseOutcomeHandler` must
therefore be idempotent, as its javadoc says. `ParseCompletionHandler` is: see its third
check below.

A callback for a job whose record is gone gets a 404, the same as an unknown job. A
callback for a stopped job still finds its record, and replaces the `Stopped` failure
with its own outcome (see "Stop").

## Step 4: reading the parse into the repository

**Class:** `ParseCompletionHandler` (a `ParseOutcomeHandler`). **Called by:**
`ParseOutcomeDispatcher.settle`.

It opens a session as the `iap-extraction` service user and checks:

| Check | If it fails |
| --- | --- |
| The `target` file still exists and is under a submission | Returns "not taken", and the sweep offers the outcome again. |
| The file's `parseJobId` is this outcome's job id | It looks again, up to 10 looks half a second apart (`COMMIT_WAIT_LOOKS`), because the walk that queued the parse may not have committed yet. Then it returns "not taken": this is an old parse that the file no longer waits for. |
| The file's `parseStatus` is still `queued` | It only clears the staging folder. This is the same outcome delivered again. |

On a failed parse, it logs a `WARN` with the daemon's reason and records the problem "A
document could not be parsed: <reason>" for an administrator. The submitter is only told
to try again. This happens before the engine call, so if the engine call fails and the
sweep offers the outcome again, it is logged again.

Then it fires `documentParsed` on the submission, as the `iap-extraction` user. The
payload carries the file path, `succeeded`, `error`, `markdown`, `tokens`, and `pdf`
when a `<stem>.pdf` exists next to the Markdown.

The `documentParsed` system workflow runs two steps:

1. **`ingestParse`** (`IngestParseHandler`, using `ParseResultIngester`).
   - On a failed parse, the file gets `parseStatus=failed` and `parseError` (or "The
     parse failed without details").
   - On a success, the Markdown and the PDF are copied onto the file node. Details
     below.
2. **`queueExtraction`** (`QueueExtractionHandler`): adds a Sling job on
   `iap/extraction/extract` that names the submission. It does this for failed parses
   too, so the reading can report the failure.

Only after the engine has committed does `ParseCompletionHandler` delete the staging
folder (`ParseResultIngester.discardStaging`, which calls
`ParseService.discardStaging`). Until that commit, the shared volume holds the only copy
of the parse. If the folder cannot be removed, the handler returns "not taken". The job
record, the only thing that names the folder, then stays, and the sweep tries again
later.

What `ParseResultIngester.ingest` checks and writes:

| Check | Result |
| --- | --- |
| The Markdown path is on the shared volume (`ParseService.isStagedPath`) | If not: `parseStatus=failed`, "The parse named ..., which is not on the shared volume", and the problem "The document daemon named a file outside the shared volume" is recorded. A file outside the volume is never read. |
| The Markdown file exists | If not: `parseStatus=failed`, "The parse named ..., which is not there". |
| The PDF path is on the shared volume | If not: the PDF is skipped, a warning is logged, and the same problem is recorded. |
| The upload is itself a PDF (`File.isUploadAPdf`, which reads `uploadedFile/jcr:content/jcr:mimeType`) | The PDF is not stored a second time. `File.getFilePdf()` returns the upload instead. |

The ingester also has a message for a success that names no Markdown ("The parse
finished without naming the text it produced"), but nothing reaches it. The callback
refuses such a success with a 400, and `IngestParseHandler` would throw first ("A
successful parse names the Markdown it produced"). That reverts the walk,
`ParseCompletionHandler` does not take the outcome, and the sweep offers it again until
it drops the record at 30 minutes. The file stays `queued`.

On success the file gets `parseStatus=completed`, `tokens` (the daemon's estimate), a
`markdownFile` child (`text/markdown`) and, for office uploads, a `pdfFile` child
(`application/pdf`). A new parse deletes the old child before it writes the new one.
Files are streamed into the repository, not loaded into memory first. Anything else the
pipeline left on the volume (a `.docx` made from a `.doc`, temporary files) is deleted
with the folder.

## Step 5: whose turn it is to read

**Class:** `ExtractAnswersJobConsumer`. **Job topic:** `iap/extraction/extract`.
**Queue:** `iap-extraction`, one job at a time (`queue.maxparallel` = 1).

Every parse that lands queues a job, so a submission with three uploads queues three
jobs. The job decides who reads, not the workflow step. A workflow step runs before its
commit and cannot see what other requests are doing. The job's own session sees
committed data.

The checks, in order:

| Check | If it fails |
| --- | --- |
| The job names a submission, and it still exists | The job is dropped. |
| A job queued by Read again (job property `readAgain=true`) finds `extractionStatus` at `running` | The job returns `FAILED`, so Sling runs it again later. The job was queued before the Read again walk committed, and until that commit the old status and claim are still there. |
| No current file is still `queued` (`SubmissionFiles.isEveryParseSettled`) | The job returns `FAILED`, so Sling runs it again later. A parse that landed but is not committed yet looks "queued" for a moment. |
| The submission can be checked out, if it was checked in (`ExtractionStatus.checkOut`) | The job is dropped, with an error for an administrator. A submission is versionable, and when checked in it is read-only, with everything under it. One checkout covers every later write. |
| The job's session can read the submission's schema version | `extractionStatus=failed`, "The document could not be read", and a problem is logged for an administrator. Throwing instead would end the job with `running` left behind. |
| The schema version's `readingWorkflow` points at a `wf:WorkflowVersion` that exists and is tagged `active` (`SubmissionFiles.hasReadingWorkflow`, the same check parsing makes) | `extractionStatus=failed`, "Nothing is set up to read this request's documents". Firing the event anyway would start nothing, or be refused, and leave `running` forever. |
| `extractionReadingClaimed` is not set, and setting it (with the job's Sling job id in `extractionReadingClaimedBy`) commits. A claim that already holds this job's own id counts as its own: after a restart, Sling runs an unfinished job again with the same id. | A claim held by another id means another job has the reading, and this one is dropped. Two jobs that race write different ids, so the repository refuses the second commit. |

Then the job fires `extractAnswers` on the submission. If that throws, the job "gives
up": in a commit of its own, it writes `extractionStatus=failed` with "The document
could not be read", and removes the claim. Without this, the submission would stay
`running` forever.

`ReadingRuns.begin` registers the job's thread under the submission path, so that Stop
can reach it (see "Stop" below). A stopped reading gives up the same way, but records
"Reading was stopped." instead.

## Step 6: starting the schema's reading workflow

**System workflow:** `extractAnswers`. It has one step: the built-in `startWorkflow`
handler, with `workflowFrom: schemaVersion/readingWorkflow` and `replaceActive: true`
(`iap:replaceActive` in its diagram).

- It follows the submission's `schemaVersion` reference, then the schema version's
  `readingWorkflow` reference, to a `wf:WorkflowVersion`. If the chain does not end at a
  workflow version, nothing happens.
- `replaceActive` cancels every instance of the same workflow (any version) still active
  on the submission, with its open tasks, before it starts the new one. So a reading
  that was waiting for the submitter to confirm a category is replaced, not run twice.
- The reading workflow is a normal content workflow with a saved instance, so it can
  branch and wait for a person. A system workflow can do neither, which is why the
  reading is not written into `extractAnswers` itself.

Why the reading is not a few steps in the submission's own workflow: nothing could start
them there. A reading begins when a parse lands, in a background job, not in a request
aimed at that instance. And nothing can deliver a message to a running instance: the
engine refuses a free-standing catching event instead of waiting on it, and a boundary
event fires only when its timer runs out (see "Known gaps" in
[workflows.md](workflows.md)). So the reading gets its own instance, started on the
submission when its parse lands.

Steps after the reading workflow's first user task run in the request that completes
that task, with that person as the actor, and are saved in that request's commit. The
job is long finished by then. Its give-up does not cover these steps, and the extraction
queue does not limit them to one at a time (the LLM call gate still does). Stop can
still reach a classify or intake step there while the status is `running`, because those
steps register the request's thread with `ReadingRuns` too.

Only the `iap-extraction` user may fire `documentParsed` and `extractAnswers`: their
start events name it as the only performer. Administrators can too, since they pass
every performer check. The `documentParsed` payload carries paths on the shared volume,
and no ordinary user should be able to have files read from there.

## Step 7: the gate (classifying the document)

**Class:** `ClassifyDocumentHandler`. **Handler name:** `classifyDocument`. **Schema
type:** `sch:ClassificationRequirement`.

A classification requirement is a form requirement with one question (found by walking
its children, sections included). The question's options are the categories to pick
from. The requirement also has:

| Property | Meaning | Default |
| --- | --- | --- |
| `prompt` | What the model must decide, and how to tell | required |
| `document` | The name of the document requirement whose upload is classified | required |
| `confidenceThreshold` | The confidence a pick needs to be saved. A less sure pick is not saved, and the question waits for the submitter. | 0.7 |
| `template` (or `template.md`) child | Reference text shown to the model, for example the usual structure of a research protocol | none |

What the step does:

1. Collects the "open" classifications. It skips a classification whose condition does
   not hold, one with no question or no `document`, and one whose question already has
   a value or a run that found a pick (an empty value saved by the form does not count).
   So a second upload does not pay again for a pick already made, and a newer version of
   the document does not change it either. This differs from the intake, which asks a
   question with only a suggestion again: a pick decides the reading's route.
2. Groups them by `document`. For each document:
   - No upload: skipped.
   - Parse `failed`: `extractionStatus=failed`, "The document could not be read. Please
     try again", and the step stops. The parse's own reason stays in `parseError`.
   - Parse not `completed` yet: skipped.
   - Otherwise: all of that document's classifications are asked together, as one group.
3. The call goes through `AnswerIntakeService`, the same code as the intake (step 8).
   Each template goes in the `<reference>` block, after the document, under
   `# Reference for "<label>"`. Each question's instructions are the requirement's
   `prompt`; its categories are listed as the values to answer with.
4. Only sure picks are saved. A pick is sure when the model found it, its confidence is
   at or above the requirement's `confidenceThreshold`, and every value it names is one
   of the question's options. A sure pick is written like any extracted answer (see
   "Writing an answer"): a run with confidence, reasoning and quotes under the
   question's answer, whose value stays empty. Nothing at all is written for any other
   pick, so the question stays open for the submitter and for the next reading. The
   option check is needed because not every provider keeps a reply within the schema's
   list. The gate must not guess.
5. If the model could not be asked, or answered unreadably twice:
   `extractionStatus=failed`, and the step stops.

This step does not decide whether the workflow acts on the pick: the reading workflow's
gateways do. The pick is not in `value`, so a gateway reads it through the `decision`
condition source (`DecisionOperandResolver`, submissions module). That source gives the
submitter's value, or else the suggestion (`Answer.getValueOrSuggestion`). The form's
own conditions keep using the `answer` source, so nothing the submitter sees changes
because of a pick they have not accepted.

## Step 8: reading the answers (the intake)

**Class:** `IntakeAnswersHandler`. **Handler name:** `intakeAnswers`.

Activity properties a reading workflow can set on this step:

| Property | Meaning | When absent |
| --- | --- | --- |
| `requirement` | **Required.** One requirement name, or several (a list, or comma-separated). The step asks only these requirements' questions, each requirement as its own group, in that order, at most ten questions per call. | The step fails with "This reading is not set up correctly", and the administrator is told which step names none. So does a name that is not a form requirement of the schema version: a typo, or a document requirement. |
| `documents` | The document requirement names to read, in order. Each document's text goes under its own heading (`# Preamble` for `preamble`), and together they are one document to the model. | The first upload with a completed parse |
| `promptFrom` | Reference files from the bundle's `/prompts` folder, sent in the `<reference>` block after the document. A name the bundle does not have fails the step. | No reference |

In a diagram these are the service task's `iap:requirement`, `iap:documents` and
`iap:promptFrom` attributes. `documents` and `promptFrom` are lists, written
comma-separated. `requirement` is one string, which the handler splits on commas itself.
All three are in the `properties` list of `ServiceTask.json`, together with
`iap:message` and `iap:blocks` (step 9) and `iap:replaceActive` (step 6). See [What an
executable graph carries](workflows.md#what-an-executable-graph-carries) for how that
list works.

What the step does:

1. **Which documents.** With `documents`: each named document with a completed parse is
   read. A named document that was never uploaded is skipped. If a named document's
   parse failed, the step records `failed` with "The document could not be read. Please
   try again", and asks nothing; the reason stays in `parseError`. If no document is
   left, it records `failed` with the reason (`describeNothingToRead`). Without
   `documents`: it reads the first upload with a completed parse, or records `failed`
   with the reason.
2. **Which questions.** Only the questions of the named requirements that apply
   (conditions resolved), that have an `extractionPrompt`, and that **the submitter has
   not answered**. Only the submitter's own non-blank value counts as an answer: a
   question with only a suggestion is asked again, and so is an empty value saved by the
   form (a field somebody clicked through or emptied). The first reading of a newer
   document version asks every question, answered or not (see "A newer version").
   Questions are keyed by their path inside the schema version with dots for slashes
   (`study.title`), so two sections may both have a `title`.
3. **Nothing to ask.** If every question already has an answer, or a named requirement
   asks nothing (for example its condition does not hold): the step does nothing.
4. **Ask** the model (see the next section).
5. **No call went through** (every reply unreadable twice, or the model unreachable):
   `extractionStatus=failed` with the reason, and nothing is written.
6. **Otherwise** every question whose call went through gets a run, found or not (see
   "Writing an answer"). The status stays as it is: `finishReading` (step 9) ends the
   reading. When some calls did not go through, their questions get no run, so the next
   reading asks them again, and the step leaves the note "Some questions could not be
   read. Read again to try them again" (`ExtractionStatus.recordNote`), which
   `finishReading` keeps.

A model that cannot be reached is recorded as `failed`, not thrown as a workflow error.
Throwing would undo the answers that earlier steps of the reading wrote, and the job's
give-up would replace the real reason with the general "The document could not be read".

## Step 9: ending the reading

**Class:** `FinishReadingHandler`. **Handler name:** `finishReading`.

Changes `extractionStatus` from `running` to `done`, with the step's `message`
(`iap:message` in a diagram) if it has one, for example to say that the reading waits
for the submitter. Any other status is left as it is: a step that already wrote `failed`
said something this step does not know. A reading workflow puts this step at the end of
every path, so the view never spins forever.

A step where the request cannot go on also sets `blocks` (`iap:blocks` in a diagram),
for example after the gate decided the document is not what the request asks for. If the
status is still `running`, the step then sets `extractionBlocked=true` on the
submission, and the `message` says why. A new reading removes the flag when it records
`running`: when Retry queues a parse, or on Read again. Nothing on this branch reads the
flag yet. The request's own workflow and the form are meant to read it, to stop the
request from going on.

`finishReading` is the only step that writes `done`; the intake leaves the status to it.
Without a `message` of its own it keeps a note an earlier step left, such as the
intake's "Some questions could not be read"; with one, its own message wins, since that
is the one the workflow's author chose to show.

## Asking the model

**Classes:** `AnswerIntakeService` (plans and sums up), `ExtractionPrompt` (what each
call sends), `ResponseSchema` (the reply format), `DocumentReader` (makes the calls),
`ExtractionReply` (reads a reply), `ModelCall` (one call that Stop can end), `Prompts`.
Both the gate and the intake use them. The prompt building comes from the
`extraction-prompts` branch.

### What is sent

Every call on a document opens with the same text: the system prompt, then the
document. Only what follows changes. A hosted provider caches the start of a prompt, and
a local model reuses its KV cache for it, so after the first call on a document (the
gate's, usually) the document costs little: a call on a document the provider had not
seen took 47.4 s, a repeat 3.8 s. That is what makes several small calls cheaper than
one big one.

- **System prompt:** `prompts/extraction_system.md`, the same for every call. It sets
  the rules: the document is data, and instructions inside it are ignored; for each
  question, quotes first, then reasoning, then the answer; a quote is copied word for
  word and stays within one page; an answer with no quote that supports it is null.
- **User message**, in this order:
  - `<document>`: the document text. A `document`, `reference` or `questions` tag inside
    the text, even one written loosely such as `</ document >`, is written as plain text
    (`&lt;/document&gt;`). The document cannot end its own block or open a block of
    questions and pass what follows off as instructions.
  - `<reference>`, only when the step has some: the gate's templates, or the intake's
    `promptFrom` files. It comes after the document so it does not change the start that
    the calls share.
  - `<questions>`: one entry per question, under its key (`### study.title`), with
    `Question`, `Details` (the description), `Asked because` (the purpose),
    `How to find it` (the `extractionPrompt`, or the classification's `prompt`) and
    `Answer with`: the answer's form in words, such as "a whole number from 1 to 5000",
    "a date, as YYYY-MM-DD", "text, in this form: ..." (the pattern message), or the
    options as `` `value`: label (description) ``.
- **Reply schema** (strict JSON schema, named `iap_extraction`): one object per
  question, with `evidence` (a list of `{quote, page}`), `reasoning`, `value` and
  `confidence`, in that order. A provider writes the properties in schema order, so the
  model gives its quotes before it commits to a value. The value is typed from the
  question: a number is a JSON number, a yes or no is a boolean, several answers are an
  array, and a question with options takes only their values (an `enum`, per item for
  several). Null always means "not found"; there is no separate found flag. Bounds and
  patterns are in the question block only, because strict structured output rejects
  `minimum`, `maximum` and `pattern`.

A question's `responseShape` is not sent: the form of the answer comes from the
question's own data type, `maxAnswers`, bounds, pattern and options.

### Calls of at most ten questions

The questions are grouped: the gate asks one document's classifications as one group,
and the intake asks each requirement it names as its own group. A group with more than
ten questions (`MAX_FIELDS_PER_CALL`) is split into several calls, in order. A smaller
call gives a shorter reply, which a model gets right more often, and a reply that cannot
be read loses only its own questions.

### How much of the document fits

Each call's reply gets `maxOutputTokens` = 500 + 400 per question. The document is
fitted once, against the largest call of the reading, so it is the same in every call
and the shared start stays the same. It gets what is left of the model's context window
(`CallBudget.calculateDocumentTokenBudget`):

```
document budget = window - 15% safety margin - largest reply
                  - (system prompt + reference + largest question block)
```

This is the only place the size is decided; there is no separate maximum to keep in step
with it.

| Term | Why |
| --- | --- |
| window | The active model's `contextLimitTokens`. When the model does not say, or the settings cannot be read: 131072 (`DEFAULT_CONTEXT_LIMIT_TOKENS`), the smallest window of any model in the catalogue. |
| 15% safety margin | Tokens are estimated at 4 characters each (`CHARS_PER_TOKEN`), which undercounts dense Markdown, and the provider's chat template adds tokens of its own. |
| `maxOutputTokens` | A vLLM-style provider refuses a request when the prompt plus the requested output would not fit. It does not stop writing early. |

A document that does not fit keeps its opening (75% of the room) and its end (25%). The
opening holds the cover page, the synopsis and the objectives; the end holds the
appendices, the site list and the signature page. The opening stops at the last
paragraph break before its limit, and the end starts at the first paragraph break after
its own limit, so neither part starts or stops in the middle of a sentence. What the
opening gives up goes to the end. A text with no paragraph breaks is cut exactly at the
limits. The two parts are joined by `CallBudget.OMISSION_MARKER`, a line in brackets
saying the middle was left out, and the system prompt tells the model that the text on
the two sides does not follow on.

`AnswerIntakeService` logs every cut as `Document cut to fit`, with the file and how
many characters were sent and left out. If there is not even room for the omission note,
no call is planned and the model is not asked: the step fails with "The questions leave
no room to show the model the document". A blank document fails with "The document has
no text that can be read". Quotes are always checked against the whole document, so a
quote from the part that was cut still counts.

This is a real limit: an answer that is only in the middle of a very long protocol is
not found, and the confidence does not show it. Sending the whole document instead would
get the call refused, and nothing would be answered at all.

### Two tries, no more

`DocumentReader` makes the calls one after another, each through `ModelCall`, so a Stop
ends it. A reply cannot be read when it is empty, holds no JSON object
(`ModelReplies.readJsonObject` takes the text between the first `{` and the last `}`),
or leaves out one of the questions the schema requires. Then it asks once more. The note
saying what was wrong goes after the questions, under `# Correction`, so the second try
opens with the same text and still uses the cache. A second bad reply loses that call's
questions only; the other calls still go ahead.

A reply that opens more `{` than it closes ran out of room. Asking again with the same
room would likely cut it off again, so its questions are asked in two halves instead,
each with the whole call's `maxOutputTokens`. A number too long to write out (more than
100 digits, such as `1e999999999`) is read as no answer.

A model that cannot be reached stops the reading: the calls after it are not made, since
each would only wait out the same outage. The outage is recorded once, with its
exception. Separately, the LLM client sends a failed request (for example one that timed
out) once more.

| Calls that went through | Result |
| --- | --- |
| All | Every question asked gets a run |
| Some | Those calls' questions get runs; the step leaves the note "Some questions could not be read. Read again to try them again" |
| None, a model was unreachable | The step fails with "The model could not be reached" |
| None, every reply unreadable | The step fails with "The model's answer could not be read" |

A reply that cannot be read twice is recorded for an administrator once per reading,
as "The model did not answer in the required shape": a model that ignores the schema is
a setting to fix.

### One call at a time

The LLM module allows one model call at a time on the whole instance (`LLMCallGate`,
implemented by `CallGateImpl`, a fair semaphore with one slot). There is nothing to
configure. The extraction queue (`iap-extraction`, topic `iap/extraction/extract`) also
reads one submission at a time. A wider queue would not read anything sooner. It would
only move the waiting from the queue, where a job can wait as long as needed, to the
gate, where a call fails when its timeout runs out.

Why one: the provider handles a proposal-sized call alone, whatever else it is asked.
Measured against prompter on 2026-09-21, model `Qwen3.8-27B`, 200,000-token prompts,
each call with a document the provider had not seen before:

| Calls at once | Wall (s) | Per call (s) |
| --- | --- | --- |
| 1 | 39.3 | 39.3 |
| 2 | 77.3 | 38.6 |
| 4 | 152.8 | 38.2 |
| 6 | 228.4 | 38.1 |
| 8 | 304.0 | 38.0 |
| 12 | 455.1 | 37.9 |

Nothing failed, and the wall time grows in a straight line with the number of calls. The
provider does not refuse extra requests; it queues them. So sending more than one at a
time gains nothing: the extra calls wait in the provider's queue against prompter's 600
s `timeoutSeconds`. The last of the 12 calls came back after 455 s, most of that time.
Waiting at the gate keeps the wait where the caller can see it.

A caller waits for the slot for three calls' worth of `timeoutSeconds` (`QUEUED_CALLS`
in `OpenAIClient`), not one. If it waited exactly as long as one call may take, it would
give up just as the slot was about to come free, and for a reading that is a failure the
submitter sees.

Extraction makes one classify call per document, then one intake call per requirement
for every ten questions, so the limit matters more across submissions than within one.
The first call on a document pays for reading it, about 39 s; later calls on the same
document reuse the provider's cache and take a few seconds each. Ten proposals finish
about six and a half minutes after the first one starts, plus a few seconds per extra
call.

If this is measured again, give every call its own document. The same run with one
identical body for every call reported 3.8 s per call, and made a limit of 8 look right.
That was the provider's prefix cache answering, not the model working: a document it had
not seen took 47.4 s, against 3.8 s for a repeat.

## The checks that keep answers honest

| Rule | Where | What happens |
| --- | --- | --- |
| The intake never sends a question without an `extractionPrompt` | `ExtractionField.isExtractable` | Questions meant only for people are not sent. The gate asks with the classification's own `prompt`. |
| A null or blank `value` is "not found" | `ExtractionReply` | The intake writes a run with no `extractedAnswer`; the gate writes nothing. |
| Several values for a question taking one are "not found" | `ExtractionReply` | Picking one of them would be a guess. A list of one value is that value. |
| A value names an option by its value, or by its label in any case | `ExtractionReply.matchOption` | A value matching two options that read the same, or none, is kept in the model's words, for a person to correct. |
| A quote must really be in the document | `QuoteVerifier`, through `DocumentScan.locate` | A quote that is not found is dropped, never stored. |
| Quotes shorter than 3 characters do not count | `MIN_QUOTE_LENGTH` = 3 | Too easy to match by chance |
| A quote of at most 24 characters matches only whole words | `isOnWordEdges` | "yes" is not found in "eyes", nor "100" in "1000" or "$2,100,000" |
| A close match must be at least 85% right | `MATCH_FLOOR` = 0.85 | Edit distance against the best-matching stretch of text |
| Numbers and negations must match exactly | `NEGATION_WORDS` (not, no, never, without, none, nor, neither, cannot) | "was not approved" never matches "was approved" |
| A quote may not join two documents sent together | `isAcrossParts` | Those words were never next to each other |
| Confidence drops when quotes are not found | `getEvidenceFactor` | See below |
| Confidence stays between 0 and 1 | `ModelReplies.readConfidence` | A number outside 0 to 1 is clamped. A missing value or a non-number reads as 0. |
| No answer's value is ever written | `ExtractedAnswers.write` | What the model read is a run under the answer. Only the submitter's save writes `value` (see "When the submitter acts on it"). |
| The intake does not ask a question the submitter answered | `ExtractionFields.getAnswered` | Only a non-blank value counts. A question with only a suggestion, or an empty value, is asked again on the same version, and the new run is the one shown. The first reading of a newer version asks every question, answered or not (see "A newer version"). |
| The gate does not ask a classification already answered or picked | `ExtractionFields.getAnsweredOrSuggested` | A pick decides the reading's route, so it is made once, even of a newer version. Only the submitter changes it. |

How a quote is matched (`QuoteVerifier`): both texts are lowercased, page markers are
removed, and whitespace is collapsed, no-break spaces included. An exact match wins. Otherwise, for quotes longer
than 24 characters (`ANCHOR_LENGTH`) and at most 1000 (`MAX_FUZZY_QUOTE`), it looks for
the quote's first, middle and last 24 characters in the text. It takes up to 4 candidate
places (`MAX_CANDIDATES`, at most 2 per anchor), and finds the stretch of nearby text
that needs the fewest edits to become the quote. The best stretch must score at least
0.85 and match on numbers and negations. A longer quote must be there exactly.

The anchors are there because comparing a quote against a whole protocol is quadratic.
The three anchors are found quickly, and only the text around each hit is compared edit
by edit: from half a quote length before it to 1.5 quote lengths after it
(`WINDOW_FACTOR`). A model that changed all three anchors did not copy the passage. A
quote over 1000 characters that is not there word for word was made up, not copied.

A quote is checked against the whole document, not only the part that fitted in the
call. Where the token budget fell says nothing about whether a passage is real.

Both sides are normalized by the same function, `QuoteVerifier.normalize`.
`DocumentScan` normalizes the document line by line with it, so the document comes out
the same way the quote does. If the two differed (for example two spaces at a paragraph
break in the document and one in the quote), a correct quote across that break would
never be found. The document is normalized once, not once per quote. For a document read
alone, `ParsedDocuments` keeps the result (see "Who calls whom"), so the classify and
intake steps share it. An intake that names its `documents` joins their texts into one
scan itself (`IntakeAnswersHandler.getScan`, `DocumentScan.join`), and does not use that
cache.

How confidence is adjusted: for a found answer, `confidence * (0.5 + 0.5 * kept quotes /
distinct quotes given)`. All quotes found keeps the confidence, half found takes off a
quarter, and none found (or no quotes at all) halves it (`UNVERIFIED_PENALTY` = 0.5).
The answer can still be right, so it is not set to zero.

Each kept quote is placed under the nearest Markdown heading above it (`DocumentScan`,
lines starting with `#` to `######`), so the reviewer can see which section it came
from.

## Writing an answer

**Classes:** `ExtractionFields.write`, `ExtractedAnswers.write`.
`ExtractionFields.write` saves one run for each result it is given, found or not. The
intake gives it every question it asked. The gate gives it only its sure picks (step 7).

```
sub:Submission
+-- <uuid> (sub:Answer)
    +-- question  -> REFERENCE to the sch:Question
    +-- value     = only what the submitter gave; a reading never writes it
    +-- <uuid> (sub:Extraction), one per reading of the question
        +-- extractedAnswer = the model's value, several joined by commas; absent if none
        +-- confidence      = after the evidence adjustment
        +-- reasoning
        +-- sources         -> REFERENCE to every sub:DocumentVersion read
        +-- editDistance, percentageDistance = written when the submitter first acts on it
        +-- evidenceRejected = written by reviewExtraction
        +-- <uuid> (sub:Context), one per kept quote
            +-- quote, header, page
            +-- source -> WEAKREFERENCE to the version it came from
                          (only when the step names its `documents`)
```

The run goes under the answer the question already has, such as the empty one left by
the form or by `createParts`. When there is no answer, one is created with no value. If
the run cannot be written, nothing is left under the answer. Each question is written on
its own: one that fails to write (for example because its source file is gone) is logged
and skipped, and the others are still saved. A run that found nothing still names the
versions it read. This is how a later reading knows those versions were already read for
that question.

A reply's values are matched to the question's options once, when the reply is read
(`ExtractionReply`), so a run already holds option values: first by value, then by label,
ignoring case. "English" becomes `english`, and a category label becomes the category's
path, such as `/Categories/...`. If no option matches, or two options match equally, the
model's own words are kept, so a person can see what it said and fix it. Several values
are saved as one comma-separated text.

How the suggestion is read (`Answer.getSuggestedValues`, from the newest run,
`Answer.getLatestExtraction`): a single-value question keeps the text as one value,
trimmed; a multi-value question splits it on commas (`Answer.splitAnswer`). Nothing is
matched again.

### When the submitter acts on it

The answer's `value` is written only by a save (`saveAnswers`, submissions module): the
submitter accepted the suggestion, edited it, or picked something else. A save that
gives the answer a value is measured against the suggestion the form offered just before
the save (`Answer.getOfferedSuggestion`, see "A newer version"), on the newest run, if
nobody has acted on that run yet (`SuggestionDistance`, called by `SaveAnswersHandler`).
A run kept out of sight is never measured. `editDistance` is the Levenshtein distance
between the two, each joined with ", ". `percentageDistance` is that distance as a
percentage of the suggestion's own length. Clearing a field measures nothing, and a run
keeps the first measure it got. So what the submitter did needs no flag of its own:

| On the run | Meaning |
| --- | --- |
| No `editDistance` | Nobody has acted on it yet (`Extraction.isActedOn` is false) |
| `editDistance` 0 | Accepted as it was |
| `editDistance` above 0 | Edited or replaced |

### A newer version

A submitter never deletes a document. A new upload for the same requirement adds a
`sub:DocumentVersion`, and every older version stays.

The first reading of a newer version asks every question of the intake step, answered or
not. `ExtractionFields.getUnreadInNewerVersions` decides it for each question: a file the
step reads is not its document's first version, and none of the question's own runs
cites it yet. Each file is checked on its own, so a newer version that was already read
does not hide another that nobody has read. Every question asked gets a run that cites
the new version, found or not. So each question is read from the version once; after
that, Read again on it asks only what the submitter has not answered. A question whose
call failed got no run, so the next reading asks it again, answered or not.

Nothing the newer version says changes an answer. What the form shows beside it is
`Answer.getOfferedSuggestion`, worked out from the runs and the answer, so nothing extra
is stored. With N the newer version's value, C the submitter's answer, and P the run
before N:

| Case | The answer | Shown beside it |
| --- | --- | --- |
| No answer yet | empty | N, the newest run's suggestion |
| N differs from both C and P (or there is no P) | C stays | N, so the submitter can choose |
| N is the same as C | C stays | nothing: there is nothing to choose |
| N is the same as P | C stays | nothing: the submitter saw it before and kept C |
| N found nothing | C stays | nothing |

Values are compared as a set, ignoring case and spaces at either end. The run is written
in every case, so the version counts as read. Older runs are kept but never shown. The
gate (step 7) does not do this: a classification that already has a value or a pick is
never asked again.

## What the submitter can do

Each of these is a system workflow on `sub/Submission`, fired with `POST
<submission>.<event>.json` (see "Events over HTTP" in [workflows.md](workflows.md)).
Their start events let `everyone` in, and then the handler checks `SubmitterAccess`:
only the person who created the submission, and only while it is a draft. An
administrator who is not the submitter is refused too.

| Event | Handler | What it does |
| --- | --- | --- |
| `stopProcessing` | `StopProcessingHandler` | Stops a parse or a reading that is in progress |
| `readAgain` | `ReadAgainHandler` | Runs the reading again over the documents already parsed, asking every question the submitter has not answered |
| `retryParse` | `ParseDocumentsHandler` | Sends every upload that was never parsed or whose parse failed, when the schema names a reading workflow |
| `reviewExtraction` | `ReviewExtractionHandler` | Records that the quotes behind a suggestion do not support it |

### Stop

1. If `extractionStatus` is not `running`, nothing happens. A late Stop must not undo a
   finished reading.
2. If a thread is reading this submission (`ReadingRuns`), it is told to stop. It is
   interrupted only while it waits on a model call. Anywhere else it may be working in
   the repository, which an interrupt can break, so it is only marked. The model call
   checks for a stop before and after each request (`ModelCall.chat`), so a reply that
   arrives after a Stop is thrown away. A stop that comes after the model answered,
   while the step still checks quotes or writes, is caught when the step ends
   (`ReadingRuns.end`). `classifyDocument` and `intakeAnswers` then throw, and the
   engine undoes the walk, so nothing that reading wrote is kept.
3. Reading jobs for this submission still waiting in the job queue are removed.
4. For each current file:
   - Parse `queued`: `ParseControl.abandon` (see below). The file gets
     `parseStatus=failed` with "Stopped before the document was read", and loses
     `parseJobId`, `sharedPath` and `tokens`.
   - Parse `completed`, and a reading thread on this instance was reading it (the step
     named the file in `ReadingRuns`): its `markdownFile` is deleted, and the file is
     marked failed with "Stopped while the document was being read". The next try parses
     it again instead of reading text the submitter just stopped. A claim with no
     reading thread is left over from a reading that ended or from a restart, and the
     text is kept.
5. The submission gets `extractionStatus=failed` with "Reading was stopped.", and the
   claim is removed.

The stopped job sees the stop, writes the same "Reading was stopped." status in its own
commit, and ends.

`ParseControl.abandon` (`ParseAbandonment`, documents module) reads the job record
first. Then it sends `/cancel` to the daemon without waiting for the answer, unless the
record already has an outcome:

| Job record | What `abandon` does | Returns |
| --- | --- | --- |
| No job id, or not a valid one | Nothing, not even a cancel | `true`: no record names the staging folder, so `StopProcessingHandler` deletes it (`sharedPath`) |
| Valid id, record missing | Sends the cancel | `true`, for the same reason |
| Already `completed` or `failed` | Leaves it, and sends no cancel | `false` |
| `queued` or `active` | Marks it `failed` with error `Stopped`, sets `finished`, removes the outputs, and keeps `target` and the staging path. It commits this in a session of its own, then sends the cancel. If the commit conflicts (a callback or the sweep changed the record first), it reverts, logs at `INFO`, and still sends the cancel. | `false` |

It never deletes the staging folder or the record itself. What happens next to a stopped
record depends on the Stop's own walk:

- **The walk commits.** The file no longer names the job. The sweep offers the `Stopped`
  record to the handlers, and `ParseCompletionHandler` does not take it (the job id does
  not match). Until then, each sweep logs a `WARN` that no handler took it. 30 minutes
  after it settled, the sweep drops the record and its folder, and records the problem
  "A parse outcome was never taken in" (step 3).
- **The walk is undone.** The file still waits on the job. The first sweep at least 2
  minutes after the stop gives the `Stopped` failure to `ParseCompletionHandler`, and
  the file settles as `failed`.
- **The daemon calls back late.** Its outcome replaces the `Stopped` record.
  `ParseCompletionHandler` takes it only if the file still waits on that job.

### Read again

Refused with a 409 while `extractionStatus` is `running` ("This request is already being
read"). Otherwise it sets `running`, removes the claim, and queues the same job that a
parse queues (step 5), with the job property `readAgain=true`. The job is queued before
this walk commits, so that property tells it to wait until it sees `running`.

Read again on the same version asks every question of each intake step that the
submitter has not answered: a question with only a suggestion is asked again, and so is
one whose value is empty. Each gets a new run, and the newest run is the one shown, even
when it found nothing where an older run found something. A question the submitter
answered is skipped.

A newer version goes further: the first reading of it asks every question of an intake
step, answered or not (see "A newer version"). The gate is different: a classification
that already has a value or a pick is never asked again, because the pick decides the
reading's route.

### Retry a failed parse

This runs the `parseDocuments` step. Only uploads with no `parseStatus`, or with
`failed`, are sent, and nothing is sent when the schema names no reading workflow.
Uploads for a requirement that sets `skipParsing` are never sent. Usually the daemon was
down, not the document broken.

### Review the quotes behind a suggestion

Payload: `question` (the path inside the schema version, as the save endpoint uses it),
and `evidenceRejected` (`true` or `false`, as a boolean or a string). The handler finds
the run the form showed, which is the newest run (`Answer.getLatestExtraction()`), and
stores `evidenceRejected` as given. It says the quote does not back the suggestion. It
is a report about the extraction, not about the answer, so it decides nothing. Accepting
or changing a suggestion is not a review: it is a save of the answer (see "When the
submitter acts on it").

A missing `question` gives a 400, and so does a question nothing was extracted for. A
payload without `evidenceRejected` changes nothing.

## Statuses

`extractionStatus` on the submission (`ExtractionStatus`):

| Value | Meaning | Written by |
| --- | --- | --- |
| `running` | Documents are being parsed or read | `parseDocuments`, `readAgain` |
| `done` | Every answer that could be read was saved; `extractionMessage` may say more | `intakeAnswers` (unless an earlier step already wrote `failed`), `finishReading` |
| `failed` | Parsing or reading failed; `extractionMessage` says why | `classifyDocument`, `intakeAnswers`, the job's give-up, `stopProcessing` |

While it is `running`, the submission page (`SubmissionView`, `isBeingRead` in
`submissionForm.ts`) holds the send step back with "Wait until the document has been
read.". The editor hides the questions and blocks uploads, so the submitter answers once
the suggestions are there, and no request is sent before they are. The page reads the
submission again every 3 seconds until the reading ends, then shows what it filled in.
This is only the page: the server does not refuse a save or a send while a reading runs.

`parseStatus` on each `sub:File`:

| Value | Meaning |
| --- | --- |
| (none) | Never sent to be parsed |
| `queued` | Sent, no outcome yet |
| `completed` | Parsed and read in; `markdownFile` is there |
| `failed` | Failed, lost, or stopped; `parseError` says which |

Only the job node under `/var/documents/jobs` is ever `active`. It goes from `queued` to
`active`, then to `completed` or `failed`, and is deleted once a handler takes the
outcome. A Stop marks a `queued` or `active` job `failed`, with error `Stopped`.

Messages the submitter can see in `extractionMessage`:

| Message | Cause |
| --- | --- |
| No document has been uploaded | Nothing to read |
| The document has not been read yet | A parse is still queued |
| The document could not be read. Please try again | The parse failed. Its reason stays in the file's `parseError`, in the log ("Parse job ... failed") and in error tracking. |
| No uploaded document could be read | Uploads exist, but none has a finished parse, and none failed or is queued (for example, never sent to be parsed). With `documents` set, this is worked out from every upload, not only the named ones. |
| The document has no text that can be read | The Markdown is blank |
| The questions leave no room to show the model the document | The prompt fills the window |
| The model's answer could not be read | Two unreadable replies |
| The model could not be reached | The call failed: the model is down, there are no active settings, the call slot did not come free in time, or the model answered with empty text (for example, it ran out of output tokens) |
| The document's text could not be read | The stored Markdown could not be read back from the repository |
| The document could not be read | Firing the reading threw in the job (a step failed), or the job cannot read the schema version |
| (the reading workflow's own text) | A `finishReading` step's `message` |
| Reading was stopped. | Stop |
| Nothing is set up to read this request's documents | The schema names no reading workflow, or it no longer exists, or it is not tagged `active` |
| This reading is not set up correctly | An `intakeAnswers` step names no `requirement`, or one that is not a form of the schema |
| Some questions could not be read. Read again to try them again | Some of an intake step's calls did not go through; `finishReading` still ends the reading as `done` |

## What a reading leaves in the log

Every step a reading passes logs one line at `INFO`, so a slow reading can be explained
without adding anything. In order, for one submission:

| Line | Where | What it carries |
| --- | --- | --- |
| `Parse staged` / `Parse queued` | documents (`ParseJobService`) | The staged path and size in bytes; the job, path and target. This is the start time. |
| `Document sent to be parsed` | `ParseDocumentsHandler` | The `sub:File`, the job and the file name |
| `Reading started` | `ParseDocumentsHandler` | The submission and how many parses this step queued (`parsesQueued`) |
| `Parse dispatched` | documents (`ParseJobConsumer`) | `acceptedInMs`: how long the daemon took to accept the work, not to do it |
| `parse job=... done` | the daemon (stderr) | `waitMs` for the parse slot, `parseMs` for the conversion, `tokens`, the Markdown path |
| `Parse completed` / `Parse failed` | documents (`ParseCallbackServlet`) | `parseMs` measured from the dispatch, and `tokens` |
| `Parse outcome handed over` | documents (`ParseOutcomeDispatcher`) | The job, the target, whether it succeeded, how many handlers |
| `Parse came back` | `ParseCompletionHandler` | The job, the file, the submission, whether it succeeded, `tokens` |
| `Parse read in` | `ParseResultIngester` | The Markdown and PDF sizes in bytes, `tokens`, and the milliseconds to store them |
| `Reading queued` | `QueueExtractionHandler` | The submission, once per parse that lands; the job waits until every parse has settled |
| `Parse staging removed` | documents (`ParseJobService`) | The staging folder, once the parse is in the repository |
| `Reading run started` / `Reading run done` | `ExtractAnswersJobConsumer` | Marks the start and end of a reading that finishes; `done` carries the status and `ms`. A failed reading logs an `ERROR` instead, and a stopped one `Reading of ... was stopped`. |
| `Reading step started` / `Reading step done` | `ClassifyDocumentHandler`, `IntakeAnswersHandler` | `step=classify` with the file and the number of classifications, or `step=intake` with the requirement and the number of questions; `done` adds how many were decided or found, and `ms` |
| `Document cut to fit` | `AnswerIntakeService` | The file, and how many characters were sent and left out |
| `LLM request served` | `OpenAIClient` (llm) | The model, with `gateWaitMs` and `callMs` kept apart |
| `LLM call done` | `DefaultLLMClient` (llm) | The stage, the prompt size, `maxOutputTokens`, the milliseconds, the reply size |

Read again logs `Reading asked for again`. Stop logs `Reading stopped`, and the
documents module logs `Parse stopped` for each parse job it marks stopped.

A failed parse also logs `Parse job ... for ... failed: <reason>` at `WARN` from
`ParseCompletionHandler`. A daemon that never answers, or refuses the dispatch, logs a
`WARN` from the documents module instead of `Parse failed`.

The two LLM lines are kept apart on purpose. The call gate allows one call at a time, so
a slow reading is usually a reading that waited, and one total would not say which it
was. The stage on `LLM call done` is the response schema's name, `iap_extraction`, for
both classify and intake calls. The step line next to it says which step, and which file
or requirement, the call was for.

None of this can be measured from the job records. A job queued for a node is deleted as
soon as its outcome is taken, so the log is the only place a finished parse is recorded.
A job queued without a target (one from the polling endpoint), or one whose outcome no
handler took (a stopped one, once the Stop has committed), keeps its record until
`StaleParseJobSweeper` drops it with its staging folder: 30 minutes after it settled, or
later while the folder cannot be removed.

## Data the module reads and writes

On `sub:Submission`:

| Property | Type | Meaning |
| --- | --- | --- |
| `extractionStatus` | String | See "Statuses" |
| `extractionMessage` | String | A short message for the person looking at it |
| `extractionReadingClaimed` | Boolean | A job has taken this reading. Removed when new documents are sent, on Stop, on Read again, and when a reading gives up. |
| `extractionReadingClaimedBy` | String | The Sling job id of the job that holds the claim. If two jobs race, both write `true`, which the repository merges without a conflict; their ids differ, so the second commit is refused. A job that Sling runs again after a restart keeps its id, and finds the claim is still its own. Removed with the claim. |
| `extractionBlocked` | Boolean | The last reading ended with the request unable to go on (`finishReading` with `blocks`). Removed when a new reading records `running`. |

On `sub:File`:

| Property / child | Meaning |
| --- | --- |
| `fileName` | The upload's name, read when staging (its extension tells the daemon the file type). When it is missing, the document's `title` is used, which is where the upload handler keeps the name today. |
| `uploadedFile` | The upload, as received |
| `parseStatus`, `parseError` | See "Statuses" |
| `parseJobId` | The job it was queued under; an outcome for another job is ignored |
| `sharedPath` | Where it was staged; used to clean up after a failure or a Stop |
| `tokens` | The daemon's size estimate |
| `markdownFile` | The text the model reads |
| `pdfFile` | The PDF the text came from, only for office uploads |

On the schema side: `readingWorkflow` on `sch:SchemaVersion` (a reference to the reading
workflow version), and on `sch:Question` the `extractionPrompt`, `purpose`,
`description`, `dataType`, `maxAnswers`, `minValue`/`maxValue`, `pattern`/`patternMessage`,
and option children or `optionsFrom` (`responseShape` is not used). `optionsFrom` names
a content path. For a catalogue such as `/Categories`, the live leaves are the options,
with their descriptions (`OptionCatalog`).

## Who calls whom

| Class | Kind | Called by | Calls |
| --- | --- | --- | --- |
| `ParseDocumentsHandler` | handler `parseDocuments` | `retryParse` | `ParseService.stage`, `.queue`, `.discardStaging` |
| `ParseCompletionHandler` | `ParseOutcomeHandler` | `ParseOutcomeDispatcher` | engine (`documentParsed`), `ParseResultIngester.discardStaging` |
| `IngestParseHandler` | handler `ingestParse` | `documentParsed` | `ParseResultIngester.ingest` |
| `ParseResultIngester` | component | the two above | `ParseService.isStagedPath`, `.discardStaging` |
| `QueueExtractionHandler` | handler `queueExtraction` | `documentParsed` | `JobManager.addJob` |
| `ExtractAnswersJobConsumer` | Sling job consumer | the `iap-extraction` queue | `ReadingRuns`, engine (`extractAnswers`) |
| `ClassifyDocumentHandler` | handler `classifyDocument` | a reading workflow | `ReadingRuns`, `AnswerIntakeService`, `ParsedDocuments`, `DocumentText.readText` (templates), `ExtractionFields.getAnswered`, `.write` |
| `IntakeAnswersHandler` | handler `intakeAnswers` | a reading workflow | `ReadingRuns`, `AnswerIntakeService`, `ParsedDocuments`, or `DocumentText.readText` and `DocumentScan.join`, `ExtractionFields`, `Prompts` |
| `FinishReadingHandler` | handler `finishReading` | a reading workflow | `ExtractionStatus` |
| `ReadAgainHandler` | handler `readAgain` | `readAgain` | `JobManager.addJob` |
| `StopProcessingHandler` | handler `stopProcessing` | `stopProcessing` | `ReadingRuns.getFiles`, `.stop`, `JobManager.findJobs`, `.removeJobById`, `ParseControl.abandon`, `ParseService.discardStaging` |
| `ReviewExtractionHandler` | handler `reviewExtraction` | `reviewExtraction` | `Answer.getLatestExtraction` |
| `AnswerIntakeService` | component | classify, intake | `LLMClientFactory`, `ExtractionPrompt.plan`, `DocumentReader.read` (which calls `ModelCall`, `ExtractionReply`, `DocumentScan.locate`) |
| `ParsedDocuments` | component | classify, intake | `DocumentText.readText`, `DocumentScan.of` |
| `ReadingRuns` | component | job, classify, intake, stop, `ModelCall` | (thread registry) |

Helpers with no state of their own (except `Prompts`, which keeps a static cache of the
prompt files): `SubmissionFiles` (current uploads, reasons, settled check),
`SubmitterAccess`, `ExtractionStatus`, `ParsePropertyNames`, `ExtractionField(s)`,
`ExtractedAnswers`, `FieldResult`, `ExtractionPrompt`, `ResponseSchema`,
`DocumentReader`, `ExtractionReply`, `ModelCall`, `ModelReplies`, `DocumentScan`,
`DocumentText`, `QuoteVerifier`, `Prompts`.

`ParsedDocuments` keeps only the last document read, keyed by the Markdown's path and
its `jcr:lastModified`. A new parse changes that time, so old text is never served. A
file with no recorded time is never reused.

## What commits when

The ingester and every handler write through the engine's session, and the engine
commits the whole walk at once, so a submission never shows half a parse or half a
reading. These writes happen outside that commit:

| Step | What it writes on its own | Why |
| --- | --- | --- |
| `parseDocuments` | The staged copy on the shared volume, and the job node, committed by `ParseService.queue` in a session of its own | The job node must exist before the Sling job runs. It is also why a failure to queue deletes what was staged: no job node names that folder, so no sweep would find it. |
| `queueExtraction`, `readAgain` | A Sling job | The JobManager commits the job node in its own session |
| `stopProcessing` | Removes queued reading jobs; `ParseControl.abandon` marks the parse job record failed in its own session; the staging folder is deleted only when no job record names it | A Stop has to reach work that is already outside the submission |

The claim and the give-up in step 5 also commit on their own, and the staging folder in
step 4 is deleted only after the commit; those steps say why.

## Configuration

| Where | Setting | Default | Meaning |
| --- | --- | --- | --- |
| `modules/extraction/.../feature.json` | service user `iap-extraction` | | `jcr:read`, `jcr:modifyProperties` and `jcr:versionManagement` on `/Submissions`: it only sets the claim and the status, and checks the submission out first when it is checked in; the engine writes everything else as its own user. `jcr:read` on `/Schemas` and `/Workflows`, to check that the reading workflow exists. Granted by name: a service user mapped as `[iap-extraction]` holds only that principal, so what `/Schemas` grants to `everyone` does not reach it. |
| same | queue `iap-extraction` | `maxparallel` 1 | One submission read at a time |
| same | bundle start order | 29 | After the LLM client, the engine and the models |
| documents `feature.json` | `daemonUrl` on PID `io.uhndata.iap.documents.internal.DaemonConnection` | `${docling.url}` (`http://docling:18765`; `http://localhost:18765` with no configuration) | Where the daemon is. The one PID is read by both `ParseJobConsumer` (dispatch) and `ParseAbandonment` (Stop). |
| same PID | `responseTimeout` | 30 s | How long to wait for the daemon to answer a dispatch or a `/cancel`. A Stop does not wait for `/cancel`; only the log sees the answer. |
| same PID | `daemonToken` | `IAP_DOCLING_TOKEN` | Overrides the daemon token from the environment |
| PIDs of `ParseJobConsumer` and `ParseCallbackServlet` | `callbackToken` | `IAP_DOCLING_CALLBACK_JWT` | Overrides the callback token from the environment |
| documents `feature.json` | `StaleParseJobSweeper.maxAgeMinutes` | 30 | When an unanswered parse is given up |
| environment | `IAP_DOCLING_CALLBACK_JWT` | | The shared token for callbacks; without it no parse is dispatched |
| environment | `IAP_DOCLING_TOKEN` | | Optional bearer token for `/parse` and `/cancel` |
| PID `io.uhndata.iap.documents.internal.ParseJobService` | `sharedDocs` | `IAP_SHARED_DOCS`, then `/shared-docs` | Where uploads are staged; overrides the environment |
| environment | `IAP_SHARED_DOCS` | `/shared-docs` | The shared volume |
| LLM settings | the active model and its context window | 131072 tokens if unset | See the `llm` module |

## Writing a reading workflow

A reading workflow is normal workflow content under `/Workflows`, named by
`readingWorkflow` on the schema version. Its service tasks use the handlers above. A
reading workflow could look like this (the research-proposal demo, not on this branch,
has one called `readProposal`):

1. `classifyDocument`: is this a proposal, and which kind?
2. A gateway on the `is_proposal` decision, with a `decision` operand so it sees the
   pick before anybody accepts it (step 7). A "no" ends the reading. No pick at all,
   because the model was unsure, waits at a user task for the submitter.
3. A gateway on the category, leading to one `intakeAnswers` step per kind, each naming
   the requirements to ask (for example `[common, prospective]`).
4. `finishReading` at the end of every path.

Keep in mind:

- Every path must end at `finishReading`, or the submission stays `running`.
- A user task that waits for the submitter should come after a `finishReading` with a
  `message`, so the view stops spinning while it waits.
- Only `finishReading` writes `done`. A path that ends after an intake step without it
  stays `running` (see step 9).
- Up to its first user task, the reading runs as `iap-extraction`, in the job. After
  that, the rest runs in the request that completes the task, as that person (see step
  6).
- Tag the reading workflow's version `active`. An inactive one is treated as no reading
  workflow: nothing is parsed, and a reading already queued fails with "Nothing is set up
  to read this request's documents".
- A gateway guard cannot name a question by its path (see "Known gaps" in
  [workflows.md](workflows.md)), so routing on the category needs the question's UUID.
  That ties the guard to one schema version: a version copied from it holds a copy of
  the question, with its own UUID.

## Measuring the extraction

`ExtractionEvaluation` (test tree, from the `extraction-prompts` branch) asks a set of
documents a schema's questions through the production code (`ExtractionPrompt`,
`DocumentReader`, the LLM module's `OpenAIClient`) and scores the answers against known
ones (`AnswerScoring`). It runs only when `extraction.dataset` names a dataset, so a
normal build skips it; `-Dextraction.dryRun=true` plans every call and sends none, and
needs no key. Its summary holds counts and rates only, never document text. How to build
a public dataset from ClinicalTrials.gov and run it:
`tools/dev/extraction-eval/README.md`.

## What is still missing

| Gap | Effect |
| --- | --- |
| The `attachDocument` workflow does not run a `parseDocuments` step, and no schema sets `readingWorkflow` | No upload is parsed on its own. Only `retryParse` sends one, and only when the schema version names a reading workflow. |
| The submission page only knows that a reading is running; the view that shows `extractionMessage` and where answers came from is not merged | Nobody sees why a reading failed, or which answers a model suggested |
| Only the start and the end of a long document are read | The middle of a document longer than the context window is never read, and the confidence does not show it |
| Only the first parsed upload is read unless `documents` is set | Other files, such as a consent form or a cover letter, are ignored by default |
| The category does not choose the schema | It is recorded next to the schema the submitter already chose |
| No chat over the document (two calls: a router, then an answer) | The submitter cannot ask the document a question of their own |
| Nothing better than asking the submitter when the model cannot place a study | The reading waits, and only goes on when somebody answers the category and completes the task |

## Files

Paths are under `modules/extraction/`, except the packaging file, whose path is from the
repository root. Classes are in `src/main/java/io/uhndata/iap/extraction/internal/`,
content in `src/main/resources/SLING-INF/content/`, prompts in
`src/main/resources/prompts/`.

### Wiring

| File | What it does |
| --- | --- |
| `pom.xml` | The bundle. Depends on `iap-llm`, `iap-documents-api`, `iap-workflows`, `iap-submissions-api`, `iap-schemas-api`, `iap-data-content-api`, `iap-error-tracking-api`, `iap-java-utils`, and `iap-tags-api` for tests. Sets a 0.99 coverage floor and says why. |
| `src/main/features/feature.json` | Declares the bundle at start order 29, after the LLM client, the workflow engine, and the submissions and schemas models. Creates the `iap-extraction` service user and runs the extraction job queue one submission at a time (see "Configuration"). |
| `packaging/slingfeature/src/main/features/core/extraction.json` | Deploys the bundle. Without it the module builds but is never installed. |

### Reading a parse in

| File | What it does |
| --- | --- |
| `ParseResultIngester` | Copies a finished parse onto the `sub:File` node, under the child names the `File` model defines (`MARKDOWN_CHILD`, `PDF_CHILD`). `ParseCompletionHandler` calls its `discardStaging` once the engine has committed; the ingester deletes nothing on its own. |
| `ParsePropertyNames` | Property names and status values for a parse, as stored on a `sub:File` |
| `ParsedDocuments` | Reads a parsed document once per reading instead of once per step, when it is read alone |
| `DocumentScan` | A parsed document ready to have quotes checked against it: normalized once, with its headings |

### Shared by every call

| File | What it does |
| --- | --- |
| `DocumentText` | Reads a parsed document's text, and tells whether a line is a heading. Finding the heading above a quote is `DocumentScan.getHeading`. |
| `ModelReplies` | Reads a JSON object and its fields out of a model reply, without failing on a messy reply |
| `ModelCall` | Sends one message to the model in a way a Stop can end |
| `Prompts` | Loads prompts from the bundle, cached |
| `prompts/extraction_system.md` | The system prompt every call opens with: the input blocks, the evidence rules, the answer rules |

### Choosing what to ask

| File | What it does |
| --- | --- |
| `ExtractionField` | One question as the model is asked it, with the form of its answer, built from a `sch:Question` |
| `ExtractionFields` | Which questions a step asks, how they are keyed, and saving one run per result |

### Asking and reading

| File | What it does |
| --- | --- |
| `AnswerIntakeService` | Plans the calls, makes them, and sums up how they went |
| `ExtractionPrompt` | What each call sends: the document, the reference text, the questions; at most ten per call |
| `ResponseSchema` | The strict JSON schema a reply must follow, typed from each question |
| `DocumentReader` | Makes the calls one by one; a reply that cannot be read loses only its own questions, and an unreachable model stops the reading |
| `ExtractionReply` | Reads one reply: typed values to text, options matched, quotes checked, confidence lowered for quotes not found |
| `FieldResult` | One question's answer after its quotes were checked: values, reasoning, confidence, quotes |
| `QuoteVerifier` | Whether a quote is really in the document |
| `prompts/tcps2_reference.md` | A reference text a step can add with `promptFrom`. No shipped step names it yet. |

### Running it

| File | What it does |
| --- | --- |
| `ParseDocumentsHandler` | Service task `parseDocuments`: stages every upload that still needs a parse, and queues it |
| `ParseCompletionHandler` | Takes a finished parse from the documents module and fires `documentParsed` on the submission |
| `IngestParseHandler` | Service task `ingestParse`: reads the parse onto the file, or records that it failed |
| `QueueExtractionHandler` | Service task `queueExtraction`: queues the reading job; the job reads once every parse has settled |
| `ReadAgainHandler` | Service task `readAgain`: the submitter asks for the reading again; queues the same job |
| `ExtractAnswersJobConsumer` | The background job that decides whose turn it is to read, and fires `extractAnswers` |
| `ClassifyDocumentHandler` | Service task `classifyDocument`: one call per document for the classification requirements that point at it; only sure picks are written as suggestions |
| `IntakeAnswersHandler` | Service task `intakeAnswers`: the intake for one or more requirements, over the named documents (see step 8) |
| `FinishReadingHandler` | Service task `finishReading`: moves a submission still `running` to `done` with the step's `message`, and marks it blocked when the step sets `blocks` |
| `ReviewExtractionHandler` | Service task `reviewExtraction`: what the submitter says about the quotes behind a suggestion |
| `StopProcessingHandler` | Service task `stopProcessing`: stops a parse or a reading that is still in progress |
| `ReadingRuns` | Which thread is reading each submission, so a Stop can reach it |
| `SubmitterAccess` | Lets only the submitter, and only while the request is a draft, change how it is read |
| `ExtractionStatus` | The submission's reading state: `extractionStatus`, `extractionMessage`, the claim and the blocked flag, and the values they take |
| `SubmissionFiles` | A submission's latest uploads, the first one parsed, and whether every parse has settled |
| `ExtractedAnswers` | Writes an answer, its run and its quotes. References are set with `ReferenceUtils` from `iap-java-utils`. |
| `SystemWorkflows/documentParsed` (`.json`, `bpmn.xml`) | Reads a finished parse in, then queues the reading. Performer: `iap-extraction` only. |
| `SystemWorkflows/extractAnswers` (`.json`, `bpmn.xml`) | Starts the reading workflow the schema names, ending an earlier one still open. Performer: `iap-extraction` only. |
| `SystemWorkflows/readAgain` (`.json`, `bpmn.xml`) | Queues the reading again for the submitter |
| `SystemWorkflows/retryParse` (`.json`, `bpmn.xml`) | Sends again the uploads whose parse failed, and any never sent. Today the only workflow that runs `parseDocuments`. |
| `SystemWorkflows/stopProcessing` (`.json`, `bpmn.xml`) | Stops a parse or a reading that is still in progress |
| `SystemWorkflows/reviewExtraction` (`.json`, `bpmn.xml`) | Records that the quotes behind a suggestion do not support it |

Outside this module: the documents module's `ParseService` (stage and queue a parse for
a repository node), `ParseControl` (stop one) and `ParseOutcomeHandler` (be told how it
ended), and the submissions module's `AttachDocumentHandler`, which writes the versioned
document layout. A form servlet that serves the extraction to the form, and the frontend
that shows it, are not on this branch yet.
