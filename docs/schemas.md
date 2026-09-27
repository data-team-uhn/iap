# Schemas

**Module:** `modules/schemas` · **Bundles:** `iap-schemas-api` (start-order 27), `iap-schemas-impl` (28) ·
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

## Editing through workflows

Nothing writes to `/Schemas` directly: every change is an event, `POST <path>.<event>.json`, handled
by a system workflow shipped with `schemas/impl` (`content/SystemWorkflows/`). Each admits only
`iap-administrators`, and each is its own definition, so a deployment can change one, adding an
approval step to publishing say, without touching the others.

The lifecycle is in those definitions, not in code. Where one event means different things in
different states, several workflows wait for it, each guarded by a condition on the target's own
tags (`tags`) and those it inherits from its schema (`inheritedTags`), and the one whose guard holds
runs. An event no guard admits is refused with a 409, and `@events` (see
[workflows.md](workflows.md)) never offers it.

| Target | Event | Guard | Steps |
|---|---|---|---|
| `/Schemas` | `create` (`title`, optional `version`) | | create the schema, send it `createVersion` |
| a schema | `createVersion` (optional `version`) | not `retired` | add an empty version, tag it `draft` |
| a schema | `update` (`patch`) | | edit `title` |
| a schema | `retire` | not `retired` | tag it `retired` |
| a schema | `activate` | `retired` | remove `retired` |
| a schema | `discard` | | delete it, with its versions |
| a version | `update` (`patch`) | `draft` | edit `version`, `description`, `workflow` |
| a version | `update` (`patch`) | not `draft` | edit `description` |
| a version | `activate` | `draft`, schema not retired | check it can be published, tag it `active` |
| a version | `activate` | `retired`, schema not retired | tag it `active` |
| a version | `retire` | `active` | tag it `retired` |
| a version | `discard` | | delete it |

A **patch** is one JSON object in the `patch` parameter: a key left out is left alone, `null`
removes the property, anything else is the new value. The whole patch is checked before anything is
written, and only the fields the workflow lists in its `fields` are accepted: a published version
keeps everything submissions may depend on, and only its wording can change.

An editor learns which fields it may offer from the `fields` serialization: `@fields` on each schema
and version lists the fields the requesting user's `update` would change there, with a label, a
kind, and whether each is mandatory or runs over several lines. It is read from the configuration of
the update workflow that would run, so no editor keeps a list of its own.

A draft is **published** only when nothing in it would break once it is frozen: answer counts and
value bounds that are not upside down, patterns that compile, option values that are present and
unique, and conditions that use known comparisons on questions of this same version. Every problem
is reported at once. Publishing a draft does not retire the version before it; several versions may
be active at once.

**Discarding** goes through the deletion service, into the archive: anything something else refers
to, such as a version that submissions or a category point at, is refused, with the referrers
listed. What is in use is retired instead.

Whether a version **accepts submissions** is the guard of the `submit` event on it, which the
submissions module's workflow will define: open to its own `active` versions, while the schema is
not retired.
