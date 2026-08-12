# Workflows

A workflow is the process something is put through: who has to look at a submission, in what order, what
may happen while they do, and when it is finished. Workflows are authored as BPMN 2.0 diagrams, stored in
the repository as a graph of nodes, and executed by creating an *instance* of one and moving tokens
through that graph.

This page describes the data model and the Sling Models over it. The execution engine is not written yet;
what exists today is everything it will read and write.

## The three trees

| Path              | Holds                                                                        |
|-------------------|------------------------------------------------------------------------------|
| `/Workflows`      | The workflows themselves — definitions, their versions, and the parsed graphs |
| `/WorkflowTypes`  | The vocabulary: what kinds of node exist at all                               |
| inside the resource | Runtime instances, in the `wf:instances` container of whatever they drive   |

### Definitions and versions

```
/Workflows                         wf:WorkflowsHomepage
└── timeOffRequest                 wf:WorkflowDefinition   title, active, systemWorkflow
    └── 1.0                        wf:WorkflowVersion      version, active, bpmnXmlParsedHash
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

### The vocabulary

`/WorkflowTypes` is the translation table between BPMN and the repository. Each `wf:FlowNodeType` says
that a given XML element means a given kind of node:

```json
"MessageStartEvent": {
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

`/WorkflowTypes` carries the `iap:Documented` mixin, so its catalogue is served at
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
        └── approve_1               wf:TaskInstance       taskDefinitionId, label, assignee, status, outcome
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
the latter.

Anything that workflows should be able to run over carries the `wf:WorkflowAttachable` mixin, which
autocreates the container:

```
[sub:Submission] > iap:Entity, wf:WorkflowAttachable

[wf:WorkflowAttachable]
  mixin
  + wf:instances (wf:WorkflowInstances) = wf:WorkflowInstances AUTOCREATED IGNORE
```

which gives `Submission.getWorkflowInstances()`. One thing may have several workflows running over it at
once — a review process and a periodic reminder, say — so it is a list, not a single lifecycle.

**`IGNORE` is load-bearing, not tidiness.** Every `iap:Entity` is `mix:versionable`, and so is a workflow
instance. Under the default on-parent-version setting, checking in a submission copies the entire live
workflow into version storage, and *restoring an earlier revision rolls the workflow back with it* — an
editor reverting a typo would quietly un-approve a proposal. Verified against Oak both ways: with the
default, a restore rewound the token to where it had been at check-in; with `IGNORE`, the submission
reverted and the workflow carried on untouched. The same reasoning is why `iap:Entity` declares its
`iap:links` child `IGNORE`.

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

## Known gaps

- **No execution engine.** The runtime node types exist and nothing yet writes them.
- **`wf:SequenceFlow.conditionExpression` is a raw string.** It should use the structured conditions
  mechanism, the way schema items express their conditions; it is left as an expression until the
  conditions module lands.
- **Whether system workflows persist state at all** is undecided. User workflows live inside the resource
  they drive; the bootstrap ones have no such resource yet when they run.
- **Event definitions carry no payload yet.** A timer event is recognized as a timer, but there is
  nowhere to put its duration, and a message event records its `messageRef` without resolving it to the
  `<bpmn:message>` declared at document level — which is what the engine's event dictionary will need.
  The vocabulary can copy XML *attributes*; these payloads live in nested *elements*, and the mechanism
  for pulling those across is best designed alongside the parser that needs it.
- **No lanes.** BPMN lanes are the natural place for "this task belongs to the coordinator, that one to
  the board", and task assignment currently has no source at all. Mapping lanes onto groups touches how
  `ApprovalRequirement.approverGroup` already works, so it is a design decision rather than an omission.
- **No subprocesses, call activities or multi-instance markers.** "One review per assigned reviewer" is a
  multi-instance activity in BPMN, and that is the first of these likely to be wanted.
- **Signal, escalation, conditional and link events** are unmapped; the vocabulary covers timer, message,
  error and terminate.
- **The `bpmn:` prefix is matched literally** rather than by namespace URI. Safe while every diagram comes
  from the in-app editor, which always emits that prefix; a document from elsewhere using `bpmn2:` or a
  default namespace would not be recognized.
- **No `oak:index` definitions**, and no frontend beyond the proof-of-concept BPMN editor.
