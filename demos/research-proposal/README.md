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
| `/Schemas/researchProposal`    | The proposal document first, then two classification requirements read from it: whether it is a research proposal (with the protocol structure as reference) and what kind of study it is (options from `/Categories/Proposal`); the common questions about the study, each with an extraction prompt; a requirement per study type (consent for prospective studies, data sources for retrospective ones); two administrative questions filled in by hand; then a scientific review |
| `/Schemas/pfq`                 | A patient facing questionnaire: a few questions answered by hand, the questionnaire and an optional preamble, a classification of whether the upload is a questionnaire, the questions read out of it, then a review |
| `/Workflows/researchProposal`  | The process, as BPMN: check and complete → review → approved or refused. Uploading the proposal is what starts the reading |
| `/Workflows/readProposal`      | The reading: classify the upload, stop if it is not a proposal, then the common questions and those of its study type, in one call |
| `/Workflows/pfq`, `/Workflows/readPfq` | The same for a questionnaire: classify, then read the questions only if it is one |
| `/Categories/Proposal`, `/Categories/PFQ` | The top categories a submission is raised under, each naming its schema, with the study types (and PROM/PREM) below |
| `/Schemas/ethicsReviewDemo`, `/Workflows/ethicsReviewDemo`, `/Categories/EthicsReview` | A study document checked against TCPS2 on its own, with no classification first |
| `demo-researcher`              | Raises proposals. Member of `proposal-researchers`                     |
| `demo-reviewer`                | Decides. Member of `proposal-reviewers`, which the schema routes review to |

Passwords match the usernames; this only ever runs behind `--demo`.

The demo has no Java of its own. Everything it uses is a platform capability: the `parseDocuments`,
`classifyDocument` and `intakeAnswers` service tasks and the `documentParsed` and `extractAnswers`
system workflows ship with the extraction module, the upload with the submissions module.

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
2. **They upload the document** — `POST <submission>.attachDocument.json` with the file, which the
   form's upload control does. The upload becomes a `sub:Document` holding a version, whose `sub:File`
   holds the bytes as `uploadedFile`. The `attachDocument` system workflow then runs
   `parseDocuments`: every upload not parsed yet is staged on the volume shared with the document
   daemon and queued, and the submission records `extractionStatus: running`. A schema that names no
   `readingWorkflow` is left alone.
3. **The daemon parses in the background** and POSTs the outcome back. The platform fires
   `documentParsed` on the submission: the `ingestParse` service task reads the Markdown, the PDF
   rendition onto the file, the parse job record is deleted, and once no parse is
   still going, `queueExtraction` queues the reading.
4. **A background job fires `extractAnswers`**, which runs `/Workflows/readProposal`. It starts with
   `classifyDocument`: one call asks every classification requirement that points at the uploaded
   document, `is_proposal` and `proposal_category`, with their prompts, their options and the protocol
   structure as reference. The picks are stored as answers, pre-filled like any other.
5. **The reading acts on a pick only once it is settled**: confirmed or changed by the researcher, or
   picked by the model with a confidence at or above the requirement's `confidenceThreshold` (0.7).
   - The classify call failed (no API key, provider down): the reading ends as failed, and *Try again*
     reads it once more.
   - `is_proposal` settled as *no*: the reading ends and nothing else is read.
   - `is_proposal` settled as *yes* and a settled study type: one more call reads the common questions
     and those of the study type, and `extractionStatus` becomes `done`. A settled study type that
     neither list names reads the common questions only.
   - Anything not settled: the reading pauses on a task for the researcher, shown under that
     classification, and the page says the reading waits for them. They confirm or pick the answer and press *Continue once this is confirmed*, and the reading goes on.
6. **The researcher checks the answers** on the second page of the form, completes what was not found,
   and completes the step; the proposal moves to `submitted` and `demo-reviewer` sees it.
7. **`demo-reviewer` decides** — `POST` to the `review` task with `outcome=approved` or `rejected`.

The same reading can be asked for again at any time with `POST <submission>.extractAnswers.json`.

A questionnaire goes the same way: *Patient facing questionnaire* in the dialog, then the upload, then
`is_questionnaire`. A settled *yes* reads the questions out of the questionnaire and its preamble; a
settled *no* ends the reading and leaves them to be answered by hand. A preamble uploaded later reads
again, and fills only what is still unanswered.

## Questions that depend on the category

The category is the answer to `proposal_category/decision`: one path, such as
`/Categories/Proposal/Prospective/Interventional/ClinicalTrials`. A condition compares value sets and
has no "starts with", so a requirement meant for every prospective study lists every path in that
branch and asks whether the answer is among them:

```json
"cond:condition": {
  "jcr:primaryType": "cond:SingleCondition",
  "comparator": "includes any",
  "operandA": { "jcr:primaryType": "cond:ConditionOperand", "source": "decision", "value": [ "proposal_category/decision" ] },
  "operandB": { "jcr:primaryType": "cond:ConditionOperand", "value": [ "/Categories/Proposal/Prospective", "/Categories/Proposal/Prospective/Observational", "..." ] }
}
```

The `decision` source reads the answer to a question of the submission's schema, but only once it is
settled, as above. It works from a workflow gateway too, which the `answer` source does not.

The lists in the schema and in `readProposal` mirror the `/Categories/Proposal` tree. A category
added there has to be added here too, or proposals filed under it get only the common questions.

## Where it is going

- Email to the researcher when the reading is done or could not be done.
