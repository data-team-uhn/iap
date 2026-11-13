# Tags

**Module:** `modules/tags` · **Bundle:** `iap-tags-api` · **Packages:**
`…tags.api` (`TagManager`, `Tag`, `TagRepairService`), `…tags.models`,
`…tags.spi`

Tags are short named markers, such as `incomplete`, `submitted` or `retired`, stored in
the multivalued `tags` property of any `data:Content` node. A tag can be placed only
once it is **defined**: a `tag:Definition` node under `/Tags` says what the tag means,
where it may go, and how it behaves.

## Defining a tag

A definition is a `tag:Definition` child of the `/Tags` homepage (`tag:Homepage`, which
repoinit creates and every user may read). Modules ship their definitions as initial
content.

| Property | Type | Meaning |
|---|---|---|
| `name` | String | The exact string stored in `tags`. Defaults to the node name. Set it only for a string awkward as a node name |
| `label` | String | The name people read. Defaults to the name |
| `description` | String | What the tag means and when it applies, in one short sentence |
| `category` | String[] | The subjects the tag belongs to. Some categories hold states that exclude each other, others hold tags that apply together |
| `inheritable` | Boolean | Flows **down**: the content under a tagged node carries it too |
| `aggregated` | Boolean | Flows **up**: a node carries it when content below it does, up to the nearest `tag:Boundary` |
| `targetResourceTypes` | String[] | The resource types it may be placed on, subtypes included. Empty means any |
| `color` | String | A CSS color. The chip's background, text and border all derive from it, as the variant says |
| `variant` | String | `soft` (the default) tints the background, `outlined` draws the text and border only, `filled` fills the chip with the color |
| `icon` | String | A MUI icon name, such as `EditOutlined`. A name `tagIcons.tsx` does not list shows nothing |
| `order` | Long | The position in listings, lowest first. Tags without one come last |
| `system` | Boolean | Only the code managing the tag may add or remove it |

## Java API

```java
final Taggable taggable = version.as(Taggable.class);

Set<String>      explicit  = taggable.getTags();
Collection<Tag>  effective = taggable.getEffectiveTags();   // definition + origins + sources
Set<String>      names     = taggable.getEffectiveTagNames();
boolean          any       = taggable.hasTag("retired");    // any origin
boolean          own       = taggable.hasOwnTag("retired");
List<TagDefinition> here   = taggable.getApplicableDefinitions();

taggable.tag("retired");                    // + tag(name, allowSystem)
taggable.untag("retired");                  // + untag(name, allowSystem)
taggable.tagReplacing("active", false);     // in place of tags sharing a category
taggable.setTags(List.of("a", "b"));        // + setTags(names, allowSystem)
resolver.commit();
```

Everything goes through the resolver the model was read with, under the caller's own
permissions, and the caller commits the writes. `tag`, `untag`, `tagReplacing` and
`setTags` check the definitions. A tag placed must exist, apply to the content, and not
be a `system` tag. A tag removed must not be a `system` tag. The `allowSystem` variants
are for the code that manages a system tag. `tagReplacing` moves content from one state
to the next: it places a tag, and removes the content's own tags that share any of its
categories. Tags of other categories, and undefined tags, stay.

`Tag` carries `getName()`, `getDefinition()`, `isDefined()`, `getOrigins()`
(`EXPLICIT`/`COMPUTED`/`INHERITED`/`AGGREGATED`) and `getSources()`.

Two things to watch:

- **`getEffectiveTags()` visits the subtree** down to the nearest boundaries, to find
  the aggregated tags. On a large subtree, read the four properties, or call
  `getEffectiveTagNames()`.
- **A tag string left on a node after its definition was deleted** is still reported
  by the read methods. It can be removed, but never added.

`TagManager` (OSGi) reads the definitions: `getDefinitions()`, `getDefinition(name)`,
`findDefinitions(category, query)`, plus the property name constants. It takes **no
resolver**. It reads with its own service user, so a caller needs no session, and it
caches the definitions until something under `/Tags` changes. **An ACL on one
definition therefore does not hide it from the manager's callers.**

`TagDefinition` and `TagsHomepage` are the models of the definitions, with
`appliesTo(content)` and the `TagDefinition.DISPLAY_ORDER` comparator.

## Marking content taggable

The `tag:Taggable` mixin declares the tag properties: `tags`, the three derived
properties, and `tagComputationState`. Every `data:Content` node has it through its
supertypes, and a node of another type, such as `nt:file`, gets it by adding the mixin.

The `Taggable` model adapts any content. Writing fails on a node whose types cannot
store the properties.

## Materialized propagation

Inheritance and aggregation are worked out at commit time and stored, one property per
phase, so a query or a status display reads them without a join:

| Property | Phase | Holds |
|---|---|---|
| `inheritedTags` | `TOP_DOWN` | every `inheritable` tag belonging to an **ancestor** |
| `computedTags` | `LOCAL` | the tags computed for the node from its **own content** |
| `aggregatedTags` | `BOTTOM_UP` | every `aggregated` tag belonging to a **descendant** below the nearest boundary |

All three are multivalued Strings declared by the mixin. **The propagation editor
maintains them, so never write them.** A tag belongs to a node when it is placed on it
or computed for it, so a computed tag propagates like a placed one.

A query then filters without joins:

```sql
SELECT * FROM [sub:Submission] AS s
 WHERE s.tags = 'incomplete' OR s.computedTags = 'incomplete'
    OR s.aggregatedTags = 'incomplete' OR s.inheritedTags = 'incomplete'
```

The properties belong to **phases, not processors**. A query or an index names the
properties it filters on, so the set must not depend on which processors a deployment
registers.

### The SPI

| Piece | What it is |
|---|---|
| `TagProcessor` | Contributes tags for one node. Declares `getPhase()`, `getScope()`, `getPriority()` and `computeTags(context)`. The processors of a phase contribute to its one property, which stores their union |
| `TagProcessor.Phase` | `TOP_DOWN` → `LOCAL` → `BOTTOM_UP`, in that order on each node, each with its own property |
| `TagProcessor.Scope` | `NODE` (the node and its parent, and its children for `BOTTOM_UP`; the default) or `ENTITY` (the whole enclosing `data:Entity`, recomputed in full whenever anything inside it changes) |
| `TagContext` | What a processor gets: `getNode()`, `getParent()`, `getPath()`, `getScopeRoot()`, `getAggregationSources()`, `getDefinitions()` |
| `TagDefinitions` | The definitions under `/Tags`, read from Oak node states, so propagation needs no resolver |
| `TagPropagationEditor(Provider)` | The Oak commit editor that runs the phases |

Built-in processors: `TagInheritanceProcessor` (down) and `TagAggregationProcessor`
(up). Other modules may register their own.

Three rules for a processor:

- **A `LOCAL` processor may read `inheritedTags` but never `aggregatedTags`.** See
  `TagProcessor.Phase.LOCAL`.
- **The scope is also what makes a processor run again.** A tag that depends on
  something wider than an `ENTITY`, such as another entity, cannot come from a
  processor. A listener or a job can place it as a `system` tag after the fact, and it
  is stale until then.
- **A processor may read state outside its context only if that state never changes
  while the node it tags exists.** Nothing else makes it run again. Once a
  `sch:SchemaVersion` is published, only its wording can change, so a processor may
  depend on the rest of it but not on its wording.

The editor writes a property only when its value changes, and recomputation spreads
only while stored values keep changing. Copies always derive from the tags belonging to
a node, and travel in one direction, so deleting the source clears every copy.

### Propagation details

- **Copies travel in one direction only.** An aggregated copy on an ancestor is not
  inherited back down by the source's siblings, even for a tag that is both
  `aggregated` and `inheritable`.
- **Derived properties are written only on a node whose types declare them.** A type
  that accepts any property, such as `nt:unstructured` and so the repository root, does
  not declare them, and a strict type cannot store them. The copies stop at such a
  node. A node of a free-form type becomes taggable by adding the mixin.
- **`targetResourceTypes` restricts placing a tag, not its copies.** An aggregated copy
  marks an ancestor, which is not of the type the tag targets. What limits how far a
  copy climbs is the nearest `tag:Boundary`.
- **Editing a definition recomputes nothing.** Changing `aggregated` or `inheritable`,
  or deleting a definition, leaves the stored copies as they were, since no content
  changed. `POST /Tags.repair.json?tag=<name>` fixes them.

### How far a copy travels

Inheritance and aggregation are bounded differently. An `inheritable` tag flows from a
node somebody chose to tag into that node's subtree, so whoever placed it chose its
reach. An `aggregated` tag would reach every ancestor of every tagged node, so it needs
a declared top: the **`tag:Boundary`** mixin. A boundary carries the aggregate of
everything beneath it, and passes nothing upward. It stops only what flows up: an
inheritable tag placed above one still reaches inside.

`data:EntityHomepage` is a boundary, so no aggregate climbs above a homepage.

**Entities that own a subtree are boundaries too**: `sub:Submission`, `sch:Schema`,
`sch:SchemaVersion`, `wf:WorkflowDefinition` and `wf:WorkflowVersion`. A submission
carries the aggregate of its answers, documents and reviews, which is what a listing
filters each submission on, and `/Submissions` carries nothing. Otherwise every commit
anywhere beneath a homepage would rewrite its `aggregatedTags`, and nothing reads that
union.

A definition therefore sees nothing aggregated from its versions, since a version is a
boundary too. Summarising the versions is a query over them, not an aggregated tag.

**A copy the committer may not write fails the commit.** Oak checks the editor's writes
against the committing session, after the editors have run. The commit fails with a
bare `OakAccess0000: Access denied` that names no path. A deployment that ships an
`aggregated` definition must let everyone who can write the content it aggregates from
also write the boundary above that content:

```
set ACL for iap-error-tracking
    allow   jcr:read,jcr:addChildNodes,jcr:modifyProperties,jcr:nodeTypeManagement    on /LoggedErrors
end
```

Here `/LoggedErrors` is both the container and the boundary, so one grant covers both.

## When a computation fails

A failing processor **never fails the commit**. A tag can be recomputed later, but a
user's typing cannot, and the editor runs on every commit, so a throwing processor would
block unrelated writes.

The phase whose processor threw keeps its last values. A union missing one contributor
could drop a tag that still applies, and a tag that lingers is safer than one that
disappears. The node is marked `tagComputationState = failed`, so the affected nodes
can be found, and code that must not act on stale tags can tell. The failure is logged,
and recorded through error tracking once per commit as
`ErrorContext.of(processor.getClass(), "computeTags")`.

### Repair

The next commit that reaches a node carrying `tagComputationState` recomputes it in
full, every phase over the whole subtree, and removes the property.

| Value | Written by | Means |
|---|---|---|
| `failed` | the editor | a processor threw, and the stored tags are the last good ones |
| `recomputing` | a repair | a repair asked for the tags to be recomputed |

The editor reads only whether the property is there, and the value tells an operator
why the node is marked. **`recomputing` is never seen at rest** unless a commit was
interrupted, since it is written and removed within one commit. The sweep looks for the
property, not for `failed`, so it finds those too.

A repair computes nothing itself: it marks nodes, and the propagation editor recomputes
them. The `iap-tag-repair` service user writes the marks. It may read anything and set
properties, but never create or remove a node.

Two triggers, both returning a `RepairReport(marked, failed)`, or throwing
`TagRepairException` when the repair cannot run at all:

- **`TagRepairService.repairFailed()`**, run by a scheduled sweep (the `Tag Repair`
  configuration: `schedule` `0 0 * * * ? *`, hourly; `enabled`, true by default). An
  index finds the marked nodes, and the sweep writes nothing when there are none.
- **`POST /Tags.repair.json?tag=<name>`** (`TagRepairService.repair(name)`), for the
  content affected by an edited definition. It runs only on request: how much work it
  is depends on how widely the tag is used, and saving a definition should not set off
  a recomputation across the repository. A caller who may set properties on `/Tags` may
  run it, as their own session answers. Any tag name is accepted. The answer counts the
  nodes marked and those that could not be, and one node that cannot be marked does not
  stop the rest. A repair that cannot run at all, without its service user or with a
  failing query, answers 503 and is recorded.

## REST

`GET /Tags.search.json` lists the definitions. Its filters combine:

| Parameter | Filters to |
|---|---|
| `category=<name>` | tags listing this category, ignoring case |
| `query=<text>` | tags with this text in their name, label or description, ignoring case |
| `target=<path>` | tags that may be placed on the resource at that path; a 400 if the caller cannot read it |

The answer is `{"tags": [...], "total": <n>}`, with each definition in full. The
default `/Tags.json` renderings still give the raw nodes.

## Self-documentation

`GET /Tags.doc.md` and `.doc.json` render the tags through [autodoc](autodoc.md): one
section per category, and one subsection per tag with its description, its behaviors
and where it may be placed. A tag in several categories is listed under each, and one
without a category under `uncategorized`. The heading comes from `title` and
`description` on `/Tags`.

## Future work

- Access restrictions by tag: an ACL restriction evaluated against a resource's tags,
  such as granting write only while a record is not `submitted`.
