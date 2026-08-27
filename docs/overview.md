# Overview

IAP puts things through institutional approval. Its first deployment is a Research Ethics Board's,
replacing antiquated software for managing research study proposals — but nothing in the platform is
specific to research ethics, or to research at all. What a submission has to contain, who has to
look at it, in what order, and what happens at each step are authored as content in a running
instance rather than written into the code.

This page is the *why*: what the product is meant to be, and the two paradigms almost every
structural decision follows from. It deliberately explains no mechanism in full — each of those has
its own page, listed in the [documentation index](README.md).

## What it is meant to be

A generic, extensible, workflow-powered platform that a deployment configures into a specific tool —
not a proposal-authorization application with configuration bolted on.

- **The core platform stays generic.** Concrete deployments are customized in separate downstream
  repositories that add branding and theming, workflow definitions, schema definitions, and
  integrations with whatever systems that institution already runs. Nothing about one institution's
  process belongs here.
- **Non-research processes are in scope deliberately**, not tolerated. The `demos/time-off-request`
  workflow exists to keep that honest: if a leave request cannot be modelled with the same
  vocabulary as an ethics submission, the vocabulary has become too specific.
- **Cross-institution submissions are a known future requirement** — a hub-and-spoke federation in
  which one submission is reviewed and approved by several institutions. It is not designed yet,
  which is precisely why any decision about identity, ownership, or a reference that crosses a
  boundary deserves more care than its immediate use needs.

The vocabulary follows from this. A **submitter** files a **submission** against a **schema**, and
those words stay domain-neutral even where a given deployment would say "researcher" and "proposal".
Renaming a concept to match the first client is how a platform stops being one.

## Two paradigms

Almost every structural decision in the repository comes out of one of two commitments: everything
the platform knows is **content**, and everything that changes it is a **workflow**.

They pull in the same direction. Content is inert — it describes what is, never what may happen next
— so something has to mediate change, and a process engine is a poor fit for data it cannot address
uniformly. One store with one addressing scheme is what makes a single engine able to drive all of
it.

## Everything is content

There is one store, the JCR repository, and everything lives in it: the domain data, the definitions
that configure behavior, the UI wiring, and the platform's own configuration. Not a database for
data plus files for configuration plus code for behavior — one tree, one addressing scheme, one
access-control model, one serialization, one way to query.

| Tree             | Holds                                                             |
|------------------|-------------------------------------------------------------------|
| `/Submissions`   | The submissions themselves                                        |
| `/Schemas`       | What a submission must contain, version by version                |
| `/Categories`    | The choosing tree, each leaf naming a schema version              |
| `/Workflows`     | Workflow definitions, their versions, and the parsed graphs       |
| `/WorkflowTypes` | The BPMN vocabulary                                               |
| `/Tags`          | Tag definitions                                                   |
| `/LinkTypes`     | Link type definitions                                             |
| `/Extensions`    | Which UI component is plugged into which extension point          |
| `/Archive`       | Deleted content, pending restore or purge                         |
| `/LoggedErrors`  | Recorded failures                                                 |
| `/Metrics`       | Usage counters                                                    |
| `/libs`, `/apps` | Resource-type hierarchy, platform configuration, and a deployment's overrides of either |

### The base hierarchy

Four abstract node types carry everything the rest of the platform relies on. A domain module
declares its own concrete types under them and inherits the whole toolbox.

```
data:Content            anything IAP stores — taggable, serializable, addressable
├── data:Entity         a thing stored as a whole: referenceable, versionable, linkable, last-modified
├── data:EntityPart     a piece that only exists inside an entity — an answer, a review comment
└── data:EntityHomepage the container listing entities of one type: access-controllable, queryable
```

`data:Entity` is the unit of identity and history: it is `mix:referenceable`, so other content can
point at it, and `mix:versionable`, so its past is recoverable. A `data:EntityPart` is neither,
because it has no independent existence — an answer outside its submission is not a smaller thing,
it is a broken one. A `data:EntityHomepage` is `rep:AccessControllable` because a collection is the
natural place to say who may see this *kind* of thing.

### Why the base type is where mechanisms attach

This is the actual payoff, and it is the design test to apply before adding anything cross-cutting.
Each of these mechanisms attaches to `data:Content` or `data:Entity` rather than to any domain type,
so a new domain type gets all of them the moment it is declared, and none of them has to know that
domain exists:

| Mechanism                 | Attaches as                                         |
|---------------------------|-----------------------------------------------------|
| Tagging                   | `tag:Taggable`, on `data:Content`                   |
| Ad-hoc relations          | `link:Linkable`, on `data:Entity`                   |
| Conditional applicability | `cond:Conditionable`, on whatever applies sometimes |
| Version history           | `mix:versionable`, on `data:Entity`                 |
| Self-documentation        | `doc:Documented`, on a catalogue                    |
| Deletion and archival     | any content, through a veto SPI                     |
| JSON serialization        | an adapter on any resource                          |

**So a new cross-cutting mechanism attaches to the base type, not to a domain type.** A tagging
scheme that only submissions could use, or a deletion service that had to be taught about each new
entity, would have to grow a branch per domain and would be wrong for the next one.

### Definitions are content too

The tag vocabulary, the link types, the category tree, the workflow definitions, the BPMN element
vocabulary, the UI extension registrations, the theme and login configuration under `/libs/iap/conf`
— all of it is stored as content rather than compiled in or read from files.

Two consequences worth stating. Defining a new tag or category is a content node, so a deployment
adds one without a code change and without a release. And because a definition is content, every
mechanism above applies to it: definitions are serialized by the same adapters, secured by the same
ACLs, deleted and restored through the same service, and documented at runtime by the same
[autodoc](autodoc.md) endpoints — which is what makes `/WorkflowTypes.doc.json` able to drive the
BPMN editor's toolbars straight from the vocabulary the parser reads.

Definition-style node types extend `data:Content` directly rather than `data:Entity`. They are
platform vocabulary, referred to by name, so referenceability and version history would be dead
weight and the entity semantics would be a misleading promise.

### Reading it: Sling Models, not JCR

Nothing outside the model hierarchy touches the repository. Every content node adapts to a Sling
Model, and `Content` is the base of them all — property access, typed child and reference lookups,
JSON, and a re-view of the same node as another model:

```java
Submission submission = resource.adaptTo(Submission.class);
SchemaVersion version = submission.getSchemaVersion();     // a resolved model, never a UUID
List<Review> reviews = submission.getReviews();
```

Reference accessors return resolved models rather than identifiers, and child accessors filter by
resource type before adapting. That filter is not a nicety: adaptation is not a type check, so a
model registered for one resource type will happily wrap a node of an unrelated one, and the caller's
`!= null` test would take the result for the thing it asked for.

Abstract bases — `Requirement`, `FlowNode`, `Condition` — are not registered models. Each concrete
subtype lists the bases it answers for in its own `@Model`, so asking for the base yields the actual
subtype rather than a generic node missing its fields. That dispatch runs on the Sling resource-type
hierarchy, which is why **a new node type needs a `/libs/<prefix>/<Type>/ROOT.json` naming its
supertype** — a node carries only the single supertype its node type autocreates, and without those
nodes the chain cannot be walked and the dispatch quietly stops matching.

### Serving it

Any resource serializes to JSON, shaped by selectors that compose: `.deep` recurses, `.simple` trims
to the essentials, `.dereference` resolves references into their targets, and `.files`, `.identify`
and `.properties` add their own slices. Some are on by default and are turned *off* by prefixing the
name with `-`, as in `/Submissions/42.-dereference.json`. A numeric selector bounds the traversal
depth on Sling's usual convention — `.0` is the node alone, `.1` adds its children — and anything
past the limit appears as its path rather than being dropped. Selectors may also be passed as
repeated `selector` query parameters, for callers that cannot put them in the path.

New shapes are `ResourceJsonProcessor` services, so a module teaches the serializer about its own
content without the serializer knowing the module exists.

### JCR is one level below the real data model

The stored shape is not the shape anyone is served. Reads go through a service user that gathers
content and adapts it into a projection for the current user, so **what a given user may see is
decided by that projection rather than by JCR access control**. Two consequences:

- "There is no per-node or per-version ACL for X" is usually not a real objection here. Oak's
  permission model constrains what a *user session* may read, and for service-read stores no user
  session reads anything. The ACL that matters is the blunt one: deny everyone, allow the service
  user.
- Redaction therefore becomes application code, which the repository can no longer enforce for us.
  The trade is accepted deliberately — one service with one entry point is auditable in one place —
  but it means a projection bug is a disclosure bug, and those services are the ones that earn
  thorough tests.

## Every change is a workflow

Clients do not perform CRUD on content. A workflow engine mediates every repository change: the
HTTP surface accepts domain events — "file a submission", "submit a review", "resolve a comment" —
and the workflow definition decides what happens, in what order, and whether it is allowed at all.

**Why:** business rules scattered across servlets and post-processors, validating after the fact,
proved the harder thing to maintain. Approval is an inherently process-shaped domain, so the process
should be the thing that is written down, in one place, in a form a non-programmer can read. The
engine is IAP's own rather than an embedded third-party one.

Workflows are authored as **BPMN 2.0 diagrams**, stored as both the `bpmn.xml` they were drawn as
and the graph of nodes it parses into, and executed by creating an *instance* and moving tokens
through that graph. Two kinds exist: **user workflows**, bound to a schema version and describing how
a submission progresses, and **system workflows**, which are the platform's own behavior — what
happens when a schema is created, when a submission is archived.

### Authorization lives in the definition

This is the consequence that reaches furthest, and the one most easily mistaken for a gap. Nobody
holds repository write permission on the content a workflow manages: the engine writes as its own
service user, once the definition has said the actor belongs there. A flow node's `performers` names
the principals who may make execution pass through it — who may fire an event, who may complete a
task.

So an absent or empty `performers` list admits **nobody**, deliberately. A definition that forgot to
say who may use it should refuse everyone until it does; silence is never permission.

A workflow instance also lives **inside the thing it drives**, in a `wf:instances` container, so it
is found, secured and deleted along with it. See [workflows](workflows.md) for the runtime model, the
BPMN vocabulary, and the traps around versioning an entity that has a live workflow inside it.

### Status

The workflow **data model** is in place — the node types, the Sling Models over them, and the editor
that keeps a stored graph in step with its diagram. The **execution engine is not merged yet**: what
exists today is everything it will read and write. Until it lands, workflow-first describes the
committed design rather than the running system, and content is still reached through ordinary Sling
requests.

## The layers

| Layer            | What                                                                          |
|------------------|-------------------------------------------------------------------------------|
| Repository       | Apache Jackrabbit Oak — segment/TAR by default, MongoDB or PostgreSQL optionally |
| Application      | Apache Sling on Apache Felix; services are OSGi Declarative Services components |
| Assembly         | Sling Feature Model — every module ships its own feature file, and the packaging module aggregates them into what `start.sh` launches |
| HTTP             | Sling resource resolution: a path addresses content, selectors and extensions choose the representation |
| UI               | A React single-page app, composed of extensions plugged into extension points |

The UI is not a monolith with hooks. The page shell, the app bar, the routed views and the dashboard
widgets are all extension points, and a feature contributes to the interface by shipping a component
plus a small content node registering it — so the consumer of a point renders whatever is registered
without knowing the contributors. See [UI extensions](ui-extensions.md).

## How a module is put together

A backend module owns a slice of the content model end to end: its node types, the models over them,
the services acting on them, and the content and configuration it needs to exist.

```
modules/<name>/
├── src/main/resources/SLING-INF/nodetypes/<name>.cnd     the node types
├── src/main/resources/SLING-INF/content/libs/<prefix>/   ROOT.json per type — resource-type hierarchy
├── src/main/resources/SLING-INF/content/{Extensions,Tags}/  UI registrations, tag definitions
├── src/main/features/feature.json                        its own slice of the assembly
└── src/main/java/io/uhndata/iap/<name>/
    ├── api/  spi/  models/                               exported: other modules compile against these
    └── internal/                                         not exported: services, servlets, editors
```

Packages named `api`, `spi` and `models` are exported and are the module's contract; `internal` (and
an `impl` submodule, where a module is large enough to split) is not. Larger modules separate the two
into submodules so that a consumer can depend on the contract without dragging the implementation in
— which is what lets any module record errors through `iap-error-tracking-api` without a dependency
cycle.

One namespace prefix per domain module, URIs of the form `https://iap.uhndata.io/<prefix>/`:

| Prefix   | Covers                     | Prefix   | Covers                  |
|----------|----------------------------|----------|-------------------------|
| `data`   | the base content hierarchy | `cat`    | categories              |
| `sub`    | submissions                | `cond`   | conditions              |
| `sch`    | schemas                    | `del`    | deletion and the archive |
| `wf`     | workflows                  | `doc`    | self-documentation      |
| `tag`    | tags                       | `err`    | error tracking          |
| `link`   | links                      | `ext`    | UI extensions           |
| `app`    | the application shell      | `metric` | metrics                 |

## Known gaps

- **No execution engine yet**, as above. It is the piece the rest of the design is waiting on.
- **Federation is undesigned.** Cross-institution review is a stated requirement with no model
  behind it, which is why references that would cross an institutional boundary are worth treating
  carefully now rather than discovering later.
- **The read-side projection is a principle more than a layer.** The rule that reads are adapted per
  user by a service is settled; there is no single service that all reads currently pass through.
- **Downstream customization is a convention, not a mechanism.** That deployments live in their own
  repositories and add definitions, branding and integrations is how the platform is meant to be
  used, but nothing in the build or the packaging enforces the boundary.
