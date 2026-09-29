# Workflows

A workflow is the process something is put through: who has to look at a submission, in what order, what
may happen while they do, and when it is finished. Workflows are authored as BPMN 2.0 diagrams, stored in
the repository as a graph of nodes, and executed by creating an *instance* of one and moving tokens
through that graph.

This page describes the data model and the Sling Models over it. The execution engine is not written yet;
what exists today is everything it will read and write.

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

Seven things exist for the engine rather than for the diagram, all set by hand today and by the BPMN
parser once it exists:

- **`messageName` on an event** is the domain event name it catches or throws, resolved from the BPMN
  `messageRef`. It is what an incoming event is matched against.
- **`targetResourceType` on a version**, e.g. `wf/WorkflowsHomepage`, is the resource type whose events
  that version handles, which is how a workflow describing the platform's own behavior is found. User
  workflows need none: they are reached through the schema version that references them.
- **A `cond:condition` on a start event** guards it: the workflow only starts when the condition holds for
  the event's target, evaluated by the [conditions](conditions.md) module. This is how several system
  workflows answer the same message in different states of the target, e.g. `activate` on a draft and on a
  retired schema version, and how an event the target's state does not allow is refused. At most one guard
  may hold at a time; two holding at once is a contradiction between definitions, and none holding is a 409.
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
  since a lifecycle is a state rather than a growing pile of markers. It is being replaced by the `addTag`
  and `removeTag` service tasks below; today only a user workflow's end event honours it.

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

### Events over HTTP

A `POST` to a resource under workflow control is a domain event, sent to the engine with the request
parameters as its payload (`:`-prefixed ones excluded). The event is the target's default — `create` on a
homepage, `complete` on a user task — unless a selector names one: `POST /Schemas/x/1.0.activate.json`
sends `activate`. Nothing is registered per event; a name no definition waits for is a 409.

The types under workflow control are the ones the definitions say: the `targetResourceType` of every system
workflow version, active or not, plus `wf/TaskInstance`. `WorkflowEventServlet` is bound to exactly those, with
any extension, and `WorkflowEventServletRegistrar` binds it again whenever `/SystemWorkflows` changes. So a
module brings a type under control by shipping a system workflow for it, and nothing else registers a servlet.

The one way around the engine is the `.import` extension, which forwards the request untouched to the Sling POST
servlet. The repository still decides who may write, and on content the engine manages only an administrator
can, so it is a tool for importing content by hand: `tools/dev/test-data/generate-test-data.sh` imports the demo
schema with `POST /Schemas.import`.

### Available events

`WorkflowEngine.getAvailableEvents(resource)` answers what the asking user could send to a resource right now:
on a task, `complete` while it is open and its activity names them; anywhere else, the message of every system
start event for the resource's type whose guard holds and whose `performers` admit them. It is the same
matching and the same performer check an event goes through, asked without sending one, so a frontend decides
what to offer from the workflows rather than from a copy of their rules.

Available means the engine would take the event, not that it will succeed: the payload can still be invalid,
and a step can still refuse, as a publish check refusing an incomplete schema would.

`WorkflowEngine.inspectWorkflow(resource, event, reader)` goes one step further and reads the definition that
would handle an event, e.g. how its steps are configured. Definitions are only readable through the engine's
own session, open only during the call, so the definition is handed to `reader` rather than returned.

Over HTTP it is the `events` serialization processor, off by default: `GET /Schemas.1.simple.events.json` adds
`@events` to the homepage and to each schema, which is how a listing learns its rows' actions in one request.

### Built-in service tasks

A few handlers are the engine's own, because what they do is generic:

| `handler` | Configuration | Does |
| --- | --- | --- |
| `createEntity` | `entityType` | Creates an entity of that type under the target, titled by the event's `title` |
| `startWorkflow` | `workflowFrom` | Starts the user workflow a chain of references leads to, e.g. `schemaVersion/workflow` |
| `addTag` | `tag`, `replaceExisting` | Places the tag; with `replaceExisting`, first removes the host's own tags sharing a category with it |
| `removeTag` | `tag` | Removes the tag |
| `sendEvent` | `message` | Sends that event, with the same payload, to what the execution created, or else the target |
| `updateContent` | `fields` (a child node listing the fields, with their `label`, `help`, `multiline`, `referenceType`, `referenceRoot`, `choices`, `appliesWhen`) | Applies the event's `patch` to what the execution created, or else the target |
| `createContent` | `types` (a child node listing the types it may create, each with its `nodeType` and `label`), `nameFrom` (optional) | Creates, in the target, content of the event's `type`, placed before the sibling the event names as `before`, or else last |
| `moveContent` | `within` (optional: a resource type the content must stay inside) | Moves the target into the event's `parent`, or within its own, placed before the sibling the event names as `before`, or else last |
| `copyContent` | `sourceType`, `skipProperties`, `dropTagCategories` (all optional) | Copies what the event's `source` holds into what the execution created, or else the target; without a `source`, does nothing |

The tag tasks are how a workflow says what it did to its host's state, so that a lifecycle is content: a
transition is a guarded start event followed by an `addTag` with `replaceExisting`. They act on what the
execution has created, once it has created something, and on the target otherwise, the same rule
`startWorkflow` follows. They may place and remove `system` tags. Only tags placed on the host itself are
touched; inherited or computed tags are unaffected.

`sendEvent` chains system workflows: `createSchema` creates the schema, then sends it `createVersion`, whose
own workflow creates the first version and tags it. The chained workflow runs inside the sending one, in the
same session and the same commit, so either both happen or neither does. It is matched, guarded and authorized
like any event, for the same user; the caller is still answered with what the first workflow created.
Workflows sending events to each other more than ten deep are taken to be looping, and refused.

`updateContent` changes content from one JSON object in the event's `patch`: a key left out is left alone,
`null` or blank removes the property, anything else is its new value, a list of values for a field holding
several, and a reference is given as the path of the node it points at. Only the fields the activity lists may
change, and of those only the ones the target's node type declares by name (not through a residual definition):
the declaration says whether a field is mandatory, whether it holds one value or several, and whether it holds
text, a whole number, a number, true or false, or a reference, and the patch must give values of that kind. A field
of any other type cannot be edited. So one activity can serve several types, each keeping to its own fields.

What the activity says about a field is content too: a `label`, a short `help` text, whether it is `multiline`,
the `referenceType` a reference must point at and the `referenceRoot` it must be under, and two rules the patch is
held to:

- **`choices`**: the values the field may take, as child nodes, each named by its value (or giving it as `value`)
  with an optional `label`; any other value is refused.
- **`appliesWhen`**: `{ "property": "dataType", "values": ["long", "double"] }`, the field applying only while that
  other property of the node holds one of those values, as the patch leaves it. Setting a field that does not apply
  is refused, and a field that stops applying loses its value unless it is mandatory; one naming no property applies
  nowhere.

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

The whole patch is checked before anything is written. The `fields` serialization adds `@fields` to content an
update would change: the fields the requesting user's `update` event would accept there, with everything the
activity says about them and what their declarations say (`kind`, `multiple`, `mandatory`, `default`), read from
the activity of the workflow that would run, so an editor offers exactly what the update accepts, and can apply
the same `appliesWhen` as the values change. `FieldsDialog` (`frontend-commons`) is that editor.

A workflow version may also carry a `notice`: words for the people it serves, while it is the workflow that would
take their event. Unlike its `description`, which says what it does for whoever maintains it, a notice is written
for its users. The `fields` serialization adds the update's as `@notice`, next to `@fields`: what the update allows,
in words. Where guarded updates split a lifecycle, each says what it allows, so a page can show whichever applies
without knowing the states, and the words change with the rule, in the same file.

```json
"v1": {
  "jcr:primaryType": "wf:WorkflowVersion",
  "notice": "Only the wording of this version can be corrected. Anything else needs a new version.",
  ...
}
```

`createContent` adds content inside the target: something of the `type` the event names, which must be one the
activity lists and one the target's type declares it holds. A child definition says what a node holds only if the
content model declares it and names a type (`+ * (sch:FormItem)`): the catch-alls every `sling:Folder` inherits from
JCR, and definitions requiring no more than `nt:base`, merely tolerate children. The new content goes before the
sibling named in `before`, or else last, and is named after the first words of the first field listed in `nameFrom`
that the event's `patch` gives, or else after its type. It is created empty: an `updateContent` task that follows
fills it in from the same patch, since it acts on what was created, and refuses to leave a mandatory field empty.
The `creatable` serialization adds `@creatable` to content a `create` event would add to: each type the requesting
user's workflow would create there, with its `label` and the `fields` it starts with, described as in `@fields`,
including the `default` values new content of that type starts with.

`moveContent` moves the target, with everything under it, into the node the event names as `parent`, or within its
own parent when it names none, before the sibling named in `before`, or else last: one task both reorders and moves.
The new parent must hold the target's type, by the same rule as `createContent`; nothing moves into itself; and an
activity naming a resource type as `within` keeps the target inside the same nearest ancestor of that type, such as
the schema version a question belongs to. A name already taken at the destination is replaced by a free one. The move
is made with the `ContentMover` service (`java-utils`), which lets the modules that name content by where it is
prepare first, as `MoveParticipant`s: the conditions module makes `answer` operands naming a moved question by path
name it by identifier. The new path is what later steps act on, and what the event is answered with.

`copyContent` is how a workflow starts something as a copy of something else, e.g. a schema version from
another. The copy is made with the `ContentCopier` service (`java-utils`), which copies any structure node by
node in the engine's commit: names, types, order and binaries are kept, references inside the copy point at the
copies and references outside are kept, and protected properties and modification stamps are left out.
`sourceType` refuses any other kind of source, `skipProperties` leaves out properties of the source node
itself, such as a label the copy has its own of, and `dropTagCategories` leaves out its tags in those
categories. What a module maintains rather than stores, it keeps out of copies, or adjusts in them, with a
`CopyParticipant`: the tags module leaves out computed tags, the links module the links container, and the
conditions module points `answer` operands at the copied questions.

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
