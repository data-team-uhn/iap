# Data requirements

**Module:** `modules/data-requirement` · **Bundles:** `iap-data-requirement-api`
(start-order 28), `iap-data-requirement-impl` (28) · **Models:**
`io.uhndata.iap.datarequirement.models`

A data requirement asks a submitter which existing data they need, and lets them choose
it field by field out of a catalogue. A catalogue describes the databases a team can
release data from, their collections and their fields. It is content, and it is
published in versions, so what a submitter was offered never changes underneath them.

The module is optional. `packaging/slingfeature/src/main/features/optional/` adds it to
the test and demo distributions only, and `start.py --test` or `--demo` adds it to a
local instance. A distribution that wants data requirements takes
`iap-data-requirement-impl`'s feature, which brings both bundles.

## Data model

```
/Catalogues                      datareq:CataloguesHomepage
└── registry                     datareq:Catalogue          title, description
    └── v2                       datareq:CatalogueVersion   version, description
        └── visits               datareq:Database           identifier, label, description
            └── Visit            datareq:Collection         identifier, label
                └── visitDate    datareq:Field              identifier, label, description,
                                                            cardinality, dataType, phi, example

/Schemas/study/v1
└── data                         datareq:DataRequirement    label, catalogue → datareq:CatalogueVersion

/Submissions/…/study
└── <part>                       datareq:Selection          fulfills → the requirement, fields
```

A version is a full copy: a field that did not change between two versions is two nodes.
A version is named `v1`, `v2` and so on, and `version` holds the label a reader sees,
such as `2026-08`.

**A selection holds keys, not references.** A key is `database/collection/field`, built
from the three `identifier`s by `Field.getKey()`. A reference would have to be rewritten
for each new version, while a key stays the same and is what an export outside the
platform carries. The browser builds keys the same way, and the save refuses a key the
version does not offer, so the two spellings must not drift. `phi` has no default:
absent means nobody assessed the field, and `false` means it holds no personal health
information.

`/Catalogues` is created by the module's repoinit and is readable by everyone. A
submitter has to see what is on offer, and a reviewer has to read a filed selection's
keys against the catalogue.

## Pinning a catalogue version

**A data requirement names one catalogue version, never a catalogue.** Its mandatory
`catalogue` REFERENCE points at a `datareq:CatalogueVersion`, so every submission filed
under a schema version is offered the same fields. Offering a new catalogue version
takes a new schema version, as offering a new workflow version does. Copying a schema
version keeps the reference, and an author points the copy at a newer version in the
draft.

`Content.getReference` adapts whatever node a reference leads to, so the model checks
the type itself:

```java
CatalogueVersion getCatalogueVersion();  // the version; NullPointerException if it leads elsewhere
CatalogueVersion findCatalogueVersion(); // the same, or null if missing, unreadable or not a version
Selection findSelectionIn(Content submission);  // the selection answering it, or null
```

`DataRequirementCatalogueCheck`, a `ValidityCheck` on `sch/SchemaVersion`, refuses to
publish a schema version whose data requirement names a catalogue version that is not
open. Open means tagged `active` while neither the version nor its catalogue carries
`retired`, which is what `CatalogueVersion.isActive()` answers. A schema version already
published keeps the version it names, whatever happens to it later.

## Answering a data requirement

| Piece | What it does |
|---|---|
| `SelectionPartCreator` | Gives each data requirement that applies an empty selection when the submission's parts are made |
| `saveDataSelection` workflow | `POST <submission>.saveDataSelection.json` with `requirement` (its name or path) and `fields`, repeated once per key; `@creator` only |
| `SaveDataSelectionHandler` | Replaces the whole selection; refuses a key the named version does not offer (400), anybody but the creator (403), a submission no longer a draft (409) |
| `SelectionCompletenessEvaluator` | Marks a selection with nothing chosen as missing |
| `DataRequirementDescriber` | Adds `fields`, `catalogueVersion` (its path) and `catalogueVersionLabel` to the requirement on the submission's `.form.json` |

The form component, `DataRequirement.tsx`, loads the version with `useNode(path, "3",
readCatalogue)` and saves each change through the editor's queue. It registers through
the `RequirementComponent` extension point that `submissions` declares, so its asset is
not lazy.

`catalogue/` holds `DataCatalogue`, the browser itself. It owns no catalogue and no
selection: both arrive as props, and `readOnly` disables every control that would change
the selection. An ESLint rule keeps `catalogue/` free of imports from other modules, so
the browser can be shown anywhere.

## Serialization

| Processor | Default | What it does |
|---|---|---|
| `catalogueSummary` | on | Writes a data requirement's `catalogue` as `{"@path", "version", "catalogue": {"@path", "title"}}`, instead of the whole version `dereference` would inline |
| `dataCatalogueFieldKey` | on | Adds each field's `key` |

## The catalogue lifecycle

A catalogue version carries one tag in the `lifecycle` category: `draft`, `active` or
`retired`. A catalogue may carry `retired` too. That tag is inheritable, so retiring a
catalogue closes every version under it without touching them, and reopening it brings
each back as it was. The tags are defined in `modules/lifecycle`.

Every change is an event handled by a system workflow shipped with the api bundle
(`content/SystemWorkflows/`). Each admits only `iap-administrators`, and uses generic
steps from `modules/workflows`.

| Definition | Target | Event | Guard | Steps |
|---|---|---|---|---|
| `activateCatalogueVersion` | a version | `activate` | `draft`, catalogue not retired | check it can be published, retire the active version, tag it `active` |
| `reactivateCatalogueVersion` | a version | `activate` | `retired`, catalogue not retired | retire the active version, tag it `active` |
| `retireCatalogueVersion` | a version | `retire` | `active` | tag it `retired` |
| `retireCatalogue` | a catalogue | `retire` | not `retired` | tag it `retired` |
| `reopenCatalogue` | a catalogue | `activate` | `retired` | remove `retired` |

Two definitions catch `activate` on a version, and their guards exclude each other,
since the engine refuses an event that starts two workflows. The guards read
`inheritedTags` for whether the catalogue is retired. An event no guard admits is
refused with a 409. `CatalogueSystemWorkflowsTest` checks that in each state each event
starts one workflow or none. No validity check applies to a catalogue version yet, so
the first activation's check always passes.

Retiring a catalogue version stops it being offered to new schema versions. Schema
versions that already name it keep it, and so do the submissions filed under them.

## In the admin console

The Catalogues tool (`/admin/catalogues`) lists every catalogue with its versions nested
under it. Each catalogue has a page, `/admin/catalogues/<catalogue>`, listing its
versions, and each version a page, `/admin/catalogues/<catalogue>/<version>`, showing
its fields in `DataCatalogue` with `readOnly` set. The dashboard widget reads
`/Catalogues.adminSummary.json`, which the search module answers for any homepage.

The actions are extensions on two points the module declares, so another module can add
its own:

| Point | `ext:pointId` | Actions |
|---|---|---|
| `CatalogueActions` | `datareq/catalogue/actions` | Retire, Reopen |
| `CatalogueVersionActions` | `datareq/catalogueVersion/actions` | Activate, Retire |

Each action shows only when the node's `@events` offers its event.

## What is still missing

- **Publishing a version through the engine.** Catalogues and versions arrive as initial
  content. Creating a version, editing a draft and discarding one need workflows, and
  checks a version must pass before it is published, such as unique keys.
- **Choosing a data requirement in the schema editor.** The editor cannot add a data
  requirement or change the version it names, since the schemas module lists the
  requirement types it creates and the fields it edits.
- **Comparing two versions**, to show a reader which fields have moved on.
- **A lifecycle API.** `CatalogueVersion.isActive()` and the `retired` tag's list of
  targets each restate the lifecycle rules for one type.
