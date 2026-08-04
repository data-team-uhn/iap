# Error tracking

The error tracking module (`modules/error-tracking`) records errors that a running instance could
not deal with on its own, storing them in the repository under **`/LoggedErrors`** and surfacing
them through the [status report](status.md). A log file rotates away and nobody reads it; a
recorded error waits until somebody asks how the instance is doing.

This is a **diagnostic sink, not a log replacement**: code should still log through SLF4J as usual,
and additionally record an error here when it is something a system administrator has to know about
even hours later.

The module is in two bundles. `iap-error-tracking-api` is the way in — the service, the static
facade, and the context a caller describes a failure with — and depends on nothing but Sling's
`Resource`, so anything that can fail may depend on it. `iap-error-tracking-impl` holds the
recording, the node types and the report, and depends on the data model and on tags.

## Recording an error

```java
ErrorLogger.logError(e);

ErrorLogger.logError(e, ErrorContext.of(MyComponent.class, "readDefinitions")
    .about(resource)
    .actingFor(resolver.getUserID())
    .with("attempt", attempt));
```

The static `ErrorLogger` facade is the **normal** way in, not a fallback for code that cannot hold
an OSGi reference. Its tolerance is the point: before the module starts and after it stops, the
call silently does nothing, which is exactly what a diagnostic sink should do. A mandatory
`@Reference ErrorLoggerService` would instead stop a perfectly good component from starting because
the thing that records its failures is missing. The service is still published for anyone who wants
OSGi to tell them about it.

Recording is **always safe to call** and never throws: the caller is by definition already dealing
with a failure and must not be handed a second one. It is also cheap — see
[Recording does not touch the repository](#recording-does-not-touch-the-repository).

### Describing the circumstances

`ErrorContext` carries what the caller knows beyond the failure itself. Every part is optional and
`null` is always ignored, so describing a failure never needs a null check at a site that is
already failing.

| Part | What it is for |
| --- | --- |
| `component` | The class that was running. For a plugin, the plugin's own class rather than the framework that called it, since that is what has to be fixed. Guessed from the topmost frame that is ours when the caller does not say |
| `operation` | What it was trying to do: a short label chosen in code, such as `computeTags` |
| `subject` | The path of the content it was working on |
| `actor` | The user it was working for. Absent for anything outside a request |
| details | Anything else worth knowing, rendered to text immediately |

`component` and `operation` take part in deciding whether two failures are **the same fault**, so
they must be constants chosen in code. A value that does not look like one — a path, an identifier,
a rendered value — is still recorded, but is quietly left out of the fault's identity rather than
being allowed to mint a record per distinct value.

### Something wrong that nothing was thrown for

Not every failure comes with a throwable. A mis-authored condition naming a comparator that does
not exist is exactly the "silently wrong, nobody was there to see it" case this module exists for,
and there is nothing to catch:

```java
ErrorLogger.logProblem("unknown comparator",
    ErrorContext.of(ConditionEvaluatorImpl.class, "evaluate").about(condition.getPath()));
```

Like `operation`, the problem is a **constant phrase** chosen in code; whatever varies goes in the
subject.

## What is stored

One node per **distinct fault**, under the `err:LoggedErrorsHomepage` node at `/LoggedErrors`. A
fault something was thrown for is an `err:LoggedFailure`; one nothing was thrown for is an
`err:LoggedProblem`. Both are `err:LoggedError`s, and both are ordinary `iap:Content`, so they read
as JSON, list through the pagination servlet, and carry tags like anything else.

| Property | Type | Meaning |
| --- | --- | --- |
| `component` | String | The class that was running |
| `operation` | String | What it was trying to do |
| `occurrences` | Long | How many times this fault has been recorded so far |
| `jcr:created` | Date | When it was **first** seen, autocreated through `mix:created` |
| `lastOccurrence` | Date | When it was **last** seen |
| `subjects` | String[] | A bounded sample of the paths it happened to, most recent first |
| `actors` | String[] | A bounded sample of the users it happened on behalf of |
| `lastContext` | String | Everything else said about the most recent occurrence |
| `type` | String | *(failures)* The class name of what was thrown |
| `messages` | String[] | *(failures)* A bounded sample of the messages it was seen with |
| `stackTrace` | String | *(failures)* The stack trace of one occurrence |
| `problem` | String | *(problems)* What is wrong |

No read access is granted on `/LoggedErrors` to anybody: a stack trace quotes whatever the failing
code was working on, which may be anything at all. What can reach it is the module's own service
user, the platform-wide readers Sling installs, and administrators.

### One node per distinct fault

A node is named after a **fingerprint of the fault**: the classes of the throwable chain, the
frames they were thrown at, and the component and operation the caller named. Recording is then a
lookup of one child by name — no query, and therefore no index to maintain.

What is deliberately **not** in the fingerprint is everything that varies with the data rather than
with the fault: the message, the subject, the actor. Broken code reporting `Cannot read
/Submissions/1234` and `Cannot read /Submissions/5678` is *one* fault seen twice, not two faults.

That distinction is load-bearing rather than cosmetic. Since [nothing is ever
deleted](#nothing-is-ever-deleted), a fingerprint that included the message would let one broken
code path over a large import mint tens of thousands of permanent nodes in a flat container that
the status report then lists on every poll. With the message out, the number of records is bounded
by the number of ways this build can fail — by the code, not by how much data flows through it.

Frame class names are normalized before hashing, because the JVM numbers the classes it generates:
the same lambda is `$$Lambda$14` in one run and `$$Lambda$27` in the next. Left alone, those
numbers would split one fault into as many records as the JVM felt like making classes.

### The samples are evidence, not a work queue

`subjects`, `messages` and `actors` keep a bounded, most-recent-first sample. There is deliberately
no count of how many distinct values were left out, because keeping one would mean either keeping
them all or making a number up.

So the report can tell you *that* something systematic is happening, *what kind*, and enough
examples to reproduce it — but it is not the place to ask "which submissions do I have to retry".
Content that has to be found and repaired must carry its own marker for that, the way a node whose
tags could not be computed carries `tagComputationFailed`.

### Recording does not touch the repository

`logError` fingerprints the failure, folds it into an in-memory tally and returns. A single
background thread writes the accumulated tallies out shortly afterwards, one commit per batch.

This is a requirement rather than an optimization. The callers most worth having are commit hooks,
and a commit hook runs *inside* a commit: Oak's segment store guards commits with a single
non-reentrant permit, so a session opened and committed from within a commit hook would block
forever on a permit its own thread already holds, wedging every write in the instance. Recording
asynchronously also means a failing loop costs one commit per interval instead of one per
occurrence, and a single writer removes the race that used to lose occurrence counts between
threads recording the same fault at once.

Three consequences worth knowing:

- a recorded error is not in the repository the instant the call returns;
- a failure raised *while* a record is being written is logged and dropped rather than tallied,
  which is what stops a fault that touches error tracking from feeding itself forever;
- the tally is bounded, and so is the rate at which one fault is written. Neither is a retention
  policy — the tally is keyed by fingerprint, so it is bounded by the same thing the stored records
  are. Should even that overflow, the count of what could not be kept up with is reported rather
  than quietly forgotten.

## Dealing with a recorded error

Nothing is ever deleted here, so without a second axis the first error an instance ever hits would
leave it reporting itself as unhealthy forever — and a report that is always red is one nobody
reads. Instead, an error is **acknowledged**: somebody looks at it and records what they decided.

```
POST /LoggedErrors/<fingerprint>.acknowledge
    resolution=known-issue
    note=fix is in the next release
```

The decision is appended as an `err:Acknowledgement` child — who decided, when, what, why, and how
much had happened by then — rather than replacing anything. An error that was acknowledged, then
recurred, then was acknowledged again keeps all three facts, which is the point of keeping errors
around at all.

`resolution` names one of the tags in the `error-triage` category, shipped by this module and
documented at [`/Tags.doc.md`](autodoc.md): `known-issue`, `wont-fix`, or a plain `acknowledged`. A
deployment that adds a triage tag of its own can use it with no code change.

### An error that happens again asks for attention again

The triage markers on an error are **computed** from its decisions by a tag processor, never placed
by hand. A decision records the occurrence count it was taken at, so:

- recording another occurrence increments the count past that, and the error goes back to
  `unacknowledged` on its own;
- that marker is aggregated, so `/LoggedErrors` itself carries it while anything under it needs
  attention, and the status report goes back to `ERROR`.

No scheduled job, no stale flag, and no clock comparison — a count cannot be confused by two things
happening in the same second, or by a clock that disagrees. "We acknowledged this because the fix
is in the next release" and "the fix did not work" are exactly the pair that a triage system has to
tell apart to stay trustworthy.

### Nothing is ever deleted

There is deliberately **no retention policy**. Every recorded error stays until somebody deals with
it. A time limit would be wrong because an error often cannot be fixed quickly — a partner
institution whose server dropped a data push may take days or weeks to come back, and the record of
what failed is exactly what is needed to retry it once they do. A fixed cap would be wrong for the
same reason: there is no count at which the oldest unresolved error stops mattering. The only thing
collapsed is a repeat of a fault already recorded, which loses no information because it is counted
instead. The service user is not even granted the privilege to remove a node, so this is a property
of the deployment rather than a promise made in code.

## The status report

The `LoggedErrorsStatusReporter` contributes a report tagged `problems` and `errors`:

| Situation | Status | Headline |
| --- | --- | --- |
| `/LoggedErrors` missing or of the wrong type | `ERROR` | `*ERROR*: Errors cannot be logged` |
| nothing recorded | `DEBUG` | `No errors are logged` |
| something needs attention | `ERROR` | `There are N errors logged, M occurrences in total` |
| everything acknowledged | `INFO` | `All N logged errors have been acknowledged` |

`INFO` for the acknowledged case, deliberately: `SUCCESS` would be a lie, since something did
break and the record is still there, while `WARNING` would still trip any monitor thresholding
above `INFO` — which is the exact noise acknowledging an error is meant to remove. `INFO` is
visible to a person asking and invisible to a monitor watching for anything worse.

What the report may say depends on who is reading. A component, an operation and a count describe
the instance's own code and are shown to anyone; a stack trace, a message, a path or a user quote
whatever the failing code was working on and are shown only to a reader who is logged in. So the
summary table — what broke, doing what, how often, when — is useful even on the unauthenticated
`/system/status`, which is new: previously an anonymous reader learned only that there were errors.

The privileged report describes the ten most recently seen in full and names *which* triage tag
silenced each acknowledged one, so nothing is ever silenced invisibly.

Listing the errors reads the children of `/LoggedErrors` directly rather than querying for them, so
no index is needed for the report to stay cheap.

## Over HTTP

Because a recorded error is ordinary content, it needs no serialization code of its own:

- `GET /LoggedErrors/<fingerprint>.json` — one recorded error;
- `GET /LoggedErrors.json` — the container, including the aggregated marker that answers "is
  anything wrong" in one property read;
- `GET /LoggedErrors.paginate.json?sortBy=lastOccurrence&descending=true` — a listing, with the
  pagination servlet's filtering;
- `GET /LoggedErrors.deep.json` — everything, which on a container that is never pruned can be very
  large. Admin-only and explicitly asked for, but worth knowing.

## Future work

- **Wiring the callers in.** Nothing records anything yet. The rule to apply: record a failure only
  when nobody will find out any other way *and* it is a fault of the machine or the deployment
  whose identity is bounded by how the instance is built, never by what a caller sent. That rule
  admits the tag propagation editor, the tag and link definition readers, the backlink listener and
  the JWT key loading; it excludes anything a client can trigger with input of its own choosing.
- **An index**, if the number of *distinct* faults on a real deployment ever grows enough that
  listing the container's children stops being cheap, or if searching the stack traces is ever
  wanted. It should ride along with the deferred index over the tag properties rather than being a
  module-private one.
