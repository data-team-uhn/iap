# Schemas

**Module:** `modules/schemas` · **Bundle:** `iap-schemas-api` (start-order 27) ·
**Models:** `io.uhndata.iap.schemas.models`

A schema describes what an institutional process asks of a submission: the questions to
answer, the documents to provide, the approvals to obtain. Submissions are filed against one
**version** of a schema, and that version must never change under them — which is why a schema
is only a container, and its content lives in versions.

## Data model

```
/Schemas                         sch:SchemasHomepage
└── clinicalStudy                sch:Schema           title
    └── 1.0                      sch:SchemaVersion    version, description, workflow → wf:WorkflowVersion
        ├── basics               sch:FormRequirement  label
        │   ├── design           sch:Section          title
        │   │   └── arms         sch:Question         text, dataType, minAnswers, maxAnswers, …
        │   │       └── placebo  sch:AnswerOption     value, label
        │   └── cond:condition   (when this requirement applies)
        ├── consent              sch:DocumentRequirement  required, acceptedFileTypes, template
        └── reb                  sch:ApprovalRequirement  approverGroup
```

Versions, requirements, sections and questions are `orderable`: the order they are stored in is
the order they are presented in. Every requirement and form item is `cond:Conditionable`, so it
may carry one condition deciding whether it applies (see [conditions.md](conditions.md)).
Questions and requirements are referenceable, because answers, documents and reviews point back
at them.

### Questions

| Property | Notes |
|---|---|
| `text`, `description` | What the submitter reads. |
| `dataType` | `text`, `long`, `double`, `boolean`, `date`, `file`. |
| `minAnswers`, `maxAnswers` | How many values an answer takes. A positive minimum is what "required" means, a maximum other than 1 is what "multiple" means; zero or negative leaves that end open. |
| `minValue`, `maxValue` | Bounds for numeric answers. |
| `pattern`, `patternMessage` | A regular expression every text value must match, and what to say when one does not. |
| `optionsFrom` | A content path whose live items are the options, instead of child options. |
| `purpose`, `extractionPrompt`, `responseShape`, `rubricTags` | What answer extraction needs. |

A question with `sch:AnswerOption` children is answered only with their values. The **value** is
what an answer stores and what a condition compares against, so changing it changes the meaning
of every answer already recorded; the **label** is only what the submitter reads.

### Resource types

Every requirement and form item resolves to the abstract `sch/SchemaPart`
(`sch/Question` → `sch/FormItem` → `sch/SchemaPart` → `data/EntityPart`, and likewise through
`sch/Requirement`). That is what lets one servlet binding and one workflow target cover every
part of a schema, including requirement types added later. Answer options are not schema parts.

## Lifecycle

A schema version carries one tag in the `lifecycle` category: `draft`, `active` or `retired`. A
schema may carry `retired` too. That tag is inheritable, so the versions of a retired schema
are retired along with it without being touched, and reopening the schema brings them back as
they were.

The models only store these tags; they do not say what a state allows. Which moves exist
between states, what may still be edited, and whether a version accepts submissions are all
decided by the workflows that act on schemas, as guards on the events they wait for. A version
carrying no lifecycle tag can be moved by none of them, so a schema imported by hand should tag
its versions, as `tools/dev/test-data/DemoStudy.json` does.

The `draft`, `active` and `retired` definitions ship with this module (`content/Tags/`) because
submissions use `draft`, categories `retired`, and both already depend on it. `active` applies
to versions only.
