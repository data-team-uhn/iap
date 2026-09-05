# History

The history module (`modules/history`) records **what happened to the content**: who asked for it,
why, what it did to each resource, and — where the process says a milestone was reached — what the
content looked like afterwards. It is the record an authorization decision has to be defensible
against months later, when nobody remembers and the log file has rotated away.

> **Status.** The store, its node types, their Sling Models and the recorder exist. **Nothing writes
> to it yet.** Recording happens where every data change already happens — in the workflow engine —
> and the engine needs a phase that runs *after* it commits, which it does not have yet. Everything
> below describes a mechanism that is built and tested; the sentence that is not yet true is "the
> platform records its own actions".

## Two stores, one record

The content of a past state is a **JCR version**, in the repository's own version storage. Everything
a reader needs to know *about* a change is a node of ours under **`/History`**. The two halves are
joined by a version identifier on the entry.

That split is not a compromise, it is the only shape available, and both halves of the reason were
measured rather than assumed:

- **A version can never be annotated.** An `nt:version` and its `jcr:frozenNode` are entirely
  protected: no property, no mixin, not ever. So anything that might need to grow, be corrected, or
  be linked to a later decision cannot live in version storage.
- **Nor can the content copy be hand-rolled.** A subtree copied with `Workspace.copy` keeps its
  `REFERENCE` properties as *enforced* references, so every snapshot of a submission would pin its
  schema version, its questions and its documents against deletion forever, and the deletion module
  would start reporting history as the blocker. A frozen node is exempt from referential integrity —
  it can hold a reference to something later deleted — which is exactly what a record of the past
  needs.

Version storage has two more properties worth knowing, both of which the design leans on: frozen
copies of binaries **share the stored bytes** with the original, and a frozen node keeps the
original's `sling:resourceType`, so a past state can be read back through the ordinary Sling Models
and serialization stack.

## One commit, one action

**An action is one event delivery** — one thing a person or a timer asked for — and *not* one step of
the workflow that carried it out.

That follows from how the engine commits. `WorkflowEngineImpl` runs a workflow to quiescence and
commits everything at once, reverting the lot on failure, so an individual step is not separately
committed and cannot be a separate fact: a per-step record would assert several events inside one
atomic commit, every one of which would have to be un-asserted on a rollback.

It has a second benefit worth protecting. Switching the active workflow version retires one version
and activates another; that is **one action whether the definition spends one service task on it or
two**. Audit granularity must not change because somebody refactored a definition.

## The cause is recorded once; the effect is recorded per resource

One action commonly affects several resources, and usually does something *different* to each. Both
halves are needed to say so:

- **`hist:Action`** — the cause, recorded once: the actor, what was asked for, the workflow instance
  and the exact version in force, the activity and its label as the definition read at the time, the
  task, the outcome and what was said about it.
- **`hist:Entry`** — one per affected resource: which resource, where and what it was at the time,
  **the part it played**, which properties changed, and the snapshot if one was taken.

`role` on the entry is where the meaning lives. A record that only listed the resources an action
touched could not say which workflow version was *retired* and which *activated*; a record per
resource could not say that both happened together for one reason.

**The record never holds a value that changed, only the names of the properties that changed.** That
keeps it small, keeps it readable at a glance, and says something the snapshot beside it cannot.

Entries are named after the affected resource's identifier, so "what did this action do to that
resource" is a path rather than a search — and so the repository itself refuses a second entry for
the same resource in the same action.

## Recording

```java
final String action = this.recorder.record(session, RecordedAction
    // The canonical user id, as the repository spells it -- not ResourceResolver.getUserID()
    .by(actorId, "activateVersion")
    .workflow(instanceId, versionId)
    .activity(activity.getElementId(), activity.getLabel())
    .affecting(RecordedEffect.on(previous, "retired", "active"))
    .affecting(RecordedEffect.on(current, "activated", "active"))
    .build());
session.save();                       // the change and its record, together

// ... take the snapshots, which cannot be part of that commit ...

this.recorder.completeSnapshots(session, action, Map.of(current.getIdentifier(), versionName));
```

Two rules, and both are load-bearing.

**The record shares the caller's transaction.** `record` leaves its nodes *pending* in the session
the change itself is being made in, and the caller commits both together — so there can be no
committed change without its record, and no record of a change that was abandoned.

This is the exact opposite of [error tracking](error-tracking.md), which writes in a session of its
own, and the contrast is worth remembering whenever either is touched: a failure has to be recorded
*because* the caller's transaction is being rolled back, while history has to be rolled back *with*
it.

**Snapshots come afterwards, and cannot come with it.** A JCR check-in refuses to run while its
session has pending changes, and commits by itself. So taking a snapshot inside the caller's
transaction is both impossible and, in the cases where it would work, wrong — it would flush half of
the caller's work early and break the engine's all-or-nothing rule. Hence the two phases, and hence
`complete` on the action: until it is set, an entry with no snapshot may simply not have got one yet;
once it is set, an entry without one never wanted one. **An action left incomplete is the honest
record of a snapshot that was wanted and failed**, and is worth more than a record claiming the
action finished what it did not.

### Who may write

Because the writing happens in the caller's session, the privilege has to be the caller's. The store
grants it to the **`iap-history-writers`** group, and a module that records joins that group in its
own repoinit — a group rather than a list of names, so the store never has to know which modules
record. The store's own `iap-history` user is confined to maintaining the buckets.

### Where an action is filed

Actions are spread over a prefix tree, `/History/<xx>/<yy>/<zz>/<action>`, by
[`PrefixTree`](../modules/java-utils/src/main/java/io/uhndata/iap/utils/PrefixTree.java) — the same
mechanism the archive uses, and the same layout Oak files version histories under. A single parent
holding every action ever taken is a node no repository browser can open and one the repository
itself starts warning about.

One consequence for callers: **`PrefixTree.bucketFor` cannot run in the caller's session.** It saves
each bucket as it creates one, and calls `refresh(false)` to recover from a race, either of which
would commit or discard half of the caller's work. The recorder therefore creates a missing bucket in
a session of the store's own — buckets are inert and shared, so one left behind by a transaction that
then fails costs nothing — and refreshes the caller's session, keeping its pending changes, to see it.

## Reading

**Nobody reads the store directly.** No read access is granted on `/History` — not to `everyone`, not
to the people whose own work it describes. It is read by a service user which adapts what it finds
into what the person asking may see.

That is deliberate, and it is what makes a single stored history serve submitters and reviewers
alike: who may learn what about a past change is decided when the history is served, not by
permissions in the store. It also has to be that way, because the content half of the record lives in
JCR version storage, where per-version permissions cannot be expressed at all — Oak ties
version-storage reads to the versionable node's own path.

The cost is that redaction becomes application code the repository cannot enforce for us. It is one
service with one entry point, which is what makes that acceptable, and it is also why a bug there is
a disclosure bug rather than a display bug.

### A past state does not describe itself

Every node in a frozen subtree reports `nt:frozenNode` as its `jcr:primaryType`, and carries the real
type and the **live** resource's identifier under `jcr:frozenPrimaryType` and `jcr:frozenUuid` —
properties the JSON serializer drops as repository bookkeeping. Served as it stands, a past state
therefore has a type nothing in the application has heard of, and says nothing about what it is a copy
of.

`FrozenNodeProcessor` (in `modules/serialization/json`) puts them back where a reader expects them and
leaves out the copy's own. It is **enabled by default wherever the content is frozen**: an
unannounced past state is worse than none, because nothing downstream can tell that what it is
looking at is old.

## Read markers

`hist:Watchable` is a mixin for content that remembers who has looked at it, adding a `hist:lastSeen`
container with one `hist:Marker` per viewer, named after their canonical user id.

**Under the content, not under the person**, on the cardinalities: a busy reviewer accumulates a
marker for every item they ever open, while an item is only ever looked at by a handful of people.
The lookup that actually happens — what has this person seen of this item — is a direct path either
way, so what is left to decide by is which parent grows without bound. Nothing enumerates one
person's markers: a dashboard starts from what is assigned to them and asks about each item.

Two details that are easy to get wrong:

- **The container is `IGNORE` on-parent-version.** Without that, opening a page would change what a
  snapshot of it contains, and restoring an old revision would tell people they have not seen what
  they have.
- **Key on the canonical user id** — the one the repository itself uses — and never on
  `ResourceResolver.getUserID()`, which returns whichever spelling was typed at login, so `Admin` and
  `admin` would become two markers for one person. Ask the JCR session, whose user id *is* canonical;
  `getRemoteUser()` has the same defect and cannot be fixed by a resource provider.

A marker records **when** somebody looked, and not which version they saw. Snapshots exist only at
declared milestones, so the newest thing to have happened to an item usually has no version of its
own; a marker naming a version could not see it. Compare `seenAt` against the most recent action
naming the item. A "what changed since I looked" *diff* does need a snapshot to start from, which is
why the marker keeps that too — and why such a diff is necessarily coarser than the
has-anything-changed indicator beside it.

## What a version costs

Measured against a real on-disk Oak segment store, for a submission with 100 answers:

| versions | store | growth | nodes in version storage |
| --- | --- | --- | --- |
| 0 (live only) | 442 KiB | — | 4 |
| 10 | 1 980 KiB | +1 538 KiB | 1 024 |
| 30 | 5 084 KiB | +1 555 KiB | 3 064 |
| 50 | 8 179 KiB | +1 545 KiB | 5 104 |

About **154 KiB and 102 new nodes per version**, flat, for the life of the entity. Three things
follow, and they are why the design is shaped the way it is:

- **There is no delta storage.** A version whose content is *identical* to the one before costs
  153 KiB — the same as one that changed an answer. A frozen node is a full copy of the subtree,
  written fresh.
- **Nothing reclaims it.** A full compaction did not shrink the store; the frozen copies are
  reachable content, not garbage. Only removing versions frees the space.
- **Binaries are the cheap part**, dramatically so: ten versions of a submission carrying a 2 MiB
  document cost **76 KiB in total**, not 20 MiB, because every frozen copy points at the same stored
  bytes. Documents are not what makes versioning expensive — structure is.

So **snapshots are taken only where a workflow declares a milestone**, never on every change, and
check-in is treated as a milestone rather than as a way to make a node writable. Every incidental
check-in would cost a full copy for no information.

## Node types

| Type | What it is |
| --- | --- |
| `hist:Log` | The store root at `/History` and every prefix-tree bucket under it |
| `hist:Action` | One thing that was asked for, and the cause behind it |
| `hist:Entry` | What that action did to one resource, and the part it played |
| `hist:Annotation` | Something said about a past change afterwards — the only way to annotate one |
| `hist:Watchable` | Mixin: content that remembers who has looked at it |
| `hist:LastSeen` | The markers on one piece of content |
| `hist:Marker` | How much of one item's history one person has seen |

Nothing here is versionable and `/History` is not under a versionable ancestor, so no
on-parent-version attributes appear in `history.cnd` — with the single exception of `hist:Watchable`,
which is added to content that *is*.

## Deliberately not here

- **Purging does not reach version storage.** Deleting a versionable node leaves its history, and
  every frozen copy in it, readable and queryable under `/jcr:system` — `oak-core` does ship an
  `OrphanedVersionCleaner`, but it does not fire for a plain deletion. This is latent today, since
  nothing is ever checked in, and becomes real with the first snapshot. Making a purge complete means
  removing every non-root version; the emptied history node itself is protected and stays behind as
  an inert husk.
- **Retention.** Snapshots are kept forever. Since nothing else reclaims the space, thinning them is
  the only lever there is if the volume ever becomes a problem.
- **Restore.** There is no revert, and if one is added it must not be `VersionManager.restore`:
  reviews are part of a snapshot, so a repository-level rollback would roll back the conversation
  along with the answers — the same hazard that put `IGNORE` on the workflow-instance container, where
  reverting a typo would otherwise silently un-approve a proposal. A revert has to be a workflow step
  that reads a snapshot and **writes forward**, which also keeps the record append-only.
