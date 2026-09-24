# Demo: research proposal

A researcher uploads a proposal; the platform reads it and fills the form in; the researcher checks
the answers and sends the proposal; a reviewer accepts or refuses it.

This is the demo the answer-extraction pipeline is built against. Each step of the pipeline is proved
by something in use here, and the demo grows as more of it is ported.

```
mvn clean install
./start.sh --test --demo
```

`--test` as well as `--demo`: the categories a proposal is filed under live in the test data
(`/Categories`), and the demo does not ship its own copy.

## What it installs

| Path                           | What                                                                    |
|--------------------------------|-------------------------------------------------------------------------|
| `/Schemas/researchProposal`    | The proposal document first, then the common questions: whether it is a proposal, what kind of study it is (options from `/Categories`), and eleven questions about the study, each with an extraction prompt and the purpose a reviewer judges it by; a requirement per study type (consent for prospective studies, data sources for retrospective ones); two administrative questions filled in by hand; then a scientific review |
| `/Workflows/researchProposal`  | The process, as BPMN: upload → sent to be read → check and complete → review → approved or refused |
| `/Workflows/readProposal`      | The reading: the common questions in one call, stop if it is not a proposal, then the questions of its study type |
| `demo-researcher`              | Raises proposals. Member of `proposal-researchers`                     |
| `demo-reviewer`                | Decides. Member of `proposal-reviewers`, which the schema routes review to |

Passwords match the usernames; this only ever runs behind `--demo`.

The demo has no Java of its own. Everything it uses is a platform capability: the `parseDocuments`
service task and the `documentParsed` and `extractAnswers` system workflows ship with the extraction
module, the upload with the submissions module.

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

`start.sh` points IAP at the daemon on `localhost:18765` and, when `IAP_DOCLING_CALLBACK_URL` is
unset, sets it to this instance's port. A daemon already running still uses the URL it was started
with, so a second instance on another port needs that daemon restarted with the new URL.
In Docker Compose the daemon is the `docling` service instead. The shipped default is the `prompter`
provider with the `Qwen3.8-27B` model, so a
`PROMPTER_API_KEY` is all it needs; a different provider or model is picked under `/admin/llm` (as
`admin`). Without a key the model calls fail, the reading is marked failed and nothing is filled in.

## What runs

1. **`demo-researcher` raises a proposal** — `POST /Submissions` with a `title` and this schema's
   version path; the bootstrap creates it in `draft` and puts it under its workflow, which parks on
   `upload`.
2. **They upload the document** — `POST <submission>.attachDocument.json` with the file, which the
   form's upload control does. The upload becomes a `sub:Document` holding a version, whose `sub:File`
   holds the bytes as `uploadedFile`.
3. **They complete the upload step** — `POST` to the `upload` task. On its way to the next step the
   walk runs `parseDocuments`: every upload not parsed yet is staged on the volume shared with the
   document daemon and queued, the file records its `parseJobId` and `sharedPath`, and the submission
   records `extractionStatus: running`. The walk parks on `complete`, and the submission view shows a
   spinner: *Reading the uploaded document*.
4. **The daemon parses in the background** and POSTs the outcome back. The platform fires
   `documentParsed` on the submission: the `ingestParse` service task reads the Markdown, the PDF
   rendition onto the file, the parse job record is deleted, and once no parse is
   still going, `queueExtraction` queues the reading.
5. **A background job fires `extractAnswers`**, which runs `/Workflows/readProposal`. One call asks
   the common questions, whether this is a proposal and what kind of study it is among them, over the
   whole document. Each answer it found is recorded as a `sub:Answer` with a `sub:Extraction`
   (confidence, reasoning, source) and `sub:Evidence` (the quotes). A document that is not a proposal
   has none of those answers recorded and the reading ends. Otherwise the category decides which
   study-type questions are read next, and
   `extractionStatus` becomes `done`. A category the model was not sure of is left unanswered: the
   researcher answers it in the form and presses *Choose what kind of study this is*.
6. **The researcher checks the answers**, completes what was not found, and completes the step; the
   proposal moves to `submitted` and `demo-reviewer` sees it.
7. **`demo-reviewer` decides** — `POST` to the `review` task with `outcome=approved` or `rejected`.

The same reading can be asked for again at any time with `POST <submission>.extractAnswers.json`.

## Questions that depend on the category

The category is the answer to `common/category`: one path, such as
`/Categories/Prospective/Interventional/ClinicalTrials`. A condition compares value sets and has no
"starts with", so a requirement meant for every prospective study lists every path in that branch and
asks whether the answer is among them:

```json
"cond:condition": {
  "jcr:primaryType": "cond:SingleCondition",
  "comparator": "includes any",
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", "source": "answer", "value": [ "common/category" ] },
  "operandB": { "jcr:primaryType": "cond:ConditionOperand", "value": [ "/Categories/Prospective", "/Categories/Prospective/Observational", "..." ] }
}
```

The lists in the schema and in `readProposal` mirror the `/Categories` tree in the test data. A category
added there has to be added here too, or proposals filed under it get neither set of questions.

## Where it is going

- Letting the category the model picked choose the schema, instead of recording it beside one already
  chosen.
- Email to the researcher when the reading is done or could not be done.
