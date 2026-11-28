# Brief: seed content for the reviewer pretotype

We are building a **pretotype** of the reviewer experience in IAP, UHN's institutional authorization platform:
real REB and Digital reviewers will review seeded submissions in a working instance, and it must feel real.
Every submission is a **retrospective data study** (secondary use of existing data) filed against the schema in
`/home/marta/ia45/pretotype/schema-outline.md`, read that first: it lists every question (path, wording, answer
format, options, the condition under which it is asked), the documents, and the reviewer groups' risks (topics)
with the questions and documents each risk draws on.

The studies are public protocols from ClinicalTrials.gov, in `/home/marta/ia45/pretotype/protocols/<NCT>/`:
the PDF, and `<name>.txt`, its text exactly as PDF.js reads it, page by page (`=== page N` markers). They come
from various institutions; **write the submission as if the same study team were filing it at UHN**: keep the
PI, the science, the cohort, the dates and everything the protocol says, but answer as a UHN study team would
(e.g. data from UHN's Epic EMR, stored on a UHN secure server) where the protocol is silent or site-specific.
Never invent anything that contradicts the protocol, except for the deliberate weaknesses below.

## What to write: one JSON file, `/home/marta/ia45/pretotype/content/<NCT>.json`

```json
{
  "nct": "NCT05188001",
  "title": "Short submission title, as a study team would type it (not the full protocol title if it is long)",
  "answers": {
    "study/pi": "Dr. Su-Yin MacDonell",
    "classification/purpose": "research",
    "classification/sources/dataSources": ["emr", "administrative"],
    "consent/priorConsent": false,
    "classification/cohort/startDate": "2017-01-01",
    "classification/cohort/records": 9500
  },
  "weaknesses": ["One line per deliberate weakness, saying what it is and where, for the people running the test"],
  "extractions": [
    {
      "question": "classification/cohort/startDate",
      "extractedAnswer": "2017-01-01",
      "quote": "exact words copied from the .txt",
      "page": 4,
      "confidence": 0.92,
      "reasoning": "One or two sentences, as a model would explain where it found the answer."
    }
  ],
  "screening": {
    "summary": "Two or three sentences: the AI pre-review's overall read of the submission.",
    "assessments": [
      {
        "approval": "reb",
        "topic": "waiver",
        "summary": "One or two sentences on this risk.",
        "comments": [
          {
            "text": "The concern, as the AI would raise it to a reviewer: specific, plain, no hedging boilerplate.",
            "kind": "gap",
            "severity": "major",
            "suggestion": "What would address it, when there is something to say.",
            "subjects": ["consent/waiver/impracticable"],
            "quote": "exact words from the protocol .txt that show the problem, or omit when the problem is something missing",
            "page": 6
          }
        ]
      }
    ]
  },
  "variableList": [
    {"field": "Age at surgery", "source": "Epic", "identifying": false, "why": "Adjust for age in the model."}
  ],
  "dataSharingAgreement": null
}
```

### Answers
- Keys are question paths from the outline. Values: `true`/`false` for true/false questions; the option
  **value** (not label) for ONE of; a list of option values for LIST of; `YYYY-MM-DD` dates; integers; text.
- Answer **every question that is asked** given the other answers (respect the `[only when …]` conditions:
  don't answer a question whose condition does not hold). Optional questions: answer when the team plausibly
  would. Leave out questions that are not asked.
- Write long text the way a busy research coordinator does: complete sentences, specific, a few lines, not
  polished prose. Vary length; some answers short.
- Lab, imaging, Princess Margaret data, agreements, health professionals, AI: say yes only when the protocol
  supports it, so the conditional reviews start only where they should.

### Weaknesses (the part that makes the review real)
Build in **2 to 4 realistic weaknesses** per study (fillers: 1 or 2), the kind REB and Digital reviewers actually
catch, grounded in the protocol: an answer that contradicts the protocol (e.g. "anonymized" while the protocol
keeps a linking log with MRNs); a waiver justified by inconvenience; an end date in the recent past; data or
processing leaving UHN that the form doesn't declare; vague retention; a missing small-cell rule; a sensitive
category not ticked; AI used but `digital/ai/used` false. List each in `weaknesses`.

### Extractions (AI-extracted answers, shown as notes on the protocol)
- 8 to 20 per deep study (fillers 4 to 8), for questions whose answer the protocol actually states.
- `quote` must be **copied exactly** from the `.txt` (one sentence or a phrase, 5 to 40 words, on one page;
  whitespace differences are fine, nothing else). `page` is the `=== page N` it is under.
- `extractedAnswer` is in the same format as the answer. In about one case out of five it should **differ** from
  the submitter's answer, which is how the review screen flags a mismatch. Make those differences the meaningful
  ones (they often coincide with a weakness).

### Screening (the AI pre-review)
- Assess the REB risks that matter for this study (deep: 6 to 10 risks, fillers: 2 or 3) and the Digital risks
  when tools, AI or external services are involved. `approval`/`topic` must exist in the outline (e.g.
  `reb/waiver` → `"approval": "reb", "topic": "waiver"`; Digital is `"approval": "digitalReview"`).
- 0 to 3 comments per assessed risk. `kind`: `gap` (something missing), `conflict` (two things disagree),
  `concern` (a risk worth a reviewer's look). `severity`: `critical`, `major`, `medium`, `minor`, `suggestion`.
  Most are medium or minor; a deep study may have one major; critical is rare.
- `subjects` are question paths (or document names: `protocol`, `variables`, `dsa`, `priorConsentForm`).
- The AI must be **useful and right**, not alarmist: no comment the protocol answers already. A few assessments
  may have no comments, with a summary saying why the risk looks handled.

### Variable list
The fields the study collects, from the protocol's data collection section when it has one (or plausibly from
its variables and outcomes): 12 to 40 rows. Mark identifiers honestly (MRN, dates of birth, postal codes…).

### Data sharing agreement
Only when your answers say data leaves UHN (`handling/leaving/leaves` not `["no"]`): an object
`{"parties": [...], "purpose": "...", "data": "...", "safeguards": "...", "term": "..."}`, a few lines each.
Otherwise `null`.

## Quality bar
Reviewers know TCPS 2, PHIPA and UHN policy. Anything vague, inconsistent with the protocol, or obviously
generated breaks the illusion. Prefer fewer, sharper comments. Use plain Canadian English. No personal names
other than those in the protocol. Do not touch any other file.
