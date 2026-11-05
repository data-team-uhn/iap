# Categories

**Module:** `modules/categories` · **Bundle:** `iap-categories` (start-order 28) ·
**Models:** `io.uhndata.iap.categories.models`

Submitters choose from a tree of categories worded in their own terms, instead of
naming a schema. The leaf they choose names the schema version for their submission,
and through it the workflow.

The module contributes node types, two models, and the administrative UI. It ships no
categories: the tree is a deployment's own content. `test-data` loads a sample one.

## Data model

`/Categories` is a `cat:CategoriesHomepage` (`data:EntityHomepage` +
`doc:Documented`). Everything below it is a `cat:Category` (`data:Entity`), nested to
any depth. Both are `orderable`: sibling order is the JCR child order, which
administrators arrange.

| Property | Type | Notes |
|---|---|---|
| `label` | String | **Mandatory.** Shown to submitters; the node name is the technical name. |
| `description` | String | What belongs in the category, as prose for whoever chooses one. |
| `schemaVersion` | Reference | The `sch:SchemaVersion` that submissions filed here follow. |

**The leaf rule.** A category with no subcategories is a leaf. Submissions are filed
under leaves, and only leaves are expected to carry a `schemaVersion`. A node type
cannot express "only when it has no children of this type", so the UI upholds the
rule. `CategoryDialog` hides the schema version picker on a category with children,
and warns before a bound parent gains its first one. Saving then removes the parent's
binding with `unbindSchemaVersion()`.

**Node names must not be numeric.** The UI reads the children as the keys of the
parent's JSON object (`parseCategoryChildren` walks `Object.entries`). JavaScript lists
integer-like keys first, in numeric order, so `2024` comes back out of its stored
position, and reordering then picks the wrong neighbour. Nothing enforces this:
`create()` sends `:nameHint=<label>`. Name the node `studies2024`, and put the year in
the `label`.

## Retirement

Retiring a category closes it, and everything under it, to new submissions. The
catalogue leaves it out. Retirement is the inheritable `retired` tag, not a property.
The tags module copies an inheritable tag onto the whole subtree at commit time, so finding every
retired category is a query. The tag's definition ships with `modules/lifecycle`
(`content/Tags/retired.json`).

The property the tag arrives in tells the two states apart, in Java
(`hasTag`/`hasOwnTag`) and in the frontend (`inheritedTags`/`tags`):

```java
public boolean isRetired()     { return as(Taggable.class).hasTag(RETIRED_TAG); }
public boolean isRetiredHere() { return as(Taggable.class).hasOwnTag(RETIRED_TAG); }
```

Only the category that carries the tag can have it lifted, so the UI offers to unretire
only there.

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
| `.deep.json` | The tree in stored order, with bound schema versions inlined by `dereference` |
| `.deep.simple.json` | The same, with each schema version cut to its own properties |
| `.paginate.json` | A flat listing, with the standard filtering and sorting parameters |
| `.doc.md` / `.doc.json` | The autodoc catalogue, not the tree |

The manager reads `.deep.simple.json`. Without `simple`, each inlined schema version
brings its requirements and its workflow, BPMN included.

The catalogue is a flat list of the **live leaves**: the categories a submission may be
filed under now. `collectLiveLeaves()` stops at a retired category instead of checking
each leaf below it. `Category` overrides `toMarkdown()`, for a level 2 heading with the
label only. It also overrides `documentationJsonBuilder()`, which adds `path` and `id`,
since a node name is unique only among its siblings. The catalogue's title and
introduction are the homepage's autocreated `title` and `description`.

## Writes

Deletion goes through the platform's deletion endpoint. Every other write is a POST
from `useCategoryTree` to the Sling POST servlet, and each is followed by a reload of
the whole tree. The direct POSTs are temporary (see the gaps below), so their request
shapes are documented in the hook.

`DELETE` answers **409** when something still references the category or a deletion
guard refuses, with the reason in `status.message`. `useCategoryTree` raises
`DeletionRefusedError`, and the UI then offers to retire the category instead. Deleted
content goes to the archive.

## Frontend

`categoryModel.ts` parses and computes, and neither renders nor fetches:
`parseCategoryTree`, `flattenTree`/`flattenForParentPicker` (with `excludePath`, so a
category cannot be moved under itself), `findNode`, `childrenOf`, `isDescendantPath`,
`hasDuplicateLabel`.

`useCategoryTree.ts` does all the reading and writing, and exposes `create`, `update`,
`unbindSchemaVersion`, `move`, `reorder`, `setRetired`, `remove` and `reload`.

The manager is at `/admin/categories`, through two extensions: `iap/coreUI/view` →
`CategoryManager.js` (lazy), and `iap/adminDashboard/entry` → `CategoriesWidget.js`.

## Access

The module's repoinit (service ranking 300) creates the homepage and grants `everyone`
`jcr:read` on `/Categories`, since submitters browse it to choose. Anonymous users still
cannot read it: `sling.auth.requirements` requires a login everywhere except a few
paths, such as `/libs`, `/apps` and the login page, whatever the ACLs say.

## Known gaps

- **Nothing references a category yet.** `sub:Submission` has no `category` property.
  Adding one has to settle how it relates to the submission's own mandatory
  `schemaVersion`.
- **Administrative writes belong in the workflow engine.** The direct POSTs should
  become `createCategory`/`moveCategory`/… system workflows.
- **Retiring writes the tag directly**, without the checks `Taggable.tag()` makes: the
  tag is defined, applies to this resource type, and is not a system tag. There is no
  HTTP endpoint for tagging yet.
- **No `oak:index`** for querying categories.
