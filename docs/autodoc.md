# Autodoc

**Module:** `modules/autodoc` · **Bundle:** `iap-autodoc` · **API:**
`io.uhndata.iap.autodoc.api`

Autodoc lets a feature document itself at runtime: which tags are defined, which node
types the workflow engine understands. The catalogue is built from the content the
running instance holds.

Two interfaces and one mixin. `AutoDocumentable` goes on the model of the node that
serves the catalogue; `DocumentedItem` goes on the models of the entries in it. Both
carry default Markdown and JSON serializations, so a basic catalogue needs no
serialization code.

## The endpoint

`AutodocServlet` is a `JakartaOptingServlet` registered on `sling/servlet/default`
for the `doc` selector and the `json` and `md` extensions. Its `accepts()` calls
`Node.isNodeType("doc:Documented")`, which is true both when the mixin is set
directly on the node and when it is inherited from a supertype of the primary type.

| Request               | Response                                            |
|-----------------------|-----------------------------------------------------|
| `GET <path>.doc.md`   | `text/markdown`, from `toMarkdown()`                |
| `GET <path>.doc.json` | `application/json`, from `toDocumentationJson()`    |

A feature that needs a different rendering registers its own servlet for the `doc`
selector on its resource type, and that more specific registration wins.

Live example: `/Tags.doc.md` and `/Tags.doc.json`.

## Implementing it

### `AutoDocumentable`: the catalogue

Three abstract methods; everything else is defaulted.

```java
@NotNull  String getDocumentationTitle();      // e.g. "Tags"
@Nullable String getDocumentationIntro();      // null if the title says it all
@NotNull  List<? extends DocumentedItem> getDocumentedItems();   // in display order
```

The `TagsHomepage` model, without the accessor it reuses:

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

`AutoDocumentable` **must** appear in `adapters`. The servlet adapts the resource to
it, and without that adapter it answers 404 with a plain-text message.

The title and introduction come from properties. The `doc:Documented` mixin declares
`title` and `description`, and the primary type gives them autocreated defaults,
which a deployment can edit:

```
[tag:Homepage] > data:Content, rep:AccessControllable, doc:Documented
  - title (STRING) = "Tags" autocreated
  - description (STRING) = "All the tags defined in this instance, grouped by category." autocreated
```

### `DocumentedItem`: the entries

```java
@NotNull  String getName();                            // the exact referencing string
@Nullable String getDescription();
@NotNull  String getDocumentationLabel();              // defaults to getName()
@NotNull  List<String> getDocumentationCategories();   // defaults to List.of()
@NotNull  List<String> getDocumentationDetails();      // defaults to List.of()
@NotNull  JsonObjectBuilder documentationJsonBuilder();
```

Only `getName()` and `getDescription()` are abstract. A `Content`-based model has
`getName()` already, so it adds a description and nothing else.

Item models do **not** need `DocumentedItem` in their `@Model` adapters: the servlet
adapts only the catalogue's node, and the items come from `getDocumentedItems()`.

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

Categories are a `List<String>` the model computes, not necessarily a stored
property. `TagDefinition` returns its categories; `FlowNodeType` falls back to a
default group when the property is absent:

```java
@Override
public List<String> getDocumentationCategories()
{
    return this.category == null || this.category.length == 0
        ? List.of(getDefaultCategory())
        : List.of(this.category);
}
```

`getDocumentedItemsByCategory()` groups the items:

- Categories are sorted by name, but `uncategorized` always sorts **last**,
  regardless of alphabetical position.
- Items keep their display order within a category.
- An item in several categories is **repeated** under each.
- An empty category list puts the item under `uncategorized`
  (`AutoDocumentable.DEFAULT_CATEGORY`).
- If the only category present is `uncategorized`, `toMarkdown()` leaves out the `##`
  headings.

## Output shapes

Markdown is `#` title, intro, `##` per category, then per item:

```markdown
### Inheritable label (`name`)

The description, with raw newlines converted to Markdown hard breaks.

- **Inheritable**: implicitly carried by everything inside tagged content
- **May only be placed on**: `sub/Submission`
```

In JSON, `items` maps each category to an array of items:

```json
{
  "title": "Tags",
  "description": "All the tags defined in this instance, grouped by category.",
  "items": {
    "completeness": [
      { "name": "incomplete", "label": "Incomplete", "description": "…",
        "category": ["completeness"], "inheritable": false, "aggregated": true,
        "system": true, "color": "#8a5a1f", "icon": "RuleOutlined", "order": 10,
        "path": "/Tags/incomplete" }
    ]
  }
}
```

## Extending an item's output

Two hooks, both per item.

`getDocumentationDetails()` returns Markdown fragments, shown as bullets after the
description:

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

`documentationJsonBuilder()` adds fields to the base ones. Start from
`DocumentedItem.super.documentationJsonBuilder()`, or the item loses its `name`,
`label`, `description` and `category`:

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

## If it does not work

| Symptom | Cause |
|---------|-------|
| `.doc.md` falls through to default handling | Mixin missing, so `accepts()` returned false |
| 404, `No documentation available for <path>` | Mixin present, `adaptTo(AutoDocumentable.class)` returned null: interface missing from `adapters`, or `resourceType` wrong |
| Title or intro empty | Backing properties unset; check the autocreated defaults on the primary type |
| Everything under `uncategorized` | `getDocumentationCategories()` returning an empty list |
| No `##` headings | Only `uncategorized` present: the flat rendering |
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
