# History

**Module:** `modules/history` · **Bundle:** `iap-history-api` · **Packages:**
`io.uhndata.iap.history.api` (`HistoryRecorder`, `RecordedAction`, `RecordedEffect`),
`io.uhndata.iap.history.models`

Records what happened to the content: who asked for it, why, what it did to each
resource, and, where the process declares a milestone, what the content looked like
afterwards. An authorization decision has to be defensible against this record months
later.

> **Status.** The store, its node types, their Sling Models and the recorder are built
> and tested. **Nothing records yet**: the workflow engine does not call the recorder,
> and has no phase after its commit for the snapshots to run in. Nothing serves the
> history to a reader, and nothing writes read markers.

## Two stores, one record

The content of a past state is a **JCR version**, in the repository's version storage.
Everything said *about* a change is a node under **`/History`**. An entry's `snapshot`
holds the version's identifier, which joins the two.

- **A version cannot be annotated.** An `nt:version` and its `jcr:frozenNode` are
  protected: no property, no mixin. Anything that may grow, be corrected or be linked
  to a later decision lives under `/History`.
- **A snapshot is a version, never a copy.** A subtree copied with `Workspace.copy`
  keeps its `REFERENCE` properties enforced, so every copy would stop its schema
  version, questions and documents from being deleted. A frozen node is exempt from
  referential integrity.

A frozen copy shares a binary's stored bytes with the original, and keeps its
`sling:resourceType`, so a past state reads back through the ordinary Sling Models.

## Actions and entries

**An action is one event delivery**: one thing a person or a timer asked for, not one
step of the workflow that carried it out. The engine commits a whole run at once and
reverts it whole, so a step is never a fact on its own. Retiring one workflow version
and activating another is one action, whether the definition spends one service task on
it or two.

One action usually does something different to each resource it touches.
`hist:Action` records the cause, once. `hist:Entry` records what the action did to one
resource, and the `role` that resource played: which version was *retired* and which
*activated*.

| `hist:Action` | Holds |
| --- | --- |
| `createdBy` | **Mandatory.** The canonical username of who asked |
| `onBehalfOf` | Whom they acted on behalf of: a canonical username, or an email address or URI for an external user |
| `operation` | **Mandatory.** A short verb naming what was asked for, for readers of the log: `activate`, `purge` |
| `workflowInstance` | The path of the content workflow instance the action started or advanced |
| `workflowVersion` | The `jcr:uuid` of the version of the workflow that handled the request |
| `activityId`, `activityLabel` | The `elementId` of the step of that version that handled it, and the label it gives the step |
| `taskInstance` | The path of the `wf:TaskInstance` whose completion caused the action |
| `outcome`, `outcomeNote` | The task's outcome, one of its `outcomeOptions`, and what the person deciding wrote |
| `event` | The name of the workflow event that carried the request: `save`, `complete` |
| `component` | The class name of the component that acted, when no workflow handled the request |
| `parentAction` | The path of the action this one is part of |
| `occurredAt` | When it happened, where that is not when it was recorded; if absent, `jcr:created` |
| `complete` | `false` until the snapshots are in; see [Recording](#recording) |

| `hist:Entry` | Holds |
| --- | --- |
| `subject` | **Mandatory.** The `jcr:uuid` of the affected node |
| `subjectPath`, `subjectType` | **Mandatory.** Its path and its primary node type, such as `sub:Submission`, at the time |
| `role` | **Mandatory.** The part it played: `submitted`, `retired`, `activated` |
| `changes` | The names of the JCR properties that changed |
| `snapshot` | The `jcr:uuid` of the JCR version taken after the action, if any |

`jcr:createdBy` on an action is whoever wrote the record, often a service user. The
person who asked for it is `createdBy`, which `Action.getRequestedBy()` reads. `role` is
free-form: the workflow definitions own the vocabulary.

**The record holds the names of the properties that changed, never their values.** A
record carrying a value would have to be redacted when the content it describes is
purged.

An entry is named after its subject's identifier, so the repository refuses a second
entry for the same resource in the same action.

## Recording

```java
// HistoryRecorder
String record(Session session, RecordedAction action);       // the action's path
void   completeSnapshots(Session session, String actionPath,
                         Map<String, String> snapshots);      // subject id -> version id

// RecordedAction.by(requester, operation), then any of
  .onBehalfOf(principal)  .workflow(instance, version)  .activity(id, label)
  .task(instance, decision, note)  .event(name)  .component(name)  .partOf(identifier)
  .affecting(effect)  .build()

// RecordedEffect: reads the identifier, path and type off the node
static RecordedEffect on(Node subject, String role, String... changes);
```

An illustrative call, for activating a workflow version, which retires the one it
replaces. Activation runs a system workflow, which has no instance to name:

```java
final String action = this.recorder.record(session, RecordedAction
    .by(UserIds.canonical(resolver), "activate")
    .activity(activity.getElementId(), activity.getLabel())
    .affecting(RecordedEffect.on(previous, "retired", "tags"))
    .affecting(RecordedEffect.on(current, "activated", "tags"))
    .build());
session.save();

final Version snapshot = versionManager.checkin(current.getPath());

this.recorder.completeSnapshots(session, action,
    Map.of(current.getIdentifier(), snapshot.getIdentifier()));
```

**The record shares the caller's transaction.** `record` leaves its nodes pending in the
caller's session, and the caller commits them with the change. No committed change
lacks its record, and no abandoned change leaves one.
[Error tracking](error-tracking.md) is the opposite: a failure must be recorded although
the transaction it broke is rolled back, so its record is written apart from it.

**Snapshots come after the commit.** A JCR check-in refuses to run while its session has
pending changes, and commits by itself. So the caller commits, takes the snapshots, and
then calls `completeSnapshots`. Until then the action's `complete` is `false`, and an
entry without a `snapshot` may not have got one yet. Once it is `true`, an entry without
one never wanted one. An action left incomplete records a snapshot that was wanted and
failed.

### Who may write

The writing happens in the caller's session, so the privilege has to be the caller's.
The store's repoinit grants read and write on `/History` to the
**`iap-history-writers`** group, and a module that records joins the group in its own
repoinit. The store's own `iap-history` service user holds the same grants: the
recorder creates buckets as it, and it is the user the history is read as.

**Nobody holding these grants can delete an action or an entry**: neither
`jcr:removeNode` nor `jcr:removeChildNodes` is granted. They can change a property,
which attaching a snapshot afterwards needs.

### Where an action is filed

An action has a random name, and is filed in a prefix tree under
`/History/<xx>/<yy>/<zz>/<action>` by
[`PrefixTree`](../modules/java-utils/src/main/java/io/uhndata/iap/utils/PrefixTree.java).

**A missing bucket is created in the store's own session**, and the caller's session
is then refreshed with `refresh(true)` to see it. In the caller's session,
`PrefixTree.bucketFor` would save or discard the caller's pending work, since it saves
each bucket and calls `refresh(false)` after a race. `PrefixTree.pendingBucketFor`
would make two callers adding the same new bucket conflict, and fail the second
caller's change. A bucket left behind by a transaction that later fails is empty and
harmless.

## Reading

**No person reads the store directly.** No person is granted read on `/History`, not
even the people whose work it describes. The history is meant to be served by a service
that reads the store as `iap-history` and adapts what it finds to what the person asking
may see. Oak
cannot express a permission on a single version, so who may learn what about a past
change is decided when the history is served. A bug in that service would be a
disclosure bug.

### A past state does not describe itself

Every node in a frozen subtree reports `nt:frozenNode` as its `jcr:primaryType`, and
keeps the real type and the live resource's identifier under `jcr:frozenPrimaryType`
and `jcr:frozenUuid`. The `simple` JSON processor drops both.

`FrozenNodeProcessor` (the `frozen` processor, in `modules/serialization/json`) serves
them as `jcr:primaryType` and `jcr:uuid`, and leaves out the copy's own type and
identifier and the original's mixins. It is enabled by default when the resource
requested is frozen, since nothing downstream could otherwise tell that what it reads is
old. Serializing a version or a version history does not enable it for the frozen nodes
inside: ask for the `frozen` selector.

## Read markers

`hist:Watchable` is a mixin for content that remembers who has looked at it. It adds a
`hist:lastSeen` container holding one `hist:Marker` per viewer, named after the
viewer's canonical user id.

| `hist:Marker` | Holds |
| --- | --- |
| `seenAt` | **Mandatory.** When they last looked |
| `seenAction` | The path of the newest action they had seen, for when the time cannot tell two apart |
| `seenSnapshot` | The `jcr:uuid` of the newest JCR version of the content they had seen |

Markers are filed under the content, not under the person. An item is looked at by a
handful of people, while a busy reviewer opens a great many items. Finding what one
person has seen of one item is a path either way, so what decides it is which parent
grows without bound.

- **`hist:lastSeen` is `IGNORE` on-parent-version.** Without it, opening a page would
  change what its next snapshot holds, and restoring an old revision would tell people
  they have not seen what they have.
- **Key on the canonical user id** from `UserIds.canonical`, never
  `ResourceResolver.getUserID()`. That returns the spelling typed at login, so `Admin`
  and `admin` would become two markers for one person.

`seenAt`, not a version, answers "has anything happened since I looked": compare it
against the most recent action naming the item. Snapshots exist only at milestones, so
the newest change to an item usually has no version of its own. A "what changed since I
looked" diff starts from `seenSnapshot`, and is coarser for the same reason.

## What a version costs

Measured on Oak 2.4 against an on-disk segment store, for a submission with 100
answers:

| versions | store | nodes in version storage |
| --- | --- | --- |
| 0 (live only) | 442 KiB | 4 |
| 10 | 1 980 KiB | 1 024 |
| 30 | 5 084 KiB | 3 064 |
| 50 | 8 179 KiB | 5 104 |

About **154 KiB and 102 nodes per version**, flat for the life of the submission.

- **A version that changed nothing costs the same.** A frozen node is a full copy of
  the subtree.
- **Compaction reclaims none of it.** The copies are live content. Only removing
  versions frees the space.
- **Binaries are cheap.** Ten versions of a submission carrying a 2 MiB document cost
  76 KiB in total, since every copy points at the same stored bytes.

So **snapshots are taken only where a workflow declares a milestone**, never on every
change. An incidental check-in costs a full copy and records nothing.

## Node types

| Type | What it is |
| --- | --- |
| `hist:Log` | The store root at `/History`, and every prefix-tree bucket under it |
| `hist:Action` | One request somebody made: who, what, and what handled it |
| `hist:Entry` | What an action did to one resource, and the part it played |
| `hist:Annotation` | A note a person added to a recorded change: a correction, a clarification, a decision |
| `hist:Watchable` | Mixin: content that remembers who has looked at it |
| `hist:LastSeen` | The markers on one piece of content |
| `hist:Marker` | How recently one person looked at one piece of content |

## What is still missing

- **Nothing records.** The engine does not call the recorder, and has no phase after
  its commit for the snapshots to run in.
- **Nothing serves the history.** The service that reads the store as `iap-history` and
  decides what each person may see of it is not written.
- **Nothing writes read markers**, and no node type declares `hist:Watchable`.
- **Purging does not reach version storage.** Deleting a versionable node leaves its
  history readable under `/jcr:system`. Oak's `OrphanedVersionCleaner` does not fire for
  a plain deletion. A complete purge removes every non-root version; the emptied
  history node is protected and stays behind, inert. Latent until the first snapshot.
- **No retention.** Snapshots are kept forever, and thinning them is the only way to
  reclaim the space.
- **No revert.** If one is added, it must not be `VersionManager.restore`. Reviews are
  part of a snapshot, so restoring one would roll back the conversation along with the
  answers, and a typo fix could silently un-approve a proposal. A revert is a workflow
  step that reads a snapshot and writes forward.
