# Tags

**Module:** `modules/tags` · **Bundle:** `iap-tags-api` · **Packages:**
`…tags.api` (`TagManager`, `Tag`, `TagRepairService`), `…tags.models`,
`…tags.spi`

Short named markers — `incomplete`, `submitted`, `sensitive` — in the multivalued
`tags` property every `data:Content` node may carry. Unlike free-form labels, every
usable tag must be **defined**: a `tag:Definition` node under `/Tags` is the single
source of truth for what it means, where it may go, and how it behaves.

## Defining a tag

`tag:Definition` children of the `/Tags` homepage (`tag:Homepage`, created by
repoinit, world-readable). Modules contribute definitions through initial content;
`test-data` ships a demo set.

| Property | Type | Meaning |
|---|---|---|
| `name` | String | The exact string stored in `tags`; defaults to the node name. Set explicitly only for strings awkward as node names |
| `label` | String | Display name, defaults to the name |
| `description` | String | What it means and when it applies |
| `category` | String[] | Grouping facets. A category is a *subject*, not necessarily a lifecycle: some hold mutually exclusive states, others hold tags that apply together |
| `inheritable` | Boolean | Flows **down**: content under a tagged node implicitly carries it |
| `aggregated` | Boolean | Bubbles **up**: a node carries it when any descendant explicitly does, as far as the nearest `tag:Boundary` |
| `targetResourceTypes` | String[] | Resource types it may be placed on, subtypes included; empty means unrestricted |
| `color` | String | CSS color; background, text and border are all derived from it per the variant |
| `variant` | String | `soft` (default) tints the background, `outlined` draws text and border only, `filled` uses the color as a loud fill |
| `icon` | String | MUI icon name, e.g. `EditOutlined`. Names outside the curated set (`tagIcons.tsx`) display nothing |
| `order` | Long | Listing position, lower first, unordered last |
| `system` | Boolean | Platform-managed: regular API calls cannot add or remove it |

## Java API

```java
final Taggable taggable = submission.as(Taggable.class);

Set<String>      explicit  = taggable.getTags();
Collection<Tag>  effective = taggable.getEffectiveTags();   // definition + origins + sources
Set<String>      names     = taggable.getEffectiveTagNames();
boolean          any       = taggable.hasTag("sensitive");  // cheap single check
boolean          own       = taggable.hasOwnTag("sensitive");
List<TagDefinition> here   = taggable.getApplicableDefinitions();

taggable.tag("sensitive");                  // + tag(name, allowSystem)
taggable.untag("sensitive");                // + untag(name, allowSystem)
taggable.setTags(List.of("a", "b"));        // + setTags(names, allowSystem)
resolver.commit();
```

Everything runs through the resolver the model was read with, under the caller's own
permissions, and writes are the caller's to commit. `tag`/`untag`/`setTags` validate
against the definitions: the tag must exist, apply to the content, and not be a
`system` tag. The `allowSystem` variants are reserved for the platform code owning a
system tag.

`Tag` carries `getName()`, `getDefinition()`, `isDefined()`, `getOrigins()`
(`EXPLICIT`/`COMPUTED`/`INHERITED`/`AGGREGATED`) and `getSources()`.

Two things to watch:

- **`getEffectiveTags()` visits the whole subtree** for aggregation. Don't call it on
  huge trees; read the four properties, or `getEffectiveTagNames()`.
- **Undefined tag strings already on a node** — left by a deleted definition — are
  still reported by the read methods and can be removed, but never added.

`TagManager` (OSGi) is the vocabulary service: `getDefinitions()`,
`getDefinition(name)`, `findDefinitions(category, query)`, plus the property-name
constants. It takes **no resolver** — the code most in need of definitions (commit
hooks, scheduled jobs) often has no user session — so it reads with its own service
user and caches until `/Tags` changes. **Restricting an individual definition with an
ACL therefore has no effect.**

`TagDefinition` and `TagsHomepage` give typed read access to the definitions,
including `appliesTo(content)` and the `TagDefinition.DISPLAY_ORDER` comparator.

## Marking content taggable

The `tag:Taggable` mixin declares the tagging properties once: `tags`, the three
materialized phase properties, and `tagComputationState`. Every `data:Content` node
carries it through its supertypes, so all domain content is taggable; other types
(`nt:file`) opt in with a plain `addMixin`.

It is a declaration aid, not a gate — the `Taggable` model adapts any content, and
writing simply fails on types that cannot store the properties.

## Materialized propagation

Resolving inheritance and aggregation on demand would make queries (JQL `JOIN`s) and
status displays expensive, so derived tags are materialized at commit time, one
property per phase:

| Property | Phase | Holds |
|---|---|---|
| `inheritedTags` | `TOP_DOWN` | every `inheritable` tag belonging to an **ancestor** |
| `computedTags` | `LOCAL` | tags computed for the node from its **own content** |
| `aggregatedTags` | `BOTTOM_UP` | every `aggregated` tag belonging to a **descendant** |

All three are multivalued Strings declared by the mixin and **maintained by the
system — never write them manually**. "Belonging to" covers both hand-placed and
computed tags: a computed tag propagates exactly like an explicit one.

A query then filters without joins:

```sql
SELECT * FROM [sub:Submission] AS s
 WHERE s.tags = 'incomplete' OR s.computedTags = 'incomplete'
    OR s.aggregatedTags = 'incomplete' OR s.inheritedTags = 'incomplete'
```

Properties belong to **phases rather than processors** on purpose: a query and its
index must name the properties they filter on, which is only possible if that set is
fixed rather than depending on which processors a deployment registers.

### The SPI

| Piece | What it is |
|---|---|
| `TagProcessor` | Contributes tags for one node. Declares `getPhase()`, `getScope()`, `getPriority()`, `computeTags(context)`. Every processor of a phase contributes to that phase's single property, which stores the union |
| `TagProcessor.Phase` | `TOP_DOWN` → `LOCAL` → `BOTTOM_UP`, fixed order per node, each mapped to its property |
| `TagProcessor.Scope` | `NODE` (node + parent; cheap and usual) or `ENTITY` (whole enclosing `data:Entity`, recomputed in full whenever anything inside changes) |
| `TagContext` | What a processor gets: `getNode()`, `getParent()`, `getPath()`, `getScopeRoot()`, `getDefinitions()` |
| `TagDefinitions` | Oak-level snapshot of `/Tags`, so propagation works inside any commit with no resolver |
| `TagPropagationEditor(Provider)` | The Oak commit editor running the phases |

Built-in processors: `TagInheritanceProcessor` (down) and `TagAggregationProcessor`
(up). A validation or AI-check module contributes `LOCAL` ones.

Constraints that are not negotiable:

- **A `LOCAL` processor may read `inheritedTags` but never `aggregatedTags`.** Reading
  what its own results feed into is what would let a computation cycle form.
- **Scope is also the re-trigger radius.** Anything wider than `ENTITY` cannot be a
  processor: a tag depending on a *different* entity belongs in a listener or job
  placing explicit `system` tags after the fact, accepting the staleness.
- **A processor may read state outside its context only when that state is immutable
  for the lifetime of the node it tags** — nothing else re-triggers the computation.
  A published `sch:SchemaVersion` qualifies, which is what lets an answer be validated
  against its question.

All editor writes are compare-and-set; recomputation spreads only while stored values
keep changing. Removals converge because copies always derive from the tags belonging
to a node, chained in one direction, so deleting the source provably clears every copy.

### Propagation details

- **Copies travel one direction only.** An aggregated copy on an ancestor is not
  re-inherited by the source's siblings, even for a tag that is both `aggregated` and
  `inheritable`.
- **Derived properties are only written on types that declare them.** A type merely
  tolerating residual properties (`nt:unstructured`, so every free-form container
  including the repository root) has *not* opted in, and strict types that would
  reject them never could. All of those act as propagation boundaries; a free-form
  node becomes taggable by adding the mixin.
- **`targetResourceTypes` restricts explicit placement only.** Derived copies are
  exempt, and must be — an aggregated copy exists to mark an ancestor, which by
  construction is not of the type the tag targets. What bounds a copy is the boundary
  below it.
- **Editing a definition recomputes nothing.** Flipping `aggregated`/`inheritable`, or
  deleting a definition, leaves stored copies alone: no content changed. Fix with
  `POST /Tags.repair.json?tag=<name>`.

### How far a copy travels

Inheritance and aggregation are bounded differently because they are asymmetric. An
`inheritable` tag flows from a node somebody deliberately tagged into that node's
subtree — whoever placed it chose the reach. An `aggregated` tag has no such author,
since every ancestor of every tagged node is a candidate, so it needs a declared top:
the **`tag:Boundary`** mixin. A boundary carries the aggregate of everything beneath
it and contributes nothing upward. It stops only what flows up — an inheritable tag
placed above one still reaches inside.

`data:EntityHomepage` is a boundary, putting the top where the value has a reader: a
homepage is what lists and filters its entities on `aggregatedTags`, and nothing above
it lists anything.

**Entities owning a subtree are boundaries too**, so the aggregate stops one level
lower still: `sub:Submission`, `sch:Schema`, `sch:SchemaVersion`,
`wf:WorkflowDefinition`, `wf:WorkflowVersion`. A submission carries the aggregate of
its answers, documents and reviews — exactly what a listing filters on — and
`/Submissions` carries nothing. The practical reason: a homepage's `aggregatedTags`
would be rewritten by every commit anywhere beneath it, making one shared node a write
hotspot for every change to any entity. And nothing reads that union — a listing
filters each row on that row's own derived tags, never on the sum across rows.

The cost, which is a choice and not an oversight: a definition sees nothing aggregated
from its versions, since a version is a boundary too. Summarising versions is a query
over them, not an aggregated tag.

**Without a top, an aggregated tag climbs to the repository root, and that breaks
writes.** Every property the editor sets is attributed to the committing session, and
Oak evaluates permissions *after* editors run, so a copy landing on an ancestor the
committer may not write fails their commit outright — with a bare `OakAccess0000:
Access denied` naming no path. A deployment shipping an `aggregated` definition must
let everyone who can write the content it aggregates from also write the boundary
node:

```
set ACL for iap-error-tracking
    allow   jcr:read,jcr:addChildNodes,jcr:modifyProperties,jcr:nodeTypeManagement    on /LoggedErrors
end
```

where `/LoggedErrors` is both container and boundary, so one grant covers both.

## When a computation fails

A failing processor **never fails the commit**. Losing data a user just entered
because a tag could not be computed is far worse than carrying a stale tag — tags can
be recomputed, the typing cannot — and the editor runs on *every* commit, so a
throwing processor would block unrelated writes.

The phase whose processor threw keeps its last successful values: storing a union
knowingly missing a contributor would replace good values with worse ones, and a tag
lingering after it stopped applying is safer than one silently disappearing while it
still applies. The node is marked `tagComputationState = failed` so affected nodes can
be found, and so code that must not act on stale tags (an access check) can tell. The
failure is logged and recorded through error tracking as
`ErrorContext.of(processor.getClass(), "computeTags")`.

### Repair

Any node carrying `tagComputationState` is recomputed in full — every phase, whole
subtree — by the next commit reaching it, and the property is removed once the values
can be trusted.

| Value | Written by | Means |
|---|---|---|
| `failed` | the editor | a processor threw; stored tags are the last good ones |
| `recomputing` | a repair | stored tags were declared untrustworthy |

Nothing branches on the value; it exists to tell an operator *why* a node is marked.
**`recomputing` should never be observed at rest** — it is written and consumed within
one commit, so a node holding it had its commit interrupted. The sweep looks for the
property's presence rather than for `failed`, so those get picked up too.

Repair computes nothing itself: it marks nodes and lets the propagation editor, the
one piece that knows the phase order and the scopes, do the work. Marking is a
property write by the `iap-tag-repair` service user, which may read anywhere and
modify properties but never create or remove a node.

Two triggers, both returning a `RepairReport(marked, failed)`:

- **`TagRepairService.repairFailed()`**, run by a scheduled sweep (`Tag Repair`
  configuration: `schedule` `0 0 * * * ? *`, hourly; `enabled` default true). Driven
  by an index and writes nothing when nothing is wrong.
- **`POST /Tags.repair.json?tag=<name>`** (`TagRepairService.repair(name)`), for
  content affected by an edited definition. Deliberate rather than automatic: how much
  work it is depends on how widely the tag is used, and a repository-wide
  recomputation as a side effect of saving a definition is a surprise. Whoever may
  edit the definitions may run it — the permission is read off `/Tags` through the
  caller's own session. The report says how many nodes were marked and how many could
  not be, rather than abandoning the repository over one unwritable node.

## REST

`GET /Tags.search.json` lists the definitions, with combinable filters:

| Parameter | Filters to |
|---|---|
| `category=<name>` | tags listing this category (case-insensitive) |
| `query=<text>` | tags containing the text in name, label or description (case-insensitive) |
| `target=<path>` | tags placeable on the resource at that path |

Response is `{"tags": [...], "total": <n>}`, each entry the full definition. The
default `/Tags.json` renderings remain available for raw access.

## Self-documentation

`GET /Tags.doc.md` and `.doc.json` render the vocabulary through
[autodoc](autodoc.md) — one section per category, one subsection per tag with its
description and applicable behaviours. Multi-category tags are listed under each;
uncategorized ones under `uncategorized`. The heading comes from `title` and
`description` on `/Tags`.

## Future work

- Tag-based access restrictions: an ACL restriction pattern evaluated against a
  resource's tags, e.g. granting write only while a record is not `submitted`.
- UI for displaying and filtering by tags, driven by the definitions.
