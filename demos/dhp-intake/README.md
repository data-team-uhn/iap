# Demo: Digital Health Platform intake

A researcher describes the patient data their study needs and sends the request. Their research plan is filled
in from the answers as it is sent.

The questions are the "Proposed question flow" (Q1–Q17) of the DHP intake questionnaire. A "Study information"
section comes first, holding what the research plan needs that Q1–Q17 do not ask: the title, the Principal
Investigator, the background, the analysis and the references.

```
mvn clean install
./start.sh --demo
```

## What it installs

| Path                      | What                                                                       |
|---------------------------|----------------------------------------------------------------------------|
| `/Schemas/dhpIntake`      | The intake questionnaire in eight sections, and the research plan generated from it |
| `/Workflows/dhpIntake`    | The process, as BPMN: raised → filled in and sent → research plan generated → marked submitted |
| `DhpIntakeDatesEvaluator` | Keeps a request from being sent while its start date is in the future, its end date comes before its start date, or a repeating request stops in a month already over |

There are no users of its own. Sign in as the time off demo's `demo-requester`, which `--demo` also installs.

## How the questionnaire's logic is expressed

Each follow-up question carries a condition on the answer it follows up. A question asked only after a gate
(Q3–Q6 after Q2, the waiver criteria after Q11, Q9–Q10 after Q8, Q12a after Q12, Q13a–c after Q13) sits in a
section of its own, and that section carries the condition. A question that is hidden is not required.

Yes/No questions offer `yes` and `no` options rather than using the `boolean` type, so that conditions and the
template compare known values.

## The generated research plan

`researchPlan` is a generated document, as in the time off demo. Its template is
`SLING-INF/content/Schemas/dhpIntake/v1/researchPlan/template`, a `.docx` with no extension. It was made from
the research office's `ResearchPlanTemplate.docx`: each of its Word content controls became a Jinja expression,
with the formatting the control gave typed-in text. The nine "Requested Data" category lines were replaced by
what the intake asks about: the data sources, frequency, patient identity and linkage with other data.

The template reads answers as `submission.answers.intake.<section>.<question>`. A choice gives the option's
value, such as `repeating`, so the template spells out each option's wording. A list (multi-select) is tested
with `"value" in ...`, and each chosen option gets a paragraph of its own.

Sending needs the generation worker running (`generate_compose.py --generation`, or the worker on
`localhost:18766` beside `start.py`).

## Not supported yet

The questionnaire asks for some things the platform cannot do yet. Each has a stand-in for now:

| The questionnaire asks for                        | For now                                                            |
|---------------------------------------------------|--------------------------------------------------------------------|
| Paragraph-length answers (background, analysis…)  | One-line text questions                                            |
| Q10 as a month picker                             | Text in the form `YYYY-MM`, checked by a pattern                   |
| Q13a as repeatable source/owner pairs             | One source per line of a multi-line answer                         |
| Q17 as a single checkbox                          | One "I confirm" option, required                                   |
| Information boxes with links (Q11a, Q17)          | Plain text in descriptions; links cannot be clicked                |
| Q17's plain-language policy summary               | Only the policy's name and address, until the summary is written   |
| "Flag to" REB, Legal, CTO or UHN Digital          | Not built: no approvals or routing yet                             |
| Cross-checks (Q12 against Q13; Q13b against Q12a) | Pointed out in the help text only                                  |
| Q5's site list                                    | Free text                                                          |
| Q4 "I'm not sure" routed to a data analyst        | Allowed with other options, and noted in the research plan         |
| Launching the Data Catalogue after sending        | Not built                                                          |

Still open in the questionnaire itself: whether sources other than UHN internal data follow a different path
(Q1), the CAPCR ID format (Q1), whether "I'm not sure" excludes other choices (Q4), whether a recurring end
date may be "Ongoing" (Q7), and the list of UHN-approved storage locations (Q15).
