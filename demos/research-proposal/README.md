# Demo: research proposal

A researcher uploads a proposal; the platform reads it and fills the form in; the researcher checks
the answers and sends the proposal; a reviewer accepts or refuses it.

This is the demo the answer-extraction pipeline is built against. Each step of the pipeline is proved
by something in use here, and the demo grows as more of it is ported.

```
mvn clean install
./start.sh --test --demo
```

`--test` as well as `--demo`: the categories the gate files a proposal under live in the test data
(`/Categories`), and the demo does not ship its own copy.

## What it installs

| Path                           | What                                                                    |
|--------------------------------|-------------------------------------------------------------------------|
| `/Schemas/researchProposal`    | The proposal document first, then eleven questions about the study, each with an extraction prompt and the rubric tags that say where in a proposal its answer lives; a section that depends on the category the gate filed the proposal under (consent for prospective studies, data sources for retrospective ones); two administrative questions filled in by hand; then a scientific review |
| `/Workflows/researchProposal`  | The process, as BPMN: upload → sent to be read → check and complete → review → approved or refused |
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
export IAP_DOCLING_CALLBACK_URL="http://localhost:8080/system/documents/parseCallback"
export IAP_DOCLING_CALLBACK_JWT="any-shared-secret"
cd modules/documents/processing/src/main/python && python docling_daemon.py

# window 2: IAP, with the same folder and secret, and the key of the LLM provider
export IAP_SHARED_DOCS="C:/Users/me/Git/iap/modules/documents/shared-docs"
export IAP_DOCLING_CALLBACK_JWT="any-shared-secret"
export PROMPTER_API_KEY="..."
./start.sh --test --demo
```

`start.sh` points IAP at the daemon on `localhost:18765`; in Docker Compose it is the `docling` service
instead. Then pick the provider and model under `/admin/llm` (as `admin`). Without that the default
`local` provider (Ollama) is used, and with nothing listening there the gate answers "undetermined" and
fills nothing in.

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
   rendition and the chunk tree onto the file, the parse job record is deleted, and once no parse is
   still going, `queueExtraction` queues the reading.
5. **A background job fires `extractAnswers`**: the gate decides whether the document is a proposal
   and which category it belongs to, a second look settles the category if the gate was unsure, the
   intake puts every question with an extraction prompt to the model over the parsed chunks, and each
   answer it found is recorded as a `sub:Answer` with a `sub:Extraction` (confidence, reasoning,
   source) and `sub:Evidence` (the quotes). `extractionStatus` becomes `done` and the spinner stops.
   A document the gate could not place stops it instead, with the banner
   *Not able to safely identify whether the submitted document is a proposal*, and nothing is filled in.
6. **The researcher checks the answers**, completes what was not found, and completes the step; the
   proposal moves to `submitted` and `demo-reviewer` sees it.
7. **`demo-reviewer` decides** — `POST` to the `review` task with `outcome=approved` or `rejected`.

The same reading can be asked for again at any time with `POST <submission>.extractAnswers.json`.

## Questions that depend on the category

The gate records the category on the submission as `proposalCategory`: one path, such as
`/Categories/Prospective/Interventional/ClinicalTrials`. A condition compares value sets and has no
"starts with", so a question meant for every prospective study lists every path in that branch and asks
whether the recorded one is among them:

```json
"cond:condition": {
  "jcr:primaryType": "cond:SingleCondition",
  "comparator": "includes any",
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", "source": "property", "value": [ "proposalCategory" ] },
  "operandB": { "jcr:primaryType": "cond:ConditionOperand", "value": [ "/Categories/Prospective", "/Categories/Prospective/Observational", "..." ] }
}
```

The lists in the schema mirror the `/Categories` tree in the test data. A category added there has to be
added here too, or proposals filed under it get neither question.

The "Depends on the study type" section itself is shown only once a category is recorded (`is not empty`
on the same property). Both of its questions are still put to the model in the same intake call as the
rest, so when the section appears its answer is already there; the form hides the question that does not
apply.

## Where it is going

- Showing the confidence and the evidence behind each pre-filled answer in the form.
- Step 2 of the extraction: a targeted re-ask of the low-confidence answers over the chunks the
  intake did not read, then a sweep.
- Letting the category the model picked choose the schema, instead of recording it beside one already
  chosen.
- Email to the researcher when the reading is done or could not be done.
