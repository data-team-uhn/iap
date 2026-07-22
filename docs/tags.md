# Tags

Any IAP resource can be marked with **tags**: short named markers like `incomplete`, `submitted`,
or `sensitive`, stored in the multivalued `tags` property that every `iap:Content` node may carry.
Unlike free-form labels, every usable tag must first be **defined**: an `iap:TagDefinition` node
under `/Tags` is the single source of truth for what the tag means, where it may be placed, and
how it behaves, instead of scattered code relying on an off-hand understanding of ad-hoc marker
strings.

This is the successor of CARDS's `statusFlags` mechanism, generalized: the property applies to
every content node, and the flag vocabulary is formalized as first-class definition nodes rather
than a convention spread across modules.

## Defining a tag

A tag definition is an `iap:TagDefinition` child node of the `/Tags` homepage (an
`iap:TagsHomepage` node created by repoinit, world-readable). Modules contribute definitions
through their initial content, e.g. the test-data module ships a demo set in
`test-data/src/main/resources/SLING-INF/content/Tags/`.

| Property | Type | Meaning |
| --- | --- | --- |
| `name` | String | The exact string stored in `tags` properties; defaults to the definition node's name, set it explicitly only for tag strings that would be awkward as node names |
| `label` | String | Display name, defaults to the tag name |
| `description` | String | What the tag means and when it applies |
| `category` | String[] | Grouping/filtering facets, e.g. `lifecycle`, `validation`, `privacy` |
| `inheritable` | Boolean | The tag flows *down*: resources under a tagged node implicitly carry it too (e.g. everything inside a `sensitive` submission is sensitive) |
| `aggregated` | Boolean | The tag bubbles *up*: a node implicitly carries it when any descendant explicitly does (e.g. a submission with an `incomplete` answer is incomplete) |
| `targetResources` | String[] | `sling:resourceType`s the tag may be placed on, subtypes included; empty means unrestricted |
| `color` | String | Optional CSS color for displaying the tag |
| `order` | Long | Optional listing position, lower first, unordered last |
| `system` | Boolean | Managed by the platform: regular API calls cannot add or remove it |

## The Java API

The `iap-tags-api` bundle (`modules/tags/api`) exposes:

- **`TagManager`** (`io.uhndata.iap.tags.api`, an OSGi service) — the one entry point for working
  with tags:
  - *Definitions*: `getDefinitions`, `getDefinition`, `findDefinitions(resolver, category, query)`,
    `getApplicableDefinitions(resource)`.
  - *Reading*: `getTags(resource)` returns the explicit tags; `getEffectiveTags(resource)` also
    resolves inherited and aggregated tags, returning `Tag` values carrying the definition, the
    origins (`EXPLICIT` / `INHERITED` / `AGGREGATED`), and the source paths; `hasTag` /
    `hasOwnTag` are cheaper single-tag checks. Aggregation visits the whole subtree, so avoid
    computing effective tags on huge trees.
  - *Writing*: `tag`, `untag`, `setTags` validate against the definitions (the tag must exist,
    apply to the resource, and not be a `system` tag — variants with an `allowSystem` parameter
    are reserved for the platform code owning a system tag). Like other Sling persistence
    operations, changes are only saved when the caller commits the resource resolver. Undefined
    tag strings already present on a node (e.g. left behind by a deleted definition) are still
    reported by the read methods and may be removed, but never added.
- **`TagDefinition`** and **`TagsHomepage`** (`io.uhndata.iap.tags.models`, Sling Models) — typed
  read access to the definition nodes, including `appliesTo(resource)` and the
  `TagDefinition.DISPLAY_ORDER` comparator.

## Materialized propagation

Waiting until a tag is needed and then walking the tree to resolve inheritance and aggregation
would make queries (JQL `JOIN`s) and status displays expensive, so derived tags are **copied up
and down the tree at commit time** instead, following CARDS's practice of materializing
`statusFlags` for query performance:

- `aggregatedTags` holds every `aggregated` tag explicitly placed on any *descendant*;
- `inheritedTags` holds every `inheritable` tag explicitly placed on any *ancestor*.

Both are multivalued String properties declared on `iap:Content`, **maintained by the system —
never write them manually**. Together with the explicit `tags` they answer both needs without
tree walks: a status display reads the three properties of the node itself (or calls
`TagManager.getEffectiveTagNames(resource)`), and a query filters without joins:

```sql
SELECT * FROM [sub:Submission] AS s
 WHERE s.tags = 'incomplete' OR s.aggregatedTags = 'incomplete' OR s.inheritedTags = 'incomplete'
```

The machinery is an extensible SPI (`io.uhndata.iap.tags.spi`), not a hardcoded editor:

- **`TagProcessor`** — computes the derived tags of one node as a pure function of the node, its
  parent, and the tag definitions. Each processor declares the property it owns, the traversal
  phase it runs in (`TOP_DOWN`, seeing the ancestors' freshly recomputed state, or `BOTTOM_UP`,
  seeing the descendants'), and a priority ordering it within its phase. The two built-in
  processors are `TagAggregationProcessor` (up) and `TagInheritanceProcessor` (down); new
  behaviors (e.g. validation-computed tags) plug in as new services.
- **`TagDefinitions`** — an Oak-level snapshot of the `/Tags` definitions handed to processors,
  so propagation works inside any commit, whatever session it comes from, with no resource
  resolver needed.
- **`TagPropagationEditor`(`Provider`)** — the Oak commit editor invoking the processors, one
  after another, on every node whose tag surroundings changed. All writes are compare-and-set,
  recomputation spreads only while stored values keep changing, and removals converge: copies
  always derive from *explicit* placements chained in one direction, so deleting the source
  provably clears every copy.

Propagation details worth knowing:

- Copies travel in one direction only: an aggregated copy on an ancestor is not re-inherited by
  the source's siblings, even for a tag that is both `aggregated` and `inheritable`.
- Derived properties are only written on nodes carrying a `sling:resourceType` (every
  `iap:Content` node does). Strict node types that would reject extra properties — file contents,
  access control entries, the system and index subtrees — are never touched and act as
  propagation boundaries.
- `targetResources` restricts where a tag may be *explicitly placed*; derived copies are exempt.
- Changing a definition's `aggregated`/`inheritable` flags only affects subtrees touched by
  later commits; existing copies are not retroactively recomputed.

## The REST endpoint

`GET /Tags.search.json` lists the defined tags as JSON, with optional filters that can be
combined:

- `category=<name>` — only tags listing this category (ignoring case);
- `query=<text>` — only tags containing the text (ignoring case) in their name, label, or
  description;
- `target=<path>` — only tags that may be placed on the resource at that path.

The response is `{"tags": [...], "total": <n>}`, each entry serializing the full definition
(name, label, description, category, inheritable, aggregated, targetResources, color, order,
system, path). The plain `/Tags.json` (and deeper `.2.json` etc.) default renderings remain
available for raw access.

## Future work

- An `oak:index` on `tags`/`aggregatedTags`/`inheritedTags` once querying by tag is needed
  (deferred together with the other domain indexes).
- Tag-based access restrictions (the CARDS `UnsubmittedFormsRestrictionPattern` equivalent).
- UI for displaying and filtering by tags, driven by the definitions.
