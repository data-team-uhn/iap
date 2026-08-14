# Demo: time off request

Someone asks for a day off; their approver accepts or refuses it.

This is the smallest process that is still a whole one, and it is deliberately the *first* thing built
rather than the last. Each platform capability gets proved by something in use here, not only by its own
unit tests.

```
mvn clean install
./start.sh --demo
```

## What it installs

| Path                        | What                                                                     |
|-----------------------------|--------------------------------------------------------------------------|
| `/Schemas/timeOffRequest`   | What a requester fills in — duration, dates, absence type, and a doctor's note when the absence is sick leave |
| `/Workflows/timeOffRequest` | The process, as BPMN: submitted → budget looked up → approve → approved or refused |
| `TimeOffBudgetHandler`      | The demo's own Java, plugged into the engine's service-task extension point |
| `demo-requester`            | Asks for the day off. Member of `time-off-requesters`                    |
| `demo-approver`             | Decides. Member of `time-off-approvers`, which the schema routes approval to |

Passwords match the usernames. That is fine precisely because this only ever runs behind `--demo` or the
demo integration-test suite; a real deployment installs none of it.

## What runs

The whole process, end to end, and `specs/demo/` walks through exactly this:

1. **`demo-requester` raises a request** — `POST /Submissions` with a `title` and this schema's version
   path. They hold no repository rights whatsoever; the bootstrap's start event names `everyone` as a
   performer, so the engine vets the schema version, creates the submission in its `draft` state and writes
   it on their behalf. The same user POSTing to `/Workflows` is refused with a 403, because that definition
   names `iap-administrators` instead — the two answers differ only in what the workflows say.
2. **The request goes under its workflow immediately**, because the schema version names one. On its way
   the walk passes a service task that looks up how many days the requester has left and records them on
   the request — the demo's own code, reached through the engine's handler extension point, since nothing
   about counting time off belongs in the platform. It then parks: a `wf:WorkflowInstance` appears inside
   the submission with a token on `fillIn`, the task the requester now owes.
3. **Filling the request in is that task**, performed by `@creator` — this request's own requester, which no
   group could name — and tagged `draft`, so the request is editable for exactly as long as the token rests
   there. Answers are saved one at a time as they are given, and the demo's budget rule refuses a save that
   would ask for more days than the lookup in step 2 found.
4. **`demo-requester` sends it** — `POST` to the `fillIn` task, with no outcome, because there is nothing to
   decide: it is finished or it is not. That is the whole of what a submit button does. The walk carries on
   to `approveRequest`, raises the approver's task, and the tag on that task moves the request to
   `submitted` — after which the save workflow refuses further answers, without being told anything new.
   Somebody else POSTing to `fillIn` is refused with a 403, the approver included.
5. **Reading follows from the same declarations**: starting the instance granted read to the requester and
   to `time-off-approvers`. A request `demo-requester` neither raised nor approves stays invisible to them.
6. **`demo-approver` decides** — `POST` to the task with `outcome=approved` or `rejected`, which are the
   outcomes that task declares. The gateway routes on the outcome, the end event it reaches says what that
   means to the submission, and its state becomes `approved` or `rejected`. The requester, who can see the
   task, is refused a 403 if they try to decide it themselves.

What the demo does *not* yet show: the boundary event and the reminder-after-two-days shape, since nothing
delivers timers yet; and the flow nodes here are hand-written to mirror the diagram, because the BPMN
parser that would generate them does not exist.

The schema version does point at its workflow version, so the two halves are already joined.

## Editing the process

`bpmn.xml`, an `nt:file` child of the workflow version, is the source of truth, and the visual editor is
how it is meant to be edited. It embeds diagram interchange as well as the process, so it renders rather
than arriving as an unpositioned pile of shapes.

Conventions worth keeping if you add to this:

- **Version nodes are named `v1`, not `1.0`.** A dot in a node name breaks Sling path resolution —
  `/Schemas/timeOffRequest/1.0.json` parses as the resource `.../1` with selector `0`, and 404s. The
  readable label lives in the `version` property. That is not an argument against `bpmn.xml`: a dot only
  bites when what follows it is an extension something else renders, or reads as a version number. Since
  nothing renders a workflow version as `xml`, a missing `bpmn.xml` still answers a clean 404.
- **References are written by path, not by UUID.** Prefix the property with `jcr:reference:` and give the
  target's path. Order the `Sling-Initial-Content` entries so the target loads first. Note that the JSON
  serializer embeds the referenced node rather than emitting the identifier; add the `-dereference`
  selector when you want the raw UUID back.
- **Initial content names a *directory*.** `SLING-INF/content/Schemas;path:=/Schemas` with the descriptor
  at `SLING-INF/content/Schemas/timeOffRequest.json`: the filename gives the node name, the directory gives
  the target tree. Naming the file instead is allowed and its descriptor is read correctly, but `path` is
  then ignored for placement and the node lands at the repository root.

## Where it is going

The feature list this grows into, each item chosen because it forces a platform capability into existence.
Done so far: the questions and their display conditions, the conditional doctor's note, and the budget
lookup as the first project-supplied handler. What is left:

- An approver looked up automatically rather than named by hand. **Needs a decision first**: `performers`
  is a property of the definition's flow node, shared by every instance of it, so "this requester's
  supervisor" cannot be expressed today — it needs either instance variables feeding performer resolution
  or a resolver extension point.
- Validating the doctor's note against the reason given for the absence. Waiting on the language-model
  module being built separately; `sch:DocumentRequirement.aiCheckPrompt` is where the instruction will go,
  and is deliberately left unset until something reads it.
- Requests over 30 days needing a second approval. **Blocked**: a gateway currently routes by matching a
  sequence flow's `conditionExpression` against the instance's `outcome` variable, so it cannot branch on
  the answers. `wf:SequenceFlow` carries a raw string where it should carry a `cond:condition`, which the
  conditions module can now support.
- Unapproved requests flagged after two days; email on creation, approval and staleness. **Blocked**:
  nothing delivers timers, so a parked catching event has no way to wake.
- Raising a request by email as well as through the UI. The engine's entry point is already
  channel-agnostic; what is missing is something that turns an arriving mail into an event.

Two things the demo is still waiting on from the platform rather than from itself: the BPMN parser, since
the flow nodes here are hand-written to mirror the diagram and both have to be edited together; and
answer options on `sch:Question`, which has `dataType` but no way to declare that "absence type" is one of
three values — so the questions that ought to be choices are text, and their display conditions compare
against strings the schema cannot constrain.

The budget-check service is meant to stay specific to this demo. It exists to prove that a project can
bring its own Java code, and each item above should identify an extension point the core needs to expose
rather than becoming a core feature itself. Its answers are canned for the same reason: a demo that
required a human resources system to run would prove nothing about the platform.
