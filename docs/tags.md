# Tags

Any IAP resource can be marked with **tags**: short named markers like `incomplete`, `submitted`,
or `sensitive`, stored in the multivalued `tags` property that every `iap:Content` node may carry.
Unlike free-form labels, every usable tag must first be **defined**: an `iap:TagDefinition` node
under `/Tags` is the single source of truth for what the tag means, where it may be placed, and
how it behaves, instead of scattered code relying on an off-hand understanding of ad-hoc marker
strings.

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
| `category` | String[] | Grouping/filtering facets, e.g. `lifecycle`, `validation`, `privacy`. A category is a subject, not necessarily a lifecycle: some hold states a node has one of at a time, others hold tags that apply together, and the UI shows every tag a node carries in the category it was asked about |
| `inheritable` | Boolean | The tag flows *down*: resources under a tagged node implicitly carry it too (e.g. everything inside a `sensitive` submission is sensitive) |
| `aggregated` | Boolean | The tag bubbles *up*: a node implicitly carries it when any descendant explicitly does (e.g. a submission with an `incomplete` answer is incomplete), as far as the nearest `iap:TagBoundary` — see [How far a copy travels](#how-far-a-copy-travels) |
| `targetResourceTypes` | String[] | `sling:resourceType`s the tag may be placed on, subtypes included; empty means unrestricted |
| `color` | String | Optional CSS color the tag is displayed in; the chip's background, text and border are all derived from it, per the `variant` |
| `variant` | String | How the tag displays: `soft` (the default) tints the background with the color and clamps the color into readable text, `outlined` draws only that readable text and a matching border on a transparent background, `filled` uses the color as a loud fill under contrasting text |
| `icon` | String | Optional MUI icon name displayed next to the label, e.g. `EditOutlined`; names outside the UI's curated icon set (the tags module's `tagIcons.tsx`) display no icon |
| `order` | Long | Optional listing position, lower first, unordered last |
| `system` | Boolean | Managed by the platform: regular API calls cannot add or remove it |

## The Java API

The `iap-tags-api` bundle (`modules/tags/api`) exposes a models-only API:

- **`Taggable`** (`io.uhndata.iap.tags.models`, a Sling Model) — the tags-aware view of a piece of
  content, and the entry point for working with its tags: view any content model as it,
  `content.as(Taggable.class)`, then read, place, or remove tags. Everything works through the
  resolver the model was read with, and is subject to the caller's own permissions.
  - *Reading*: `getTags()` returns the explicit tags; `getEffectiveTags()` also resolves computed,
    inherited and aggregated tags, returning `Tag` values carrying the definition, the origins
    (`EXPLICIT` / `COMPUTED` / `INHERITED` / `AGGREGATED`), and the source paths; `hasTag` /
    `hasOwnTag` are cheaper single-tag checks. Aggregation visits the whole subtree, so avoid
    computing effective tags on huge trees. `getApplicableDefinitions()` lists what may be placed
    here.
  - *Writing*: `tag`, `untag`, `setTags` validate against the definitions (the tag must exist,
    apply to the content, and not be a `system` tag — variants with an `allowSystem` parameter
    are reserved for the platform code owning a system tag). Like other Sling persistence
    operations, changes are only saved when the caller commits the resource resolver. Undefined
    tag strings already present on a node (e.g. left behind by a deleted definition) are still
    reported by the read methods and may be removed, but never added.
- **`TagManager`** (`io.uhndata.iap.tags.api`, an OSGi service) — the vocabulary service:
  `getDefinitions()`, `getDefinition(name)`, `findDefinitions(category, query)`. These take no
  resolver: the definitions are platform vocabulary, world-readable by design, and the code most
  in need of them — commit hooks, scheduled jobs — often has no user session at all, so the
  manager reads them with its own service user and caches them until `/Tags` changes. Restricting
  an individual definition with an access control policy therefore has no effect.
- **`TagDefinition`** and **`TagsHomepage`** (`io.uhndata.iap.tags.models`, Sling Models) — typed
  read access to the definition nodes, including `appliesTo(content)` and the
  `TagDefinition.DISPLAY_ORDER` comparator.

## Marking content taggable

The **`iap:Taggable` mixin** (declared next to `iap:Content` in the content module) declares the
tagging properties once — the explicit `tags` plus the three materialized phase properties and the
`tagComputationState` marker. Every `iap:Content` node carries the mixin through its supertypes, so
all domain content is taggable out of the box, and other node types, e.g. `nt:file`, become
taggable with a plain `addMixin`. The mixin is a declaration aid, not a gate: the `Taggable`
*model* adapts any content, and writing simply fails on nodes whose types cannot store the
properties.

## Materialized propagation

Waiting until a tag is needed and then walking the tree to resolve inheritance and aggregation
would make queries (JQL `JOIN`s) and status displays expensive, so derived tags are **materialized
at commit time** instead, one property per processing phase:

- `inheritedTags` holds every `inheritable` tag belonging to an *ancestor*;
- `computedTags` holds the tags computed for the node from its *own content*;
- `aggregatedTags` holds every `aggregated` tag belonging to a *descendant*.

All three are multivalued String properties declared by the `iap:Taggable` mixin, **maintained by
the system — never write them manually**. "Belonging to" a node means both the tags a user placed explicitly and
the ones a processor computed for it: a computed tag propagates exactly like a hand-placed one.

Together with the explicit `tags` they answer both needs without tree walks: a status display reads
the four properties of the node itself (or calls `Taggable.getEffectiveTagNames()`), and a
query filters without joins:

```sql
SELECT * FROM [sub:Submission] AS s
 WHERE s.tags = 'incomplete' OR s.computedTags = 'incomplete'
    OR s.aggregatedTags = 'incomplete' OR s.inheritedTags = 'incomplete'
```

Properties belong to phases rather than to processors on purpose: a query, and the index behind it,
have to name the properties they filter on, which is only possible if that set is fixed and known in
advance rather than depending on which processors a deployment happens to register.

The machinery is an extensible SPI (`io.uhndata.iap.tags.spi`), not a hardcoded editor:

- **`TagProcessor`** — contributes tags for one node. Each processor declares the phase it runs in,
  how much of the repository it needs to see, and a priority ordering it within its phase; every
  processor of a phase contributes to that phase's single property, which stores the union of what
  they computed. The phases run in a fixed order on each node — `TOP_DOWN` (the ancestors' tags flow
  in), `LOCAL` (the node's own content is examined), then `BOTTOM_UP` (the descendants' tags flow
  up). A `LOCAL` processor may read what its node inherited but never what it aggregated: reading
  what its own results feed into is what would let a computation cycle form. The two built-in
  processors are `TagInheritanceProcessor` (down) and `TagAggregationProcessor` (up); a validation
  or AI-check module contributes its own `LOCAL` ones.
- **`TagProcessor.Scope`** — how much a processor may look at, which is the same thing as how much
  re-triggers it: `NODE` (the node and its parent, the cheap and usual case) or `ENTITY` (the whole
  enclosing `iap:Entity`, recomputed in full whenever anything inside it changes, for a tag that
  depends on more than one of an entity's parts). Anything wider cannot be a processor at all — a
  tag depending on a *different* entity belongs in a listener or a job placing explicit `system`
  tags after the fact, accepting the staleness that comes with it.
- **`TagContext`** — what a processor is handed for one node: the node, its parent, its path, the
  scope root if it asked for one, and the definitions. A processor may read state outside all of
  that only when that state is immutable for the lifetime of the node it tags — nothing else
  re-triggers the computation when such state changes. A published `sch:SchemaVersion` qualifies,
  which is what lets an answer be validated against its question.
- **`TagDefinitions`** — an Oak-level snapshot of the `/Tags` definitions handed to processors,
  so propagation works inside any commit, whatever session it comes from, with no resource
  resolver needed.
- **`TagPropagationEditor`(`Provider`)** — the Oak commit editor running the phases on every node
  whose tag surroundings, or own content, changed. All writes are compare-and-set, recomputation
  spreads only while stored values keep changing, and removals converge: copies always derive from
  the tags *belonging to* a node, chained in one direction, so deleting the source — a hand-placed
  tag, or the content a tag was computed from — provably clears every copy.

Propagation details worth knowing:

- Copies travel in one direction only: an aggregated copy on an ancestor is not re-inherited by
  the source's siblings, even for a tag that is both `aggregated` and `inheritable`.
- Derived properties are only written on nodes whose types *declare* them, which is what the
  `iap:Taggable` mixin does and what every `iap:Content` node inherits. A type that merely tolerates
  residual properties — `nt:unstructured`, and so every free-form container, the repository root
  included — has not opted in, and strict types that would reject them (file contents, access
  control entries, the system and index subtrees) never could. All of them act as propagation
  boundaries; a free-form node becomes taggable by adding the mixin.
- `targetResourceTypes` restricts where a tag may be *explicitly placed*; derived copies are exempt,
  and have to be — an aggregated copy exists to mark an *ancestor*, which is by construction not of
  the type the tag targets. What bounds a copy is the boundary below, not the target types.
- Changing a definition's `aggregated`/`inheritable` flags does not by itself recompute the copies
  already stored elsewhere, and neither does deleting a definition: no content changed, so nothing
  recomputes. Repair those with `POST /Tags.repair.json?tag=<name>` — see [Repair](#repair).

### How far a copy travels

Inheritance and aggregation are bounded differently, because they are asymmetric. An **inheritable**
tag flows out of a node somebody deliberately tagged into that node's own subtree, so it is bounded
by construction: whoever placed it chose the reach. An **aggregated** tag has no such author — every
ancestor of every tagged node is a candidate — so it needs a declared top, and that is the
`iap:TagBoundary` mixin. A boundary carries the aggregate of everything beneath it and contributes
nothing to its own ancestors. It stops what flows up and nothing else: an inheritable tag placed
above one still reaches the content inside it.

`iap:EntityHomepage` is a boundary, which puts the top where the value has a reader — a homepage is
what lists and filters its entities on `aggregatedTags`, and nothing above it lists anything.
Anything else that contains content and is not an entity homepage may declare itself one too.

**Entities that own a subtree are boundaries as well**, so the aggregate stops one level lower still:
`sub:Submission`, `sch:Schema`, `sch:SchemaVersion`, `wf:WorkflowDefinition` and `wf:WorkflowVersion`.
A submission therefore carries the aggregate of its answers, documents and reviews — which is exactly
what a listing filters a submission on — and `/Submissions` carries nothing. Two reasons, and the
first is the practical one: a homepage's `aggregatedTags` is rewritten by every commit anywhere
beneath it, so without a lower boundary one shared node becomes a write hotspot for every change to
any of its entities. And nothing reads that union — a listing filters each row on that row's own
derived tags, never on the sum across rows.

The cost is worth stating, because it is a choice rather than an oversight: a definition sees nothing
aggregated from its versions, since a version is a boundary too. If a definition ever needs to
summarise its versions, that is a query over them, not an aggregated tag.

Without a top, an aggregated tag climbs to the repository root, and both consequences are bad. The
value stops answering any question: the root's aggregate is the union of every aggregated tag in the
repository. And, less obviously, **it breaks writes**. Every property the propagation editor sets is
attributed to the session that committed, and Oak evaluates permissions *after* the editors run, so
a copy landing on an ancestor the committer may not write fails their commit outright — with a bare
`OakAccess0000: Access denied`, naming no path. That is worth knowing when granting rights: a
deployment shipping an `aggregated` definition must let everyone who can write the content it
aggregates from also write the boundary node, e.g.

```
set ACL for iap-error-tracking
    allow   jcr:read,jcr:addChildNodes,jcr:modifyProperties,jcr:nodeTypeManagement    on /LoggedErrors
end
```

where `/LoggedErrors` is both the content's container and its boundary, so one grant covers both.

### When a computation fails

A failing processor **never fails the commit**. Losing data a user just entered because a tag could
not be computed would be a far worse outcome than carrying a stale tag — the tags can always be
computed again, the typing cannot — and the editor runs on *every* commit, including those that have
nothing to do with tags, so a processor throwing would block unrelated writes too.

Instead, the phase whose processor threw keeps the values it last computed successfully: storing a
union that is knowingly missing one of its contributors would replace good values with worse ones,
and a tag that lingers after it stopped applying is safer than one that silently disappears while it
still does. The node is marked `tagComputationState = failed` so the affected nodes can be found and
recomputed, and so that code which must not act on stale tags — an access check, say — can tell.
The failure itself is logged; once the error-tracking module is in place it will also be reported
there, which is how a system administrator learns about it.

### Repair

The marker is also how a node is *put right*. Any node carrying `tagComputationState` is recomputed
in full — every phase, over its whole subtree — by the next commit that reaches it, and the property
is removed once the values can be trusted again. It has two values, and the difference is only who
asked:

| value | written by | means |
|--------------|------------|-----------------------------------------------------------|
| `failed` | the editor | a processor threw; the stored tags are the last good ones |
| `recomputing` | a repair | the stored tags were declared untrustworthy |

Nothing branches on the value — the recomputation is the same either way — so it exists to tell an
operator *why* a node is marked. **`recomputing` should never be observed at rest**: it is written
and consumed within a single commit, so a node found holding it is one whose commit was interrupted
in between. The sweep looks for the property's presence rather than for `failed`, so those get picked
up too. Repair therefore computes nothing itself: it marks the
affected nodes and lets the propagation editor, the one piece that knows the phase order and the
scopes, do the work.

Two things trigger it.

- **A sweep runs on a schedule** (`Tag Repair` configuration, hourly by default) and repairs
  everything flagged. It is driven by an index and writes nothing when there is nothing wrong, so a
  healthy repository pays almost nothing for it. Turn it off with `enabled=false`.
- **`POST /Tags.repair.json?tag=<name>`** repairs the content affected by an edited definition. This
  one is deliberate rather than automatic: how much work it is depends on how widely the tag is
  used, and starting a repository-wide recomputation as a side effect of somebody saving a
  definition is a surprise. Whoever may edit the definitions may run it — the permission is read off
  `/Tags` through the caller's own session. The response reports how many nodes were marked and how
  many could not be, since a repair reports what it could not do rather than abandoning the rest of
  the repository over one unwritable node.

Marking is a property write and nothing more, done by a service user (`iap-tag-repair`) that may
read anywhere and modify properties, but never create or remove a node.

## The REST endpoint

`GET /Tags.search.json` lists the defined tags as JSON, with optional filters that can be
combined:

- `category=<name>` — only tags listing this category (ignoring case);
- `query=<text>` — only tags containing the text (ignoring case) in their name, label, or
  description;
- `target=<path>` — only tags that may be placed on the resource at that path.

The response is `{"tags": [...], "total": <n>}`, each entry serializing the full definition
(name, label, description, category, inheritable, aggregated, targetResourceTypes, color, variant,
icon, order, system, path). The plain `/Tags.json` (and deeper `.2.json` etc.) default renderings remain
available for raw access.

## Self-documentation

The tag vocabulary documents itself through the platform's
[self-documentation mechanism](autodoc.md): `GET /Tags.doc.md` renders a human-readable
Markdown catalogue — one section per category, one subsection per tag with its description and
the behaviors that apply to it (inheritable, aggregated, system, allowed targets) — and
`GET /Tags.doc.json` returns the same catalogue as JSON. Tags belonging to several categories are
listed under each of them; tags without a category appear under `uncategorized`. The catalogue's
heading can be reworded by setting the `title` and `description` properties on the `/Tags` node.

## Future work

- Tag-based access restrictions: an ACL restriction pattern evaluated against a resource's tags,
  e.g. granting write access only while a record is not `submitted`.
- UI for displaying and filtering by tags, driven by the definitions.
