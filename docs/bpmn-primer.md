# A primer on workflows and BPMN

You already know how your institution's processes work: who fills in what, who has to
sign off, what happens when somebody objects, how long people are given. This page is
about writing that down in a form the platform can actually run — as a **diagram**, not
as code.

No programming is involved. If you can draw your process on a whiteboard, you can draw it
in the editor. What follows is the vocabulary, and enough of the rules to keep you out of
the usual traps.

## What a workflow is

A **workflow** is your process, written down precisely enough that the platform can carry
it out: who has to look at something, in what order, what people are allowed to do at
each point, and when it is finished.

Two words that come up constantly, and are easy to mix up:

- A **workflow** — sometimes a *workflow definition* — is the process itself, the diagram
  you draw. One per kind of thing: "how a leave request is handled".
- A **workflow instance** is one particular run of it. Priya's leave request from
  Tuesday. Hundreds of instances of one workflow can be in flight at once, each at its own
  point in the diagram.

Think of the workflow as the recipe and the instance as tonight's dinner.

## BPMN, and why a standard

The diagrams follow **BPMN** — Business Process Model and Notation — an international
standard for drawing processes. It is not something this platform invented: a BPMN
diagram means the same thing to a colleague at another institution, to a consultant, to a
textbook, and to the software.

You do not need to learn all of it. Four shapes will carry almost everything you draw.

## The four shapes

![The four basic BPMN shapes](images/bpmn-shapes.svg)

- An **event** — a circle — is something that happens. A request arrives; a deadline
  passes; the process finishes.
- An **activity** — a rounded box — is something that gets done. Usually called a
  **task**.
- A **gateway** — a diamond — is where the path splits or comes back together. A decision
  point.
- A **sequence flow** — an arrow — is what happens next.

The first three are **nodes**: the steps the platform works through when it runs your
diagram. The arrows connect them, and are not nodes themselves.

Put together, they read left to right like a sentence:

![A simple approval process, start to finish](images/bpmn-simple-flow.svg)

## Following the path: the token

When a process starts, imagine a marker being placed on the start event. It moves along
the arrows as things happen, and it stops wherever the process is waiting for somebody.
That marker is called a **token**, and it is the single most useful idea for reading a
diagram.

![The token moving through a process](images/bpmn-token.svg)

Where the token sits *is* the state of that request. If it is resting on "Review", then
somebody owes a review and nothing else will happen for that token until they give one.
When the token reaches an end event, that run is over.

This is also how the platform answers "what is waiting on me?" — it looks for tokens
resting on tasks you are allowed to act on.

## Events: how things start, pause, and finish

**Start event** (thin circle). Where a run begins. Every workflow needs one.

**End event** (thick circle). Where a run finishes. You may have several — "approved" and
"rejected" are perfectly good separate endings, and it is usually clearer than forcing
both into one.

**Intermediate event** (double circle). Something that happens in the middle. Two kinds
carry most of the load:

- A **timer**, drawn with a small clock, fires when a deadline passes or a stretch of
  time goes by — "five days after the review started".
- A **message**, drawn with an envelope, is how a process talks to the world outside it.
  A *throwing* message sends something — the email telling a reviewer they are needed —
  and the process carries straight on. A *catching* message waits for something to
  arrive before going any further.

These markers are not only for the middle. A start event with an envelope begins a run
because a message arrived, and an end event with one finishes by sending a notification —
which is how every notification in this page's diagrams is drawn.

Either kind can also sit on the *edge* of a task:

![Interrupting and non-interrupting boundary timers](images/bpmn-boundary-timer.svg)

An event sitting on the edge of a task is a **boundary event**. It watches for as long as
that task is open. This is how you say "give them five days, then send a reminder"
without anyone having to remember to check.

Look at the border, because it changes the meaning entirely. A **solid** border
*interrupts*: the task is abandoned and the flow leaving the event is taken instead. A
**dashed** border does *not*: the side branch runs while the task stays open. Reminders
should have a dashed border, so that the original task you are reminding about can
still be performed. If a task times out and needs to be cancelled, that would be a
timer with a solid border to halt the original work.

## Activities: who or what does the work

Two kinds matter.

A **user task** is work a person does — reviewing, approving, filling something in. The
process stops here and waits. This is where most of your diagram's meaning lives.

A **service task** is work the platform does by itself — filing a record, updating a
status, copying an answer onto the study. It takes no time from anyone's day and the
process carries straight on through it.

The distinction matters because **only user tasks make the process wait**. If your diagram
seems to finish instantly, it is probably all service tasks and you have not said where a
human is needed.

## Gateways: splitting and rejoining

![Exclusive, parallel and inclusive gateways](images/bpmn-gateways.svg)

An **exclusive gateway** (a diamond marked ×) is a fork in the road. Exactly one path is
taken, based on a condition — "was it approved?", "is it more than five days?". Label
every outgoing arrow with the condition that chooses it. BPMN allows exclusive gateways
to be drawn without the ×; we always use the version with the × for clarity.

A **parallel gateway** (a diamond marked +) sends the process down *every* path at once.
Use it when two reviews can happen independently and neither should wait for the other.

An **inclusive gateway** (a diamond marked with a circle) is the one in between: every
path whose condition holds is taken, which may be one of them, several, or all.

Every exclusive and inclusive gateway should carry a **default flow**, the outgoing arrow
drawn with a small cross near its start. It is the path taken when none of the other
conditions are satisfied so the process can always continue.

An exclusive split needs no join at all: only one branch can be taken, so there is never
more than one token to bring back together. However,
**parallel and inclusive splits need a matching join if branches need to merge.**
The second gateway waits for the branches that were started and then lets a single token
continue. Without it, later nodes may be processed multiple times, once by each followed
branch.

## Who is allowed to act

A task is not finished when somebody clicks a button; it is finished when somebody *who is
allowed to* clicks it. In the editor, each task carries a list of **performers** — the
groups or people who may act on it.

Two things worth knowing before you draw:

- **An empty list means nobody.** Leaving performers blank does not mean "anyone" — the
  task will refuse everybody until you say who. That is deliberate: a process that has
  forgotten to say who approves should stop, not let everyone approve.
- **Name groups, not people.** "Department managers" survives somebody leaving; "Sam
  Okafor" does not.

## A worked example

Here is the whole vocabulary in one diagram — the time-off request that ships with the
platform as a demonstration:

![A complete time-off request process](images/bpmn-time-off.svg)

Read it as: a request is filed. A manager has to decide, and only managers can. If five
days pass without a decision, the dashed timer fires, the manager is reminded, and that
little branch finishes on its own — the request is still sitting with the manager. Once
the decision comes, the path splits: approved requests get booked, and everything else
takes the default flow — the arrow with the cross — and ends with the requester being
told why. Either way the run ends.

Notice how much of the process is in the *labels*, not the nodes. "After 5 days",
"approved", "performers: managers" — the nodes give the skeleton, and the labels are
where your institution's actual rules live.

## Versions: why you cannot just edit a running process

Workflows are **versioned**. When you change a process, you are making a new version,
and the old one keeps running.

This matters more than it sounds. Requests already in flight continue under the rules
that applied when they started — which is usually what fairness requires, and always what
an auditor asks about. Somebody whose request was filed in March is judged by March's
process, even if you changed it in April.

The practical consequence: **do not expect an edit to affect requests already underway.**
If a change has to apply to them, that is a conversation to have before you make it.

## Getting started in the editor

1. **Sketch it on paper first**, in plain words. Who does what, in what order, what can
   go wrong. The editor is for writing it down, not for working it out.
2. **Start with the happy path.** Start event, the tasks in order, end event, all in a
   straight line. Get that right before adding any branches.
3. **Add the decisions.** Where the path forks, put a gateway — exclusive if exactly one
   path applies, inclusive if several may — label every arrow with its condition, and
   mark one of them as the default flow.
4. **Say who may act.** Go through every user task and set its performers. This is the
   step most often skipped, and a task with nobody named will not work.
5. **Add the time limits last.** Boundary timers on the tasks where waiting forever is a
   real risk.
6. **Read it back aloud**, following the arrows. If you cannot narrate it as a sentence,
   neither can anybody else.

## Common mistakes

| Mistake | What happens |
| --- | --- |
| No performers on a user task | Nobody can complete it; the request stops there for good |
| A gateway with no default flow | A request matching none of the conditions gets stuck |
| A parallel or inclusive split with no join | Everything after it happens multiple times, once by each followed branch |
| Naming individuals as performers | The process breaks when they change roles or leave |
| Everything is a service task | The process runs to the end instantly, resulting in no human processing |
| No timers | One unread email stalls a request indefinitely |
| A reminder drawn with a solid border | The task is cancelled instead of being nudged along |

## Glossary

| Term | Meaning |
| --- | --- |
| **BPMN** | Business Process Model and Notation — the standard these diagrams follow |
| **Workflow** (definition) | The process itself, as drawn |
| **Version** | One issue of a workflow. Runs that started under it keep following it |
| **Instance** | One particular run — one person's request |
| **Token** | The marker showing where a run has got to |
| **Node** | An event, gateway or task within a workflow. Something that is processed during execution of an instance |
| **Event** | A circle. Something that happens: a start, an end, a deadline |
| **Timer event** | An event that fires when a deadline passes or a stretch of time goes by |
| **Message event** | An event that sends something out of the process, or waits for something to arrive |
| **Boundary event** | An event on the edge of a task, watching while the task is open. Solid border: it interrupts the task. Dashed: it does not |
| **Activity / task** | A rounded box. Something that gets done |
| **User task** | A task a person does. The process waits |
| **Service task** | A task the platform does. The process carries on |
| **Gateway** | A diamond. The path splits or rejoins |
| **Exclusive gateway** (×) | One path only, chosen by a condition. An unmarked diamond means the same |
| **Parallel gateway** (+) | Every path, at once — and a matching one to rejoin them |
| **Inclusive gateway** (○) | Every path whose condition holds — one, several or all |
| **Default flow** | The arrow marked with a cross, taken when no other condition is satisfied |
| **Sequence flow** | An arrow. What happens next |
| **Performers** | Who is allowed to act on a task |

## Where to go next

This page covers what you need to read and draw a process. If you want to know how the
platform stores and runs what you have drawn — the node types, the engine, the parts a
developer works with — that is in [workflows.md](workflows.md).
