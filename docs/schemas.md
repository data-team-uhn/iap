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

Requirements, sections and questions are `orderable`: the order they are stored in is the order
they are presented in. A schema's versions are not, so they are listed by label, in numeric order
(`1.0`, `2.0`, `10.0`). Every requirement and form item is `cond:Conditionable`, so it
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
| `/Schemas` | `create` (`title`, optional `version`, `source`) | | create the schema, send it `createVersion` |
| a schema | `createVersion` (optional `version`, `source`) | not `retired` | add a version, empty or a copy of `source`, tag it `draft` |
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
| a version | `create` (`type`, optional `before`, `patch`) | `draft` | add a form, document or approval requirement |
| a requirement, section or question | `update` (`patch`) | its version `draft` | edit anything it holds (see below) |
| a requirement, section or question | `update` (`patch`) | its version not `draft` | correct what it says (see below) |
| a requirement, section or question | `create` (`type`, optional `before`, `patch`) | its version `draft` | add a section or question to a form or section, or an option to a question |
| a requirement, section or question | `discard` | its version `draft` | delete it, with what it holds, unless a condition elsewhere depends on it |
| a requirement, section or question | `move` (optional `parent`, `before`) | its version `draft` | move it, with what it holds, elsewhere in its version |
| a requirement, section or question | `rename` (`name`) | its version `draft` | give it another identifier, where it stands |
| an answer option | `update` (`patch`) | its version `draft` | edit its `value`, `label` or `description` |
| an answer option | `discard` | its version `draft` | delete it |
| an answer option | `move` (optional `parent`, `before`) | its version `draft` | move it before another option, or to the end of a question's options, in its version |
| an answer option | `update` (`patch`) | its version not `draft` | correct its `label` or `description` |

A **patch** is one JSON object in the `patch` parameter: a key left out is left alone, `null`
removes the property, anything else is the new value. The whole patch is checked before anything is
written, and only the fields the workflow lists in its `fields` are accepted: a published version
keeps everything submissions may depend on, and only its wording can change.

While a version is a **draft**, its parts and answer options can change in anything they hold: a question's
`dataType`, answer counts, bounds, pattern and `optionsFrom`, what a document requirement accepts and
whether it is required, who approves an approval, an option's `value`, and all of their wording. Which
fields a question offers follows its `dataType`: bounds only for numbers, a pattern only for text, and a
field that stops applying when the `dataType` changes is removed. Conditions and templates come later.

Each of a version's two update workflows says what it allows as its `notice` (see [workflows.md](workflows.md)),
which the version's page shows under its title: that anything can change in a draft, or that only the wording of a
published version can be corrected, the rest needing a new version.

Parts and options are **added** with the engine's `createContent` task (see [workflows.md](workflows.md)), where
the node types say they may go: requirements in a version, sections and questions in a form or a section, options
in a question. The new part goes before the sibling named in `before`, or else last, and is filled in from the
`patch` with the fields a draft's update offers; `@creatable` tells an editor what may be added where. Parts and
options are **removed** into the archive. A question that a condition elsewhere in the version names stays, and
the refusal names the parts whose conditions depend on it (`ConditionDependencyVeto`, a deletion veto, which asks
the conditions module what depends on what); removing the condition's own part along with the question is fine.
Parts and options are **moved** with the engine's `moveContent` task, into the `parent` the event names, or within
their own, before the sibling named in `before`, or else last, so one event both reorders and moves. They go only
where the node types say they may, and never out of their version. A condition that names a moved question by its
path names it by its identifier from then on, so a move breaks no condition.
Parts are **renamed** with the engine's `renameContent` task, and created with the identifier asked for, to a name
made of letters, digits, `-` and `_`, starting with a letter or a digit, which keeps dots out of paths, where they
would read as selectors. The create workflows also say so in words, as their `nameHint`, which the editor shows
under the identifier, and a test holds the create and rename workflows to the same pattern and words. The editor
suggests an identifier from what a new part says, as `@creatable` allows it, until one is given. An option has no
identifier of its own, which `createSchemaPart` says by listing it as not `named`: its `value` is what answers store,
and a draft can edit it.
The guards read the version's own tags: a condition's `property` and `tags` operands resolve on the enclosing
entity, which for a part or an option is its version.

Parts and answer options of a published version can only be **corrected**, and a
correction reaches the submissions already filed against that version at once. A correction may not
change what a stored answer means, which answers are valid, or which parts apply: those take a new
version. What can be corrected is what people read and what guides reading answers out of documents:
a requirement's `label` and `description`, a section's `title` and `description`, a question's `text`,
`description`, `patternMessage`, `purpose` and `extractionPrompt`, a document requirement's
`aiCheckPrompt`, and an option's `label` and `description`. An option's `value`, a question's data type,
answer counts, bounds, pattern and conditions, and what a requirement requires, cannot.

An editor learns which fields it may offer from the `fields` serialization: `@fields` on each schema,
version, part and option lists the fields the requesting user's `update` would change there, with their
kind, the values they may take and when they apply (see `updateContent` in [workflows.md](workflows.md)).
It is read from the configuration of the update workflow that would run, so no editor keeps a list of
its own. A version's `workflow` may point only at a user workflow, under `/Workflows`.

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

### Copying

A new version, of an existing schema or of a new one, can start as a copy of any version, of any
schema and in any state, named by its path in the event's `source`. `createVersion`'s workflow creates
the version, then copies the source into it with the engine's `copyContent` task (see
[workflows.md](workflows.md)), keeping the new version's own label, then tags it `draft`, which
replaces where the source stood. The copy keeps every part, option, condition and template; references
to anything outside it, such as the version's workflow, are kept, and conditions name the copied
questions.

## In the admin console

The Schemas tool (`/admin/schemas`) lists every schema with its versions nested under it. Each
schema has a page, `/admin/schemas/<schema>`, listing its versions, and each version a page of its
own, `/admin/schemas/<schema>?version=<name>`, showing everything it asks of a submission. The version
is a query parameter because version names such as `1.0` have dots, and the admin console leaves a
path whose last segment has a dot to normal resolution, as a selector or an extension.
