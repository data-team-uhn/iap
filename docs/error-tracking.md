# Error tracking

The `iap-error-tracking` module (`modules/error-tracking`) records errors that a running instance
could not deal with on its own, storing their stack traces in the repository under
**`/LoggedErrors`** and surfacing them through the
[status report](status.md). A log file rotates away and nobody reads it; a recorded error waits
until somebody asks how the instance is doing.

This is a **diagnostic sink, not a log replacement**: code should still log through SLF4J as usual,
and additionally record an error here when it is something a system administrator has to know about
even hours later.

## Recording an error

Two ways in, both reaching the same place:

```java
// Preferred: an OSGi reference to the service
@Reference
private ErrorLoggerService errorLogger;
...
this.errorLogger.logError(e);

// For code that cannot hold a reference — a commit hook constructed per node, say
ErrorLogger.logError(e);
```

Both are **always safe to call**. Recording an error is best effort: the caller is by definition
already dealing with a failure and must never be handed a second one, so nothing here throws.
Before the module starts and after it stops, `ErrorLogger.logError` silently does nothing; when the
repository cannot be reached, or `/LoggedErrors` does not exist, the failure to record is logged and
swallowed.

## What is stored

One `err:LoggedError` node per **distinct** error, under the `err:LoggedErrorsHomepage` node at
`/LoggedErrors`:

| Property | Type | Meaning |
| --- | --- | --- |
| `type` | String | The class name of what was thrown, e.g. `java.lang.IllegalStateException` |
| `message` | String | The throwable's own message, absent when it was thrown without one |
| `stackTrace` | String | The full stack trace, causes included, as it would be printed to a log |
| `occurrences` | Long | How many times this exact error has been recorded so far |
| `jcr:created` | Date | When the error was **first** seen, autocreated through `mix:created` |
| `lastOccurrence` | Date | When the error was **last** seen |

Only the module's own service user (`iap-error-tracking`) can read or write `/LoggedErrors`:
a stack trace quotes whatever the failing code was working on, which may be anything at all.

### One node per distinct error

A node is named after the **digest of its stack trace**, so recording an error is a lookup of one
child by name — no query, and therefore no index to maintain. The same failure thrown again from
the same place lands on the node already describing it and increments `occurrences`; a loop failing
ten thousand times leaves one node behind, not ten thousand copies of itself.

Two errors share a node only when their stack traces are **identical, message included**. An error
that names what it was working on (`Cannot read /submissions/1234`) is therefore still recorded
separately for every distinct thing it failed on, which is usually what you want — those are
different failures, not one failure repeating.

Counting is best effort under concurrency: if two threads record the same new error at the same
instant, one of them loses the race and logs that it could not record it. The error itself is
recorded by the winner either way; only the count of that one occurrence is lost.

### Nothing is ever deleted automatically

There is deliberately **no retention policy**. Every recorded error stays until somebody deals with
it. A time limit would be wrong because an error often cannot be fixed quickly — a partner
institution whose server dropped a data push may take days or weeks to come back, and the record of
what failed is exactly what is needed to retry it once they do. A fixed cap would be wrong for the
same reason: there is no count at which the oldest unresolved error stops mattering. The only thing
that is collapsed is a repeat of an error already recorded, which loses no information because it
is counted instead.

## The status report

The `LoggedErrorsStatusReporter` contributes a report tagged `problems` and `errors`:

- no recorded errors — a `DEBUG` report, invisible unless explicitly requested;
- recorded errors, privileged — an `ERROR` report headed `There are N errors logged, M occurrences
  in total` (the occurrence count is left out when every error happened once), quoting the stack
  traces of the **10 most recently seen** errors, each preceded by how often it happened and when
  it was last seen, and followed by a count of the ones left out. Nothing is discarded from the
  repository, but a report nobody can read through is no more useful than no report at all;
- recorded errors, unprivileged — an `ERROR` report giving only the counts, since the traces may
  quote confidential data;
- **`/LoggedErrors` missing** — an `ERROR` report saying so. The container is created by repoinit,
  so its absence means the repository was not initialized properly and every error is being dropped
  on the floor rather than recorded. That is worth knowing *before* the failure that needed
  recording happens, which is why it is reported louder than "no errors".

Listing the errors reads the children of `/LoggedErrors` directly rather than querying for them, so
no index is needed for the report to stay cheap.

## Future work

- **An index**, if the number of *distinct* errors on a real deployment ever grows enough that
  listing the container's children stops being cheap. Deduplication makes that unlikely: the count
  grows with distinct failures, not with how often they happen.
