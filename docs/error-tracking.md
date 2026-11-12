# Error tracking

**Module:** `modules/error-tracking`, in two bundles ·
**API:** `io.uhndata.iap.errortracking.api` · **Impl:**
`…errortracking.internal`, `…errortracking.models`

Records the errors a running instance could not deal with on its own, under
`/LoggedErrors`, and reports them through the [status report](status.md). A log file
rotates away, and a recorded error stays until somebody looks at it.

It does not replace the log. Keep logging through SLF4J, and also record here what a
system administrator needs to know about hours later.

`iap-error-tracking-api` depends on nothing but the Sling API, so any module may depend
on it. `iap-error-tracking-impl` holds the recording, the node types and the report, and
depends on the data model, tags and status.

## Recording

```java
ErrorLogger.logError(e);

ErrorLogger.logError(e, ErrorContext.of(MyComponent.class, "readDefinitions")
    .about(resource)                        // String path or Resource
    .actingFor(UserIds.canonical(resolver))
    .with("attempt", attempt));

ErrorLogger.logProblem("unknown comparator",
    ErrorContext.of(ConditionEvaluatorImpl.class, "evaluate").about(condition.getPath()));
```

The static `ErrorLogger` facade is the normal way to record. Before the module starts
and after it stops, a call does nothing. A component that records its failures through
it therefore needs no reference to `ErrorLoggerService`, and starts without it.
`ErrorLoggerService` is published too, with `getDroppedCount()`.

Recording never throws, and never waits for the repository. Its caller is already
handling a failure.

`logProblem` records a failure that nothing was thrown for, such as a mis-authored
definition.

Record a failure only when nobody would learn of it any other way, and when it is a
fault of the code or the deployment. A fault that a client's input decides is answered
to that client instead.

### ErrorContext

Every part is optional, and a `null` is ignored, so a failure site needs no null checks.

| Part | For |
|---|---|
| `component` | The class that was running. For a plugin, the plugin's class, not the framework that called it. For a failure, the topmost `io.uhndata.iap.` class of the stack trace when unset |
| `operation` | What it was doing: a short label chosen in code, such as `computeTags` |
| `subject` | The path of the content it was working on |
| `actor` | The username of the user it was working for; absent outside a request |
| `with(k, v)` | Any other detail, rendered to text at once. Up to 20 details, of up to 500 characters each |

### Identity has to be constant

`component`, `operation` and a problem's phrase decide whether two failures are **the
same fault**, so they must be constants chosen in code. The check is on their
characters: `[A-Za-z0-9_.$ -]`, up to 64 characters for a label and 255 for a component.

A component or operation that fails the check is left out of the record. For a
failure, the component is then taken from the stack trace. A problem's phrase is named
by its leading run of label characters, so `unknown comparator: 'sameDay'` is recorded
as `unknown comparator`, and the whole phrase is kept in `messages`, as a throwable's
message would be. A phrase that does not start with a usable name is recorded as
`unspecified problem`.

## Storage

One node per **distinct fault** under `/LoggedErrors` (`err:LoggedErrorsHomepage`). A
fault something was thrown for is an `err:LoggedFailure`, and one nothing was thrown for
is an `err:LoggedProblem`. Both are `err:LoggedError` and `data:Content`, so they
serialize, paginate and carry tags like other content.

| Property | Type | Meaning |
|---|---|---|
| `component`, `operation` | String | What was running, and what it was doing |
| `occurrences` | Long | How many times the fault has been recorded |
| `jcr:created` / `lastOccurrence` | Date | When it was first and last recorded |
| `subjects` / `actors` / `messages` | String[] | Samples, most recent first, of up to 20 / 10 / 5 distinct values |
| `lastContext` | String | The details given with the most recent occurrence that had any |
| `type` / `stackTrace` | String | *(failures)* the class of the throwable, and the trace of the first occurrence, cut at 64 KB |
| `problem` | String | *(problems)* what is wrong |

**Nobody is granted read access** on `/LoggedErrors`, since a stack trace quotes
whatever the failing code was working on. Only the module's service user, service users
that may read the whole repository, and the repository superuser can read it.

### The fingerprint

The node name is a SHA-256 of the component, the operation, and, for a failure, the
class names and frames of the throwable chain (up to 10 causes, 50 frames each). To
record, the writer looks up one child by name, with no query and no index.

The fingerprint leaves out the message, the subject and the actor.
`Cannot read /Submissions/1234` and `…/5678` are one fault seen twice.

Nothing is ever deleted. A fingerprint with the message in it would let one broken code
path, over a large import, create tens of thousands of nodes, in a flat container that
the status report lists on every poll. Without it, the number of records grows with the
ways this build can fail, not with the data it handles.

Frame class names lose the numbers the JVM gives generated classes before hashing:
`0x…` addresses, `$$Lambda$N`, `$ProxyN` and `AccessorN`. Those numbers differ from one
run to the next, and would split one fault across many records.

### Samples are evidence, not a work queue

Nothing counts the values a sample leaves out. The record says that something
systematic is happening, what kind, and gives enough examples to reproduce it. It does
not list which submissions to retry: content that needs repair carries its own marker,
as a node whose tags could not be computed carries `tagComputationState`.

### Recording does not touch the repository

`logError` fingerprints the failure, adds it to a tally in memory, and returns. One
`iap-error-tracking` thread writes the tallies out, one commit per batch, when a
recording asks it to and every 60 seconds (`WRITE_INTERVAL_MS`). Each fault is written
at most once per 60 seconds.

Commit hooks record errors. Oak's segment store guards commits with a single
**non-reentrant** permit, so a session that commits from inside a commit hook waits
forever for the permit its own thread holds, and every write in the instance stops.

Consequences:

- A recorded error is not in the repository the moment the call returns.
- A failure raised while the thread writes records is logged and not tallied. A
  `ThreadLocal` guard keeps such a failure from feeding itself.
- The tally holds at most 1000 faults (`MAX_PENDING`). It is keyed by fingerprint, so
  it is bounded by the same thing as the records. A recording that does not fit is
  dropped and counted, and the report shows the count. After 3 writes in a row fail,
  the writer pauses for 5 minutes. A batch that fails to write goes back into the tally.

## Acknowledging

Nothing is deleted. Without a way to set errors aside, the first error an instance hit
would keep it reporting itself unhealthy forever.

```
POST /LoggedErrors/<fingerprint>.acknowledge.json
    resolution=known-issue
    note=fix is in the next release
```

The decision is **added** as an `err:Acknowledgement` child, with who, when, what, why,
and the occurrence count at the time. An error acknowledged, then seen again, then
acknowledged again keeps all three facts.

`resolution` names a tag in the `error-triage` category, other than `unacknowledged`.
The module ships `acknowledged`, `known-issue` and `wont-fix`. The servlet also accepts
a tag that a deployment adds to the category, but the console's form offers only the
three shipped ones.

A tag processor computes the triage tags from the decisions, and nobody places them by
hand. A decision records the occurrence count it was taken at. The next occurrence takes
the count past it, and the error goes back to `unacknowledged` by itself. That tag is
aggregated, so `/LoggedErrors` carries it while any error under it needs attention. No
job runs on a schedule, and no clock is compared: a count is not confused by two
decisions in the same second, or by clocks that disagree.

**There is no retention policy.** An error often cannot be fixed quickly: a partner
institution whose server dropped a push may take weeks, and the record is what is
needed to retry. A fixed cap fails for the same reason, since there is no count at which
the oldest unresolved error stops mattering. Only a repeat of a recorded fault is
collapsed, and its count keeps it. The service user may not remove a node, so the
deployment enforces this, not the code.

## The status report

`LoggedErrorsStatusReporter` contributes a report tagged `problems` and `errors`:

| Situation | Status | Headline |
|---|---|---|
| `/LoggedErrors` missing or of the wrong type | `ERROR` | `*ERROR*: Errors cannot be logged` |
| nothing recorded | `DEBUG` | `No errors are logged` |
| an unacknowledged failure still happening | `ERROR` | `There are N errors logged, M occurrences in total` |
| unacknowledged errors, none still happening | `WARNING` | `…no failure seen in the last 60 minutes` |
| everything acknowledged | `INFO` | `All N logged errors have been acknowledged` |
| nothing recorded, but recordings dropped | `WARNING` | `*WARNING*: N errors could not be recorded` |

Two settings on **Error Report**
(`io.uhndata.iap.errortracking.internal.ErrorReportConfiguration`) decide what makes an
`ERROR`:

| Setting | Default | Meaning |
|---|---|---|
| `recentFailureWindow` | `60` | Minutes after it was last seen that a failure still counts as happening now |
| `problemsAreUrgent` | `false` | Whether a recent problem makes an `ERROR`, as a recent failure does |

A deployment that wants every unacknowledged failure to be an `ERROR` sets a window
longer than an instance ever runs. A window of zero or less would silence every
`ERROR`, so the reporter logs a warning and uses 60 instead.

Two more choices:

- **Dropped recordings make a `WARNING`, not an `ERROR`.** The dropped count lasts until
  the recording service restarts, so an `ERROR` would keep an instance red that long
  after one burst. The count appears in every headline, however much has been
  acknowledged, since nobody saw what was dropped. It names no content, so every reader
  sees it.
- **`INFO`, not `SUCCESS` or `WARNING`, once everything is acknowledged.** Something did
  break, so not `SUCCESS`. A `WARNING` would still trip a monitor that watches for
  anything above `INFO`.

What a reader sees depends on who they are. Components, operations, counts and dates
describe the instance's own code, and every reader sees them, on the unauthenticated
`/system/status` too. Stack traces, messages, paths, users and the notes on decisions
quote what the failing code was working on, and only the privileged report shows them.
`/system/status` gives the privileged report to the `admin` user alone. It describes up
to ten unacknowledged errors in full, and gives up to ten acknowledged errors a line
each, with the resolution and the note of the latest decision.

The reporter reads the children of `/LoggedErrors` directly, with no query, so it needs
no index.

## Over HTTP

A recorded error is ordinary content, so it needs no serialization code of its own:

| Request | Returns |
|---|---|
| `/LoggedErrors/<fingerprint>.json` | one recorded error |
| `/LoggedErrors.json` | the homepage, whose `aggregatedTags` holds `unacknowledged` while any error needs attention |
| `/LoggedErrors.paginate.json?sortBy=lastOccurrence&descending=true` | a page of recorded errors, filtered as the pagination servlet allows |
| `/LoggedErrors.deep.json` | every recorded error, which grows without bound, since nothing is deleted |
| `/LoggedErrors.adminSummary.json` | how many errors need attention, and how many there are in all, for the dashboard |

## Future work

**An index**, if the number of *distinct* faults on a deployment grows enough that
listing the container's children is no longer cheap, or if somebody needs to search
stack traces. It belongs with the deferred index over the tag properties.
