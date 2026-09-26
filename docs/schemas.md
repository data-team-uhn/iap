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

A schema version is in exactly one of three states, marked by a tag in the `lifecycle` category:

| State | Tag | Accepts submissions | May change |
|---|---|---|---|
| Draft | `draft` | no | in any way |
| Active | `active` | yes | wording only |
| Retired | `retired` | no | wording only |

Draft → active happens once and is never undone: an active version may have submissions, and
[tags.md](tags.md) relies on a published version never changing. Active and retired toggle
freely.

A version with **no lifecycle tag** reads as retired: closed and frozen, the safe side of not
knowing. Everything the platform creates is tagged; a schema imported by hand must say
`"tags": ["active"]` (or `draft`) on its version to be usable.

A **schema** is open unless `retired` is placed on it. The tag is inheritable, so the versions of
a retired schema are retired too without being touched — and reopening the schema brings them
back as they were. `SchemaVersion.getState()` reports a version's own state; `isActive()` also
looks above it.

**Several versions may be active at once**: activating a draft does not retire the version it
replaces, that is a separate decision. `Schema.getActiveVersion()` returns the first one in
order.

The `draft`, `active` and `retired` definitions ship with this module (`content/Tags/`) because
submissions use `draft`, categories `retired`, and both already depend on it. `active` applies to
versions only.
