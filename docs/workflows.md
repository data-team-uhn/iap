# Workflows

**Module:** `modules/workflows` · **Bundle:** `iap-workflows` · **API:**
`io.uhndata.iap.workflows.api` (`WorkflowEngine`, `WorkflowEvent`, `WorkflowResult`) ·
**SPI:** `io.uhndata.iap.workflows.spi` (`ServiceTaskHandler`, `WorkflowTaskContext`,
`ExecutionHost`, `Payloads`, `AbstractPropertiesHandler`) ·
**Models:** `io.uhndata.iap.workflows.models`

A workflow is the process a piece of content is put through: who has to look at a
submission, in what order, what may happen while they do, and when it is finished.
Workflows are authored as BPMN 2.0 diagrams, stored in the repository as a graph of
nodes, and executed by creating an *instance* of one and moving tokens through that
graph.

There are two kinds, and the difference is what the process is about. A **content
workflow** is the rules governing what people may do to a piece of content and when — it
persists as an instance while they work through it. A **system workflow** is the
platform's own behavior, with no person waiting in the middle of it. This page describes
the data model, the Sling Models over both, the administration console workflows are
authored in, and the engine that runs them.

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

Each move is a system workflow whose start event is guarded on the version's tags, and which tags it with
`addTag`, replacing the lifecycle tag it had, so a version is never in two places at once. Only a draft may be
edited. Every later lifecycle step indicates a workflow a submission may already be following, so changing its diagram would
change a process out from under whatever is executing it. A trial that needs another look goes
back to being a draft rather than being edited where it stands, while an active or retired version is carried
forward by drafting a copy of it.

A version carrying no lifecycle tag or an unknown one is not assumed to be in a specific state:
it cannot be edited, promoted or instantiated, and the console shows no lifecycle for it.
Of the moves, only **New draft from this** is still offered, which copies
it into a genuine draft. **View** is always allowed.

A version is numbered by the platform's one rule for versions
(`VersionNumbers` in `java-utils`, `versionNumbers` in `frontend-commons`): one past the largest number a
version's node name starts with, so a version discarded from the middle leaves no number for a new one to
take again. Its node is named `v` and that number; what readers see is its `version` label, which the author
chooses, and which defaults to the number (`3.0` after `v2`) — suggested in the console, and applied by the
server when a request names none. Labels take no part in the numbering, being free text, as likely a year as
a number, so a label can say anything to the user.

At most one version of a definition is active at a time, and that is an invariant of the transition rather
than of the node type: promoting a version retires the one it supersedes in the same save, so there is no
moment at which two versions claim to be current. The engine reads the `active` tag off a version's stored
`tags` rather than through the tags service, so which version runs never depends on that service being up.

**A definition has no `active` flag of its own.** Whether a workflow may run is whether one of its versions
is active. Stored as well, the two could disagree, and the stored one would be the side nothing enforces.
Whether it is *retired* is read off its versions the same way — one is retired and none is active, which is
where retiring the active version without a replacement leaves it — and the console works both out from the
versions it lists.

A version keeps both representations of its graph: the `bpmn.xml` it
was authored as, which the visual editor loads and saves, and the flow nodes that XML was parsed into,
which is what the engine reads. `bpmnXmlParsedHash` records the source as of the last successful parse,
so a graph that has fallen behind its diagram can be spotted.

The source is an `nt:file` child rather than a property, so that a diagram can be downloaded and
re-uploaded as the document it is, and so that it does not weigh on every serialization of the version.
It is served at the version's own path — `/Workflows/timeOffRequest/v1/bpmn.xml`.

Writing it is an event rather than a repository write: a diagram is a multipart part named `bpmn.xml` on a
`save` or `createVersion` event, and the handler behind that event decides where it lands — so a version
and the diagram it starts from arrive in one request, in one commit. A `createVersion` event naming a
`source` carries no diagram at all: the new version is a copy of the source, diagram included. See
[Managing workflows](#managing-workflows) for the events themselves. Its on-parent-version is `COPY`, so
checking a version in captures the diagram with it. `WorkflowVersion.getBpmnFile()` hands back the file
rather than its contents, leaving the caller to decide how to read a document of unknown size.

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

Several things exist for the engine rather than for the diagram, derived from the diagram's `iap:*`
extension attributes wherever a version says its BPMN is authoritative, and set by hand elsewhere:

- **`messageName` on an event** is the domain event name it catches or throws, resolved
  from the BPMN `messageRef`, and what an incoming event is matched against.
- **`targetResourceType` on a version**, e.g. `wf/WorkflowsHomepage`, is the resource
  type whose events a system workflow handles. Content workflows need none: they are
  reached through the schema version that references them.
- **A `cond:condition` on a start event** guards it: the workflow starts only when the
  [condition](conditions.md) holds for the event's target. This is how several system
  workflows answer the same message in different states, e.g. `activate` on a draft and
  on a retired schema version. At most one guard may hold at a time: two holding is a
  contradiction between definitions, none is a 409.
- **`performers` on a flow node** names the principals allowed to make execution pass
  through it: who may fire an event, and who may complete a user task. It corresponds to
  BPMN's `potentialOwner` resource role; see [Who is
  allowed](#who-is-allowed-the-workflow-decides).
- **`handler` on an activity** names the service task handler that performs it. An
  activity naming none is a user task, which waits for a person.
- **`outcomes` on an activity** lists the decisions its task may be completed with,
  which a gateway downstream routes on, so that a task list knows what to offer. An
  empty list means there is nothing to decide, only something to do.
- **`hostTag` on an end event** places a tag on the host when a content workflow
  finishes there, retiring whatever tag the host carries in the same category. Elsewhere
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
| Exclusive vs. parallel gateway | Node type | The engine is meant to route one token or all of them |
| Event-based gateway | Node type | It is meant to wait instead of evaluating, unlike every other gateway |
| Boundary vs. free-standing catch | Containment | Same event; only where it is stored differs |
| Terminate vs. ordinary end | Property | Same node, but it is meant to end the instance rather than a branch |

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
/Submissions/proposal-42            sub:Submission        (wf:WorkflowAttachable)
└── wf:instances                    wf:WorkflowInstances  autocreated, IGNORE
    └── review                      wf:WorkflowInstance   workflowVersion, status, startTime, endTime
        ├── t1                      wf:WorkflowToken      currentNodeId
        ├── requestedDays           wf:Variable           dataType, longValue
        └── approve_1               wf:TaskInstance       taskDefinitionId, label, assignee, status,
                                                          outcome, offeredOutcomes, performers
```

A **token** is one branch of an execution and where it has got to. Tokens are the whole
of a workflow's runtime state: an incoming event is only acceptable when a token rests
on a node that catches it.

A **variable** is named by its node name, so looking one up is a child lookup, and its
value lives in the typed property its `dataType` names, so the repository indexes it as
what it is.

A **task instance** is an entity rather than a part of the instance, because people look
for tasks: "what is on my desk" is a query over task instances, not a walk of every
running workflow. Its `status` says the task is over and its `outcome` says how;
gateways route on the outcome. `offeredOutcomes` and `performers` are copied from the
defining activity when the task is raised, so it is decided on the terms it was raised
with, and whoever owes the decision can see them without reading the definition. The
copies describe rather than permit: what makes a completion lawful is still the
definition.

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

**`IGNORE` is load-bearing.** Every `data:Entity` is `mix:versionable`, and so is a
workflow instance. Under the default on-parent-version setting, checking in a submission
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

A `POST` to a resource under workflow control is a domain event, sent to the engine with the request
parameters as its payload (`:`-prefixed ones excluded). The event is the target's default — `create` on a
homepage, `save` on an entity, `complete` on a user task — unless a selector names one:
`POST /Schemas/x/1.0.activate.json` sends `activate`. A POST carrying a Sling `:operation` is refused with
a 400, since it would otherwise arrive as an empty event; remove such a resource with an HTTP `DELETE`.

The types under workflow control are the `targetResourceType` of every system workflow
version, active or not, plus `wf/TaskInstance`. `WorkflowEventServlet` is bound to
exactly those, with any extension, and `WorkflowEventServletRegistrar` rebinds it
whenever `/SystemWorkflows` changes, so a module brings a type under control by shipping
a system workflow for it.

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
| `callActivity` | `message` | Runs the system workflow waiting for that event on the host, with the event's payload, before carrying on |
| `startWorkflow` | `workflowFrom` | Starts the content workflow a chain of references leads to, e.g. `schemaVersion/workflow`, and runs it to its first wait |
| `addTag` | `tag`, `replaceExisting` | Places the tag; with `replaceExisting`, first removes the host's own tags sharing a category with it |
| `removeTag` | `tag` | Removes the tag |
| `copyContent` | `sourceType`, `skipProperties`, `dropTagCategories` (all optional) | Copies what the event's `source` holds into what the execution created, or else the target; without a `source`, does nothing |

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

`copyContent` starts something as a copy of something else, e.g. a schema version from
another, through the `ContentCopier` service (`java-utils`) in the engine's commit.
Names, types, order and binaries are kept; references inside the copy point at the
copies and references outside are kept; protected properties and modification stamps are
left out. `sourceType` refuses any other kind of source, `skipProperties` leaves out
properties of the source node itself, and `dropTagCategories` leaves out its tags in
those categories. A module keeps what it maintains rather than stores out of copies, or
adjusts it, with a `CopyParticipant`: the tags module leaves out computed tags, the
links module the links container, and the conditions module repoints `answer` operands
naming a question by UUID at its copy.

## Managing workflows

Authoring lives in the administration console, under `/admin/workflows`. A URL there carries the whole
repository path of what is being looked at, and names the page in its query only when the page needs
naming, so one set of pages serves the workflows of any homepage — this location's, the platform's own, a
later one's:

| URL | Page |
|---|---|
| `/admin/workflows` | Redirects to `/admin/workflows/Workflows`, the default homepage's listing |
| `/admin/workflows/SystemWorkflows` | The workflows stored in one homepage, a tab per homepage beside it |
| `/admin/workflows/Workflows/review` | One workflow: its properties, and its versions with their actions |
| `/admin/workflows/Workflows/review/v2` | That version's diagram, read-only |
| `/admin/workflows/Workflows/review/v2.edit` | The same diagram, editable — drafts only |

Each screen offers the way to the others: a draft being looked at offers **Edit**, and the editor offers
**Save**, **Save and view**, and **Save and close** — the same save, differing only in where it leaves the
user afterwards. A save the engine refuses navigates nowhere, since leaving would take the only copy of
what was drawn with it.

**Every prefix down to the homepage is a page in its own right**, which is the whole point of the shape:
dropping a segment moves up to the thing that contained what was being looked at, so a breadcrumb built by
cutting the URL down leads somewhere at every step.

The price of carrying a repository path is that the URL does not say which of the three things it is about.
A homepage is found by node type wherever it is, so a path is of no predictable depth: `/Workflows/review`
and `/Content/Workflows/review` are both a workflow, and counting segments from the root would read the
second as a version of `/Content/Workflows`. **Depth is therefore counted from the homepage**, which is the
only fixed point — below one it is always homepage, workflow, version — so resolving a console URL takes the
list of homepages this instance has. That list is the one the tabs are built from —
`GET /Workflows.homepages.json`, described below — fetched once and kept while the page lives, so it costs a
request when the console is first opened and nothing on any navigation after it.

Two things fall out of counting rather than keyword-matching. A version named `edit` is read as itself:
nothing in a path is ever a page, so no name below a homepage is reserved. And a URL that places nowhere —
a tree that is not a homepage here, more segments than a version can account for — is said to name nothing,
rather than being handed to a page that would render an empty workflow for it.

What this buys the breadcrumbs above the page: every step of a console URL is a page, so each crumb is a
link that leads somewhere. The pages name those steps themselves, with `usePageCrumbs`, the way a schema
version's page names its schema: a workflow's page adds the homepage it is stored in, under that homepage's
own title, and a version's page adds the homepage and then the workflow, under the workflow's title, so a
trail reads `Administration / Workflows / Time off requests`. The pages are headed the way a schema's are,
too: a workflow's by its title, with whether it runs beside it, and a version's by its label after its
workflow's title, with its lifecycle beside it and its description under it.

The console therefore registers a single view, `/admin/workflows/*`, which mounts the page that works out
what a URL addresses. A splat could never name a crumb anyway — it claims every path beneath it, and would
label each step alike — and the trail skips it, so the console's root, which redirects to the default
homepage's listing, is routed without becoming a step in the trail. Naming the steps from the pages rather
than from a view per depth is also what reads a homepage stored deeper than one segment correctly:
counted from the root, `/Content/Workflows` would have been labelled a workflow. **Every URL renders the
same page** for the same reason: a route may only end in a splat, so no pattern can pick out a page that
comes *after* a path of unknown length, and what each URL actually addresses is worked out once, by the
page the view mounts.

Two things about that split are load-bearing. **The read-only view is a different bpmn-js class**, a
`NavigatedViewer` rather than a `Modeler`: it can pan and zoom and has no palette, no context pad and no
editing behaviours, so a version instances are following is not an editor being trusted to behave. And
**editing is refused for anything but a draft**, in the page as well as in the URL: an active version is
what running instances are following, a retired one is what the instances that outlived it are still
following, and a trial is being tried as it stands, so changing any of them would alter a process out from
under the things reading it. A trial is changed by being returned to a draft; an active or retired version
is carried forward by drafting a copy, which is offered next to it.

The buttons are contributed on extension points rather than written into the pages: a
workflow's own — edit its properties, open a new version — on **`WorkflowActions`** (`wf/workflow/actions`),
shown beside its title, and each version's on **`WorkflowVersionActions`**, shown in its row and beside its
title on its own page while it is only being looked at. Six ship with the module — edit, start-trial, activate, return-to-draft,
retire, and draft-a-copy — each offered exactly where the server offers its event: on the version, or for
a copy, `createVersion` on the workflow it is a version of; another
needs an `ext:Extension` and an asset, which only its module's `assets.config` can have built: an extension
naming an asset that was not built is dropped without a word. The point is addressed by two names, as every extension point is: the page asks for the
node, `/apps/iap/ExtensionPoints/WorkflowVersionActions`, and an extension declares the
`ext:pointId` that node carries, `wf/workflowVersion/actions`.

**Every one of these actions is a system workflow, not a write.** Creating a workflow, opening a version of one,
renaming it, saving a diagram, and each of the four lifecycle moves are domain events posted at the thing
they concern, matched to a system workflow under `/SystemWorkflows` and run to an end event in one commit.
Nothing in this UI writes a node.

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

Drafting a copy of a version is `createVersion` on its workflow, with the version's path as `source`:
`createWorkflowVersion` creates the version, has `copyContent` copy the source into it, keeping the new version's
own label, and tags it a draft in place of wherever the source stood.

A POST with no selector means the target's *default* event, which follows from what it is: `create` at an
entity homepage, `save` at an entity, `complete` at a user task. Everything else names its event outright.
That rule is the resource type hierarchy's rather than a list of paths, so a homepage a later module adds
comes under it without the servlet learning about it.

**A diagram is parsed wherever it is saved.** `BpmnXmlSyncEditor` asks a root child what it *holds* rather
than what it is called: both homepages autocreate a protected `childNodeType = wf:WorkflowDefinition`, so
`/SystemWorkflows` and any homepage a deployment adds are covered, and a tree holding anything else is
walked straight past. That is the same question `GET /Workflows.homepages.json` answers to decide which tabs
the manager shows, so a tree that can be listed is exactly a tree whose diagrams are parsed — one answer
rather than two that could drift apart. A tree the editor skipped would store diagrams and derive no flow
nodes from them, leaving versions that look authored with no graph the engine can run.

Everything below a workflow homepage is reached by *node type* rather than by path — `wf/WorkflowDefinition`
and `wf/WorkflowVersion` — so the same requests manage a system workflow and a user one. The two
homepages differ only in that each has its own `create` definition: a version's `targetResourceType` names
one type, and the only type both homepages share is the one every entity homepage shares, which would have
had `/Submissions` catching it too. Two definitions is also the more useful answer — adding a process a
deployment runs and adding behavior the platform performs on its own are different acts, and each names its
own performers.

**Binding a resource type to the event servlet is what closes the direct-CRUD door, and forgetting one is
silent.** An unbound POST does not 404: it reaches the Sling POST servlet, which does exactly what it says —
a `title` sent to an unbound homepage sets that property *on the homepage* rather than being refused. A
bound type with no definition waiting answers a clean 409 instead, which is why anything a workflow is meant
to manage belongs in `resourceTypes` whether or not its definitions exist yet.

Three things this buys, none of which an endpoint could:

- **Who may do each of these is one property, in the file that says what it does.** `performers` on the
  start event, editable per deployment. That is why there are four lifecycle definitions rather than one
  that moves a version anywhere — a single one could only ever say who may change a lifecycle *at all*,
  where separate definitions can say that an author may return their own trial to a draft while only an
  administrator may activate one.
- **The lifecycle table is content.** Each move's start event says which lifecycle tags it applies to, and
  its `addTag` step what the version becomes; a fifth tag is a new definition rather than a new row in Java.
  A move a version is past the moment for is not offered on it, and refused with a 409 if sent anyway.
- **What each action does can grow without touching the platform.** A validation step before a version is
  opened, a notification when one is activated: another service task on the definition.

Three of them are more than one write, which is the reason the run commits once:

- **Activating** is `retireActiveVersions` then `addTag active`. Retiring the outgoing version in a
  second request would leave a window in which two versions of one workflow both claim to be current, and
  a client that failed between the two would leave it that way for good. As two steps of one run there is
  no moment at which the invariant does not hold, and a promotion that cannot complete retires nothing.
  `activateWorkflowVersion`'s own version is never offered `retire`: its `protectedFrom` lists the event,
  which `retireWorkflowVersion`'s guard excludes, because retired it would leave nothing able to activate
  anything again. It leaves `active` only when another version of it is activated.
- **Creating a workflow** is `createEntity`, then a `callActivity` sending `createVersion` to what it
  created: a first version is made exactly the way every later one is,
  and since the called workflow runs in the same commit, a workflow and its first draft arrive together and a
  failure part-way leaves neither. The request carries the title, the first version's label and description,
  and its starting diagram, and the call hands all of it on. What the request is answered with is the
  workflow, which is what it created; its first version is listed there, ready to be edited.
- **Opening or drafting a version** stores its diagram in the same run — carried as a `bpmn.xml` payload
  part when a version is opened, copied from the source when one is drafted — so the version node and its
  diagram arrive together or neither does. Posting directly cannot do that: Sling creates the node a file
  part's path implies before it applies `jcr:primaryType`, so a combined write leaves a `sling:Folder`
  behind and the diagram has to follow in a second request.

`bpmnAuthoritative` says whether a version's diagram owns its flow nodes. `BpmnXmlSyncEditor` reparses the
diagram of a version that says so whenever its bytes change, replacing the graph; a version that does not
keeps its graph as it was authored, whatever diagram arrives, because the translation from BPMN cannot yet
carry everything such a graph holds. A version opened empty is marked authoritative on creation: it starts
from the diagram the request brought and has no hand-written graph for a reparse to throw away, so its
diagram is the only thing its flow nodes could come from.

A copy takes the flag from its source with everything else, and `bpmnXmlParsedHash` with it, which records
the bytes the graph was last parsed from. The copied diagram, graph and hash agree, so the commit editor
leaves the copied graph as it is rather than parsing the same bytes into the same graph again; and a
hand-written graph stays hand-written, where a flag set on creation would have had it replaced, in the very
commit that copies it, by whatever its diagram parses to.

Saving a workflow's own properties goes through `saveProperties`, which writes only what the activity's
`editable` list names and refuses what its `required` list says must arrive with a value. That listing is
the whole of the safety: without it the handler would be an open write to whatever a caller cared to name,
`jcr:primaryType` included, which is exactly the direct-CRUD door these workflows replace. It also means a
deployment that wants the description editable adds a word to a definition rather than shipping code.

Listing covers every homepage, one at a time. `GET /Workflows.homepages.json` answers with every entity
homepage holding `wf:WorkflowDefinition` entities **that the caller can read** — a homepage they may not
read is simply absent, so the list describes what this user may list rather than what exists — and the
page gives each of them a tab bearing its name, listing one at a time. Every listing is then a plain query
over one tree, so paging, sorting and the total belong to a homepage rather than to a union of them, only
the tab being looked at is fetched, and a page of workflows always says where its workflows are stored.
Nothing on either side hardcodes which trees a deployment has.

Which tab is open is in the URL, and the answer to this question is what reads it, so the two cannot
disagree about what a homepage is: a tab is a page, its path is a prefix of every workflow URL below it,
and the console resolves that prefix against this same list. It is asked for once and kept while the page
lives, so the depth of a console URL is worked out without a request; a homepage added meanwhile appears
after a reload. A failed ask is not kept: the default homepage stands in, the console says what failed and
offers to retry, and the next ask goes to the server again.

The dashboard widget asks the same question and shows only the answer's size: one count per homepage,
fetched as a page of no rows at all (`.paginate.json?offset=0&limit=0`), with the frame's "Manage
workflows" action leading to the page that lists them. A grid does not fit a dashboard frame; a count
does.

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
- **Groups are matched transitively**, so a group also admits its member groups'
  members.
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
in the `handler` property, and the activity's other properties are that handler's
configuration. This is the extension point that lets a project plug its own behavior into
a workflow without touching the platform. Handlers write through the context's resolver
and never commit — the engine owns the transaction — and communicate through execution
variables (`context.setVariable(...)`), which is also how results reach the channel that
fired the event. Two calls ask the engine for more within the same execution and commit:
`sendEvent` runs the system workflow waiting for an event, and `startWorkflow` starts an
instance of a workflow on a resource. The built-in `callActivity` and `startWorkflow`
handlers are thin over them.

The two helpers are what keeps a module's own handlers consistent with the built-in ones,
which live privately in `internal.handlers`. `ExecutionHost.of` is what a task acts on:
what an earlier step of the same run created, or else the target, which is how
`createEntity` followed by `addTag` tags the entity it just made. `Payloads` reads one
payload entry: text trimmed, with blank counted as absent, or an uploaded file.

A handler that writes properties onto its target extends `AbstractPropertiesHandler`,
saying which properties the activity allows, what each may hold and what the request
asks for. The base checks the whole request before writing anything, removes a property
sent empty unless it is mandatory, and stores a reference as a `REFERENCE`.
`saveProperties` and the schemas' `updateSchemaContent` both work this way.

The first built-in handler is `createEntity`: create a node of the configured
`entityType` under the target, named by camel-casing the payload's `title`, dodging
collisions with a numeric suffix, and report the created path in the `createdPath`
variable — which is what the servlet turns into a redirect.

### The bootstrap: creating workflows is itself a workflow

`/SystemWorkflows/createWorkflow` ships with the platform: a `create`-catching message
start event, a `createEntity` service task configured with `entityType =
wf:WorkflowDefinition`, a call activity sending the new workflow `createVersion` for its
first draft, an end event. Its version declares `targetResourceType =
wf/WorkflowsHomepage`, which is how the engine knows it answers for POSTs to
`/Workflows`.

Because it is content, not code, a deployment can change what happens when a workflow is
requested — add a validation step, a notification — by editing this definition rather
than the platform. That is the point of doing it this way, and it is why the definition
ships with its version tagged `active`, and editable rather than being hardwired into the servlet.

**Everything else that authors a workflow works the same way**, which is what makes that
claim more than a demonstration: `createSystemWorkflow`, `createWorkflowVersion`,
`saveWorkflow`, `saveWorkflowDiagram`, `activateWorkflowVersion`, `startWorkflowVersionTrial`,
`returnWorkflowVersionToDraft` and `retireWorkflowVersion` all ship beside it, over four handlers of
their own — `createWorkflowVersion`, `saveProperties`, `saveWorkflowDiagram` and
`retireActiveVersions` — plus `createEntity`, shared with the bootstrap, `callActivity`
for the first version of a workflow, `copyContent` for a version drafted from another,
and `addTag` for every move in the lifecycle. The workflow
module manages its own content the way it asks every other module to manage theirs, and
the management UI holds no privileged path of its own. See [Managing
workflows](#managing-workflows) for the request each one answers.

`/Submissions` works the same way, showing the intended division of labor:
`/SystemWorkflows/createSubmission` and its `createSubmission` handler ship with the
*submissions* module, which contributes the system workflows for its own homepage
through the handler SPI exactly as a project would. That handler refuses to create a
submission from an inactive version, and sets its `schemaVersion` as a real JCR
REFERENCE.

`/SystemWorkflows/saveAnswers` targets `sub/Submission` itself, so filling a request in
— a `POST` to `<submission>.save.json` — is a workflow event like any other. Whose
request it is, and whether it is still a draft, is decided by its handler.

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
creates the task; a `complete` event aimed at the task closes it, records the outcome,
and carries the instance on. Who may complete it is decided by the `performers` of its
defining activity.

**A task can be given a deadline.** A boundary timer with a `timerDuration` is armed
when the task is raised: the deadline is recorded on the task, as `dueDate` with
`dueEventId` naming the timer, so overdue work can be found without the engine and the
deadline survives a restart. A sweep every five minutes (`DueTimers.DEFAULT_SCHEDULE`)
hands each overdue task to `receiveEvent` as an ordinary `timeout` event, delivered as
the `iap-timer` service user. An interrupting timer cancels the task, with no assignee
and no outcome, and execution leaves down the timer's own arc: that is how a process
says what running out of time means.

The timer is asked who may fire it, like any other node, and not the task: deciding the
work is not the same as declaring it late. A timer whose `performers` is empty admits
`iap-timer`, so by default only the clock fires it. A `timeout` arriving before the
deadline is refused as a conflict, whoever sends it.

A `timerDuration` is an ISO-8601 duration, years and months included, counted in the
server's calendar from when the task started. The sweep runs on the cluster's leader
only. A task closed since the sweep found it is skipped. A delivery that fails is
recorded and counted on the task as `deliveryFailures`; after three, the sweep leaves
the task alone and records that it gave up.

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
- **Nothing delivers a message.** An instance reaching a *free-standing* catching event
  is refused rather than parked, since nothing could wake it: the engine's door opens
  onto a homepage or a task.
- **Read access is never revoked.** It lasts for the life of the instance rather than
  while a task is open.
- **A gateway's guards can only ask about the execution.** They are evaluated against
  the instance, so the `variable` operand reaches what the run knows, and nothing yet
  reaches the host, which routing on a request's own answers would need.
- **A BPMN `conditionExpression` is refused, not translated.** The arc is created
  without it and the problem is recorded; a guard is written as a `cond:condition` in
  the arc's `extensionElements`.
- **Only `timeDuration` timers.** A `timeCycle` or `timeDate` timer is imported with no
  duration and never fires.
- **Widening a `performers` list on a workflow-authoring definition needs an ACL to
  match.** Sling resolves the posted-to resource before dispatching, and the only read
  granted under `/Workflows` is the homepage node itself, restricted by node type — so a
  non-administrator named as a performer of `saveWorkflow` or `activateWorkflowVersion` would
  get a 404 from the resolver rather than the engine's own answer, because the
  definition or version they posted to is invisible to them. Administrators bypass
  access control, which is why the shipped definitions (all `iap-administrators`) work
  as they stand. Widening any of them means granting `jcr:read` on
  `wf:WorkflowDefinition`/`wf:WorkflowVersion` nodes too, and that is a deliberate
  visibility decision rather than a mechanical one — which is why it is not done
  pre-emptively here.
- **"At most one active version" is enforced by the workflow, not by the repository.**
  Activating retires the outgoing version in the same commit, and the engine tolerates
  finding two actives (a promotion retires all of them). Nothing *else* can now tag a
  version — the direct-write door is closed, since no user holds rights on this content and
  the only way in is the definitions — but a service user or a repoinit script still could,
  and so could a definition that tagged a version `active` without retiring the one before
  it. A commit editor, the way `BpmnXmlSyncEditor` guards the parsed graph, is the way to
  close that last gap if it ever matters.
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
- **No `oak:index` definitions.** The console's listings and the homepage discovery both
  query without one.
