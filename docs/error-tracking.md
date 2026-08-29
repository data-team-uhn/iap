# Error tracking

**Module:** `modules/error-tracking`, in two bundles ·
**API:** `io.uhndata.iap.errortracking.api` · **Impl:**
`…errortracking.internal`, `…errortracking.models`

Records errors a running instance could not deal with on its own, under
`/LoggedErrors`, and surfaces them through the [status report](status.md). A log file
rotates away; a recorded error waits until somebody asks how the instance is doing.

This is a **diagnostic sink, not a log replacement**. Keep logging through SLF4J, and
additionally record here when a system administrator has to know hours later.

`iap-error-tracking-api` depends on nothing but Sling's `Resource`, so anything that
can fail may depend on it. `iap-error-tracking-impl` holds the recording, node types
and report, and depends on the data model and tags.

## Recording

```java
ErrorLogger.logError(e);

ErrorLogger.logError(e, ErrorContext.of(MyComponent.class, "readDefinitions")
    .about(resource)                        // String path or Resource
    .actingFor(resolver.getUserID())
    .with("attempt", attempt));

ErrorLogger.logProblem("unknown comparator",
    ErrorContext.of(ConditionEvaluatorImpl.class, "evaluate").about(condition.getPath()));
```

The static `ErrorLogger` facade is the **normal** way in, not a fallback for code
that cannot hold an OSGi reference. Before the module starts and after it stops the
call silently does nothing — a mandatory `@Reference ErrorLoggerService` would stop a
working component from starting because the thing that records its failures is
missing. `ErrorLoggerService` is published for anyone who wants OSGi to tell them
about it, and exposes `getDroppedCount()`.

Recording never throws and never blocks on the repository. The caller is by
definition already handling a failure and must not be handed a second one.

`logProblem` covers failures with nothing to catch — a mis-authored definition is
exactly the "silently wrong, nobody saw it" case this module exists for.

### ErrorContext

Every part is optional and null is always ignored, so describing a failure needs no
null checks at a site that is already failing.

| Part | For |
|---|---|
| `component` | The class that was running. For a plugin, the plugin's own class, not the framework that called it. Inferred from the topmost `io.uhndata.iap.` frame when unset |
| `operation` | What it was trying to do — a short label chosen in code, e.g. `computeTags` |
| `subject` | Path of the content it was working on |
| `actor` | User it was working for; absent outside a request |
| `with(k, v)` | Anything else, rendered to text immediately. Up to 20 entries, 500 chars each |

### Identity has to be constant

`component`, `operation` and a problem phrase decide whether two failures are **the
same fault**, so they must be constants chosen in code. The check is shape-based:
`[A-Za-z0-9_.$ -]`, up to 64 characters for a label and 255 for a component (several
of this build's own class names exceed any label length).

A value failing that check is **still recorded but left out of the identity**, rather
than being allowed to mint a record per distinct value. For a problem phrase, the
leading run of label characters is used — `unknown comparator: 'sameDay'` fingerprints
as `unknown comparator`, with the full phrase kept in `messages` exactly as a
throwable's message would be. A phrase with no usable head becomes `unspecified
problem`; nothing is dropped, since silent dropping in *this* module would be the
worst possible bug.

## Storage

One node per **distinct fault** under `/LoggedErrors` (`err:LoggedErrorsHomepage`).
A fault something was thrown for is an `err:LoggedFailure`, one nothing was thrown
for an `err:LoggedProblem`; both are `err:LoggedError` and ordinary `data:Content`, so
they serialize, paginate and carry tags like anything else.

| Property | Type | Meaning |
|---|---|---|
| `component`, `operation` | String | What was running, and doing what |
| `occurrences` | Long | Times recorded so far |
| `jcr:created` / `lastOccurrence` | Date | First and last seen |
| `subjects` / `actors` / `messages` | String[] | Bounded samples, most recent first (20 / 10 / 5) |
| `lastContext` | String | Everything else said about the most recent occurrence |
| `type` / `stackTrace` | String | *(failures)* what was thrown, and one trace |
| `problem` | String | *(problems)* what is wrong |

**Nobody is granted read access** on `/LoggedErrors`: a stack trace quotes whatever
the failing code was working on. Reachable by the module's service user, Sling's
platform readers, and administrators.

### The fingerprint

The node name is a SHA-256 of the component, the operation, and — for a failure — the
throwable chain's class names and frames (up to 10 causes, 50 frames each). Recording
is then a lookup of one child by name: no query, so no index to maintain.

Deliberately **not** in the fingerprint: the message, the subject, the actor.
`Cannot read /Submissions/1234` and `…/5678` are one fault seen twice.

That is load-bearing, not cosmetic. Since nothing is ever deleted, a fingerprint
including the message would let one broken code path over a large import mint tens of
thousands of permanent nodes in a flat container the status report lists on every
poll. Without it, the record count is bounded by the number of ways this build can
fail rather than by how much data flows through it.

Frame class names are normalized before hashing (`0x…` addresses, `$$Lambda$N`,
`$ProxyN`, `AccessorN`), because the JVM numbers generated classes differently each
run and would otherwise split one fault across many records.

### Samples are evidence, not a work queue

There is deliberately no count of distinct values left out — keeping one would mean
keeping them all or making a number up. The report can tell you *that* something
systematic is happening, *what kind*, and enough examples to reproduce it. It is not
where you ask "which submissions must I retry": content needing repair carries its
own marker, the way a node whose tags could not be computed carries
`tagComputationFailed`.

### Recording does not touch the repository

`logError` fingerprints, folds into an in-memory tally, returns. A single
`iap-error-tracking` thread writes accumulated tallies out, one commit per batch,
prompted both by recording and by its own timer (default 60s, `WRITE_INTERVAL_MS`),
which also bounds how often one fault is written.

This is a requirement, not an optimization. The callers most worth having are commit
hooks, and Oak's segment store guards commits with a single **non-reentrant** permit —
a session committed from inside a commit hook would block forever on a permit its own
thread already holds, wedging every write in the instance.

Consequences:

- A recorded error is not in the repository the instant the call returns.
- A failure raised *while* a record is being written is logged and dropped, not
  tallied — a `ThreadLocal` guard is what stops a fault touching error tracking from
  feeding itself.
- The tally is bounded (`MAX_PENDING` 1000, keyed by fingerprint, so bounded by the
  same thing the records are). Overflow is counted and reported, never quietly
  forgotten. After 3 consecutive failed writes the writer pauses 5 minutes; a batch
  that fails to write is folded back into the tally.

## Acknowledging

Nothing is deleted, so without a second axis the first error an instance hit would
leave it reporting itself unhealthy forever — and an always-red report is one nobody
reads.

```
POST /LoggedErrors/<fingerprint>.acknowledge.json
    resolution=known-issue
    note=fix is in the next release
```

The decision is **appended** as an `err:Acknowledgement` child — who, when, what,
why, and the occurrence count at the time — rather than replacing anything. An error
acknowledged, then recurred, then acknowledged again keeps all three facts.

`resolution` names a tag in the `error-triage` category shipped by this module
(`known-issue`, `wont-fix`, plain `acknowledged`); a deployment can add its own with
no code change.

Triage markers are **computed** from the decisions by a tag processor, never placed by
hand. Because a decision records the occurrence count it was taken at, another
occurrence pushes the count past it and the error returns to `unacknowledged` on its
own, and since that marker is aggregated, `/LoggedErrors` carries it while anything
under it needs attention. No scheduled job, no stale flag, no clock comparison — a
count cannot be confused by two things in the same second or by a disagreeing clock.

**No retention policy**, deliberately. An error often cannot be fixed quickly — a
partner institution whose server dropped a push may take weeks, and the record is
what is needed to retry. A fixed cap fails for the same reason: there is no count at
which the oldest unresolved error stops mattering. Only a repeat of an already-recorded
fault is collapsed, which loses nothing because it is counted. The service user is not
granted the privilege to remove a node, so this is a property of the deployment rather
than a promise in code.

## The status report

`LoggedErrorsStatusReporter` contributes a report tagged `problems` and `errors`:

| Situation | Status | Headline |
|---|---|---|
| `/LoggedErrors` missing or wrong type | `ERROR` | `*ERROR*: Errors cannot be logged` |
| nothing recorded | `DEBUG` | `No errors are logged` |
| a failure needing attention still happening | `ERROR` | `There are N errors logged, M occurrences in total` |
| needs attention, none still happening | `WARNING` | `…no failure seen in the last 60 minutes` |
| everything acknowledged | `INFO` | `All N logged errors have been acknowledged` |
| nothing recorded, but faults dropped | `WARNING` | `*WARNING*: N errors could not be recorded` |

Two things are deliberately **not** enough for `ERROR` on their own, both
configurable on **Error Report**
(`io.uhndata.iap.errortracking.internal.ErrorReportConfiguration`):

| Setting | Default | Meaning |
|---|---|---|
| `recentFailureWindow` | `60` | Minutes after last being seen that a failure still counts as happening now |
| `problemsAreUrgent` | `false` | Whether a problem can raise `ERROR` the way a failure does |

A deployment wanting to be woken for anything unacknowledged sets a window longer
than an instance ever runs — **not zero**, which cannot say what is happening now and
would silence every `ERROR` here; it is refused, with a line in the log.

Deliberate choices in the remaining statuses:

- **Overflow is loud but not red.** The dropped count is cumulative for the life of
  the process, so `ERROR` would keep an instance that hit one burst red until
  restart. It is counted in every row above, headline included, however much has been
  acknowledged — nobody ever saw what was dropped, so nothing can have acknowledged
  it. It names no content, so any reader sees it.
- **`INFO`, not `SUCCESS` or `WARNING`, for the acknowledged case.** `SUCCESS` would
  be a lie; `WARNING` still trips any monitor thresholding above `INFO`, which is the
  exact noise acknowledging is meant to remove.

Field visibility splits by reader: component, operation and counts describe the
instance's own code and are shown to anyone, including on the unauthenticated
`/system/status`; stack traces, messages, paths and users quote what the failing code
was working on and need a logged-in reader. The privileged report describes the ten
most recently seen in full and names *which* triage tag silenced each acknowledged
one, so nothing is silenced invisibly.

Listing reads the children of `/LoggedErrors` directly rather than querying, so the
report stays cheap without an index.

## Over HTTP

A recorded error is ordinary content, so it needs no serialization code of its own:

| Request | Returns |
|---|---|
| `/LoggedErrors/<fingerprint>.json` | one recorded error |
| `/LoggedErrors.json` | the container, including the aggregated marker that answers "is anything wrong" in one property read |
| `/LoggedErrors.paginate.json?sortBy=lastOccurrence&descending=true` | a listing, with the pagination servlet's filtering |
| `/LoggedErrors.deep.json` | everything — very large on a container that is never pruned. Admin-only and explicitly asked for |

## Future work

**An index**, if the number of *distinct* faults on a real deployment ever grows
enough that listing the container's children stops being cheap, or if searching stack
traces is wanted. It should ride along with the deferred index over the tag
properties rather than being module-private.

When adding a caller, the rule: record only when nobody will find out any other way
*and* the fault is one of the machine or the deployment, whose identity is bounded by
how the instance is built — never by what a caller sent. That admits the tag
propagation editor, definition readers, the backlink listener and JWT key loading; it
excludes anything a client can trigger with input of its own choosing.
