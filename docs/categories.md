# Categories

**Module:** `modules/categories` · **Bundle:** `iap-categories` (start-order 28) ·
**Models:** `io.uhndata.iap.categories.models`

Submitters pick from a tree of categories phrased in their own language rather than
naming a schema. The leaf they land on carries the schema version governing their
submission, and through it the workflow it follows.

The module contributes node types, two models, the `retired` tag, and the admin UI.
It ships no categories — the taxonomy is a deployment's own content. `test-data`
loads a sample one.

## Data model

`/Categories` is a `cat:CategoriesHomepage` (`data:EntityHomepage` +
`doc:Documented`); everything below is a `cat:Category` (`data:Entity`), nesting
arbitrarily deep. Both are `orderable`: sibling order is the JCR child order,
arranged by administrators, so common cases can come first.

| Property | Type | Notes |
|---|---|---|
| `label` | String | **Mandatory.** Shown to submitters; the node name is the technical id. |
| `description` | String | Used verbatim in the AI categorization prompt — prose, not keywords. |
| `schemaVersion` | Reference | The `sch:SchemaVersion` for submissions filed here. |

**The leaf invariant.** A category with no subcategories is a leaf; only leaves are
filed under, and only leaves are expected to carry a `schemaVersion` (without one,
submissions fall back to default behaviour). Node types can't express "only when
there are no children of this type", so the UI upholds it: `CategoryDialog` hides
the picker once a category has children and warns before a parent gains its first
one, and `unbindSchemaVersion()` exists for that transition. Splitting into group and
leaf types wouldn't help — the residual `- * (UNDEFINED)` and `+ * (nt:base)`
definitions admit `schemaVersion` on a group type and a child under a leaf type
anyway — and it would make adding a first subcategory a `jcr:primaryType` migration.

**Node names must not be numeric.** Names are read back as keys of the parent JSON
object (`parseCategoryChildren` walks `Object.entries`), and JavaScript enumerates
integer-like keys first in numeric order, so `2024` comes back out of its stored
position and reordering computes against the wrong neighbour. Nothing enforces this:
`create()` sends `:nameHint=<label>`. Name it `studies2024`, put the year in `label`.

## Retirement

Retiring closes a category to new submissions while existing ones keep referencing
it. It is the inheritable `retired` tag (`content/Tags/retired.json`,
`targetResourceTypes: ["cat/Category"]`), not a property — the repository
materializes an inheritable tag onto the whole subtree at commit time, so "this
category and its subcategories" is enforced rather than promised, and "everything
currently closed" is a query rather than a tree walk.

Which property the tag arrives in tells the two states apart, in Java
(`hasTag`/`hasOwnTag`) and in the frontend (`inheritedTags`/`tags`):

```java
public boolean isRetired()     { return as(Taggable.class).hasTag(RETIRED_TAG); }
public boolean isRetiredHere() { return as(Taggable.class).hasOwnTag(RETIRED_TAG); }
```

Only the category carrying the tag can have it lifted, so `isRetiredHere()` decides
whether the UI offers to unretire.

## Java API

```java
// CategoriesHomepage extends EntityHomepage implements AutoDocumentable
@NotNull  List<Category> getCategories();        // top-level, stored order
@NotNull  List<Category> getDocumentedItems();   // live leaves only

// Category extends Entity implements DocumentedItem
@NotNull  String getLabel();
@Nullable String getDescription();
@Nullable SchemaVersion getSchemaVersion();      // resolved, null if unset/unresolvable
@NotNull  List<Category> getSubcategories();     // stored order, filtered by resource type
          boolean isLeaf();  isRetired();  isRetiredHere();
```

## Reading over HTTP

| Request | Returns |
|---|---|
| `.deep.json` | The tree, stored order, bound schema versions inlined by `dereference` |
| `.deep.simple.json` | The same, schema versions summarized to what identifies them |
| `.paginate.json` | Flat listing with the standard filtering/sorting parameters |
| `.doc.md` / `.doc.json` | The autodoc catalogue — not the tree |

The manager uses `simple` deliberately: without it, a chip naming a bound schema
pulls down that schema's whole requirement subtree and a BPMN document.

The catalogue is a flat list of **live leaves only** — the set of valid choices.
`collectLiveLeaves()` prunes at a retired category rather than filtering leaf by
leaf. `Category` overrides `toMarkdown()` (level-2 heading, label only) and
`documentationJsonBuilder()` (appends `path` and `id`, since node names are unique
only among siblings). The primary consumer is AI-assisted categorization, which
builds its prompt from the descriptions; the heading comes from autocreated `title`
and `description` on the homepage.

## Writes

Deletion goes through the platform's deletion endpoint; every other write goes
directly to the standard Sling POST servlet from `useCategoryTree`, each followed by
a full re-read of the tree. That half is interim — see the gaps below — so the
per-operation request shapes live in the hook rather than here.

`DELETE` returns **409** when something still references the category, reason in
`status.message`. `useCategoryTree` raises `DeletionRefusedError`, which the UI
answers with an offer to retire instead. Deleted content goes to the archive.

## Frontend

`categoryModel.ts` is the pure half — no React, no fetch: `parseCategoryTree`,
`flattenTree`/`flattenForParentPicker` (with `excludePath`, so a category can't
become its own parent), `findNode`, `childrenOf`, `isDescendantPath`,
`hasDuplicateLabel`.

`useCategoryTree.ts` owns all I/O and exposes `create`, `update`,
`unbindSchemaVersion`, `move`, `reorder`, `setRetired`, `remove`, `reload`.

The manager mounts at `/admin/categories` via two extensions: `iap/coreUI/view` →
`CategoryManager.js` (lazy), and `iap/adminDashboard/entry` → `CategoriesWidget.js`.

## Access

Module repoinit (service ranking 300) creates the homepage and grants `everyone`
`jcr:read` on `/Categories` — submitters have to browse it to choose. Not
anonymously: `sling.auth.requirements` requires authentication outside `/libs`,
`/apps` and the login page regardless of ACLs.

## Known gaps

- **Nothing references a category yet.** `sub:Submission` has no `category` property.
  Adding it must settle precedence against the submission's own mandatory
  `schemaVersion`; the intent is that the category supplies it at creation and the
  submission freezes it, so re-binding never rewrites history.
- **Administrative writes belong in the workflow engine** — the direct POSTs are
  interim, and should become `createCategory`/`moveCategory`/… system workflows.
- **Retiring writes the tag directly**, skipping the validation `Taggable.tag()`
  does (tag defined, applies to this resource type, not a system tag). There is no
  HTTP tagging endpoint yet; the first should be generic, with this as its caller.
- **No `oak:index`** for querying categories.
