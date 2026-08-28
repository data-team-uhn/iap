# Institutional approval as a platform problem

Almost every institution of any size has a process by which something is proposed,
passed around for scrutiny, and eventually blessed or rejected. The software that
supports those processes has a way of absorbing the specifics of whichever
institution paid for it, with the predictable result that the next institution
needs a new program. IAP, developed by the [Data Aggregation, Translation and
Architecture (DATA) team](https://uhndata.io/) at Toronto's [University Health
Network](https://uhn.ca/), is an attempt to break out of that cycle. It is a
platform for putting things through institutional approval, with the questions of
what a "thing" is and what "approval" means left to whoever deploys it.

The first deployment is a research ethics board's, replacing older software for
managing study proposals. Nothing in the platform, though, is specific to research
ethics — or to research at all. What a submission must contain, who has to look at
it, in what order, and what should happen at each step are all authored as content
in a running instance rather than written into the code. That distinction sounds
like a small one until one looks at what it does to the rest of the system, which
is more or less the subject of the remainder of this article.

Before any of that, though, it is worth seeing what the thing actually does. A
researcher with a study to get approved arrives and is asked a series of questions
about it — is it interventional or observational, does it involve human
participants — each answer narrowing the field until the platform knows which of
its many application forms this study calls for. They then fill that form in, save
as they go, and eventually file it.

At that point the submission stops being theirs alone. The process attached to that
form takes over: it decides who is asked to look at the study and in what order,
what each of those people is allowed to do when their turn comes, and what happens
when they have done it. A reviewer may raise a comment that the researcher has to
resolve before anything continues; a coordinator may have to sign off before the
board sees it at all. Eventually the study is approved, rejected, or sent back for
revision, and every step of how it got there remains on the record.

None of that sequence is compiled into the platform. The questions, the forms, and
the process are all authored inside a running instance, which is why the same
software is meant to stretch from something as slight as a request for a few days
off to something as involved as a research proposal that several institutions have
to review and approve between them.

## Generic by construction

The stated goal is a generic, extensible, workflow-powered platform that a
deployment configures into a specific tool. That is not the same thing as a
proposal-authorization application with a configuration file bolted onto the side,
and the project takes some trouble to keep the difference visible.

Concrete deployments live in separate downstream repositories, where they add
branding and theming, workflow definitions, schema definitions, and integrations
with whatever systems the institution in question already runs. Nothing about any
one institution's process is supposed to appear in the core.

Keeping a platform honest about that sort of promise is famously difficult, so IAP
ships a canary in the form of a `demos/time-off-request` workflow. Non-research
processes are in scope deliberately rather than merely tolerated; if a leave
request cannot be expressed in the same vocabulary as an ethics submission, then
the vocabulary has quietly become too specific and something needs to be fixed.
The vocabulary itself follows the same discipline: a *submitter* files a
*submission* against a *schema*, and those words stay domain-neutral even in
deployments that would naturally say "researcher" and "proposal". Renaming a
concept to match the first client is one of the more reliable ways for a platform
to stop being one.

There is also a requirement taking shape: cross-institution submissions, in a
hub-and-spoke federation where a single submission is reviewed and approved by
several institutions. That design is still being pinned down, and until it is, any
decision about identity, ownership, or a reference that might someday cross an
institutional boundary gets more care than its immediate use would seem to justify.

Nearly every structural decision in the repository descends from one of two
commitments: everything the platform knows is content, and everything that changes
it is a workflow. The two pull in the same direction. Content is inert — it
describes what is, never what may happen next — so something has to mediate
change; meanwhile, a process engine is a poor fit for data that it cannot address
uniformly. One store with one addressing scheme is what makes a single engine
capable of driving all of it.

## Everything is content

There is exactly one store: a JCR repository, backed by Apache Jackrabbit Oak.
Everything lives in it — the domain data, the definitions that configure behavior,
the UI wiring, and the platform's own configuration. Not a database for data plus
files for configuration plus code for behavior, but one tree, with one addressing
scheme, one access-control model, one serialization, and one way to query.

The top-level layout is much as one would guess. Everything anybody has filed —
the answers, the reviews of them, and whatever else accumulates along the way —
sits under `/Submissions`, and `/Schemas` describes what a submission must contain.
The processes that govern
how a submission moves toward approval live under `/Workflows`, while
`/WorkflowTypes` holds the catalogue of individual phases and actions available for
designing those workflows. Tag and link-type definitions get `/Tags` and
`/LinkTypes`; `/Extensions` is where the entire user interface is plugged in.
Deleted content waits in `/Archive` for restoration or purging, failures accumulate
in `/LoggedErrors`, counters in `/Metrics`. Finally, `/libs` and `/apps` carry the
platform configuration and any deployment's overrides of it.

That leaves `/Categories`, which is worth a paragraph of its own, since it exists
purely to solve a human problem. A deployment of any maturity will accumulate a
great many schema versions, and confronting a submitter with that list and asking
them to pick the right one is a good way to collect submissions filed against the
wrong schema. The category tree is the narrowing questionnaire described earlier:
each branch rules out a portion of the field and each leaf names exactly one schema
version. The submitter answers questions about their study; the schema is a
consequence of the answers rather than something they had to know in advance.
Because the tree is content like everything else, a deployment shapes those
decisions to match how its own applicants actually think about their work, without
touching the schemas themselves.

Underneath all of that sit four abstract node types, which carry everything the
rest of the platform relies on:

```
data:Content            anything IAP stores — taggable, serializable, addressable
├── data:Entity         a thing stored as a whole: referenceable, versionable, linkable
├── data:EntityPart     a piece that only exists inside an entity — an answer,
│                       a review comment
└── data:EntityHomepage the container listing entities of one type:
                        access-controllable, queryable
```

A domain module declares its own concrete types under those and inherits the
entire toolbox. `data:Entity` is the unit of identity and history: it is
`mix:referenceable`, so other content can point at it, and `mix:versionable`, so
its past can be recovered. A `data:EntityPart` is neither, on the theory that it
has no independent existence — an answer outside its submission is not a smaller
thing, it is a broken one.

A `data:EntityHomepage`, meanwhile, is the container gathering every entity of a
single kind: one for submissions, one for schemas, one for each new type a module
introduces. Giving those containers a shared type means the operations one wants
from a collection can be written once. Listing and querying its contents works the
same way regardless of what is inside, as does describing what that kind of thing
is and what may be done with it — so a client, or a curious administrator, can ask
a collection to account for itself without any code having been written for that
particular kind. It is also the natural place to settle who may see this *kind* of
thing at all, rather than repeating the question for every item in it.

The payoff, and the design test to apply before adding anything cross-cutting, is
that mechanisms attach to those base types rather than to any domain type. Tagging
arrives as `tag:Taggable` on `data:Content`; ad-hoc relations as `link:Linkable`
on `data:Entity`; conditional applicability as `cond:Conditionable` on whatever
happens to apply only sometimes. Version history is `mix:versionable` on
`data:Entity`, self-documentation is `doc:Documented` on a catalogue, deletion and
archival work on any content through a veto SPI, and JSON serialization is an
adapter on any resource. A new domain type acquires all of it the moment it is
declared, and none of those mechanisms has to know that the new domain exists. A
tagging scheme that only submissions could use, or a deletion service that had to
be taught about each new entity type, would grow a branch per domain and would be
wrong for the next one.

Definitions get the same treatment. The tag vocabulary, the link types, the
category tree, the workflow definitions, the set of phases and actions they are
built from, the UI extension registrations, and the theme and login
configuration under `/libs/iap/conf` are all stored as content rather than
compiled in or read from files. Two consequences are worth spelling out. Defining a new tag or category is
just a content node, so a deployment can add one without a code change and without
a release. And, because a definition is content, every mechanism above applies to
it: definitions are serialized by the same adapters, secured by the same ACLs,
deleted and restored through the same service, and documented at runtime by the
same autodoc endpoints. That last is what allows `/WorkflowTypes.doc.json` to fill
the workflow editor's toolbars with exactly the phases and actions the parser knows
how to read, without having to write new code, recompile, and redeploy it.

Definition-style node types extend `data:Content` directly rather than
`data:Entity`; they are platform vocabulary, referred to by name, so
referenceability and version history would be dead weight and the entity semantics
would amount to a misleading promise.

## Reading and serving it

Nothing outside the model hierarchy touches the repository directly. Every content
node adapts to a Sling Model, with `Content` as the base of them all, offering
property access, typed child and reference lookups, JSON, and a re-view of the
same node as some other model:

```java
Submission submission = resource.adaptTo(Submission.class);
SchemaVersion version = submission.getSchemaVersion();
List<Review> reviews = submission.getReviews();
Set<String> tags = submission.as(Taggable.class).getTags();
```

The point of that layer is that the code reads like the domain it serves. A
submission has a schema version and it has reviews, and asking for them looks like
asking for them; nobody writing against this has to think in terms of nodes,
properties, paths, or identifiers, which are all details of how the content happens
to be stored rather than anything about approval.

The models also absorb work that would otherwise be copied from caller to caller.
Fetching the reviews of a submission means finding the right children, ignoring
everything that is not a review, and presenting each one as a review; all of that
sits behind the method name. A caller gets the answer without having to know, or
correctly reproduce, how it was arrived at.

Finally, one piece of content can be looked at in more than one way. A submission
is a submission, but it is also something taggable, something linkable, something
with a history — and the last line above is how a caller asks to see it in one of
those other lights. The thing being examined does not change; only the question
being asked of it does.

On the way out, any piece of content can be served as JSON, and the request itself
says how much of it is wanted: just this item or everything beneath it, the full
record or a trimmed one, references left as references or followed to what they
point at, and how many levels down to go before stopping.
[JSON serialization](json-serialization.md) covers the syntax. New shapes are
pluggable, so a module can teach the serializer about its own content without the
serializer knowing that the module exists.

One further point is worth stating plainly, since it is where the platform departs
from what a JCR-shaped system would normally do. The stored shape is not the shape
anyone is served. Reads go through a single service user, which gathers the content
and then adapts it into a projection for whoever is asking. What a given user may
see is therefore decided by that projection rather than by repository permissions;
the repository's own access control is used bluntly, denying everyone and allowing
the service user.

That is a real trade rather than a free win. Redaction becomes application code, so
the repository can no longer enforce it on the project's behalf. It was accepted
deliberately, on the grounds that one service with one entry point can be audited
in one place instead of being spread across a permission model that has to be got
right node by node. But it does mean that a projection bug is a disclosure bug, and
those particular services are the ones that need to earn their thorough tests.

## Every change is a workflow

Nothing on the outside creates, edits, or deletes content directly. A workflow
engine mediates every repository change: the HTTP surface accepts domain events —
file a submission, submit a review, resolve a comment — and the workflow definition
decides what happens, in what order, and whether it is permitted at all.

The motivation will be familiar to anybody who has maintained the alternative.
Business rules scattered across servlets and post-processors, validating after the
fact, turned out to be the harder thing to keep working. Approval is an inherently
process-shaped domain, so the process is what ought to be written down, in one
place, in a form that a non-programmer can read.

Workflows are authored as BPMN 2.0 diagrams and stored both as the diagram they
were drawn as and as the structure it parses into. Running one means creating an
*instance* of it — a live copy that keeps track of where the submission has got to
and advances through the diagram as each step completes. There are two kinds.
User workflows are bound to a schema version and describe how a submission
progresses; system workflows are the platform's own behavior — what happens when a
schema is created, or when a submission is archived. The engine is written in-house
rather than an embedded third-party one.

The consequence that reaches furthest is that authorization lives in the workflow
definition, and it is the part most easily mistaken for a gap. Nobody holds
repository write permission on the content that a workflow manages; the engine
writes as its own service user, once the definition has said that the actor
belongs there. A flow node's `performers` names the principals who may cause
execution to pass through it — who may fire an event, who may complete a task.

An absent or empty `performers` list therefore admits nobody, and does so
deliberately. A definition that has forgotten to say who may use it should refuse
everyone until it says so; silence is never read as permission.

A workflow instance also lives inside the thing it drives, in a `wf:instances`
container, so that it is found, secured, and deleted along with it. The runtime
model, the full catalogue of phases and actions, and the traps that come with
versioning an entity that has a live workflow inside it are all covered in
[workflows.md](workflows.md).

## Layers and modules

The stack is a conventional Sling one. Jackrabbit Oak provides the repository,
using segment/TAR storage by default with MongoDB or PostgreSQL available as
options. Apache Sling runs on Apache Felix, and services are OSGi Declarative
Services components. Assembly uses the Sling Feature Model: every module ships its
own feature file, and a packaging module aggregates them into whatever `start.sh`
launches. The HTTP surface is Sling resource resolution, where a path addresses
content while selectors and extensions choose the representation. On top sits a
React single-page application.

That application is not a monolith with hooks. The page shell, the app bar, the
routed views, and the dashboard widgets are all extension points; a feature
contributes to the interface by shipping a component along with a small content
node that registers it. The consumer of an extension point renders whatever has
been registered without knowing anything about the contributors.

A module, correspondingly, owns a slice of the platform from end to end: its node
types, the models over them, the services that act on them, the frontend code that
presents it, the UI extensions registering that code against the interface, and the
content and configuration it needs in order to exist.

```
modules/<name>/
├── src/main/resources/SLING-INF/nodetypes/<name>.cnd     the node types
├── src/main/resources/SLING-INF/content/libs/<prefix>/   ROOT.json per type
├── src/main/resources/SLING-INF/content/{Extensions,Tags}/
├── src/main/features/feature.json                        its slice of the assembly
├── src/main/frontend/src/<Name>View.tsx                  the frontend code
└── src/main/java/io/uhndata/iap/<name>/
    ├── api/  spi/  models/                               exported: the contract
    └── internal/                                         not exported
```

Packages named `api`, `spi`, and `models` are exported and constitute the module's
contract; `internal` is not, nor is an `impl` submodule where a module has grown
large enough to want one. Larger modules split the two into separate submodules so
that a consumer can depend on the contract without dragging in the implementation.
The conventions for filling that layout in are in
[java-development.md](java-development.md) and
[frontend-development.md](frontend-development.md).

Each domain module also gets one namespace prefix of its own, with URIs of the form
`https://iap.uhndata.io/<prefix>/`, which is what keeps one module's node types
from colliding with another's:

| Prefix   | Covers                     | Prefix   | Covers                   |
|----------|----------------------------|----------|--------------------------|
| `data`   | the base content hierarchy | `cat`    | categories               |
| `sub`    | submissions                | `cond`   | conditions               |
| `sch`    | schemas                    | `del`    | deletion and the archive |
| `wf`     | workflows                  | `doc`    | self-documentation       |
| `tag`    | tags                       | `err`    | error tracking           |
| `link`   | links                      | `ext`    | UI extensions            |
| `app`    | the application shell      | `metric` | metrics                  |

## What is still missing

Federation is not settled yet. The general shape of cross-institution review is
understood and work on it is under way; what takes time is the requirements
themselves, which have to be worked out across several institutions before there is
anything definite to build against. That is the reason for treating references that
would cross an institutional boundary carefully now rather than discovering the
problem later.

The read-side projection is more of a principle than a layer at the moment. The
rule that reads are adapted per user by a service is settled, but there is not yet
a single service through which all reads actually pass — which is a somewhat
uncomfortable position for a design in which a projection bug is a disclosure bug.

Downstream customization, finally, is a convention rather than a mechanism. That
deployments should live in their own repositories and add their own definitions,
branding, and integrations is how the platform is meant to be used; nothing in the
build or the packaging enforces that boundary. Whether the generic vocabulary
holds up will not really be known until a second institution tries to use it. The
time-off-request demo is a rehearsal; federation will be the examination.
