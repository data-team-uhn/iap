# Schemas

**Module:** `modules/schemas` · **Bundles:** `iap-schemas-api` (start-order 27), `iap-schemas-impl` (28) ·
**Models:** `io.uhndata.iap.schemas.models`

A schema describes what an institutional process asks of a submission: the questions to
answer, the documents to provide, the approvals to obtain. A schema is a container with
a name, and its content lives in its **versions**. A submission is filed against one
version. Once a version is published, only its wording can change.

## Data model

```
/Schemas                         sch:SchemasHomepage
└── clinicalStudy                sch:Schema           title
    └── v1                       sch:SchemaVersion    version, description, workflow → wf:WorkflowVersion
        ├── basics               sch:FormRequirement  label, documents, template
        │   ├── design           sch:Section          title
        │   │   └── arms         sch:Question         text, dataType, minAnswers, maxAnswers, …
        │   │       └── placebo  sch:AnswerOption     value, label
        │   └── cond:condition   (when this requirement applies)
        ├── consent              sch:DocumentRequirement  required, skipParsing, acceptedFileTypes, template
        ├── protocol             sch:DocumentRequirement
        │   └── funding          sch:SectionRequirement   label, required, headings, locationPrompt
        ├── is_proposal          sch:ValidationRequirement      prompt, document, confidenceThreshold, template
        ├── study_kind           sch:ClassificationRequirement  …, question → sch:Question (e.g. basics/kind)
        └── reb                  sch:ApprovalRequirement  approverGroup
            └── privacy          sch:Topic            text, description, assessmentPrompt, sources
```

Requirements, sections and questions are `orderable`: the order they are stored in is
the order they are presented in, a question's answer options included. An option is
named after its value, often a number, and a JavaScript object lists keys that look like
whole numbers first. The schema editor therefore reads the order from `@order` (see
[json-serialization.md](json-serialization.md)). A schema's versions are not ordered,
and the schema listing sorts them by label, with numbers compared as numbers (`2.0`
before `10.0`). Every requirement and form item is `cond:Conditionable`, so it may carry
one condition deciding whether it applies (see [conditions.md](conditions.md)).
Questions, requirements and topics are referenceable: answers, documents, reviews,
comments and assessments point back at them.

An approval requirement lists, in order, the **topics** its approvers want assessed. A
topic is a broad area, such as privacy or consent. Its `description` says what to look
for, `sources` names the requirements and questions that bear on it, and
`assessmentPrompt` is what the AI is asked. Each review, and the AI's screening,
assesses every topic on its own.

### Decisions about an upload

Two requirements ask a model about an uploaded document before anything else is read
out of it. Both name the document requirement whose upload is read (`document`), what
to ask (`prompt`), how sure the answer must be (`confidenceThreshold`, default 0.7), and
optional reference text (a `template` or `template.md` child). They share the abstract
`sch:DecisionRequirement`.

| Type | Asks | The answer is saved as |
|---|---|---|
| `sch:ValidationRequirement` | Is the upload what the request needs, e.g. a research proposal? | a `sub:Validation` on the submission; the submitter never answers it |
| `sch:ClassificationRequirement` | Which kind of request is it? | a suggestion on the answer to the question it references (`question`), which the submitter confirms |

Each validation reads one document: the proposal has its own, the consent form its own,
each with its own prompt and template. Once the uploads are parsed, the decisions are
made one document at a time, in the order of the document requirements: the document's
validation, then its classification. A sure `no` stops there. The document
requirement's `label` goes into what the submitter is told, so it is written to fit
"Please upload the <label>", "Unable to identify the <label>" and "The <label> is
missing, please upload it", such as "research proposal".

The classification's question is an ordinary question in a form requirement, so the form
shows it like any other. A reference to a question of the same version still points at
the right question when the version is copied.

### What a model reads for a form

A form requirement names the documents whose uploads a model reads to answer its
questions (`documents`, the names of document requirements, in the order they are
sent). It may carry reference text the model is shown with the questions, such as where
a protocol usually states its sample size (a `template` or `template.md` child, as the
decisions have). A form that names no documents is not read: its questions are left to
the submitter.

| Form | `documents` | What the model reads |
|---|---|---|
| Study details | `["proposal"]` | the proposal |
| Clinical trial | `["proposal", "consent"]` | both, joined, each under its own heading |
| Category | none | nothing: the classification fills its question |

Only the questions with an `extractionPrompt` are asked, and only while the form applies,
so a form whose condition reads the confirmed category is read only for that category.
See [extraction.md](extraction.md).

### Questions

| Property | Notes |
|---|---|
| `text`, `description` | What the submitter reads. |
| `dataType` | `text`, `long`, `double`, `boolean`, `date`. A file is asked for by a document requirement. |
| `minAnswers`, `maxAnswers` | How many values an answer takes. A positive minimum makes the question required, and a maximum other than 1 lets it take several values. Zero or less leaves that end open. |
| `minValue`, `maxValue` | Bounds for numeric answers. |
| `pattern`, `patternMessage` | A regular expression every text value must match in full, and what the submitter is told when one does not. |
| `optionsFrom` | A content path whose items are the options, instead of child options. |
| `purpose`, `extractionPrompt` | What answer extraction needs. |

A question with `sch:AnswerOption` children is answered only with their values. The
**value** is what an answer stores and what a condition compares against, so changing it
changes the meaning of every answer already recorded. The **label** is only what the
submitter reads.

### Document sections

A `sch:SectionRequirement` under a document requirement names a section that document
should contain, so that a topic can point at the funding section of the protocol rather
than at the whole protocol. Sections nest, and they are requirements in their own right:
they have a label, a description and a condition, and can be referenced. They are not
among the version's requirements, and nothing is uploaded for them.

| Property | Notes |
|---|---|
| `required` | Whether the document must contain the section. Defaults to true. |
| `headings` | The headings the section usually goes by, e.g. `Funding`, `Budget`. |
| `locationPrompt` | What an LLM is asked, to find the section when no heading matches. |

Where the sections are found is recorded for each revision of an uploaded document, as
`sub:Section` children of the `sub:DocumentVersion`. Only the current revision is read.
A section `fulfills` its requirement and consists of `sub:Passage`s, each with a `start`
and an `end` `sub:Context` quoting its first and last words in the revision's Markdown.
There are several when the section is split, none when it was looked for and not found.
No node means it was never looked for.

| Property | Notes |
|---|---|
| `method` | `heading` (an expected heading matched), `model` (an LLM located it), `pickedHeading` (a person named the heading), `selectedText` (a person selected the text). |
| `confidence` | How sure the model was; only for `model`. |
| `checkedBy` | Who picked or selected the section, or confirmed that it is missing. |

A section whose text was selected by hand follows no heading, so it may be a deviation
from the expected protocol.

### Resource types

Every requirement and form item resolves to the abstract `sch/SchemaPart`
(`sch/Question` → `sch/FormItem` → `sch/SchemaPart` → `data/EntityPart`, and likewise
through `sch/Requirement`). A servlet or a workflow bound to `sch/SchemaPart` therefore
serves every part of a schema, a new requirement type included. Answer options are not
schema parts.

## Lifecycle

A schema version carries one tag in the `lifecycle` category: `draft`, `active` or
`retired`. A schema may carry `retired` too. That tag is inheritable, so the versions of
a retired schema are retired along with it without being touched, and reopening the
schema brings them back as they were.

The node types and models hold no rule about what a state allows. The workflows that act
on schemas decide which transitions are allowed and what may still be edited, as guards
on the events they wait for. Creating a submission refuses a version that is not
`active`, or whose schema is `retired`. No workflow changes the state of a version that
carries no lifecycle tag, so a schema imported by hand should tag its versions, as
`tools/dev/test-data/DemoStudy.json` does.

The `draft`, `active` and `retired` definitions ship with `modules/lifecycle`. `active`
may be placed on a version only, not on a schema.

## Editing through workflows

Every change to `/Schemas` is an event, `POST <path>.<event>.json`, handled by a system
workflow shipped with `schemas/impl` (`content/SystemWorkflows/`). Each admits only
`iap-administrators`. Each is its own definition, so a deployment can change one, such
as adding an approval step to publishing, without touching the others.

The lifecycle is in those definitions, not in code. Where one event means different
things in different states, several workflows wait for it, each guarded by a condition
on the target's tags, and the one whose guard holds runs. A guard on a schema or a
version reads the `tags` operand, which includes the tags a version inherits from its
schema. Where it matters whether the schema is retired, it also reads `inheritedTags`
alone. An event no guard admits is refused with a 409, and `@events` (see
[workflows.md](workflows.md)) never offers it.

| Target | Event | Guard | Steps |
|---|---|---|---|
| `/Schemas` | `create` (`title`, optional `version`, `source`) | | create the schema, call `createVersion` on it |
| a schema | `createVersion` (optional `version`, `source`) | not `retired` | add a version, empty or a copy of `source` linked to it, tag it `draft` |
| a schema | `update` (`patch`) | | edit `title` |
| a schema | `retire` | not `retired` | tag it `retired` |
| a schema | `activate` | `retired` | remove `retired` |
| a schema | `discard` | | delete it, with its versions |
| a version | `update` (`patch`) | `draft` | edit `version`, `description`, `workflow` |
| a version | `update` (`patch`) | not `draft` | edit `description` |
| a version | `activate` | `draft`, schema not retired | check it can be published, retire the active version, tag it `active` |
| a version | `activate` | `retired`, schema not retired | retire the active version, tag it `active` |
| a version | `retire` | `active` | tag it `retired` |
| a version | `discard` | | delete it |
| a version | `create` (`type`, optional `before`, `patch`) | `draft` | add a form, document or approval requirement |
| a requirement, section or question | `update` (`patch`) | its version `draft` | edit its fields (see below) |
| a requirement, section or question | `update` (`patch`) | its version not `draft` | correct what it says (see below) |
| a requirement, section or question | `create` (`type`, optional `before`, `patch`) | its version `draft` | add a section or question to a form or section, or an option to a question |
| a requirement, section or question | `discard` | its version `draft` | delete it, with what it holds, unless a condition elsewhere depends on it |
| a requirement, section or question | `move` (optional `parent`, `before`) | its version `draft` | move it, with what it holds, elsewhere in its version |
| a requirement, section or question | `rename` (`name`) | its version `draft` | give it another identifier, keeping its place |
| a requirement, section or question | `condition` (`content`) | its version `draft` | set when it applies, or make it always apply with `null` |
| an answer option | `update` (`patch`) | its version `draft` | edit its `value`, `label` or `description` |
| an answer option | `discard` | its version `draft` | delete it |
| an answer option | `move` (optional `parent`, `before`) | its version `draft` | move it before another option of its question, or to the end of its question's options |
| an answer option | `update` (`patch`) | its version not `draft` | correct its `label` or `description` |

A **patch** is one JSON object in the `patch` parameter: a key left out is left alone,
`null` removes the property, anything else is the new value. The whole patch is checked
before anything is written, and only the fields the workflow lists in its `fields` are
accepted. The updates of a published version list only its wording.

While a version is a **draft**, its parts and answer options can change: a question's
`dataType`, answer counts, bounds, pattern and `optionsFrom`, what a document
requirement accepts and whether it is required, who approves an approval, an option's
`value`, and all of their wording. No workflow edits a question's `displayMode` or
`responseShape`, or a section requirement's `headings` or `locationPrompt`. Which fields
a question offers follows its `dataType`: bounds only for numbers, a pattern only for
text, and a field that stops applying when the `dataType` changes is removed.

Each of a version's two update workflows says what it allows as its `notice` (see
[workflows.md](workflows.md)), which the version's page shows under its title: that
anything can change in a draft, or that only the wording of a published version can be
corrected and anything else needs a new version.

Parts and options are **added** with the engine's `createContent` task (see
[workflows.md](workflows.md)), where the node types say they may go: requirements in a
version, sections and questions in a form or a section, options in a question. The new
part goes before the sibling named in `before`, or else last, and is filled in from the
`patch` with the fields a draft's update offers. `@creatable` tells an editor what may
be added where. Parts and options are **removed** into the archive. A question that a
condition elsewhere in the version names stays, and the refusal names the parts whose
conditions depend on it. `ConditionDependencyVeto`, a deletion veto, finds them through
the conditions module's `AnswerDependencies`. Removing the condition's own part along
with the question is allowed. Parts and options are **moved** with the engine's
`moveContent` task. They go into the `parent` the event names, or stay in their own, and
land before the sibling named in `before`, or else last. One event can therefore both
reorder and move. They go only where the node types say they may. A part never leaves
its version, and an option never leaves its question. A condition that names a moved
question by its path names it by its identifier from then on, so a move breaks no
condition.

Parts are **renamed** with the engine's `renameContent` task, and a new part is created
with the identifier asked for. Either way, the name is made of letters, digits, `-` and
`_`, and starts with a letter or a digit. The create workflows say so in words as their
`nameHint`, which the editor shows under the identifier. The editor suggests an
identifier from what a new part says, as `@creatable` allows it, until one is given. An
option is not given an identifier: `createSchemaPart` lists it with `named` false. Its
`value` is what answers store, and a draft can edit it.

When a part applies is **set** with the engine's `replaceContent` task. The event's
`content` is the part's whole new `cond:condition`, or `null` to remove it. Only
condition node types may be written. The editor builds it with the conditions module's
`ConditionDialog` (see [conditions.md](conditions.md)). A condition compares something
with values of its type: the answer to a question of the version, the submission's tags,
or one of the submission's properties. It names a question by its `jcr:uuid`. A question
taking its options from elsewhere (`optionsFrom`) offers the items there, by their
titles or labels, loaded once for the version.

A question either lists its options or takes them from elsewhere, as `optionsFrom`, but
not both. `createSchemaPart` adds no option to a question with an `optionsFrom`: its
guard reads the question's own property through an `ownProperty` operand (see
[conditions.md](conditions.md)). A draft that has both is not published. No two options
of a question share a `value`. The option workflows list `value` as `unique`, so a
repeated value is refused as it is entered. The publish check refuses one that came
another way.

The guards on parts and options read the version's own tags: a condition's `property`
and `tags` operands resolve on the enclosing entity, which for a part or an option is
its version.

Parts and answer options of a published version can only be **corrected**, and a
correction reaches the submissions already filed against that version at once. A
correction may not change what a stored answer means, which answers are valid, or which
parts apply: those take a new version. What can be corrected is what people read and
what guides reading answers out of documents: a requirement's `label` and `description`,
a section's `title` and `description`, a question's `text`, `description`,
`patternMessage`, `purpose` and `extractionPrompt`, a document requirement's
`aiCheckPrompt`, and an option's `label` and `description`. An option's `value`, a
question's data type, answer counts, bounds, pattern and conditions, and what a
requirement requires, cannot.

An editor learns which fields it may offer from the `fields` serialization: `@fields` on
each schema, version, part and option lists the fields the requesting user's `update`
could change there, with their kind, the values they may take and when they apply (see
`updateContent` in [workflows.md](workflows.md)). It is read from the configuration of
the update workflow that would run, so no editor keeps a list of its own. A version's
`workflow` may point only at a user workflow, under `/Workflows`.

A draft is **published** only when nothing in it would break once it is frozen: answer
counts and value bounds that are not reversed, patterns that compile, option values that
are present and unique, no question that both lists options and takes them from
elsewhere, and conditions that use known comparators and aggregators on questions of
this same version. Every problem is reported at once. Each rule is a `ValidityCheck`
service (`io.uhndata.iap.workflows.spi`) that applies to schema versions, and publishing
runs every one that applies, so a module that adds parts to a schema can add the checks
they need. Activating a version retires the one that was active, in the same run, so one
version at a time takes new submissions. Those already filed against the retired one
carry on.

**Discarding** goes through the deletion service, into the archive: anything something
else refers to, such as a version that submissions or a category point at, is refused,
with the referrers listed. A version or a schema in use can be retired instead.

### Copying

A new version, of an existing schema or of a new one, can start as a copy of any
version, of any schema and in any state, named by its path in the event's `source`.
`createVersion`'s workflow creates the version, then copies the source into it with the
engine's `copyContent` task (see [workflows.md](workflows.md)), keeping the new
version's own label. It then links the copy to the source as `copiedFrom`, and tags it
`draft`, which replaces the lifecycle tag copied from the source. The copy keeps every
part, option, condition and template. References to anything outside it, such as the
version's workflow, are kept, and a condition naming a question by path keeps that path,
which now finds the copied question.

## In the admin console

The Schemas tool (`/admin/schemas`) lists every schema with its versions nested under
it. Each schema has a page, `/admin/schemas/<schema>`, listing its versions, and each
version a page of its own, `/admin/schemas/<schema>/<version>`, showing everything it
asks of a submission. A version is edited on its page, and each change is sent as an
event.

`/admin/schemas/<schema>/<version>?compare=<other>` shows what changed from `<other>`
to `<version>`: the parts added, removed, changed and moved, and the fields that
changed in each. The schema page's view extension, `Extensions/Admin/Views/Schema.json`,
sets what is compared:

| Property | Meaning |
|---|---|
| `comparisonVersionFieldsFrom` | The workflow definitions whose update fields are compared on a version |
| `comparisonPartFieldsFrom` | The same, for the parts |
| `comparisonOptionFieldsFrom` | The same, for the answer options |
| `comparisonDefaults` | The rules choosing what a version is compared with when nobody names one, tried in order: `active` (the active version), `source` (the version it was copied from) and `previous` (the version created just before it) |

The actions on a schema are extensions on the `SchemaActions` point, and those on a
version on `SchemaVersionActions`. A version action names in `ext:places` where it is
shown: `versionList` for the lists of versions, `versionPage` for a version's own page.
One naming neither is shown in both.

The dashboard widget reads `GET /Schemas.adminSummary.json`. It counts the active
versions of the schemas that are not retired, the draft versions and the retired
schemas. Each figure counts what carries that lifecycle tag itself.
