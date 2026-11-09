# Measuring answer extraction

```sh
# A public set, from ClinicalTrials.gov: 60 protocols and the registry's answers
python3 clinicaltrials.py select ~/extraction-eval/clinicaltrials
python3 clinicaltrials.py fetch ~/extraction-eval/clinicaltrials --docling ~/docling-venv/bin/python
python3 clinicaltrials.py expected ~/extraction-eval/clinicaltrials
cp questions/clinical-trial-protocol.json ~/extraction-eval/clinicaltrials/questions.json

# Ask every document every question, from the repository root
export PROMPTER_API_KEY=...
mvn -o -B -ntp -Ptests test -pl modules/extraction -Dtest=ExtractionEvaluation \
    -Djacoco.skip=true -Dextraction.dataset=$HOME/extraction-eval/clinicaltrials
```

`ExtractionEvaluation` asks a set of documents a schema's questions through the same code
production uses: the `Question` model, `ExtractionPrompt`, `DocumentReader` and the LLM
module's `OpenAIClient`. It writes its results under `<dataset>/results/<time>-<model>/`.

| File | Holds | Share it? |
| --- | --- | --- |
| `summary.md`, `summary.json` | Counts and rates per question, and the run's settings | Yes: no document text |
| `details.jsonl` | Every answer, what was expected, the reasoning and the quotes | Only where the documents may go |
| `prompts.md` | The first document's calls as they are sent, its text cut short | Only where the documents may go |

## A dataset

| Path | What it is |
| --- | --- |
| `questions.json` | A schema in the format of initial content: a `sch:Schema`, a `sch:SchemaVersion` under it, requirements under that. Each requirement is asked as its own group of calls. |
| `documents/<id>.md` | Each document, converted to Markdown by the documents module |
| `expected.json` | Optional. `{"<id>": {"<question key>": <answer>}}`, the key being the question's path inside the version with dots for slashes, such as `study.title` |

An expected answer is a JSON value, a list for a question taking several, or an object
with the answer under `value`, plus `alternatives` that also count and, for a number, a
relative `tolerance`. A question with no expected answer is counted but not scored.

## Settings

| Property | Default | |
| --- | --- | --- |
| `extraction.dataset` | none | The dataset folder; nothing runs without it |
| `extraction.questions` | `<dataset>/questions.json` | Another schema to ask |
| `extraction.model` | `Qwen3.8-27B` | The model, as the provider names it |
| `extraction.contextTokens` | 131072 | The model's context window |
| `extraction.endpoint` | Prompter's | Any OpenAI-compatible endpoint |
| `extraction.projectId` | `ia45-data` | Sent as `project_id` |
| `extraction.limit` | all | Read only the first N documents |
| `extraction.documents` | all | Read only these, comma-separated |
| `extraction.dryRun` | `false` | Plan every call and send none: shows how many calls each document needs and which documents have to be cut, without a key |

The key is read from `PROMPTER_API_KEY`, as the LLM module reads it.

## Reading the summary

| Column | Meaning |
| --- | --- |
| Found | How often the model gave an answer |
| Accuracy | Right answers out of those with an expected one. Options, yes or no and numbers must match; a list of options must name the same ones; free text must share 60% of its words (F1) with the expected text or an alternative. |
| Mean F1 | How close the answers came, which shows progress a right-or-wrong count hides |
| Quotes found | Quotes found in the document, out of those the model gave |
| Unsupported | Answers with no quote found in the document |
| Confidence right/wrong | The model's mean confidence when it was right, and when it was wrong. Close together means it cannot tell. |

The registry describes each study as it stands now, while a protocol PDF may be an older
amendment, so a wrong answer on the public set is something to look at before it is
something to fix.
