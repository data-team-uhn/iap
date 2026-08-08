# Categories

Submitters shouldn't have to know which schema applies to their work. Instead they pick from a
tree of **categories** describing kinds of submissions in their own language — "Retrospective Data
Studies", "Clinical trials involving experimental products" — and the category they land on
carries the schema version that governs what they will be asked for, and through it the workflow
their submission follows.

The tree lives under **`/Categories`** and is maintained by administrators, not by code: the
`categories` module (`modules/categories`) contributes the node types, a starter taxonomy, and the
administration UI for reshaping it.

## The data model

`/Categories` is a `cat:CategoriesHomepage`, and every node below it is a `cat:Category`.
Categories nest by containment, arbitrarily deep, and both types are `orderable`: sibling order is
the JCR child order, deliberately arranged by administrators rather than derived from labels, so
the list submitters read can put the common cases first.

| Property | Type | Meaning |
| --- | --- | --- |
| `label` | String | The human-readable name shown to submitters. Mandatory; the node name is the technical identifier. |
| `description` | String | What belongs in this category. Written as guidance for submitters, and used verbatim as input for AI-assisted categorization, so it should read as prose rather than as a keyword list. |
| `schemaVersion` | Reference | The `sch:SchemaVersion` governing submissions filed under this category, which in turn references their workflow. |
| `retired` | Boolean | No new submissions may be filed under this category or its subcategories; existing ones keep referencing it. Defaults to `false`. |

A category with no subcategories is a **leaf**, and leaves are what submissions are actually filed
under — an inner node is a grouping, not a choice. Only leaves are therefore expected to carry a
`schemaVersion`; node types cannot express "this property only applies when there are no children
of this type", so that rule is upheld by the administration UI rather than by the repository. A
leaf without one falls back to the default behavior for submissions. Note that a category may hold
children that are *not* categories without ceasing to be a leaf.

`cat:Category` extends `iap:Entity`: categories are top-level data, comparable to schemas and
workflows, rather than platform vocabulary like tag or link definitions. So each one is
referenceable (which is how a submission will point at it), versionable, tracks its last
modification, can be [tagged](tags.md) and [linked](links.md), and accepts any further properties
and children a deployment adds. `cat:CategoriesHomepage` extends `iap:EntityHomepage` and names
`cat:Category` as its `childNodeType`, since the entity type listed by a homepage is otherwise
derived from the homepage's own name — a convention that an irregular plural like this one breaks.

The module ships a starter taxonomy of study types as initial content, loaded into the
`/Categories` node that its repoinit creates. It is ordinary content: a deployment is expected to
edit, extend, or replace it, and nothing in the platform depends on those particular categories.
The whole tree is world-readable, because submitters have to browse it to choose.

## Reading the tree

The Java API is Sling Models (`io.uhndata.iap.categories.models`), like the rest of the data
model:

```java
final CategoriesHomepage categories = resolver.getResource("/Categories").adaptTo(CategoriesHomepage.class);
for (final Category top : categories.getCategories()) {
    // getSubcategories() recurses, isLeaf() says whether this one is choosable
    final SchemaVersion schema = top.getSchemaVersion();
}
```

Over HTTP, `GET /Categories.deep.json` returns the whole tree in one response, children in their
stored order, with each bound schema version inlined by the default `dereference` serialization
processor — which is what the administration UI reads. `GET /Categories.paginate.json` lists the
categories through the pagination endpoint every entity homepage carries, with the same filtering
and sorting parameters; it is a flat listing of the whole subtree, so it answers "find the
categories matching X" rather than "render the tree".

## Self-documentation

The tree documents itself through the platform's [self-documentation
mechanism](autodoc.md): `GET /Categories.doc.md` renders a Markdown catalogue and
`GET /Categories.doc.json` the same as JSON.

The catalogue is deliberately **not** the tree. It is the flat list of the categories a submission
may currently be filed under — live leaves only — because that is exactly the set of valid
choices. A retired category is excluded together with its whole subtree, since nothing may be
filed under any of its descendants either, and inner categories contribute no headings of their
own. Each entry carries its label, description, `path` and `id`, the last two being how a consumer
names the category it picked: node names are only unique among siblings.

The primary consumer is AI-assisted categorization, which builds its prompt from the served
descriptions — which is why the catalogue's own heading and introduction are kept terse, and why
they live in the `title` and `description` properties of the [`iap:Documented`](autodoc.md) mixin
on `/Categories` rather than in code: a deployment can reword them, or say something specific to
its own taxonomy, without touching the module.

## Managing the tree

Administrators reshape the tree from the **category manager**, a view at `/admin/categories`
registered on the administration console (see [UI extensions](ui-extensions.md#the-admin-console)),
together with a console widget summarizing the current tree. The manager creates categories under
any parent, renames and re-describes them, binds or unbinds a schema version, moves a subtree to a
different parent, reorders siblings, retires and unretires, and deletes.

Retiring is the counterpart to deleting: a category that submissions already reference must not
disappear, but it can be closed to new ones. The UI offers retirement when a deletion is refused
for that reason.

Writes currently go directly to the repository through the standard Sling POST servlet, each
followed by a re-read of the tree. This predates the workflow engine and is expected to move
behind it — see below.

## Future work

- **Nothing references a category yet.** `sub:Submission` has no `category` property, so the
  relationship this module exists to serve is only half modeled. Adding it also has to settle the
  precedence rule between a category's `schemaVersion` and the submission's own mandatory one; the
  intended reading is that the category supplies the schema version when the submission is
  created, and the submission then freezes it, so re-binding a category never rewrites history.
- **Administrative writes belong in the workflow engine.** Every other data change in the platform
  is a workflow event, and creating a workflow definition already goes through a system workflow;
  the category manager's direct POSTs are an interim, and should become
  `createCategory`/`moveCategory`/`retireCategory`/… system workflows once the engine lands. That
  is also what would give deletion a real answer: a referenced category should be refused with the
  reasons listed, which the generic Sling delete cannot express.
- **`retired` does not yet inherit.** The property means "this category and everything under it",
  and the served catalogue honors that by pruning retired subtrees, but a leaf under a retired
  parent still reports `retired = false` on its own, and the administration UI shows it as active.
  Either an accessor that walks the ancestors, or — closer to how the platform already handles
  derived state — an `inheritable` [tag](tags.md) materialized onto the descendants, which would
  also make "everything currently closed" queryable.
- An `oak:index` for querying categories, deferred together with the other domain indexes.
