# Categories

Submitters shouldn't have to know which schema applies to their work. Instead they pick from a
tree of **categories** describing kinds of submissions in their own language — "Retrospective Data
Studies", "Clinical trials involving experimental products" — and the category they land on
carries the schema version that governs what they will be asked for, and through it the workflow
their submission follows.

The tree lives under **`/Categories`** and is maintained by administrators, not by code: the
`categories` module (`modules/categories`) contributes the node types and the administration UI for
reshaping it, and nothing else — the taxonomy itself is a deployment's own.

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

The node name is the technical identifier, and it **must not be a number**. Node names are read
back from the JSON serialization of the tree, where a category appears as a key of its parent
object, and JavaScript enumerates integer-like keys of an object first, in numeric order, whatever
order they were written in — so a category named `2024` would come back out of the order it is
stored in, and reordering its siblings would compute against the wrong neighbour. The category
manager derives names from labels and never produces one, but hand-authored taxonomy content can:
name that category `studies2024` and put the year in its `label`.

A category with no subcategories is a **leaf**, and leaves are what submissions are actually filed
under — an inner node is a grouping, not a choice. Only leaves are therefore expected to carry a
`schemaVersion`; node types cannot express "this property only applies when there are no children
of this type", so that rule is upheld by the administration UI rather than by the repository. A
leaf without one falls back to the default behavior for submissions. Note that a leaf category may
hold other types of nodes.

Splitting `cat:Category` into a group type and a leaf type would look like a way to have the
repository enforce that, and it is not: `cat:Category` declares the residual `- * (UNDEFINED)` and
`+ * (nt:base)` definitions that every IAP type carries for extensibility, and a residual property
definition admits `schemaVersion` on a group type that never declares it, just as a residual child
definition admits a category under a leaf type. The split would only bite if categories gave up
that extensibility — and it would turn giving a leaf its first subcategory into a
`jcr:primaryType` migration. One type, with the invariant upheld where the editing happens, is the
deliberate choice.

### Retirement

Retiring a category closes it to new submissions while existing ones keep referencing it. It is
the `retired` [tag](tags.md), defined by this module under `/Tags` and marked `inheritable`, rather
than a property of the node.

That is what makes "this category **and its subcategories**" true rather than merely intended: an
inheritable tag is materialized by the repository onto every node below the one it was placed on,
at commit time, into a separate `inheritedTags` property. A category can therefore tell whether it
was retired in its own right or is covered by an ancestor — `Category.isRetiredHere()` against
`Category.isRetired()` — which is what decides where the retirement can be lifted again. It also
makes "everything currently closed" a query rather than a tree walk.

`cat:Category` extends `data:Entity`: categories are top-level data, comparable to schemas and
workflows, rather than platform vocabulary like tag or link definitions. So each one is
referenceable (which is how a submission will point at it), versionable, tracks its last
modification, can be [tagged](tags.md) and [linked](links.md), and accepts any further properties
and children a deployment adds. `cat:CategoriesHomepage` extends `data:EntityHomepage` and names
`cat:Category` as its `childNodeType`.

The module creates `/Categories` empty and ships no categories of its own: a taxonomy is a
deployment's own content, and nothing in the platform depends on any particular category. A sample
taxonomy of study types is loaded by the `test-data` module, for development and testing. The whole
tree is readable by every signed-in user, because submitters have to browse it to choose — the
module's repoinit grants `everyone` `jcr:read` on it. Not anonymously, though: `sling.auth.requirements`
requires authentication everywhere outside `/libs`, `/apps` and the login page, whatever a node's own
ACLs say.

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
processor. The administration UI asks for `GET /Categories.deep.simple.json` instead: `simple`
summarizes each inlined schema version down to what identifies it, leaving out the requirements
hanging under it and the workflow it references — otherwise naming a bound schema on a chip
downloads that schema's whole requirement subtree and a BPMN document with it.

`GET /Categories.paginate.json` lists the categories through the pagination endpoint every entity
homepage carries, with the same filtering and sorting parameters; it is a flat listing of the whole
subtree, so it answers "find the categories matching X" rather than "render the tree".

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
descriptions. The catalogue's own heading and introduction come from the `title` and `description`
properties of the [`doc:Documented`](autodoc.md) mixin on `/Categories`, so a deployment can reword
them without touching code.

## Managing the tree

Administrators reshape the tree from the **category manager**, a view at `/admin/categories`
registered on the [administration console](administration.md), together with a console widget
summarizing the current tree. The manager creates categories under any parent, renames and
re-describes them, binds or unbinds a schema version, moves a subtree to a different parent,
reorders siblings, retires and unretires, and deletes.

Retiring is the counterpart to deleting: a category that submissions already reference must not
disappear, but it can be closed to new ones. Only the category actually carrying the retirement
offers to have it lifted; one retired by an ancestor says so instead, since there is nothing on it
to take off.

Deleting goes through the platform's [deletion endpoint](deletion.md) — `DELETE` on the category
itself — rather than a repository write, because that endpoint knows what refers to the category
and can say so. A category something still points at is refused with the reasons listed, and the
dialog turns that refusal into the offer to retire instead. What is deleted is moved to the
archive rather than destroyed.

Every other write goes directly to the repository through the standard Sling POST servlet, each
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
  `createCategory`/`moveCategory`/`retireCategory`/… system workflows once the engine lands.
- **Retiring writes the tag directly.** The manager patches the `tags` property over the Sling POST
  servlet, which skips the validation `Taggable.tag()` performs — that the tag is defined, applies
  to this resource type, and is not a system tag. There is no HTTP endpoint for tagging anywhere in
  the platform yet; the first one should be generic, and this manager should be its caller.
- An `oak:index` for querying categories, deferred together with the other domain indexes.
