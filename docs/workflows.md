# Workflows

A workflow is the process something is put through: who has to look at a submission, in what order, what
may happen while they do, and when it is finished. Workflows are authored as BPMN 2.0 diagrams, stored in
the repository as a graph of nodes, and executed by creating an *instance* of one and moving tokens
through that graph.

This page describes the data model, the Sling Models over it, and the engine that runs both kinds of
workflow: the *system* ones that are the platform's own behavior, and the *user* ones that persist as
instances while people work through them.

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
└── timeOffRequest                 wf:WorkflowDefinition   title, active
    └── 1.0                        wf:WorkflowVersion      version, active, bpmnXmlParsedHash,
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
as a schema and its schema versions. A version keeps both representations of its graph: the `bpmn.xml` it
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

Writing it means a multipart POST with a part named `./bpmn.xml` and the `nt:file` type hint. Creating a
version and uploading its diagram are two requests, since Sling creates the node a file part's path
implies before it applies `jcr:primaryType`, and one combined request would leave a `sling:Folder`
behind. Its on-parent-version is `COPY`, so checking a version in captures the diagram with it.
`WorkflowVersion.getBpmnFile()` hands back the file rather than its contents, leaving the caller — the
BPMN parser, when it exists — to decide how to read a document of unknown size.

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

Six properties exist for the engine rather than for the diagram, all set by hand today and by the BPMN
parser once it exists:

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
- **`outcomes` on an activity** lists the decisions that person may complete the task with — the values a
  gateway downstream then routes on. Declared because a task list has to know what to offer: the only
  other record of which outcomes exist is the `conditionExpression` on some later gateway's arcs, which
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
                                                          outcome, offeredOutcomes, performers
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
the latter. The terms it is decided on — `offeredOutcomes` and `performers` — are copied onto it from its
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
denial coming back from the repository no longer means the user was refused; it means the engine's own
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
point of doing it this way, and it is why the definition ships `active` and editable rather than being
hardwired into the servlet.

`/Submissions` works the same way, and shows the intended division of labor: the bootstrap definition
`/SystemWorkflows/createSubmission` and its `createSubmission` handler ship with the *submissions* module,
not the workflows one — each module contributes the system workflows for its own homepages, plugged in
through the handler SPI exactly as a project would. That handler is also where "no new submissions may be
created from an inactive version" stops being a comment in the CND and becomes an enforced refusal, and it
sets the submission's `schemaVersion` as a real JCR REFERENCE — the strict node type rejects a
stringly-typed identifier at commit.

Four properties carry the engine's matching, authorization and dispatch, all set by hand today and by the
BPMN parser once it exists: `messageName` on events (the domain event name), `targetResourceType` on
versions (what a system workflow answers for), `performers` on flow nodes (who may pass through), and
`handler` on activities (who performs a service task).

## User workflows: the part that persists

A system workflow runs inside the request and leaves nothing behind. A *user* workflow is the opposite: it
outlives the request, because the next thing that has to happen is a person doing something. It persists as
a `wf:WorkflowInstance` **inside the resource it drives** — found, secured and deleted along with it — plus
a token recording where it has got to and a `wf:TaskInstance` for each thing somebody still owes.

Running one is always the same walk, from wherever the token rests through whatever can be passed
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

**A user task says what it may be decided with.** `outcomes` on the activity lists the decisions on offer, and
the engine copies them onto each task it raises as `offeredOutcomes`. Copied rather than looked up, for the
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

**Read access is materialized when the instance starts.** Acting is authorized by the definitions, but
reading cannot be — a query returns rows, and no engine can run a workflow per row — so the workflow declares
and the engine writes an ACL: the person it is being run for, plus the performers of every user task in the
version. Deriving that from `performers` rather than inventing a second vocabulary means the two can never
disagree.

## Known gaps

- **Gateway conditions are an interim placeholder.** An arc is taken when its `conditionExpression` equals
  the instance's `outcome` variable, and the arc marked default is taken when none matches. That covers the
  approve-or-reject shape and deliberately nothing more; the demo's BPMN carries the real expression
  (`outcome == 'approved'`) which the conditions module will compile, and the stored graph carries the
  literal the engine can match today.
- **One token at a time.** Parallel and inclusive gateways are rejected rather than forked, and `terminate`
  on an end event is not yet distinguished from an ordinary one, since with a single token there is nothing
  else to discard.
- **Instance variables are not exposed to handlers.** The runtime persists `outcome` as a `wf:Variable`, but
  a service task inside an instance gets variables that live only for that delivery. Typed variables are
  already in the node types; wiring them to the SPI is what is missing.
- **Nothing delivers a timer or a message.** An instance that reaches a mid-process catching event is
  refused rather than parked, because nothing could ever wake it up again.
- **Read access is granted for the life of the instance**, not only while a task is open, and is never
  revoked. Narrowing it as state changes is a refinement for when there is a reason to want it.
- **`wf:SequenceFlow.conditionExpression` is a raw string.** It should use the structured conditions
  mechanism, the way schema items express their conditions; it is left as an expression until the
  conditions module lands.
- **Event definitions carry no payload yet.** A timer event is recognized as a timer, but there is
  nowhere to put its duration, and a message event records its `messageRef` without resolving it to the
  `<bpmn:message>` declared at document level — which is what the engine's event dictionary will need.
  The vocabulary can copy XML *attributes*; these payloads live in nested *elements*, and the mechanism
  for pulling those across is best designed alongside the parser that needs it.
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
- **No `oak:index` definitions**, and no frontend beyond the proof-of-concept BPMN editor.
