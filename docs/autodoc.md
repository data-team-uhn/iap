# Autodoc

**Module:** `modules/autodoc` · **Bundle:** `iap-autodoc` · **API:**
`io.uhndata.iap.autodoc.api`

Autodoc lets a configurable or extensible feature document itself at runtime: what
tags are defined, what node types the workflow engine understands, what metrics are
tracked. The catalogue is generated from the content the running instance actually
has, so it cannot drift the way a hand-maintained page does.

Two interfaces and one mixin. `AutoDocumentable` goes on the model of the node that
serves the catalogue; `DocumentedItem` goes on the models of the entries in it. Both
carry default Markdown and JSON serializations, so a basic catalogue needs no
serialization code at all.

## The endpoint

`AutodocServlet` is a `JakartaOptingServlet` registered on `sling/servlet/default`
for the `doc` selector and the `json` and `md` extensions. Its `accepts()` calls
`Node.isNodeType("doc:Documented")`, which is true both when the mixin is set
directly on the node and when it is inherited from a supertype of the primary type.

| Request               | Response                                            |
|-----------------------|-----------------------------------------------------|
| `GET <path>.doc.md`   | `text/markdown` — `toMarkdown()`                    |
| `GET <path>.doc.json` | `application/json` — `toDocumentationJson()`        |

Because it opts in rather than binding to a resource type, a feature that needs an
entirely different rendering can register its own servlet for the `doc` selector on
its own resource type; the more specific registration wins.

Live example: `/Tags.doc.md` and `/Tags.doc.json`.

## Implementing it

### `AutoDocumentable` — the catalogue

Three abstract methods; everything else is defaulted.

```java
@NotNull  String getDocumentationTitle();      // e.g. "Tags"
@Nullable String getDocumentationIntro();      // null if the title says it all
@NotNull  List<? extends DocumentedItem> getDocumentedItems();   // in display order
```

The real `TagsHomepage`, in full:

```java
@Model(adaptables = Resource.class, adapters = { TagsHomepage.class, AutoDocumentable.class },
    resourceType = TagsHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class TagsHomepage extends Content implements AutoDocumentable
{
    public static final String RESOURCE_TYPE = "tag/Homepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    @Override
    public String getDocumentationTitle() { return this.title; }

    @Override
    public String getDocumentationIntro() { return this.description; }

    @Override
    public List<TagDefinition> getDocumentedItems() { return getDefinitions(); }
}
```

`AutoDocumentable` **must** appear in `adapters`. The servlet does
`resource.adaptTo(AutoDocumentable.class)`, and if the model is not registered as an
adapter for that interface it gets null and returns 404 with a plain-text
explanation, even though the mixin is present.

Note that the title and intro come from properties rather than string literals. The
`doc:Documented` mixin declares empty `title` and `description`, and the primary
type overrides them with autocreated defaults, which keeps the heading editable by a
deployment:

```
[tag:Homepage] > data:Content, rep:AccessControllable, doc:Documented
  - title (STRING) = "Tags" autocreated
  - description (STRING) = "All the tags defined in this instance, grouped by category." autocreated
```

### `DocumentedItem` — the entries

```java
@NotNull  String getName();                            // the exact referencing string
@Nullable String getDescription();
@NotNull  String getDocumentationLabel();              // defaults to getName()
@NotNull  List<String> getDocumentationCategories();   // defaults to List.of()
@NotNull  List<String> getDocumentationDetails();      // defaults to List.of()
@NotNull  JsonObjectBuilder documentationJsonBuilder();
```

Only `getName()` and `getDescription()` are abstract, and a `Content`-based model has
both already — so the cheap case really is just adding `implements DocumentedItem`.

Item models do **not** need `DocumentedItem` in their `@Model` adapters.
`TagDefinition` does not have it. The servlet only ever adapts the container; the
items reach it through the container's own typed accessor.

### The mixin

Add `doc:Documented` to individual nodes, or declare it as a supertype of the
primary type as `tag:Homepage` does above. Without it, `accepts()` returns false and
the request falls through to default Sling handling.

### Verify

```
curl -u admin:admin http://localhost:8080/Tags.doc.md
curl -u admin:admin http://localhost:8080/Tags.doc.json
```

## Categories and grouping

Categories are a `List<String>` computed by the model, not necessarily a stored
property. `TagDefinition` returns its `category` property; `FlowNodeType` falls back
to a default group when the property is absent:

```java
@Override
public List<String> getDocumentationCategories()
{
    return this.category == null || this.category.length == 0
        ? List.of(getDefaultCategory())
        : List.of(this.category);
}
```

`getDocumentedItemsByCategory()` does the grouping, and its behaviour is worth
knowing before you design your category names:

- Categories are sorted by name, but `uncategorized` always sorts **last**,
  regardless of alphabetical position.
- Items keep their display order within a category.
- An item in several categories is **repeated** under each.
- An empty category list puts the item under `uncategorized`
  (`AutoDocumentable.UNCATEGORIZED`).
- If the only category present is `uncategorized`, `toMarkdown()` goes flat and omits
  the `##` headings entirely.

`List.of(this.category)` rather than `Arrays.asList` is deliberate: the latter is a
live view of the model's own array, writable through by any caller.

## Output shapes

Markdown is `#` title, intro, `##` per category, then per item:

```markdown
### Inheritable label (`name`)

The description, with raw newlines converted to Markdown hard breaks.

- **Inheritable**: implicitly carried by everything inside tagged content
- **May only be placed on**: `sub/Submission`
```

JSON keys off the category, so `items` is an object of arrays, not a flat array:

```json
{
  "title": "Tags",
  "description": "All the tags defined in this instance, grouped by category.",
  "items": {
    "lifecycle": [
      { "name": "incomplete", "label": "Incomplete", "description": "…",
        "category": ["lifecycle"], "inheritable": false, "path": "/Tags/incomplete" }
    ],
    "uncategorized": []
  }
}
```

## Extending an item's output

Two hooks, both per item.

`getDocumentationDetails()` returns Markdown fragments rendered as bullets after the
description. This is where behaviour that is a boolean in the model becomes a
sentence in the docs:

```java
@Override
public List<String> getDocumentationDetails()
{
    final List<String> details = new ArrayList<>();
    if (isInheritable()) {
        details.add("**Inheritable**: implicitly carried by everything inside tagged content");
    }
    if (!getTargetResourceTypes().isEmpty()) {
        details.add("**May only be placed on**: `" + String.join("`, `", getTargetResourceTypes()) + "`");
    }
    return details;
}
```

`documentationJsonBuilder()` appends to the base contract. Always start from
`DocumentedItem.super.documentationJsonBuilder()` — skipping it costs you `name`,
`label`, `description`, and `category` on every item:

```java
@Override
public JsonObjectBuilder documentationJsonBuilder()
{
    final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
        .add("inheritable", isInheritable());
    // Optional fields are left out entirely rather than serialized as null
    Optional.ofNullable(getColor()).ifPresent(value -> json.add("color", value));
    return json.add("path", getPath());
}
```

The two hooks are also the reason to think twice before registering a custom
servlet: a custom rendering means owning both serializations and keeping them
consistent with every other catalogue in the platform.

## If it does not work

| Symptom | Cause |
|---------|-------|
| `.doc.md` falls through to default handling | Mixin missing — `accepts()` returned false |
| 404, `No documentation available for <path>` | Mixin present, `adaptTo(AutoDocumentable.class)` returned null: interface missing from `adapters`, or `resourceType` wrong |
| Title or intro empty | Backing properties unset; check the autocreated defaults on the primary type |
| Everything under `uncategorized` | `getDocumentationCategories()` returning an empty list |
| No `##` headings | Only `uncategorized` present — that is the documented flat rendering |
| Custom JSON fields present, base ones gone | Missing `DocumentedItem.super.documentationJsonBuilder()` |

## Files

```
modules/autodoc/
├── src/main/java/io/uhndata/iap/autodoc/
│   ├── api/AutoDocumentable.java      catalogue contract + MD/JSON defaults
│   ├── api/DocumentedItem.java        item contract + MD/JSON defaults
│   └── internal/AutodocServlet.java   the doc selector, opting on the mixin
├── src/main/resources/SLING-INF/nodetypes/autodoc.cnd    doc:Documented
└── src/main/features/feature.json     start-order 22
```

Reference implementations: `modules/tags/api` (`TagsHomepage`, `TagDefinition`),
`modules/workflows` (`WorkflowTypesHomepage`, `FlowNodeType`), `modules/categories`,
`modules/links/api`.
