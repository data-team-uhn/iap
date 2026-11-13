# Demo: research proposal

A researcher uploads a proposal; the platform reads it and fills the form in; the researcher checks
the answers and sends the proposal; a reviewer accepts or refuses it.

This is the demo the answer-extraction pipeline is built against. Each step of the pipeline is proved
by something in use here, and the demo grows as more of it is ported.

```
mvn clean install
./start.sh --test --demo
```

`--test` as well as `--demo`: the demo's schemas and categories sit next to the test data's users
and tags, and the demo is only tried that way.

A `.iap-data` folder from an older run may still hold the old top-level Prospective and Retrospective
categories. Delete `.iap-data` before the first demo run.

## What it installs

| Path                           | What                                                                    |
|--------------------------------|-------------------------------------------------------------------------|
| `/Schemas/researchProposal`    | The proposal document first; a validation that it is a research proposal (`is_proposal`, with the protocol structure as reference); the kind of study, a question in the `category` form that the classification `proposal_category` suggests (options from `/Categories/Proposal`); the common questions about the study, each with an extraction prompt; a form per study type (consent for prospective studies, data sources for retrospective ones); two administrative questions filled in by hand; then a scientific review. Each form that is read names `proposal` as its document |
| `/Schemas/pfq`                 | A patient facing questionnaire: a few questions answered by hand, the questionnaire and an optional preamble, a validation that the upload is a questionnaire, the questions read out of both documents, then a review |
| `/Workflows/researchProposal`  | The process, as BPMN: wait for the check of the upload → stop if it is surely not a proposal → check and complete → review → approved or refused |
| `/Workflows/pfq`               | The same for a questionnaire |
| `/Categories/Proposal`, `/Categories/PFQ` | The top categories a submission is raised under, each naming its schema, with the study types (and PROM/PREM) below |
| `/Schemas/ethicsReviewDemo`, `/Categories/EthicsReview` | A study document checked against TCPS2, with TCPS2 as the form's reference text. It shares the proposal's workflow and its validation, but asks no kind of study |
| `demo-researcher`              | Raises proposals. Member of `proposal-researchers`                     |
| `demo-reviewer`                | Decides. Member of `proposal-reviewers`, which the schema routes review to |

Passwords match the usernames; this only ever runs behind `--demo`.

The demo has no Java of its own. Everything it uses is a platform capability: the parse, the checks
of the upload and the main extraction ship with the extraction module, the upload and the `validation`
condition source with the submissions module. See [docs/extraction.md](../../docs/extraction.md).

## Running it on a developer machine

The reading needs two things beside IAP: the Docling daemon, and an LLM provider.

```bash
# window 1: the daemon, natively (Docling and LibreOffice installed), sharing a folder with IAP
export IAP_SHARED_DOCS="C:/Users/me/Git/iap/modules/documents/shared-docs"
# Must match IAP's port. A daemon started for 8080 will not reach an instance on 8081.
export IAP_DOCLING_CALLBACK_URL="http://localhost:8080/system/documents/parseCallback"
export IAP_DOCLING_CALLBACK_JWT="any-shared-secret"
cd modules/documents/processing/src/main/python && python docling_daemon.py

# window 2: IAP, with the same folder and secret, and the key of the LLM provider
export IAP_SHARED_DOCS="C:/Users/me/Git/iap/modules/documents/shared-docs"
export IAP_DOCLING_CALLBACK_JWT="any-shared-secret"
export PROMPTER_API_KEY="..."
./start.sh --test --demo
```

`start.sh` points IAP at the daemon on `localhost:18765`. Only the daemon reads
`IAP_DOCLING_CALLBACK_URL`, so it has to be set where the daemon is started; `start.sh` warns when the
URL in its own shell is missing or names another port. A second instance on another port needs the
daemon restarted with that port's URL.
In Docker Compose the daemon is the `docling` service instead. The shipped default is the `prompter`
provider with the `Qwen3.8-27B` model, so a
`PROMPTER_API_KEY` is all it needs; a different provider or model is picked under `/admin/llm` (as
`admin`). Without a key the model calls fail, the reading is marked failed and nothing is filled in.

## What runs

1. **`demo-researcher` raises a proposal** — the *New submission* dialog lists the top categories.
   Picking *Research proposal* raises a submission against the schema that category names:
   `POST /Submissions` with a `title` and that schema version's path. The bootstrap creates it in
   `draft` and puts it under its workflow, which parks on `complete`.
2. **They upload the document, and click Next** — the editor's first step shows only the documents,
   and Next waits until each required one is uploaded ("Please upload the research proposal."). An
   upload is `POST <submission>.attachDocument.json` with the file: it becomes a `sub:Document` holding
   a version, whose `sub:File` holds the bytes as `uploadedFile`. Next fires `retryParse`, whose
   `parseDocuments` step stages every upload not parsed yet on the volume shared with the document
   daemon and queues it, and the submission records `extractionStatus: running`. From then on the
   uploads are locked, unless a parse failed: the intake reads each document once.
3. **The daemon parses in the background** and POSTs the outcome back. The platform fires
   `documentParsed` on the submission: `ingestParse` reads the Markdown and the PDF rendition onto the
   file, and `queueDecisions` queues the checks of the upload.
4. **A background job checks the upload**, one document at a time, in the schema's order. For the
   proposal it asks `is_proposal` (yes or no), saves the verdict, and completes `checkProposal`, the task
   the proposal's workflow waits at. Then it asks `proposal_category` from the same parse, and saves the
   pick as a suggestion on `category/kind`; a pick below `confidenceThreshold` (0.7) is saved as not
   found.
   - The model is sure it is not a proposal: the workflow ends at *Not a research proposal*, the
     submission is blocked with "Unable to identify the research proposal", and nothing else is read.
   - Yes, or unsure: the workflow goes on to *Send for review*, and the reading ends with "Confirm the
     category to continue".
   - A call failed (no API key, provider down): the reading ends as failed, and *Read again* runs the
     check once more.
5. **The researcher confirms the kind of study**, on the editor's second step, then clicks Next. It
   waits until the checks are done and the kind of study has an answer. Next fires
   `runMainExtraction`: one call per form for the common questions and those of the confirmed study
   type, reading `proposal`. Each answer is saved as a suggestion, and `extractionStatus` becomes
   `done`. The third step shows the rest of the form.
6. **The researcher checks the answers** in the form: each suggestion is shown framed beside its quotes,
   and is saved only once they confirm or edit it. They complete what was not found, and complete the
   step. The `markSubmitted` step (`addTag` with `replaceExisting`) swaps `draft` for `submitted`, and
   `demo-reviewer` sees it. The end event then places `approved` or `rejected`.
7. **`demo-reviewer` decides** — `POST` to the `review` task with `outcome=approved` or `rejected`.

*Read again* (`POST <submission>.readAgain.json`) runs the first step not done yet: the checks of the
upload, or the questions never read. It is not offered once the request is blocked. *Try again*
(`retryParse`) sends a failed parse back.

A questionnaire goes the same way: *Patient facing questionnaire* in the dialog, then the upload, then
`is_questionnaire`. Surely not a questionnaire ends the request; otherwise the questions are read out
of the preamble and the questionnaire together. There is no kind of questionnaire to confirm, so its
second step only waits for the check.

## Questions that depend on the category

The category is the answer to `category/kind`: one path, such as
`/Categories/Proposal/Prospective/Interventional/ClinicalTrials`. A condition compares value sets and
has no "starts with", so a requirement meant for every prospective study lists every path in that
branch and asks whether the answer is among them:

```json
"cond:condition": {
  "jcr:primaryType": "cond:SingleCondition",
  "comparator": "includes any",
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", "source": "answer", "value": [ "category/kind" ] },
  "operandB": { "jcr:primaryType": "cond:ConditionOperand", "value": [ "/Categories/Proposal/Prospective", "/Categories/Proposal/Prospective/Observational", "..." ] }
}
```

The `answer` source reads only what the submitter saved, never the model's suggestion, so these
forms apply once the kind of study is confirmed. The main extraction waits for that too.

The lists in the schema mirror the `/Categories/Proposal` tree. A category added there has to be added
here too, or proposals filed under it get only the common questions.

## Where it is going

- Email to the researcher when the reading is done or could not be done.
