# Demo: time off request

Someone asks for time off; their approver accepts or refuses it.

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
| `/Workflows/timeOffRequest` | The process, as BPMN: raised → budget looked up → filled in and sent → flagged if it starts soon → approved or refused, and the requester told |
| `TimeOffBudgetHandler`      | Looks up the days left, through the engine's service-task extension point |
| `TimeOffBudgetValidator`    | Refuses an answer asking for more days than are left                      |
| `TimeOffDatesEvaluator`     | Keeps a request whose last day away comes before its first from being sent |
| `MarkUrgencyHandler`, `NightlyUrgencySweep` | Flag a sent request whose time off starts no later than tomorrow, when it is sent and again every night |
| `/SystemWorkflows/reassessTimeOffUrgency` | What the nightly sweep's event runs: the same urgency step, on one request |
| `demo-requester`            | Asks for time off. Member of `time-off-requesters`                       |
| `demo-approver`             | Decides. Member of `time-off-approvers`, which the schema routes approval to |

Passwords match the usernames. That is fine precisely because this only ever runs behind `--demo` or the
demo integration-test suite; a real deployment installs none of it.

## What runs

The whole process, end to end, and the stories in `specs/demo/stories/` walk through exactly this:

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
   group could name. The request has been `draft` since it was raised, and stays editable for exactly as long
   as the token rests there. Answers are saved one at a time as they are given, and the demo's budget rule refuses a save that
   would ask for more days than the lookup in step 2 found. A span whose last day comes before its first is
   saved but keeps the request incomplete, so that moving a span one day at a time never meets a refusal.
4. **`demo-requester` sends it** — `POST` to the `fillIn` task, with no outcome, because there is nothing to
   decide: it is finished or it is not. That is the whole of what a submit button does. The walk tags the
   request `submitted`, which retires `draft`, and carries on to `approveRequest`, the approver's task. From
   then on the save workflow refuses further answers, without being told anything new.
   Somebody else POSTing to `fillIn` is refused with a 403, the approver included.
5. **Reading follows from the same declarations**: starting the instance granted read to the requester and
   to `time-off-approvers`. A request `demo-requester` neither raised nor approves stays invisible to them.
6. **`demo-approver` decides** — `POST` to the task with `outcome=approved` or `rejected`, which are the
   outcomes that task declares. The gateway routes on the outcome, the end event it reaches says what that
   means to the submission, and its state becomes `approved` or `rejected`. The requester, who can see the
   task, is refused a 403 if they try to decide it themselves.

Around that spine the approval task is watched by two boundary timers — a non-interrupting one that marks
the request overdue after two days and leaves it where it is, and an interrupting one that expires it after
five. The approved and refused branches each tell the requester through a `notify` step; the overdue and
expiry branches only tag the request.

A sent request whose time off starts tomorrow or sooner is flagged `urgent`, first by a step right after it
is sent. The calendar changes nothing on a request, so every night the demo's sweep sends each waiting
request a `reassessTimeOffUrgency` event, and the system workflow of that name runs the same step. The engine
makes the change either way.

The schema version points at its workflow version, so the two halves are joined.

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
- Validating the doctor's note against the reason given for the absence. The language-model client exists
  (`modules/llm`); what is missing is a check that sends it the document and the instruction.
  `sch:DocumentRequirement.aiCheckPrompt` is where the instruction will go, and is deliberately left unset
  until something reads it.
- Requests over 30 days needing a second approval. **Partly unblocked**: a gateway's guard can read the
  request's answers, since an `answer` condition asked inside a workflow searches up to its submission. Two
  things are still missing. A guard names a question by a path relative to its own entity, the workflow
  version, so a shipped workflow can name the schema's questions only by an identifier minted at install.
  And `endDate - startDate > 30` is not a comparison of two operands, so date arithmetic is missing too.
- Unapproved requests flagged after two days and expired after five. **Built**, both halves: a non-interrupting boundary timer
  marks the request overdue at two days without moving it off anybody's desk, and an interrupting one
  expires it at five rather than leaving it waiting forever.
- Email on approval and rejection. **Built**: the end of each branch is a `notify` service task naming a
  wording folder, and the rejection quotes the reason the approver gave. Still missing is email on
  *creation*, and anything about a request going stale.
- Raising a request by email as well as through the UI. The engine's entry point is already
  channel-agnostic; what is missing is something that turns an arriving mail into an event.

One thing the demo is still waiting on from the platform rather than from itself: a way to name the
instance's external address, so that a notification can link to the request instead of quoting its
repository path.

The budget-check service is meant to stay specific to this demo. It exists to prove that a project can
bring its own Java code, and each item above should identify an extension point the core needs to expose
rather than becoming a core feature itself. Its answers are canned for the same reason: a demo that
required a human resources system to run would prove nothing about the platform.
