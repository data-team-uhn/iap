# Workflows

**Module:** `modules/workflows` · **Bundle:** `iap-workflows` · **API:**
`io.uhndata.iap.workflows.api` (`WorkflowEngine`, `WorkflowEvent`, `WorkflowResult`) ·
**SPI:** `io.uhndata.iap.workflows.spi` (`ServiceTaskHandler`, `WorkflowTaskContext`,
`ExecutionHost`, `Payloads`, `AbstractPropertiesHandler`, `ValidityCheck`) · **Models:**
`io.uhndata.iap.workflows.models`

A workflow is the process a piece of content is put through: who has to look at a
submission, in what order, what may happen while they do, and when it is finished.
Workflows are authored as BPMN 2.0 diagrams, stored in the repository as a graph of
nodes, and executed by creating an *instance* of one and moving tokens through that
graph.

There are two kinds. A **content workflow** is the rules governing what people may do to
a piece of content and when; it persists as an instance while they work through it. A
**system workflow** is the platform's own behavior, with no person waiting in the middle
of it. This page covers the data model, the Sling Models, the administration console
workflows are authored in, and the engine that runs them.

## The four trees

| Path                | Holds                                                                         |
|---------------------|-------------------------------------------------------------------------------|
| `/Workflows`        | Content workflows: the business processes content is put through, as definitions, their versions, and the parsed graphs |
| `/SystemWorkflows`  | The workflows that are the platform's own behavior, not a user process        |
| `/WorkflowTypes`    | The vocabulary: what kinds of node exist at all                               |
| inside the resource | Runtime instances, in the `wf:instances` container of whatever they drive     |

### Definitions and versions

```
/Workflows                         wf:WorkflowsHomepage
└── timeOffRequest                 wf:WorkflowDefinition   title
    └── v1                         wf:WorkflowVersion      version, tags, bpmnXmlParsedHash,
                                                           targetResourceType
        ├── bpmn.xml               nt:file                 the BPMN 2.0 source
        ├── start_1                wf:StartEvent           elementId, label, flowNodeType
        │   └── flow_1             wf:SequenceFlow         elementId, targetRef
        ├── approve                wf:Activity
        │   ├── flow_2             wf:SequenceFlow
        │   └── timeout            wf:IntermediateCatchingEvent  elementId, interrupting
        └── end_1                  wf:EndEvent             terminate
```

A definition holds versions, and everything that runs, runs against a specific version.
Where a version stands is its tag in the `lifecycle` category:

| Tag | What it means | Editable | Moves to |
|---|---|---|---|
| `draft` | Still being authored, and never instantiated | yes | `trial`, `active` |
| `trial` | Being tried out before the workflow commits to it; still not what instances are created from | no | `draft`, `active` |
| `active` | The one version new instances are created from | no | `retired` (withdrawn, or by a promotion in its place) |
| `retired` | Superseded or withdrawn: the instances already running carry on, no new ones start | no | `active` (retiring whichever version is active) |

Each move is a system workflow whose start event is guarded on the version's tags, and
which retags it with `addTag` and `replaceExisting`, so a version is never in two states
at once. Only a draft may be edited, since every later state is one a submission may
already be following. A trial that needs changes returns to being a draft; an active or
retired version is carried forward by drafting a copy of it. A version with no lifecycle
tag, or an unknown one, is in no state: it cannot be edited, promoted or instantiated,
and only **View** and **New draft from this** are offered on it.

Versions are numbered by `VersionNumbers` (`java-utils`, and `versionNumbers` in
`frontend-commons`): one past the largest number a version's node name starts with, so a
number freed by discarding a version is never reused. The node is named `v` and that
number. Readers see the `version` label, free text the author chooses, which defaults to
the next number (`3.0` after `v2`) — suggested by the console, and applied by the server
when a request names none.

At most one version of a definition is active: promoting a version retires the one it
supersedes in the same commit. The engine reads the `active` tag from a version's stored
`tags` rather than through the tags service, so which version runs never depends on that
service. **A definition has no `active` flag of its own**: it runs if one of its
versions is active, and is retired if one is retired and none is active. A stored flag
could disagree with its versions.

A version keeps both representations of its graph: the `bpmn.xml` it was authored as,
which the visual editor loads and saves, and the flow nodes it was parsed into, which
the engine reads. `bpmnXmlParsedHash` records the source as of the last successful
parse, so a graph that has fallen behind its diagram can be spotted.

The source is an `nt:file` child served at the version's own path,
`/Workflows/timeOffRequest/v1/bpmn.xml`, so a diagram can be downloaded and re-uploaded
as a document and does not weigh on every serialization of the version. It is written by
an event rather than a repository write: a multipart part named `bpmn.xml` on a `save`
or `createVersion` event, so a version and its diagram arrive in one commit. A
`createVersion` naming a `source` carries no diagram, since the new version is a copy of
the source, diagram included; see [Managing workflows](#managing-workflows). Its
on-parent-version is `COPY`, so checking a version in captures the diagram.
`WorkflowVersion.getBpmnFile()` hands back the file rather than its contents, leaving
the caller to decide how to read a document of unknown size.

The graph that XML parses into is stored alongside it, as flow nodes under the version:

- **Arcs are stored inside the node they leave**, so walking forwards never needs a
  query. Walking backwards, which a join gateway does, is a scan of the version by
  `FlowNode.getIncomingFlows()` — cheap at the size of a workflow, and it keeps a single
  representation of each arc.
- **Arcs name their target by `elementId`**, as BPMN does, so a version's graph is
  self-contained and can be copied, exported or re-parsed without rewriting identifiers.
  `WorkflowVersion.getFlowNode(elementId)` resolves them, boundary events included.

A **boundary event** is a `wf:IntermediateCatchingEvent` stored *inside* the activity it
watches; the same node type directly under the version is an ordinary mid-process catch,
and `IntermediateCatchingEvent.getActivity()` tells the two apart. Its `interrupting`
flag, parsed from BPMN's `cancelActivity` and true by default, decides whether it
cancels the activity or [runs beside it](#more-than-one-branch-at-once); a free-standing
event ignores it.

### What an executable graph carries

Several properties exist for the engine rather than for the diagram. Where a version's
diagram is [authoritative](#managing-workflows) they are parsed from its `iap:`
extension attributes (namespace `https://iap.uhndata.io/bpmn`); elsewhere they are set
by hand.

- **`messageName` on an event** is the domain event name it catches or throws, resolved
  from the BPMN `messageRef`, and what an incoming event is matched against.
- **`targetResourceType` on a version**, e.g. `wf/WorkflowsHomepage`, is the resource
  type whose events a system workflow handles. Content workflows need none: they are
  reached through the schema version that references them.
- **A `cond:condition` on a start event** guards it: the workflow starts only when the
  [condition](conditions.md) holds for the event's target. This is how several system
  workflows answer the same message in different states, e.g. `activate` on a draft and
  on a retired version. At most one guard may hold at a time: two holding is a
  contradiction between definitions, none is a 409.
- **`performers` on a flow node** names the principals allowed to make execution pass
  through it: who may fire an event, and who may complete a user task. It corresponds to
  BPMN's `potentialOwner` resource role; see [Who is
  allowed](#who-is-allowed-the-workflow-decides).
- **`handler` on an activity** names the service task handler that performs it. An
  activity naming none is a user task, which waits for a person.
- **`outcomeOptions` on an activity** lists the decisions its task may be completed
  with, which a gateway downstream routes on, so that a task list knows what to offer.
  An empty list means there is nothing to decide, only something to do.
- **`hostTag` on an end event** places a tag on the host when a content workflow
  finishes there, retiring the host's tags that share any of its categories. Elsewhere
  in a process, the `addTag` and `removeTag` service tasks do the same job.

### The vocabulary

`/WorkflowTypes` is the translation table between BPMN and the repository. Each
`wf:FlowNodeType` says that a given XML element means a given kind of node, and is
shipped as a file named after the entry — `MessageStartEvent.json`:

```json
{
  "jcr:primaryType": "wf:CatchingEventType",
  "label": "Message Start Event",
  "category": ["Start Events"],
  "priority": 10,
  "xmlElement": "bpmn:startEvent",
  "xmlChildElement": "bpmn:messageEventDefinition",
  "jcrNodeType": "wf:StartEvent"
}
```

Parsing matches each element against every entry and keeps the highest-`priority` match,
which stops a start event carrying a message definition from being read as a plain one.
`jcrProperties` sets fixed properties on the stored node, which is how a terminate end
event and an ordinary one share `wf:EndEvent`. It is only for what varies between
entries sharing a node type: what the node type already determines, such as whether an
event is `catching`, is autocreated and protected there instead, so the two cannot
disagree.

**Adding a kind of node is normally a vocabulary entry, not a node type.** A user task
and a service task are both plain `wf:Activity` nodes, told apart by the entry they
point at. Only distinctions the engine has to make structurally get a node type of their
own:

| Distinction | Where it lives | Why |
|---|---|---|
| User task vs. service task | Vocabulary | Both are work to be done; only the doer differs |
| Timer vs. message start event | Vocabulary | Both start the workflow; only the trigger differs |
| Exclusive vs. parallel gateway | Node type | The engine routes one token or all of them |
| Event-based gateway | Node type | It waits instead of evaluating, unlike every other gateway |
| Boundary vs. free-standing catch | Containment | Same event; only where it is stored differs |
| Terminate vs. ordinary end | Property | Same node, but it ends the instance rather than a branch |

### Self-documentation

`/WorkflowTypes` carries `doc:Documented`, so its catalogue is served at
`/WorkflowTypes.doc.json` and `/WorkflowTypes.doc.md` ([autodoc](autodoc.md)). The
visual BPMN editor builds its toolbars from that JSON, grouped by each entry's
`category`, with the `xmlElement`/`xmlChildElement` and `jcrNodeType` each one stands
for. **The shape of that output is a contract** the editor depends on.

### Runtime

A workflow lives **inside the thing it drives**, so it is found, secured and deleted
along with it:

```
/Submissions/ab/cd/ef/abcdef12-…    sub:Submission        (wf:WorkflowAttachable)
└── wf:instances                    wf:WorkflowInstances  autocreated, IGNORE
    └── review                      wf:WorkflowInstance   workflowVersion, status, startTime, endTime
        ├── t1                      wf:WorkflowToken      currentNodeId
        ├── requestedDays           wf:Variable           dataType, longValue
        └── approve_1               wf:TaskInstance       taskDefinitionId, label, assignee, status,
                                                          outcome, outcomeOptions, performers
```

A **token** is one branch of an execution and where it has got to. Tokens are the whole
of a workflow's runtime state: an incoming event is only acceptable when a token rests
on a node that catches it.

A **variable** is named by its node name, so looking one up is a child lookup, and its
value lives in the typed property its `dataType` names, so the repository indexes it as
what it is.

An instance is a **part** of what it drives, and a task a part of its instance: neither
exists without its owner, and nothing references, versions or identifies one. That is
what lets a guard read the host, since a condition walks from an instance or a task to
its enclosing entity. A **task instance** still has a node type of its own, because
"what is on my desk" is a query over task instances, not a walk of every running
workflow. Its `status` says the task is over and its `outcome` says how; gateways route
on the outcome.

Anything workflows can run over carries the `wf:WorkflowAttachable` mixin, which
autocreates the container:

```
[sub:Submission] > data:Entity, wf:WorkflowAttachable

[wf:WorkflowAttachable]
  mixin
  + wf:instances (wf:WorkflowInstances) = wf:WorkflowInstances AUTOCREATED IGNORE
```

which gives `Submission.getWorkflowInstances()` — a list, since one thing may have
several workflows running over it at once.

**`IGNORE` is load-bearing.** Every `data:Entity` is `mix:versionable`, and a workflow
lives inside one. Under the default on-parent-version setting, checking in a submission
would copy its live workflow into version storage, and restoring an earlier revision
would roll the workflow back with it: an editor reverting a typo would quietly
un-approve a proposal.

Living inside the resource has three consequences:

- **One ACL surface.** Workflow state inherits the submission's permissions. Hiding
  assignees, variables or deadlines from the submitter needs a restriction on the
  container, which is `rep:AccessControllable` for that purpose.
- **The engine writes as the `workflows` service user**, since it moves tokens for
  people who often have only read access to the submission.
- **Deleting the submission deletes its workflows.** A record that must outlive it
  belongs in [history](history.md).

### Events over HTTP

A `POST` to a resource under workflow control is a domain event, with the request
parameters as its payload (`:`-prefixed ones excluded). The event is the target's
default — `create` on a homepage, `save` on an entity, `complete` on a user task —
unless a selector names one: `POST /Schemas/x/1.0.activate.json` sends `activate`. The
default follows the resource type hierarchy rather than a list of paths, so a homepage a
later module adds is covered without the servlet learning about it. A POST carrying a
Sling `:operation` is refused with a 400, since it would otherwise arrive as an empty
event; remove such a resource with an HTTP `DELETE`.

The types under workflow control are the `targetResourceType` of every system workflow
version, active or not, plus `wf/TaskInstance`. `WorkflowEventServlet` is bound to
exactly those, with any extension, and `WorkflowEventServletRegistrar` rebinds it
whenever `/SystemWorkflows` changes, so a module brings a type under control by shipping
a system workflow for it.

**An unbound type is open to direct writes, silently.** A POST to a type the servlet is
not bound to does not 404: it reaches the Sling POST servlet, which does what it is told
— a `title` sent to an unbound homepage sets that property on the homepage. A bound type
with no definition waiting answers a clean 409 instead, so anything a workflow is meant
to manage needs a system workflow for its type whether or not its real definitions exist
yet.

The `.import` extension bypasses the engine, forwarding the request to the Sling POST
servlet. The repository still decides who may write, and on content the engine manages
only an administrator can, so it serves for importing content by hand, as
`tools/dev/test-data/generate-test-data.sh` does with `POST /Schemas.import`.

### Asking without sending

The engine answers two questions about an event without it being sent, deciding exactly
as sending it would:

- **`getAvailableEvents(resource)`** — which events the asking user could send to the
  resource in its current state. On a task, `complete` while it is open and its
  `performers` admit the user; anywhere else, the message of every system start event
  for the resource's type whose guard holds and whose `performers` admit them. Over HTTP
  it is the `events` serialization processor, off by default: `GET
  /Schemas.1.simple.events.json` adds `@events` to the homepage and to each schema, so a
  listing learns its rows' actions in one request.
- **`findApplicableWorkflow(resource, event)`** — which system workflow would handle an
  event, to read what sending it would do. It is chosen through the engine's own
  session, since a guard may read content the user cannot, and returned through the
  user's. `null` means nothing would take the event from this user.

Available means the engine would take the event, not that it will succeed: the payload
can still be invalid, and a step can still refuse. Two workflows competing for one event
make either question fail with a `WorkflowDefinitionException`.

### Built-in service tasks

A few handlers are the engine's own, because what they do is generic. Each acts on what
the execution has created, once it has created something, and on the target otherwise.

| `handler` | Configuration | Does |
| --- | --- | --- |
| `createEntity` | `entityType` | Creates an entity of that type under the target, named by camel-casing the event's `title` with a numeric suffix on collision, and reports its path in `createdPath`, which the servlet turns into a redirect |
| `callActivity` | `message` | Runs the system workflow waiting for that event on the host, before carrying on |
| `startWorkflow` | `workflowFrom` | Starts the content workflow a chain of references leads to, and runs it to its first wait |
| `addTag` | `tag`, `replaceExisting` | Places the tag; with `replaceExisting`, in place of the host's own tags sharing any of its categories |
| `removeTag` | `tag` | Removes the tag |
| `checkValid` | — | Refuses the event with every problem the applicable `ValidityCheck`s report |
| `updateContent` | `fields` | Applies the event's `patch` to the listed fields |
| `createContent` | `types`, `nameFrom`, `namePattern`, `nameHint` | Creates content of the event's `type` in the target |
| `moveContent` | `within` | Moves the target into the event's `parent`, or reorders it within its own |
| `renameContent` | `namePattern` | Renames the target to the event's `name` |
| `replaceContent` | `child`, `nodeTypes` | Replaces a child with the tree the event gives as `content` |
| `copyContent` | `sourceType`, `skipProperties`, `dropTagCategories` | Copies what the event's `source` holds |
| `addLink` | `linkType`, `to`, `linkLabel` | Links to the content the event names in its `to` entry |

A call activity, BPMN's `bpmn:callActivity`, hands work on to another workflow and waits
for it to finish, by sending the event named in its `message` to the host with the
triggering event's payload. That is how system workflows build on each other:
`createSchema` creates a schema and sends it `createVersion`, whose own workflow creates
the first version and tags it. The called workflow runs in the same JCR session and
commit, so either both happen or neither does. Its event is matched, guarded and
authorized as if the user had sent it, and the caller is still answered with what the
calling workflow created. Calls nest at most `MAX_SENT_EVENTS_DEPTH` (10) deep.

`startWorkflow` is the equivalent for content workflows. It starts an instance on a
newly created host, inside the calling workflow, and runs it until it first has to wait:
at a user task, or at an end event if nothing needs a person. `workflowFrom` names the
workflow version as a chain of reference properties from the host — for a submission,
`schemaVersion/workflow`, its schema version and then that version's workflow. Naming
the chain rather than hard-coding it keeps the workflows module from knowing what a
submission is. A chain that breaks off or does not end on a workflow version starts
nothing, which is not an error; a missing `workflowFrom`, an inactive version, or one
without exactly one start event is a definition error.

The tag tasks make a lifecycle content: a transition is a guarded event followed by an
`addTag` with `replaceExisting`. They may place and remove `system` tags, and touch only
tags placed on the host itself, never inherited or computed ones.

`checkValid` asks every `ValidityCheck` (`io.uhndata.iap.workflows.spi`) that applies to
the target what would stop it moving on, and refuses with all of it at once. Activating
or trialling a workflow version runs it, and so does publishing a schema version. The
workflow checks require exactly one start event, and of a system workflow a
`targetResourceType`, a named message start and no user task. A module protects what it
adds by registering another check.

### Editing content

`updateContent` changes content from the JSON object in the event's `patch`: a key left
out is left alone, `null` or blank removes the property, and anything else is its new
value — a list for a field holding several, and the path of the target node for a
reference. Only fields the activity lists may change, and of those only fields the
target's node type declares by name, not through a residual definition. The declaration
says whether a field is mandatory, single or multiple, and whether it holds text, a
whole number, a number, a boolean or a reference; the patch must match, and a field of
any other type cannot be edited. One activity can therefore serve several types, each
keeping to its own fields.

Each listed field may carry a `label`, a short `help`, `multiline`, and for a reference
the `referenceType` it must point at and the `referenceRoot` it must be under, plus
three rules the patch is held to:

- **`choices`**: the values the field may take, as child nodes named by their value (or
  giving it as `value`), each with an optional `label`.
- **`appliesWhen`**: `{ "property": "dataType", "values": ["long", "double"] }`, the
  field applying only while that other property holds one of those values, as the patch
  leaves it. Setting a field that does not apply is refused, and a field that stops
  applying loses its value unless it is mandatory.
- **`unique`**: no two siblings of the same type may hold the same value in the field.

```json
"fields": {
  "jcr:primaryType": "nt:unstructured",
  "dataType": {
    "label": "Answer type",
    "choices": { "text": { "label": "Text" }, "long": { "label": "Whole number" } }
  },
  "minValue": {
    "label": "Lowest value",
    "appliesWhen": { "property": "dataType", "values": ["long", "double"] }
  }
}
```

The whole patch is checked before anything is written. The `fields` serialization adds
`@fields` to content an update would change: the fields the requesting user's `update`
would accept there, with everything the activity says about them and what their
declarations say (`kind`, `multiple`, `mandatory`, `default`), read from the activity of
the workflow that would run. `FieldsDialog` (`frontend-commons`) is the editor built on
it, applying the same `appliesWhen` as values change.

A workflow version may carry a `notice` for the people who send it events, as its
`description` is for whoever maintains it. The `fields` serialization adds the notice of
the update that would run as `@notice`, so where guarded updates split a lifecycle a
page shows whichever applies without knowing the states:

```json
"v1": {
  "jcr:primaryType": "wf:WorkflowVersion",
  "notice": "Only the wording of this version can be corrected. Anything else needs a new version.",
  ...
}
```

`createContent` adds content of the event's `type` inside the target. The type must be
one the activity lists and one the target's type declares it holds, through a child
definition naming that type (`+ * (sch:FormItem)`); the catch-alls every `sling:Folder`
inherits, and definitions requiring no more than `nt:base`, only tolerate children. The
new node takes the `name` the event asks for, which must be free and allowed, and is
otherwise named from the first words, without accents, of the first `nameFrom` field the
patch gives, falling back on its type's name. `namePattern` is the rule a name must
match and `nameHint` says it in words; a listed type may give its own of each, and one
listed with `named` false refuses any name asked for. A listed type with no `nodeType`,
or one that does not exist, is offered nowhere and recorded as an error. New content
starts with only what its type sets itself; an `updateContent` after it fills it in from
the same patch, and refuses a patch leaving out a mandatory field its type gives no
default.

The `creatable` serialization adds `@creatable` to content a `create` would add to: each
type the requesting user could create there, with its `label`, its `fields` as in
`@fields` including their defaults, the `defaultName` it takes when nothing else names
it, whether it is `named`, and its `namePattern` and `nameHint`. The frontend's
`suggestName` makes names from text the way `createContent` does.

`moveContent` moves the target, with everything under it, into the event's `parent`, or
within its own parent when there is none, so the same task moves and reorders. It takes
a free name when its own is taken at the destination. It refuses a parent that does not
hold the target's type (by the same rule as `createContent`), a move into itself, and,
with `within` naming a resource type, a move out of the nearest ancestor of that type,
such as the schema version a question belongs to.

`renameContent` gives the target the event's `name`, keeping its place among its
siblings. The name must be valid and match `namePattern` when there is one, and a name a
sibling already has is refused rather than replaced by a free one. Both moves and
renames go through the `ContentMover` service (`java-utils`), which first lets each
`MoveParticipant` update whatever names the content by path: the conditions module turns
`answer` operands naming a moved question by path into ones naming it by identifier. The
new path is what later steps act on, and what the event is answered with.

Creates and moves place content before the sibling named in `before`, or else last.
`before` names a sibling, never a path; the order is the parent's, reported by the
`order` serialization (see [json-serialization.md](json-serialization.md)).

`replaceContent` writes a structured child, such as a condition, in one step. The
event's `content` is a JSON tree — properties as keys, children as objects named by
their keys, each node's type in `jcr:primaryType` — and `null` removes the child. Each
node's type must be concrete and listed in `nodeTypes`, or a subtype of a listed one,
and its parent must be able to hold that type under that name, by the parent's child
definitions for that name, or else for any name. The tree must give every mandatory
property and child its types do not create themselves; a child a type creates, such as a
condition's `operandB`, is replaced by the tree's when it gives one. Values are stored
as JSON gives them — text, whole numbers, decimals, booleans, or a list of one kind —
and converted when a declaration requires another type, such as a date. The whole tree
is checked before it replaces what was there.

`copyContent` starts something as a copy of something else, e.g. a schema version from
another, through the `ContentCopier` service (`java-utils`) in the engine's commit.
Names, types, order and binaries are kept; references inside the copy point at the
copies and references outside are kept; protected properties and modification stamps are
left out. `sourceType` refuses any other kind of source, `skipProperties` leaves out
properties of the source node itself, and `dropTagCategories` leaves out its tags in
those categories. A module keeps what it maintains rather than stores out of copies, or
adjusts it, with a `CopyParticipant`: the tags module leaves out computed tags, the
links module the links container, and the conditions module repoints `answer` operands
naming a question by UUID at its copy. Without a `source`, it does nothing.

`addLink` links what the execution acts on to the content the event names in its `to`
entry, with a link of `linkType`; an event naming nothing links nothing. That is how a
copy says where it came from: `createSchemaVersion` links a version made as a copy to
its source with a `copiedFrom` link, a type the [links](links.md) module ships. The
links container is never copied, so a copy has only its own links.

## Managing workflows

Workflows are authored in the administration console, under `/admin/workflows`. A URL
there carries the repository path of what is being looked at, so one set of pages serves
the workflows of any homepage:

| URL | Page |
|---|---|
| `/admin/workflows` | Redirects to `/admin/workflows/Workflows`, the default homepage's listing |
| `/admin/workflows/SystemWorkflows` | The workflows stored in one homepage, a tab per homepage beside it |
| `/admin/workflows/Workflows/review` | One workflow: its properties, and its versions with their actions |
| `/admin/workflows/Workflows/review/v2` | That version's diagram, read-only |
| `/admin/workflows/Workflows/review/v2.edit` | The same diagram, editable — drafts only |

**Every prefix down to the homepage is a page**, so dropping a segment moves up to what
contained it. Since homepages are found by node type at any depth, a path's meaning is
counted from its homepage — below one it is always homepage, workflow, version — so
resolving a URL needs the list of homepages, which `GET /Workflows.homepages.json` gives
and the console fetches once per page load. Nothing below a homepage is a reserved name,
so a version named `edit` is read as itself, and a URL that places nowhere names
nothing.

The console registers a single view, `/admin/workflows/*`, whose page works out what a
URL addresses and names its breadcrumbs with `usePageCrumbs`: a trail reads
`Administration / Workflows / Time off requests`. A workflow's page is headed by its
title and whether it runs; a version's by its label after its workflow's title, its
lifecycle and its description.

**The read-only view is a different bpmn-js class**, a `NavigatedViewer` rather than a
`Modeler`: it pans and zooms with no palette, context pad or editing behaviour.
**Editing is refused for anything but a draft**, in the page as well as in the URL. The
editor offers **Save**, **Save and view** and **Save and close**, the same save
differing only in where it leaves the user; a save the engine refuses navigates nowhere,
since leaving would lose what was drawn.

The buttons are extensions, not part of the pages: a workflow's own on
**`WorkflowActions`** (`wf/workflow/actions`), beside its title, and each version's on
**`WorkflowVersionActions`** (`wf/workflowVersion/actions`), in its row and beside its
title on its own page. Six ship with the module — edit, start trial, activate, return to
draft, retire, and draft a copy — each offered exactly where the server offers its
event. Another needs an `ext:Extension` and an asset built by its own module's
`assets.config`; an extension naming an asset that was never built is dropped without a
word.

**Every action is a system workflow, not a write.** Each is a domain event posted at the
thing it concerns and run to an end event in one commit, so the console writes no node
itself:

| Request | Event | Definition |
|---|---|---|
| `POST /Workflows.create.json` | `create` | `createWorkflow` |
| `POST /SystemWorkflows.create.json` | `create` | `createSystemWorkflow` |
| `POST <workflow>` | `save` | `saveWorkflow` |
| `POST <workflow>.createVersion.json` | `createVersion` | `createWorkflowVersion` |
| `POST <version>` | `save` | `saveWorkflowDiagram` |
| `POST <version>.activate.json` | `activate` | `activateWorkflowVersion` |
| `POST <version>.startTrial.json` | `startTrial` | `startWorkflowVersionTrial` |
| `POST <version>.returnToDraft.json` | `returnToDraft` | `returnWorkflowVersionToDraft` |
| `POST <version>.retire.json` | `retire` | `retireWorkflowVersion` |

Drafting a copy is `createVersion` on the workflow with the version's path as `source`:
`createWorkflowVersion` creates the version, `copyContent` copies the source into it,
keeping the new version's own label, and it is tagged a draft.

Below a homepage, everything is reached by node type — `wf/WorkflowDefinition` and
`wf/WorkflowVersion` — so the same requests manage system and content workflows. The two
homepages differ only in having their own `create` definition, since a version's
`targetResourceType` names one type and the only type both homepages share is shared by
every entity homepage, `/Submissions` included.

Doing this through definitions buys three things:

- **Who may do each action is `performers` on its start event**, editable per
  deployment. That is why the lifecycle has four definitions rather than one: separate
  ones can let an author return their own trial to draft while only an administrator
  activates.
- **The lifecycle is content.** Each move's guard says which tags it applies to, and its
  `addTag` what the version becomes; a new state is a new definition, not Java. A move a
  version is past is not offered, and refused with a 409 if sent anyway.
- **Each action can grow without touching the platform**: a validation step, a
  notification, another service task on the definition.

Three actions are more than one write, which is why a run commits once:

- **Activating** is `checkValid`, `retireActiveVersions`, then `addTag active`, so no
  moment exists at which two versions both claim to be current.
  `activateWorkflowVersion`'s own version lists `retire` in `protectedFrom`, which
  `retireWorkflowVersion`'s guard excludes: retired, it would leave nothing able to
  activate anything again.
- **Creating a workflow** is `createEntity`, then a `callActivity` sending
  `createVersion` to it, so a first version is made exactly as every later one, and the
  workflow and its first draft arrive together or not at all. The request carries the
  title and the first version's label, description and diagram; it is answered with the
  workflow.
- **Opening or drafting a version** stores its diagram in the same run, as a `bpmn.xml`
  payload part or copied from the source. A direct POST could not: Sling creates the
  node a file part's path implies before applying `jcr:primaryType`, so a combined write
  leaves a `sling:Folder` behind.

**A diagram is parsed wherever it is saved.** `BpmnXmlSyncEditor` recognizes a workflow
tree by what its root holds rather than by its name: every workflow homepage autocreates
a protected `childNodeType = wf:WorkflowDefinition`, so `/SystemWorkflows` and any
homepage a deployment adds are covered. `GET /Workflows.homepages.json` asks the same
question to choose the console's tabs, so a tree that can be listed is exactly a tree
whose diagrams are parsed.

`bpmnAuthoritative` says whether a version's diagram owns its flow nodes. The editor
reparses an authoritative version's diagram whenever its bytes change, replacing the
graph; a version that is not keeps its hand-written graph whatever diagram arrives,
because BPMN cannot yet carry everything such a graph holds. A version opened empty is
authoritative from creation, since its diagram is the only thing its flow nodes could
come from. A copy takes the flag and `bpmnXmlParsedHash` from its source, so the editor
leaves a copied graph as it is rather than reparsing it, and a hand-written graph stays
hand-written.

Saving a workflow's own properties goes through `saveProperties`, which writes only what
the activity's `editable` list names and refuses what its `required` list says must
arrive with a value. That listing is the whole of its safety: without it the handler
would write whatever a caller named, `jcr:primaryType` included.

`GET /Workflows.homepages.json` lists every entity homepage holding
`wf:WorkflowDefinition` entities **that the caller can read**, and the console gives
each a tab, listing one at a time — so each listing is a plain paged query over one
tree, and nothing hardcodes which trees a deployment has. The open tab is part of the
URL. The list is fetched once and kept while the page lives, so a homepage added
meanwhile appears after a reload; a failed fetch is not kept, the default homepage
stands in, and the console offers to retry.

The dashboard widget shows one count per homepage, read from `GET
/Workflows.adminSummary.json` — the generic entity homepage summary, which answers for
every readable peer of the homepage asked — with a "Manage workflows" action leading to
the listing.

## Sling Models

Everything above is reachable as Sling Models in `io.uhndata.iap.workflows.models`, so
callers never touch the repository directly:

```java
WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);
for (StartEvent start : version.getStartEvents()) {
    FlowNode next = start.getOutgoingFlows().get(0).getTarget();
}

FlowNode resting = token.getCurrentNode();   // instance → version → node
Activity raisedFrom = task.getDefinition();
```

The abstract bases — `FlowNode`, `Event`, `IntermediateEvent`, `Gateway`, `FlowNodeType`
— are not registered models. Each concrete subtype lists the bases it answers for in the
`adapters` of its own `@Model`, so `adaptTo(FlowNode.class)` yields the actual subtype.
That dispatch runs on the Sling resource type hierarchy, so **a new `wf:` node type
needs a `/libs/wf/<Type>/ROOT.json`** naming its parent, or it quietly stops matching.

## The engine, and system workflows

A *system workflow* is ordinary workflow content stored under `/SystemWorkflows`, and
that location is what makes it one: it describes something the platform does on its own
behalf, like turning a POST to `/Workflows` into a new workflow definition.

```
HTTP POST /Workflows ──▶ WorkflowEventServlet ──▶ WorkflowEngine.receiveEvent(target, event)
                          (a deliberately dumb        │  find the one system workflow whose message
                           translator: builds the     │  start event catches this event on this target
                           event a selector names,    │  walk it: start ─ service tasks ─ end
                           or the target's default)   ▼  one commit at the end
                                              302 Location: /Workflows/<created>
```

Everything goes through the engine's single entry point, whatever channel the event
arrived on:

```java
@NotNull WorkflowResult receiveEvent(Resource target, WorkflowEvent event)
    throws WorkflowException;
```

Receiving an event answers four questions in order, each failure its own exception,
which the servlet maps to a status:

| Question | Failure | Status |
|---|---|---|
| Is anything waiting for this event here? | `NoApplicableWorkflowException` | **409** |
| May this user fire it? | `NotAuthorizedException` | **403** |
| Does the target's state allow it now? | `InvalidStateException` | **409** |
| Is what it carries usable? | `InvalidPayloadException` | **400** |
| — | `WorkflowDefinitionException`, `WorkflowFailedException` | **500** |

### Who is allowed: the workflow decides

**Nobody holds rights on the content workflows manage.** There is no ACL granting users
write access to `/Workflows` or `/Submissions`: the engine reads and writes as its own
service user, and what a user may do is what the definitions say, through one mechanism
only.

A flow node names the principals it admits in `performers`:

```json
"requested": {
  "jcr:primaryType": "wf:StartEvent",
  "messageName": "create",
  "performers": ["iap-administrators"]
}
```

That is the whole answer to "who can create a workflow", editable per deployment. The
shipped bootstraps admit `iap-administrators` for `/Workflows` and `everyone` for
`/Submissions`. The rules:

- **An empty or absent list admits nobody.** Silence is never permission. The one
  exception is a boundary timer, whose empty list admits the `iap-timer` service user.
- **`everyone` means any authenticated user**, matched by name since it is a dynamic
  principal.
- **`@creator` means whoever raised the resource being worked on** — the event's target,
  or the host of the task being completed — as recorded in `createdBy`. Where nothing
  recorded one, such as on a homepage, it falls back to `jcr:createdBy`, the account
  that wrote the node.
- **Every other name is resolved by the principals service**, so a group admits its
  members transitively however a deployment stores it, including a role an identity
  provider synchronises with no group node.
- **Administrators pass regardless**, as they bypass access control in the repository,
  so one bad definition cannot lock out the people who could repair it.

Because the engine is privileged, *nothing downstream refuses an actor who passed the
check*: a handler that wants to treat something as forbidden has to say so itself. An
access denial from the repository means the engine's own service user is short of rights
— a deployment fault, and a **500**.

`jcr:createdBy` on what the engine creates names the service user, so the human is
recorded separately, in `createdBy`.

Users get `jcr:read` on the homepage nodes `/Workflows` and `/Submissions` themselves,
restricted by node type so nothing below is included, because Sling resolves the
posted-to resource before dispatching: an invisible `/Submissions` would answer 404
before any workflow could decide. `/SystemWorkflows` gets no such grant.

**System workflows run to quiescence inside the request and persist no instance.** So a
system workflow must be *straight-through*: no user tasks, no mid-process catching
events, exactly one arc out of every node it passes. A definition that would have to
wait is rejected as broken (**500**). Everything a run changes lands in one commit, so
an event either fully happens or does not happen at all.

### Service tasks and the handler SPI

```java
// ServiceTaskHandler
@NotNull String getName();
        void   execute(WorkflowTaskContext context)
                   throws WorkflowException, PersistenceException;

// WorkflowTaskContext — what a handler is given
Resource         getTarget();
String           getActor();
WorkflowEvent    getEvent();
Activity         getActivity();
Object           getVariable(String name);
void             setVariable(String name, Object value);
ResourceResolver getResourceResolver();
void             sendEvent(Resource target, WorkflowEvent event);
void             startWorkflow(Resource host, WorkflowVersion version);

// ExecutionHost, Payloads — reading a task's input the way the built-in handlers do
static Resource        ExecutionHost.of(WorkflowTaskContext context);
static String          Payloads.text(WorkflowEvent event, String name);
static String          Payloads.requireText(WorkflowEvent event, String name, String complaint);
static EventAttachment Payloads.attachment(WorkflowEvent event, String name);
```

Service tasks are implemented as a `ServiceTaskHandler`: the activity names its handler
in `handler`, and its other properties are the handler's configuration. This is how a
project plugs its own behavior into a workflow without touching the platform. Handlers
write through the context's resolver and never commit — the engine owns the transaction
— and pass results through execution variables (`context.setVariable(...)`), which is
also how they reach the channel that fired the event. `sendEvent` and `startWorkflow`
ask the engine for more within the same execution and commit; the built-in
`callActivity` and `startWorkflow` handlers are thin over them.

Two helpers keep a module's handlers consistent with the built-in ones in
`internal.handlers`. `ExecutionHost.of` is what a task acts on: what an earlier step of
the run created, or else the target, which is how `createEntity` followed by `addTag`
tags the entity it just made. `Payloads` reads one payload entry: text trimmed, with
blank counted as absent, or an uploaded file.

A handler that writes properties onto its target extends `AbstractPropertiesHandler`,
saying which properties the activity allows, what each may hold, and what the request
asks for. The base checks the whole request before writing anything, removes a property
sent empty unless it is mandatory, and stores a reference as a `REFERENCE`.
`saveProperties` works this way.

### The bootstrap: creating workflows is itself a workflow

`/SystemWorkflows/createWorkflow` ships with the platform: a `create`-catching message
start event, a `createEntity` service task with `entityType = wf:WorkflowDefinition`, a
call activity sending the new workflow `createVersion` for its first draft, and an end
event, its version declaring `targetResourceType = wf/WorkflowsHomepage`. Because it is
content, a deployment can change what requesting a workflow does — add a validation
step, a notification — by editing it.

**Everything else that authors a workflow works the same way.** `createSystemWorkflow`,
`createWorkflowVersion`, `saveWorkflow`, `saveWorkflowDiagram`,
`activateWorkflowVersion`, `startWorkflowVersionTrial`, `returnWorkflowVersionToDraft`
and `retireWorkflowVersion` ship beside it, over four handlers of their own —
`createWorkflowVersion`, `saveProperties`, `saveWorkflowDiagram` and
`retireActiveVersions` — plus the built-in ones. The workflows module manages its own
content the way it asks every other module to, and the console has no privileged path of
its own.

`/Submissions` works the same way: `/SystemWorkflows/createSubmission` and its
`createSubmission` handler ship with the *submissions* module, which contributes the
system workflows for its own homepage through the handler SPI exactly as a project
would. That handler refuses to create a submission from an inactive version, names it
by a UUID filed in the homepage's prefix tree, and sets its `schemaVersion` and `schema`
as real JCR REFERENCEs. `/Submissions/by-id/<uuid>` reaches a submission without knowing
its buckets.

`/SystemWorkflows/saveAnswers` targets `sub/Submission` itself, so filling a request in
— a `POST` to `<submission>.save.json` — is a workflow event like any other. Its handler
decides whose request it is and whether it is still a draft, and refuses a value for a
question the submitter is not shown, judged after the save's own answers are written: a
question the same save reveals is accepted, and clearing an answer always is.

The save then runs three more steps. `validateAnswers` asks every `AnswerValidator` (an
SPI in `submissions-api`) whether the answers, as they now stand, may be saved, and the
first to object refuses the whole save with a 400 carrying its reason. One of them
judges the constraints a question carries by asking every `AnswerConstraint`, another
SPI, in ascending `service.ranking`: the shipped ones check the answer count (10),
that a value is one the question offers (15), the data type of a number (20), its
bounds (30) and a pattern (40), so a bundle adding its own picks the ranking that puts
it where it belongs. `createParts`
asks every `PartCreator` to give each applicable item a part of its own — an answer for
every question shown, a document for every document requirement that applies — adding
what is missing and never removing, so an answer whose question stops applying keeps its
value. `markCompleteness` asks every `CompletenessEvaluator` to judge its own kind of
part, and places the aggregated `incomplete` system tag on the parts reported, removing
it from the rest, so the submission carries it while any part does. An answer is
incomplete while it holds fewer than `minAnswers` non-blank values, a required document
while nothing is uploaded into it; an approval never counts, since somebody else gives
it. An applicable item with no part is a fault: the evaluator records it and reports the
submission itself. The create and attach workflows run the last two, so a new submission
starts with its parts and marks. Detaching removes every version of a document and
leaves it empty as its requirement's part, and the detach workflow marks the submission
again.

## Content workflows: the part that persists

A content workflow outlives the request, because the next thing to happen is a person
doing something. It persists as a `wf:WorkflowInstance` inside the resource it drives,
with a `wf:WorkflowToken` for each branch in progress and a `wf:TaskInstance` for each
thing somebody still owes.

Running one is always the same walk, from wherever a token rests through whatever can be
passed automatically, until it has to stop. A walk that has not settled within
`MAX_STEPS` (200) steps is taken to be going round a cycle with nowhere to wait, and
fails as a definition error:

```
POST /Submissions ──▶ createSubmission ──▶ startWorkflow ──▶ [instance created, walked to its first wait]
                                                                        │
                                             wf:instances/timeOffRequest │  token parked on approveRequest
                                                                        ▼
POST …/approveRequest {outcome} ──▶ complete ──▶ [task closed, gateway routed, end event reached]
                                                        │
                                                        ▼  hostTag: the submission is now "approved"
```

**A user task is an activity with no handler.** The engine parks the token there and
raises a task, copying the activity's `outcomeOptions` and its `performers` onto it,
with `@creator` already answered against the host, so whoever owes the decision can see
them without reading the definition. The copies describe the task; what makes a
completion lawful is still the definition. A task offering outcomes cannot be completed
without one of them, and a task offering none takes no outcome. A `complete` event aimed
at the task closes it, records the outcome, and carries the instance on.

**A decision can be written down as a review.** A `recordReview` service task placed
directly after the user task that decides records a `sub:Review` of the approval
requirement its `requirement` names: by whoever completed the task, tagged with the
task's `outcome`, with their `outcomeNote` as a comment. It reads the decision from that
completion, so nothing may come between the two, neither a join nor a timer. The outcome
has to name a state a review can be in, such as `approved` or `rejected`, and a
completion without one is refused.

**Submitting is a user task.** A process hands a request back to its author with a task
performed by `@creator`, and completing that task sends it on; there is no submit event
or endpoint. A submission's page shows a button for each task the reader may complete,
as `@events` lists them, labelled with the task's label, leaving out tasks that offer
outcomes.

**A task can be given a deadline.** A boundary timer with a `timerDuration` — an
ISO-8601 duration, years and months included, counted in the server's calendar from when
the task started — is armed when the task is raised: the deadline is recorded on the
task, as `dueDate` with `dueEventId` naming the timer, so overdue work can be found
without the engine and the deadline survives a restart. A sweep every five minutes
(`DueTimers.DEFAULT_SCHEDULE`), on the cluster's leader only, hands each overdue task to
`receiveEvent` as an ordinary `timeout` event, delivered as the `iap-timer` service
user. An interrupting timer cancels the task, with no assignee and no outcome, and
execution leaves down the timer's own arc: that is how a process says what running out
of time means.

The timer, not the task, is asked who may fire it, since deciding the work is not the
same as declaring it late; a timer with no `performers` admits only `iap-timer`. A
`timeout` arriving before the deadline is refused as a conflict, whoever sends it. A
task closed since the sweep found it is skipped. A failed delivery is counted on the
task as `deliveryFailures`, and after `MAX_DELIVERY_FAILURES` (3) the sweep leaves the
task alone and records that it gave up.

**Read access is materialized when the instance starts.** Acting is authorized by the
definitions, but reading cannot be, since no engine can run a workflow per query row. So
the engine writes an ACL granting read to the person the instance runs for and to the
performers of every user task in the version — derived from `performers`, so the two
cannot disagree.

### More than one branch at once

An instance holds a token per branch in progress, so the walk is a queue of positions
rather than a single path.

**A parallel gateway forks and joins.** Leaving one takes every arc: the arriving token
moves onto the first and a new one is created for each of the rest. With several arcs
leading in it is a join, holding arriving tokens until one has come from every arc, then
merging one per arc into one. A token records the arc it arrived by as `arrivedBy`, so a
second token down the same arc waits for the next round instead of standing in for a
missing branch. A condition on one of its arcs is a definition error, since a parallel
gateway takes every arc regardless.

A parallel join placed after a fork that did *not* take every branch — an exclusive or
inclusive one — waits for a token that was never created, and the instance stays active
with nothing able to move it. Merge conditionally taken branches with an inclusive join
instead.

**An inclusive gateway forks as widely as applies:** every arc whose condition holds,
and every other arc with no condition, falling back on the default only when nothing
applies; with no default either, the diagram is in error. Its join cannot count
arrivals, since how many branches the fork took is recorded nowhere, so it asks whether
any other token in the instance can still reach it by following the graph, boundary
events included. The walk re-checks parked joins once every branch has stopped moving,
and because the answer comes from the graph rather than from the fork, it holds for an
instance resumed days later.

**An exclusive gateway takes one arc:** the first whose condition holds, else the
default. One with a single unconditioned way out is a merge, and passes a token straight
through.

**Two events on one instance cannot both commit.** Every event aimed at a task stamps
its instance with a new `walkId`, so two requests moving branches of one instance at
once conflict, and the loser is reverted and run again on top of the winner. Without
this, two branches reaching a join in two requests would each see only itself arrive.

**An end event ends a branch, not the process.** The instance closes when its last token
is spent. A `terminate` end event discards every remaining token and cancels every task
still waiting.

**A non-interrupting boundary event runs beside the work.** With `interrupting` set to
`false`, it leaves the task where it was and starts a second branch: "remind them after
three days" as against "give up after five". Fired deadlines are recorded on the task as
`firedEvents`, so none is delivered twice, and arming picks the earliest unfired timer
measured from when the task started: "remind after a day and a half, give up after five
days" means five days from the start.

Tokens are interchangeable, so two branches arriving at the same task mean two tasks,
each completed on its own.

## Known gaps

- **One `outcome` per instance.** Every completed task overwrites it, so a gateway after
  a join routes on whichever branch finished last.
- **Instance variables are not exposed to handlers.** `outcome` persists as a
  `wf:Variable`, but a service task inside an instance sees variables that last only for
  that delivery.
- **Nothing delivers a message**, and event-based gateways are not executed. An instance
  reaching a free-standing catching event is refused rather than parked, since nothing
  could wake it: the engine's door opens onto a homepage or a task.
- **Read access is never revoked.** It lasts for the life of the instance rather than
  while a task is open.
- **A gateway's guards cannot name a question by its path.** They are evaluated against
  the instance, a part of its host, so `variable` reaches what the run knows and `tags`
  and `property` read the host; but an `answer` operand resolves a relative question
  path against its own definition, and a workflow holds no questions, so routing on a
  request's answers needs the question's identifier.
- **A BPMN `conditionExpression` is refused, not translated.** The arc is created
  without it and the problem recorded; a guard is written as a `cond:condition` in the
  arc's `extensionElements`.
- **Only `timeDuration` timers.** A `timeCycle` or `timeDate` timer is imported with no
  duration and never fires.
- **Widening `performers` on a workflow-authoring definition needs an ACL to match.**
  The only read granted under `/Workflows` is the homepage node itself, so a
  non-administrator named as a performer of `saveWorkflow` or `activateWorkflowVersion`
  would get a 404 from the resolver before the engine could answer. The shipped
  definitions admit only `iap-administrators`, who bypass access control. Widening one
  means granting `jcr:read` on `wf:WorkflowDefinition` and `wf:WorkflowVersion` nodes, a
  visibility decision deliberately left to deployments.
- **"At most one active version" is enforced by the workflow, not the repository.**
  Activating retires the outgoing version in the same commit, and the engine tolerates
  finding two actives. A service user, a repoinit script, or a definition that tags a
  version `active` without retiring the previous one could still create two; a commit
  editor, as `BpmnXmlSyncEditor` guards the parsed graph, would close that.
- **`performers` is a principal list, not a condition.** "Only if the schema they name
  belongs to their institution" would be a condition evaluated alongside the list, which
  stays enumerable for deciding which buttons to render.
- **No lanes.** `performers` is set per node rather than derived from a lane, and
  mapping lanes onto groups touches how `ApprovalRequirement.approverGroup` works.
- **No subprocesses or multi-instance markers.** "One review per assigned reviewer" is a
  multi-instance activity, the first of these likely to be wanted.
- **Signal, escalation, conditional and link events** are unmapped; the vocabulary
  covers timer, message, error and terminate.
- **The `bpmn:` prefix is matched literally**, not by namespace URI: safe while every
  diagram comes from the in-app editor, but `bpmn2:` or a default namespace would not be
  recognized.
- **No `oak:index` definitions.** The console's listings and homepage discovery both
  query without one.
