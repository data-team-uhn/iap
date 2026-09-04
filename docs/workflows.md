# Workflows

A workflow is the process something is put through: who has to look at a submission, in what order, what
may happen while they do, and when it is finished. Workflows are authored as BPMN 2.0 diagrams, stored in
the repository as a graph of nodes, and executed by creating an *instance* of one and moving tokens
through that graph.

This page describes the data model, the Sling Models over it, the administration console workflows are
authored in, and the engine that runs both kinds of workflow: the *system* ones that are the platform's own
behavior, and the *user* ones that persist as instances while people work through them.

## The four trees

| Path                | Holds                                                                         |
|---------------------|-------------------------------------------------------------------------------|
| `/Workflows`        | The workflows themselves — definitions, their versions, and the parsed graphs |
| `/SystemWorkflows`  | The workflows that are the platform's own behavior, not a user process        |
| `/WorkflowTypes`    | The vocabulary: what kinds of node exist at all                               |
| inside the resource | Runtime instances, in the `wf:instances` container of whatever they drive     |

### Definitions and versions

```
/Workflows                         wf:WorkflowsHomepage
└── timeOffRequest                 wf:WorkflowDefinition   title
    └── 1.0                        wf:WorkflowVersion      version, state, bpmnXmlParsedHash,
                                                           targetResourceType
        ├── bpmn.xml               nt:file                 the BPMN 2.0 source
        ├── start_1                wf:StartEvent           elementId, label, flowNodeType
        │   └── flow_1             wf:SequenceFlow         elementId, targetRef
        ├── approve                wf:Activity
        │   ├── flow_2             wf:SequenceFlow
        │   └── timeout            wf:IntermediateCatchingEvent  elementId, interrupting
        └── end_1                  wf:EndEvent             terminate
```

A definition holds versions, and everything that runs, runs against a specific version — the same split
as a schema and its schema versions. Each version carries a `state`, which is its whole lifecycle:

| `state` | What it means | Editable | Moves to |
|---|---|---|---|
| `DRAFT` | Still being authored, and never instantiated | yes | `TRIAL`, `ACTIVE` |
| `TRIAL` | Being tried out before the workflow commits to it; still not what instances are created from | no | `DRAFT`, `ACTIVE` |
| `ACTIVE` | The one version new instances are created from | no | — (retired by a promotion in its place) |
| `RETIRED` | Superseded: the instances already running carry on, no new ones start | no | — (carried forward by drafting a copy) |

Only a draft may be edited, and that is enforced rather than merely offered: the `saveWorkflowDiagram`
handler refuses a diagram for anything else. Every later state is one something may be following, or about
to follow, so changing its diagram would change a process out from under whatever is executing it — which
is why a trial that needs another look goes back to being a draft rather than being edited where it stands,
while an active or retired version is carried forward by drafting a copy of it.

At most one version of a definition is active at a time, and that is an invariant of the transition rather
than of the node type: promoting a version retires the one it supersedes in the same save, so there is no
moment at which two versions claim to be current.

**A definition has no `active` flag of its own.** Whether a workflow may run is whether one of its versions
is active, and `WorkflowDefinition.isActive()` computes exactly that. Stored as well, the two could
disagree, and the stored one would be the side nothing enforces.

A version keeps both representations of its graph: the `bpmn.xml` it
was authored as, which the visual editor loads and saves, and the flow nodes that XML was parsed into,
which is what the engine reads. `bpmnXmlParsedHash` records the source as of the last successful parse,
so a graph that has fallen behind its diagram can be spotted.

The source is an `nt:file` child rather than a property, so that a diagram can be downloaded and
re-uploaded as the document it is, and so that it does not weigh on every serialization of the version.
It is served at the version's own path — `/Workflows/timeOffRequest/1.0/bpmn.xml` — and the extension is
load-bearing: Sling types a file from its name, so an extensionless one would be served as an untyped
binary, both when shipped by a bundle and when downloaded from the repository. It costs nothing, since a
version with no diagram yet still answers that path with a plain 404 — nothing renders a
`wf:WorkflowVersion` as `xml`.

Writing it is an event rather than a repository write: a diagram is a multipart part named `bpmn.xml` on a
`save` or `createVersion` event, and the handler behind that event decides where it lands — so a version
and the diagram it starts from arrive in one request, in one commit. A `draft` event carries no diagram at
all: it copies the one the version it is drafted from holds. See
[Managing workflows](#managing-workflows) for the events themselves. Its on-parent-version is `COPY`, so
checking a version in captures the diagram with it. `WorkflowVersion.getBpmnFile()` hands back the file
rather than its contents, leaving the caller to decide how to read a document of unknown size.

Two things about how the graph is addressed:

- **Arcs are stored inside the node they leave.** A node's outgoing arcs are simply its children, so
  walking forwards never needs a query. Walking backwards — which a join gateway has to do, since it is
  defined by waiting on all of its incoming arcs — is a scan of the version, done by
  `FlowNode.getIncomingFlows()`. That is the right trade at this size: a workflow has tens of nodes, and
  one representation of each arc cannot disagree with itself the way two would.
- **Arcs name their target by `elementId`, not by reference.** That is how BPMN addresses itself, and it
  keeps a version's graph self-contained: it can be copied, exported or re-parsed without rewriting
  identifiers. `WorkflowVersion.getFlowNode(elementId)` resolves them, boundary events included.

Boundary events are the one place the tree is not flat: an event watching an activity is stored *inside*
that activity, because it only listens for as long as the activity runs. There is no separate node type
for one — a `wf:IntermediateCatchingEvent` nested in an activity **is** a boundary event, and the same
node type standing directly under the version is an ordinary mid-process catch. Being stored there is
the whole of the distinction, so an event does not have to be modelled twice to be usable in both
positions. `IntermediateCatchingEvent.getActivity()` reports which case a given node is.

Whether it **interrupts** that activity is the difference between "give up after five days" and "send a
reminder after five days but keep waiting" — two quite different processes that are otherwise drawn
identically, so the flag is not decoration. It is parsed from BPMN's `cancelActivity`, whose default is
likewise true, and it is meaningful only on an attached event; on a free-standing one it is ignored.

### What an executable graph carries

Six properties exist for the engine rather than for the diagram, derived from the diagram's `iap:*`
extension attributes wherever a version says its BPMN is authoritative, and set by hand elsewhere:

- **`messageName` on an event** is the domain event name it catches or throws, resolved from the BPMN
  `messageRef`. It is what an incoming event is matched against.
- **`targetResourceType` on a version**, e.g. `wf/WorkflowsHomepage`, is the resource type whose events
  that version handles, which is how a workflow describing the platform's own behavior is found. User
  workflows need none: they are reached through the schema version that references them.
- **`performers` on a flow node** names the principals allowed to make execution pass through it — who
  may fire an event, and who may complete a user task. This is where authorization lives, because nobody
  holds repository rights on the content a workflow manages: the engine writes as its own service user,
  once the definition has said the actor belongs here. So an absent or empty list admits *nobody*,
  deliberately — a definition that forgot to say who may use it should refuse everyone until it does,
  and silence is never permission. The built-in `everyone` group means any authenticated user. It
  corresponds to BPMN's potentialOwner resource role, and to the lane a node sits in when the diagram is
  drawn with lanes.
- **`handler` on an activity** names the service task handler that performs it. An activity naming none
  is a user task: nothing can perform it automatically, so it waits for a person.
- **`outcomeOptions` on an activity** lists the decisions that person may complete the task with — the values a
  gateway downstream then routes on. Declared because a task list has to know what to offer: the only
  other record of which outcomes exist is the `cond:condition` on some later gateway's arcs, which
  is where they are *consumed* rather than announced, and which whoever does the task cannot necessarily
  read. An empty list is a statement rather than a gap — this is a task there is nothing to decide about,
  done or not done.
- **`hostTag` on a flow node** is the tag to place on the host when execution reaches that node: how a
  process says what being *here* means to the thing being processed, without needing a service task whose
  only job is to write it down. On any node rather than only on end events — on a user task it is the
  state the host is in for as long as that task waits, which is what lets a process move its host between
  states without finishing. Placing it retires whatever other tag the host carries in the same category,
  since a lifecycle is a state rather than a growing pile of markers.

### The vocabulary

`/WorkflowTypes` is the translation table between BPMN and the repository. Each `wf:FlowNodeType` says
that a given XML element means a given kind of node, and is shipped as a file of its own, named after the
entry — `MessageStartEvent.json`:

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

Parsing a document matches each element against every entry and keeps the highest-`priority` match, which
is what stops a start event carrying a message definition from being read as a plain one. `jcrProperties`
carries the fixed properties to set on the stored node — it is how a terminate end event and an ordinary
one share `wf:EndEvent` and still differ.

It is only for what genuinely varies between entries sharing a node type. Whether an event is **catching**
or throwing, by contrast, follows from its node type and never varies within one, so `catching` is
autocreated by the node type and protected against being written, rather than named here. A property a
node type already determines has no business being restated by the vocabulary: the two could then
disagree, and the stored node would be the one that lies.

**Adding a kind of node is normally a vocabulary entry, not a node type.** A user task and a service task
are both plain `wf:Activity` nodes; what tells them apart is which entry they point at. Only distinctions
the engine has to make *structurally* get a node type of their own. This is why the Java hierarchy is much
shallower than BPMN's, and it is the test to apply before adding to it:

| Distinction | Where it lives | Why |
|---|---|---|
| User task vs. service task | Vocabulary | Both are work to be done; only the doer differs |
| Timer vs. message start event | Vocabulary | Both start the workflow; only the trigger differs |
| Exclusive vs. parallel gateway | Node type | The engine routes one token or all of them |
| Event-based gateway | Node type | It waits instead of evaluating, unlike every other gateway |
| Boundary vs. free-standing catch | Containment | Same event; only where it is stored differs |
| Terminate vs. ordinary end | Property | Same node, but it ends the instance rather than a branch |

### Self-documentation, and why its shape matters

`/WorkflowTypes` carries the `doc:Documented` mixin, so its catalogue is served at
`/WorkflowTypes.doc.json` and `/WorkflowTypes.doc.md` (see [autodoc](autodoc.md)).

The JSON is **not just prose**: it is what the visual BPMN editor reads to build its toolbars, grouped by
the `category` each entry declares, falling back on the group its kind implies. Each item carries the
`xmlElement`/`xmlChildElement` it stands for and the `jcrNodeType` it is stored as. Treat the shape of
that output as a contract — the editor depends on it.

### Runtime

A workflow lives **inside the thing it drives**, so that it is found, secured and deleted along with it:

```
/Submissions/proposal-42            sub:Submission        (wf:WorkflowAttachable)
└── wf:instances                    wf:WorkflowInstances  autocreated, IGNORE
    └── review                      wf:WorkflowInstance   workflowVersion, status, startTime, endTime
        ├── t1                      wf:WorkflowToken      currentNodeId
        ├── requestedDays           wf:Variable           dataType, longValue
        └── approve_1               wf:TaskInstance       taskDefinitionId, label, assignee, status,
                                                          outcome, outcomeOptions, performers
```

A **token** is one branch of an execution and the single fact of where it has got to. Tokens are the whole
of a workflow's runtime state: an instance is "at" wherever its tokens are, and an incoming event is only
acceptable when a token is resting on a node that catches it.

A **variable** takes its name from its node name, so looking one up is a child lookup rather than a scan,
and its value lives in whichever typed property its `dataType` names — the repository then indexes it as
what it is.

A **task instance** is an entity in its own right rather than a part of the instance, because a task is
something people go looking for: "what is on my desk" should be a query over these, not a walk of every
running workflow. Its `outcome` is recorded separately from its `status` because the two answer different
questions — the status says the task is over, the outcome says how, and the gateway downstream routes on
the latter. The terms it is decided on — `outcomeOptions` and `performers` — are copied onto it from its
defining activity as it is raised, rather than looked up: a task is decided on the terms it was raised
with rather than on terms the definition may have grown since, and whoever owes the decision can rarely
read the definition at all. Those copies describe rather than permit; what makes a completion lawful is
still the definition.

Anything that workflows should be able to run over carries the `wf:WorkflowAttachable` mixin, which
autocreates the container:

```
[sub:Submission] > data:Entity, wf:WorkflowAttachable

[wf:WorkflowAttachable]
  mixin
  + wf:instances (wf:WorkflowInstances) = wf:WorkflowInstances AUTOCREATED IGNORE
```

which gives `Submission.getWorkflowInstances()`. One thing may have several workflows running over it at
once — a review process and a periodic reminder, say — so it is a list, not a single lifecycle.

**`IGNORE` is load-bearing, not tidiness.** Every `data:Entity` is `mix:versionable`, and so is a workflow
instance. Under the default on-parent-version setting, checking in a submission copies the entire live
workflow into version storage, and *restoring an earlier revision rolls the workflow back with it* — an
editor reverting a typo would quietly un-approve a proposal. Verified against Oak both ways: with the
default, a restore rewound the token to where it had been at check-in; with `IGNORE`, the submission
reverted and the workflow carried on untouched. The same reasoning is why `data:Entity` declares its
`link:links` child `IGNORE`.

Three consequences of co-locating worth knowing:

- **One ACL surface.** Workflow state inherits the submission's permissions. Convenient — whoever can
  read a submission can see its progress — but if assignees, variables or deadlines should be hidden from
  the submitter, that needs a deliberate restriction on the container, which is why it is
  `rep:AccessControllable`.
- **The engine writes as a service user.** It moves tokens on behalf of people who often have only read
  access to the submission, so it uses the `workflows` service user rather than the request's session.
- **Deleting the submission deletes its workflows.** Usually what you want; it does mean the record of
  what happened has to live somewhere else if it must outlive the submission.

Two things deliberately do *not* live inside the resource. **System workflows** cannot: the bootstrap case
is "create a submission", whose target is the `SubmissionsHomepage`, and there is no submission to live
inside yet — which is the main reason to doubt they should persist an instance at all. And an **audit
trail**, if one is needed, wants to survive deletion and restore, so it would be its own tree rather than
a child.

## Managing workflows

Authoring lives in the administration console, under `/admin/workflows`. A URL there carries the whole
repository path of what is being looked at, and names the page in its query only when the page needs
naming, so one set of pages serves the workflows of any homepage — this location's, the platform's own, a
later one's:

| URL | Page |
|---|---|
| `/admin/workflows/SystemWorkflows` | The workflows stored in one homepage, a tab per homepage beside it |
| `/admin/workflows/Workflows/review` | One workflow: its properties, and its versions with their actions |
| `/admin/workflows/Workflows/review/2-0` | That version's diagram, read-only |
| `/admin/workflows/Workflows/review/2-0?page=edit` | The same diagram, editable — drafts only |

**The page is asked for in the query rather than in the path**, because the viewer and the editor are not
one inside the other. They are the same version seen two ways, reached from the same listing, and neither
reports anything the other does not — so the editor is a mode of one page rather than a page below it: the
same URL, the same crumb, asked a second way. Each screen offers the way to the others: a draft being
looked at offers **Edit**, and the editor offers **Save**, **Save and view**, and **Save and close** — the
same save, differing only in where it leaves the user afterwards. A save the engine refuses navigates
nowhere, since leaving would take the only copy of what was drawn with it.

**A listing belongs to a homepage, and `/admin/workflows` is not a page.** Nothing is registered there, so
it is neither routed nor named: the shallowest thing the console shows is a homepage. The dashboard
widget's "Manage workflows" action leads to `/Workflows`, the homepage every deployment has, and each
homepage the widget counts links to its own listing beside it.

**Every prefix down to the homepage is a page in its own right**, which is the whole point of the shape:
dropping a segment moves up to the thing that contained what was being looked at, so a breadcrumb built by
cutting the URL down leads somewhere at every step.

The price of carrying a repository path is that the URL does not say which of the three things it is about.
A homepage is found by node type wherever it is, so a path is of no predictable depth: `/Workflows/review`
and `/Content/Workflows/review` are both a workflow, and counting segments from the root would read the
second as a version of `/Content/Workflows`. **Depth is therefore counted from the homepage**, which is the
only fixed point — below one it is always homepage, workflow, version — so resolving a console URL takes the
list of homepages this instance has. That list is the one the tabs are built from —
`GET /Workflows.homepages.json`, described below — fetched once and kept for the session, so it costs a
request when the console is first opened and nothing on any navigation after it.

Two things fall out of counting rather than keyword-matching. A version named `edit` is read as itself:
nothing in a path is ever a page, so no name below a homepage is reserved. And a URL that places nowhere —
a tree that is not a homepage here, more segments than a version can account for — is said to name nothing,
rather than being handed to a page that would render an empty workflow for it.

What this buys the breadcrumbs above the page: every step of a console URL is a page, so each is
rendered as a link that leads somewhere. Naming them takes one registration per depth, because a crumb
is labelled with the `ext:name` of the view whose target matches it, and a single view spanning the whole
tree would name every step alike. So the console registers `:homepage`, `:homepage/:workflow` and
`:homepage/:workflow/:version` as views of their own, all rendering the same page, and a trail reads
`Administration / Workflows / Workflow`.

Two things fall out of registering by depth rather than behind one splat. A view is what makes a URL a
crumb, so leaving the console's root unregistered is what removes it from the trail — there is no way
to be a route without also being a step. And the depths only line up for a homepage of one segment:
`/Content/Workflows` is a homepage the trail would call a workflow. Its pages still work, since a
`:homepage/*` view catches every depth the named three do not, ordered last so the named ones are
found first; only the labels are off, and only for a homepage stored deeper than everyone's.

**Every one of those views renders the same page.** `ext:targetURL` is handed to the router as-is, and a
route may only end in a splat, so no pattern can pick out a page that comes *after* a path of unknown
length — which means none of them can say, by its pattern alone, which of the three things its URL is
about. They are registered separately so that the trail can name them, and what each URL actually
addresses is worked out once, by the page they all mount.

Two things about that split are load-bearing. **The read-only view is a different bpmn-js class**, a
`NavigatedViewer` rather than a `Modeler`: it can pan and zoom and has no palette, no context pad and no
editing behaviours, so a version instances are following is not an editor being trusted to behave. And
**editing is refused for anything but a draft**, in the page as well as in the URL: an active version is
what running instances are following, a retired one is what the instances that outlived it are still
following, and a trial is being tried as it stands, so changing any of them would alter a process out from
under the things reading it. A trial is changed by being returned to a draft; an active or retired version
is carried forward by drafting a copy, which is offered next to it.

The per-version buttons are contributed on the **`WorkflowVersionActions`** extension point rather than
written into the manager page, the way `SubjectActions` works in the sibling `cards` project. Six ship
with the module — view, edit, start-trial, activate, return-to-draft, and draft-a-copy — and each decides
for itself which states it applies to; a seventh needs an `ext:Extension` and an asset, and no change to
any existing file. The point is addressed by two names, as every extension point is: the page asks for the
node, `/apps/iap/ExtensionPoints/WorkflowVersionActions`, and an extension declares the
`ext:pointId` that node carries, `wf/workflowVersion/actions`.

**Every one of these actions is a workflow, not a write.** Creating a workflow, opening a version of one,
renaming it, saving a diagram, and each of the three lifecycle moves are domain events posted at the thing
they concern, matched to a system workflow under `/SystemWorkflows` and run to an end event in one commit.
Nothing in this UI writes a node.

| Request | Event | Definition |
|---|---|---|
| `POST /Workflows` | `create` | `createWorkflow` |
| `POST /SystemWorkflows` | `create` | `createSystemWorkflow` |
| `POST <workflow>` | `save` | `saveWorkflow` |
| `POST <workflow>.createVersion.json` | `createVersion` | `createVersion` |
| `POST <version>` | `save` | `saveWorkflowDiagram` |
| `POST <version>.activate.json` | `activate` | `activateVersion` |
| `POST <version>.startTrial.json` | `startTrial` | `startVersionTrial` |
| `POST <version>.returnToDraft.json` | `returnToDraft` | `returnVersionToDraft` |
| `POST <version>.draft.json` | `draft` | `draftVersion` |

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
  start event, editable per deployment. That is why there are three lifecycle definitions rather than one
  `setState` — a single move endpoint could only ever say who may change state *at all*, where separate
  definitions can say that an author may return their own trial to a draft while only an administrator may
  activate one.
- **The lifecycle table is content.** `toState` and `fromStates` on the promote step say which versions a
  move applies to; a fifth state is a new definition rather than a new row in Java. A move a version is
  past the moment for is refused with a 409 naming the states it *is* for.
- **What each action does can grow without touching the platform.** A validation step before a version is
  opened, a notification when one is activated: another service task on the definition.

Two of them are more than one write, which is the reason the run commits once:

- **Activating** is `retireActiveVersions` then `setVersionState`. Retiring the outgoing version in a
  second request would leave a window in which two versions of one workflow both claim to be current, and
  a client that failed between the two would leave it that way for good. As two steps of one run there is
  no moment at which the invariant does not hold, and a promotion that cannot complete retires nothing.
- **Opening or drafting a version** stores its diagram in the same run — carried as a `bpmn.xml` payload
  part when a version is opened, copied from the source when one is drafted — so the version node and its
  diagram arrive together or neither does. Posting directly cannot do that: Sling creates the node a file
  part's path implies before it applies `jcr:primaryType`, so a combined write leaves a `sling:Folder`
  behind and the diagram has to follow in a second request.

Drafting a copy leaves `bpmnXmlParsedHash` off deliberately, so a draft never claims a parse that has not
happened for it; that missing hash is also what has the commit editor look at the copied diagram in the
first place.

What happens to the *graph* follows `bpmnAuthoritative`, which the copy inherits because it describes how
a version was authored rather than a state it moves through. Where the diagram owns the graph, the flow
nodes are not copied: the editor derives the whole tree from the diagram that just arrived, in the same
commit, and a copied tree would only be waiting to be replaced by the identical one. Where it does not —
a version whose flow nodes were authored by hand, because the translation cannot yet carry everything
they hold — the graph is copied as it stands, nested as flow nodes nest, extension properties and all:
nothing will ever derive it, so a draft without it would be a copy of a process with the process left
out.

Saving a workflow's own properties goes through `saveProperties`, which writes only what the activity's
`editable` list names and refuses what its `required` list says must arrive with a value. That listing is
the whole of the safety: without it the handler would be an open write to whatever a caller cared to name,
`jcr:primaryType` included, which is exactly the direct-CRUD door these workflows replace. It also means a
deployment that wants the description editable adds a word to a definition rather than shipping code.

A version created through the UI is marked `bpmnAuthoritative` on creation. It starts from the shipped
starting diagram and has no hand-written graph for a reparse to throw away, so its diagram is the only
thing its flow nodes could come from — and without the flag the version would be stored and its diagram
never parsed into anything the engine can run.

Listing covers every homepage, one at a time. `GET /Workflows.homepages.json` answers with every entity
homepage holding `wf:WorkflowDefinition` entities **that the caller can read** — a homepage they may not
read is simply absent, so the list describes what this user may list rather than what exists — and the
page gives each of them a tab bearing its name, listing one at a time. Every listing is then a plain query
over one tree, so paging, sorting and the total belong to a homepage rather than to a union of them, only
the tab being looked at is fetched, and a page of workflows always says where its workflows are stored.
Nothing on either side hardcodes which trees a deployment has.

Which tab is open is in the URL, and the answer to this question is what reads it, so the two cannot
disagree about what a homepage is: a tab is a page, its path is a prefix of every workflow URL below it,
and the console resolves that prefix against this same list. It is asked for once and kept for the rest of
the session — homepages appear and disappear when a bundle is installed, which is a restart and so a new
session — so the depth of a console URL is worked out without a request.

The dashboard widget asks the same question and shows only the answer's size: one count per homepage,
fetched as a page of no rows at all (`.paginate.json?offset=0&limit=0`), with the frame's "Manage
workflows" action leading to the page that lists them. A grid does not fit a dashboard frame; a count
does.

## Sling Models

Everything above is reachable as Sling Models in `io.uhndata.iap.workflows.models`, so callers never touch
the repository directly. Graph navigation is the point of them:

```java
WorkflowVersion version = resource.adaptTo(WorkflowVersion.class);
for (StartEvent start : version.getStartEvents()) {
    FlowNode next = start.getOutgoingFlows().get(0).getTarget();
}

FlowNode resting = token.getCurrentNode();          // through the instance, to its version, to the node
Activity raisedFrom = task.getDefinition();
```

The abstract bases — `FlowNode`, `Event`, `IntermediateEvent`, `Gateway`, `FlowNodeType` — are not
registered models. Each concrete subtype instead lists the bases it answers for in the `adapters` of its
own `@Model`, so `adaptTo(FlowNode.class)` yields the actual subtype rather than a generic node missing
its fields. Asking a version for its flow nodes gives back start events, activities and gateways, each as
itself.

That dispatch runs on the Sling resource type hierarchy, which is why every type has a node under
`/libs/wf` naming its parent. A resource only carries the single supertype its node type autocreates, so
without those nodes the chain from `wf/StartEvent` up to `wf/FlowNode` cannot be followed and the
dispatch quietly stops matching. **A new `wf:` node type needs a `/libs/wf/<Type>/ROOT.json` alongside
it.**

## The engine, and system workflows

The first thing the engine runs is the platform itself. A *system workflow* is ordinary workflow content —
the same node types, the same models — stored under `/SystemWorkflows`, and that location is what makes it
one: it describes something the platform does on its own behalf, like turning "someone POSTed to
/Workflows" into a new workflow definition.

```
HTTP POST /Workflows ──▶ WorkflowEventServlet ──▶ WorkflowEngine.receiveEvent(target, event)
                          (a deliberately dumb        │  find the one system workflow whose message
                           translator: builds a       │  start event catches this event on this target
                           `create` event from        │  walk it: start ─ service tasks ─ end
                           the POST parameters)       ▼  one commit at the end
                                              302 Location: /Workflows/<created>
```

Everything goes through `WorkflowEngine.receiveEvent` — HTTP is just one *translator*, and inbound email
or firing timers will feed the same door. Receiving an event answers three questions in order, and each
failure maps to its own HTTP status: is anything waiting for this event here (no → **409**), may this user
fire it (no → **403**), and is what it carries usable (no → **400**)?

A fourth refusal shares the first one's status without being the same question. `WorkflowConflictException`
is a handler saying the *target* is not in a state that admits this — promoting a version that has already
been retired, drafting a label some other version carries. A 409 either way, because nothing about the
request would be improved by sending it differently; what has to change is the target. A client can act on
the difference: `NoApplicableWorkflowException` means a stale page offering a button that does not exist
here, `WorkflowConflictException` one offering a button whose moment has passed.

### Who is allowed: the workflow decides

The middle question is the one the whole design turns on. **Nobody holds rights on the content workflows
manage.** There is no ACL granting users write access to `/Workflows` or `/Submissions`, and none is
coming: the engine reads and writes everything as its own service user. What a user may do is therefore
not what an access control list says about the data — it is what the definitions say, which means there
is exactly one way in and no second mechanism to keep in agreement with the first.

A flow node names the principals it admits in its `performers` property:

```json
"requested": {
  "jcr:primaryType": "wf:StartEvent",
  "messageName": "create",
  "performers": ["iap-administrators"]
}
```

That is the answer to "who can create a workflow": one property, in the same file that says what creating
a workflow does, editable per deployment without touching code. The shipped bootstraps read
`["iap-administrators"]` for `/Workflows` and `["everyone"]` for `/Submissions` — authoring processes is
administrative, raising a submission is what users are here for. The rules:

- **An empty or absent list admits nobody.** A definition that forgot to say who may use it refuses
  everyone until it does; silence is never permission.
- **`everyone` means any authenticated user**, matched by name because it is a dynamic principal an
  authorizable does not necessarily report belonging to.
- **`@creator` means whoever raised the resource being worked on** — the person the engine recorded when it
  created it, not `jcr:createdBy`, which names the engine's own service user for everything it writes. It is
  the one rule a group can never express, and the one most processes need: a request comes back to the person
  who made it, not to everyone who could have made one. A resource nothing raised — a homepage — is nobody's,
  so `@creator` admits nobody there.
- **Groups are matched transitively**, so naming a group also admits the members of its member groups.
- **Administrators pass regardless**, exactly as they bypass access control in the repository itself.
  Without that, one bad definition could lock out the very people who could repair it.

Two consequences worth stating plainly. First, since the engine is privileged, *nothing downstream will
refuse an actor who gets past the check* — by the time a handler runs, the repository will not say no. A
handler that wants to treat something as invisible or forbidden has to say so itself. Second, an access
denial coming back from the repository does not mean the user was refused; it means the engine's own
service user is short of rights, which is a deployment fault and a **500**.

Because the engine does the writing, `jcr:createdBy` on everything it creates names the service user. The
human is recorded separately, in `createdBy`, which is what an audit trail and any "things I raised"
listing have to read.

The one grant ordinary users do get is `jcr:read` on the homepage nodes themselves — `/Workflows` and
`/Submissions`, restricted by node type so that nothing below them is included. That is not a policy
decision but a mechanical one: Sling resolves the posted-to resource *before* dispatching to a servlet, so
an invisible `/Submissions` would answer 404 and the workflow would never get to decide anything.
`/SystemWorkflows` gets no such grant — it is the engine's own tree, nothing is posted to it, and which
definitions govern a user is none of their business.

**System workflows run to quiescence inside the request and persist no instance.** That settles the
question of their state by construction: there is none. The corollary is a hard validation rule — a system
workflow must be *straight-through*. No user tasks, no mid-process catching events, exactly one arc out of
every node it passes; a definition that would have to wait is rejected as broken (**500**), because there
is no persisted token that could rest there. Everything a run changes lands in a single commit, so an
event either fully happened or didn't happen at all.

### Service tasks and the handler SPI

What a service task *does* is a registered `ServiceTaskHandler` (in `io.uhndata.iap.workflows.spi`): the
activity names its handler in the `handler` property, and the activity's other properties are that
handler's configuration. This is the extension point that lets a project plug its own behavior into a
workflow without touching the platform. Handlers write through the context's resolver and never commit —
the engine owns the transaction — and communicate through execution variables
(`context.setVariable(...)`), which is also how results reach the channel that fired the event.

The first built-in handler is `createEntity`: create a node of the configured `entityType` under the
target, named by camel-casing the payload's `title`, dodging collisions with a numeric suffix, and report
the created path in the `createdPath` variable — which is what the servlet turns into a redirect.

### The bootstrap: creating workflows is itself a workflow

`/SystemWorkflows/createWorkflow` ships with the platform: a `create`-catching message start event, a
`createEntity` service task configured with `entityType = wf:WorkflowDefinition`, an end event. Its
version declares `targetResourceType = wf/WorkflowsHomepage`, which is how the engine knows it answers
for POSTs to `/Workflows`.

Because it is content, not code, a deployment can change what happens when a workflow is requested — add
a validation step, a notification — by editing this definition rather than the platform. That is the
point of doing it this way, and it is why the definition ships as an `ACTIVE` version, editable in place,
rather than being hardwired into the servlet.

**Everything else that authors a workflow works the same way**, which is what makes that claim more than
a demonstration: `createSystemWorkflow`, `createVersion`, `saveWorkflow`, `saveWorkflowDiagram`,
`activateVersion`, `startVersionTrial`, `returnVersionToDraft` and `draftVersion` all ship beside it, over
six handlers of their own — `createWorkflowVersion`, `saveProperties`, `saveWorkflowDiagram`,
`setVersionState`, `retireActiveVersions` and `draftWorkflowVersion` — plus `createEntity`, shared with the
bootstrap. The workflow module manages its own content the way it asks every other module to manage theirs,
and the management UI holds no privileged path of its own. See
[Managing workflows](#managing-workflows) for the request each one answers.

`/Submissions` works the same way, and shows the intended division of labor: the bootstrap definition
`/SystemWorkflows/createSubmission` and its `createSubmission` handler ship with the *submissions* module,
not the workflows one — each module contributes the system workflows for its own homepages, plugged in
through the handler SPI exactly as a project would. That handler is also where "no new submissions may be
created from an inactive version" stops being a comment in the CND and becomes an enforced refusal, and it
sets the submission's `schemaVersion` as a real JCR REFERENCE — the strict node type rejects a
stringly-typed identifier at commit.

Saving a submission's answers is the same shape and shows what a chain of service tasks buys: the
`/SystemWorkflows/saveAnswers` definition runs `saveAnswers` (write the answers), then `validateAnswers`
(put them past every registered `AnswerValidator`), then `markCompleteness` (record whether anything the
schema still asks for is unanswered, as the `incomplete` tag). Each step can refuse, and a refusal on the
way to the end event reverts the whole run — so a save that breaks a rule leaves nothing behind, and the
tag is only ever written for answers that were accepted.

That last step is why a control offering to send a request can refuse to: **whether a request is complete
is recorded on it rather than worked out by whoever asks.** A required question that a condition hides is
not missing, so completeness has to be judged against the resolved form by the same evaluator that decides
what the form shows — done once, at save time, instead of by every reader. The tag is a system tag in its
own `completeness` category: nobody can hand-place it, and it does not displace the lifecycle state.

Four properties carry the engine's matching, authorization and dispatch, derived from the diagram's
`iap:*` attributes: `messageName` on events (the domain event name), `targetResourceType` on versions
(what a system workflow answers for), `performers` on flow nodes (who may pass through), and `handler`
on activities (who performs a service task).

## User workflows: the part that persists

A system workflow runs inside the request and leaves nothing behind. A *user* workflow is the opposite: it
outlives the request, because the next thing that has to happen is a person doing something. It persists as
a `wf:WorkflowInstance` **inside the resource it drives** — found, secured and deleted along with it — plus
a `wf:WorkflowToken` for each branch in progress and a `wf:TaskInstance` for each thing somebody still owes.

Running one is always the same walk, from wherever a token rests through whatever can be passed
automatically, until it has to stop:

```
POST /Submissions ──▶ createSubmission ──▶ startWorkflow ──▶ [instance created, walked to its first wait]
                                                                        │
                                             wf:instances/timeOffRequest │  token parked on fillIn
                                                                        ▼
POST …/fillIn ──▶ complete ──▶ [task closed, instance walked on to the next wait]
                                                        │
                                                        ▼  hostTag: the submission is now tagged "submitted"

POST …/approveRequest {outcome} ──▶ complete ──▶ [task closed, gateway routed, end event reached]
                                                        │
                                                        ▼  hostTag: the submission is now tagged "approved"
```

**Starting is a service task, not a special case.** `startWorkflow` is built into the engine — putting an
entity under a workflow is the engine's own business — but it is reached as an ordinary activity, so *which*
entities get a workflow, and when, stays editable content. Which workflow is found by following the chain of
references named in the activity's `workflowFrom`: for submissions, `schemaVersion/workflow`, since it is the
schema version a submission answers that decides what it must go through. That indirection is what keeps the
workflows module from having to know what a submission is. An entity whose data names no workflow simply has
none, which is not an error.

**A user task is an activity with no handler.** Nothing can perform it automatically, so the engine parks the
token there and creates the task; a `complete` event aimed at that task closes it, records the outcome, and
carries the instance on. Who may complete it is the same `performers` mechanism as everywhere else, asked one
step later — of the task's *defining activity* rather than of a start event. Seeing a task and being allowed
to decide it are different questions, and this is where the second is answered.

**A user task says what it may be decided with.** `outcomeOptions` on the activity lists the decisions on
offer, and the engine copies them onto each task it raises under the same name. Copied rather than looked up,
for the
same reason the label is: a task is decided on the terms it was raised with, and whoever has to do it can read
the task without being able to read the definition. A task that offers none is one there is *nothing* to
decide about — it is done or it is not — and that distinction is what a task list needs, because a plain
"done" button on a task that expected a decision would silently take the default arc of the gateway after it.

**A user task also says who it is waiting for.** The engine records the defining activity's `performers` onto
each task it raises, and answers `@creator` against the host while it does — so every entry names a principal
that stands on its own, rather than a question only the host can settle. That is what makes "what is waiting
for me" a question about *tasks*: a listing cannot run the engine per row to find out, and the person owing a
decision usually cannot read the workflow the task came from. It is the answer to the first of the two
questions above, and only that one — the copy describes, it does not permit. Every completion is still checked
against the definition, so a task turning up in somebody's list is not what makes it theirs to decide.

**Reaching a node can mean something to the host.** `hostTag` on a flow node is placed on the host as a tag
when execution arrives there, which is how a process says what being *here* means to the thing being
processed, without a service task whose only job is to write it down. On an end event that is what finishing
this particular way meant; on a user task it is the state the host is in for as long as that task waits, which
is what lets a process move its host between states without finishing — a request is a draft while it is being
filled in, and submitted the moment it reaches the approver, and neither of those is an ending. Placing a tag
retires whatever other tag the host carries in the same category: the categories are what make a set of tags a
lifecycle rather than a pile of markers, so a submission that has just been approved stops being in review.
Tags outside those categories are left alone, since a host is free to carry markers that have nothing to do
with this process.

**Submitting is a user task, not a separate mechanism.** A request that can be filled in is one whose process
is parked on a task performed by `@creator`, tagged `draft`; completing that task is what sends it. There is
no "submit" event, no submit endpoint and no submitted flag — which is why what the button says is the task's
own label, and why a deployment that wants a request to go somewhere else first only edits its process.

**A task can be given a deadline.** A boundary timer — an event stored *inside* the activity, with a
`timerDuration` — is armed when the task is raised: the engine works out when the wait ends and records it on
the task itself, as `dueDate` and the `dueEventId` naming the timer. That puts the deadline where anything
looking for overdue work can see it without running the engine, and it survives a restart, which a scheduled
job in memory would not.

When it passes, a periodic sweep hands the task to `receiveEvent` as an ordinary `timeout` event, so the
clock comes through the same door as everything else. The task is cancelled — no assignee, no outcome,
because nobody did it and nothing was decided — and execution leaves down the timer's own arc rather than the
activity's, which is how a process says what running out of time *means*. There is no performer check:
`performers` says who may make execution pass through a node, and time belongs to no group; refusing the
clock for that would park the instance on a task that can never now be done.

**Read access is materialized when the instance starts.** Acting is authorized by the definitions, but
reading cannot be — a query returns rows, and no engine can run a workflow per row — so the workflow declares
and the engine writes an ACL: the person it is being run for, plus the performers of every user task in the
version. Deriving that from `performers` rather than inventing a second vocabulary means the two can never
disagree.

### More than one branch at once

An instance holds a token per branch in progress, so the walk is a queue of positions rather than a single
path. Four things follow from that, and they are the reason it was worth doing as one piece:

**A parallel gateway forks and joins.** Leaving one takes *every* arc — the arriving token moves onto the
first and a new one is created for each of the rest. A parallel gateway with several arcs leading in is a
join: each token that arrives waits on it until one has come from every arc, and then they merge back into
the one token that carries on.

BPMN lets any arc carry a condition, but a parallel gateway takes all of its arcs whatever those say — so a
condition on one could never decide anything. The engine treats that as an error in the diagram rather than
quietly ignoring it, because the two readings are far apart: an author who guarded an arc believes that
branch is sometimes not taken, and it always is.

That counting is also how a diagram deadlocks: a parallel join placed after a fork that did *not* take every
branch — an exclusive or inclusive one — waits for a token that was never created, and the instance stays
active with nothing able to move it. Use an inclusive join to merge branches that were conditionally taken;
it is exactly the case its reachability rule answers.

**An inclusive gateway forks as widely as applies.** Every arc whose condition holds is taken, as is every
arc that carries no condition, falling back on the default when nothing applies. Its join cannot count the
way a parallel one does — the fork took only the branches that applied, and how many that was is written
nowhere — so it asks the question that actually matters: *can any branch still get here?* When no other token
in the instance can reach it by following the graph, what has arrived is all that ever will. Boundary events
count as ways onwards, since a deadline can take a token off a task.

That answer changes as the other branches move, and nothing arrives at the join to announce it, so the walk
looks again at the parked joins once every branch has stopped moving, until nothing can move at all. Reading
it from the graph rather than remembering it at the fork is what makes it survive an instance being resumed
days later by somebody else.

**An end event ends a branch, not the process.** The token that reached it is spent, and the instance closes
only when the last one is gone. `terminate` on an end event is the other thing: it discards every remaining
token and cancels every task still waiting for somebody, since a task whose token has been discarded can
never be completed.

**A non-interrupting boundary event runs beside the work.** An interrupting timer cancels the task it watches
and execution leaves down the timer's arc. A non-interrupting one leaves the task exactly where it was and
starts a second branch: "remind them after three days" as against "give up after five". Which deadlines have
already fired is recorded on the task as `firedEvents`, so the sweep does not deliver the same one twice, and
arming picks the earliest timer that has not fired — measured from when the task started, so "remind after a
day and a half, give up after five days" means five days from the start rather than from the reminder.

Tokens are interchangeable: nothing distinguishes one from another beyond where it rests, which is why two
branches arriving at the same task simply mean two tasks, each completed on its own.

## Known gaps

- **Instance variables are not exposed to handlers.** The runtime persists `outcome` as a `wf:Variable`, but
  a service task inside an instance gets variables that live only for that delivery. Typed variables are
  already in the node types; wiring them to the SPI is what is missing.
- **Nothing delivers a message.** A timer is delivered — a boundary timer on a user task is armed when the
  task is raised and fired by a periodic sweep — but an instance that reaches a *free-standing* catching
  event is still refused rather than parked, because nothing could then wake it: what a message event waits
  for would have to be addressed to it, and the engine's door currently opens onto a homepage or a task.
- **Read access is granted for the life of the instance**, not only while a task is open, and is never
  revoked. Narrowing it as state changes is a refinement for when there is a reason to want it.
- **A gateway's guards can only ask about the execution.** They are evaluated against the instance, so the
  `variable` operand source reaches what the run knows — the outcome a task recorded — and nothing yet
  reaches the host it is attached to, which is what routing on a request's own answers would need.
- **The parser cannot yet fill in an event's payload.** A timer's duration now has somewhere to live —
  `timerDuration` on the catching event — but BPMN keeps it in a nested `timeDuration` element, and a
  message event records its `messageRef` without resolving it to the `<bpmn:message>` declared at document
  level, which is what the engine's event dictionary will need. The vocabulary can copy XML *attributes*;
  these payloads live in nested *elements*, and the mechanism for reaching them is best designed alongside
  the parser that needs it.
- **Widening a `performers` list on a workflow-authoring definition needs an ACL to match.** Sling resolves
  the posted-to resource before dispatching, and the only read granted under `/Workflows` is the homepage
  node itself, restricted by node type — so a non-administrator named as a performer of `saveWorkflow` or
  `activateVersion` would get a 404 from the resolver rather than the engine's own answer, because the
  definition or version they posted to is invisible to them. Administrators bypass access control, which is
  why the shipped definitions (all `iap-administrators`) work as they stand. Widening any of them means
  granting `jcr:read` on `wf:WorkflowDefinition`/`wf:WorkflowVersion` nodes too, and that is a deliberate
  visibility decision rather than a mechanical one — which is why it is not done pre-emptively here.
- **"At most one active version" is enforced by the workflow, not by the repository.** Activating retires
  the outgoing version in the same commit, and reading a version's state tolerates finding two actives (a
  promotion retires all of them). Nothing *else* can now set `state` — the direct-write door is closed,
  since no user holds rights on this content and the only way in is the definitions — but a service user
  or a repoinit script still could, and a definition that named `setVersionState` with the wrong
  `fromStates` would too. A commit editor, the way `BpmnXmlSyncEditor` guards the parsed graph, is the way
  to close that last gap if it ever matters.
- **`performers` is a principal list, not a condition.** It cannot express "and only if the schema they
  name belongs to their institution". That data-dependent half is a job for the conditions module,
  evaluated against the actor alongside the list rather than instead of it: a list can be enumerated to
  decide which buttons to render, a condition cannot.
- **No lanes.** BPMN lanes are the natural place for "this task belongs to the coordinator, that one to
  the board", and `performers` is set per node rather than derived from a lane the way a diagram would
  express it. Mapping lanes onto groups touches how `ApprovalRequirement.approverGroup` already works, so
  it is a design decision rather than an omission.
- **No subprocesses, call activities or multi-instance markers.** "One review per assigned reviewer" is a
  multi-instance activity in BPMN, and that is the first of these likely to be wanted.
- **Signal, escalation, conditional and link events** are unmapped; the vocabulary covers timer, message,
  error and terminate.
- **The `bpmn:` prefix is matched literally** rather than by namespace URI. Safe while every diagram comes
  from the in-app editor, which always emits that prefix; a document from elsewhere using `bpmn2:` or a
  default namespace would not be recognized.
- **No `oak:index` definitions.** The console's listings and the homepage discovery both query without one.
