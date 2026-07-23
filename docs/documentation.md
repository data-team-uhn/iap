# Self-documentation

The `iap-documentation` module (`modules/documentation`) lets configurable and extensible parts of
the platform document themselves at runtime: what tags are defined, what node types the workflow
engine understands, what metrics are tracked — always current, straight from the running system,
instead of hand-maintained pages that drift out of date.

## Reading the documentation

Appending the `doc` selector to the path of a documented node returns its catalogue:

- `GET <path>.doc.md` — a human-readable Markdown document;
- `GET <path>.doc.json` — the same data as JSON, for tooling and UIs.

Both renderings show a title, an introduction, and the documented items grouped into one section
per category (an item belonging to several categories is repeated under each; items without a
category are grouped under `uncategorized`, and if nothing declares a category the section
headings are left out entirely). For example, the [tag vocabulary](tags.md) is served at
`/Tags.doc.md`.

## Making a feature self-documenting

Three pieces, all provided by this module:

1. **Implement the API** (`io.uhndata.iap.documentation.api`): a Sling Model for the node serving
   the documentation implements `SelfDocumenting` (a title, an intro, and the list of items), and
   the items implement `DocumentedItem` — a minimal contract of `name`, `label`, `description`
   and `category` accessors that `Content`-based models mostly already satisfy. Both interfaces
   carry default Markdown and JSON serializations built from that contract, so a basic catalogue
   needs no serialization code at all; an item with more to say overrides
   `getDocumentationDetails()` (extra bullet points in the Markdown rendering) and
   `documentationJsonBuilder()` (extra JSON fields appended to
   `DocumentedItem.super.documentationJsonBuilder()`). The categories are plain strings computed
   by the model — often a `category` property, but they can just as well be derived, e.g. a
   workflow node type reporting its kind.

2. **Register the model as an adapter**: the `@Model` annotation must list the interface, e.g.
   `adapters = { TagsHomepage.class, SelfDocumenting.class }`, with the `resourceType` set, so
   that the serving node's resource adapts to `SelfDocumenting`.

3. **Mark the node with the `iap:Documented` mixin**, either by adding it to individual nodes or
   by declaring it as a supertype of the node's primary type
   (`[iap:TagsHomepage] > iap:Content, ..., iap:Documented`). The mixin is what makes the
   documentation endpoint respond, and it also offers optional `title` and `description`
   properties, so a deployment can reword a catalogue's heading without touching code.

The endpoint itself is generic and serves any marked node; a feature needing a completely
different rendering can register its own servlet for the `doc` selector on its resource type,
which takes precedence over the generic one.
