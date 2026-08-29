# Metrics

**Module:** `modules/metrics` · **Bundle:** `iap-metrics` ·
**API:** `io.uhndata.iap.metrics.api` (`MetricsManager`, `Metric`)

Named usage counters — proposals submitted, notification emails sent — kept in the
repository, so counts survive restarts and are shared by every node of a cluster.

## Recording

Inject `MetricsManager`, define the metric once at activation (defining is idempotent),
then hold the returned handle:

```java
@Reference
private MetricsManager metricsManager;

this.metric = this.metricsManager.createMetric("submittedProposals")
    .withLabel("Submitted proposals")
    .withDescription("How many research proposals were submitted for review")
    .withCategory("Submissions")
    .withDefaultOrder(10)                      // placement within the category; 0 if unset
    .withAccessLevel(Metric.AccessLevel.ADMIN) // PUBLIC unless restricted
    .withRolloverSchedule("0 0 0 * * ?")       // nightly; manual-only if unset
    .create();

// when the counted event happens
this.metric.increment();
```

The manager only defines (`createMetric(name)…create()`) and looks up (`getMetric`,
`getMetrics`). Everything else is on the handle:

```java
long          getCurrentValue();     // ever-growing total
long          getPreviousValue();    // the value at the last roll-over
long          getCurrentDelta();     // current − previous: this period so far
long          getLastDelta();        // the period that was closed
ZonedDateTime getLastUpdated();
ZonedDateTime getLastRollover();
String        getRolloverSchedule();
void          increment();           // + increment(long amount)
void          rollOver();
```

### Handles and staleness

A handle asked for **by name** — from `create()` or `getMetric(name)` — reads the
repository on every access, so it never goes stale and can be held for a component's
lifetime.

A handle from **`getMetrics()`** reports the values read while listing, because a
listing is a point-in-time view: reading each value separately would cost a repository
session each, and rendering ten metrics would open seventy sessions instead of one.

Either kind *writes* correctly. A roll-over closes the period against the counter's
current value, never against the value its handle happens to be reporting.

### Behaviours to count on

- **`increment` never throws.** A failure to count is logged and swallowed, so recording
  can never break the operation being counted. Everything else — `rollOver`, reads,
  `create` — reports failure as an unchecked `MetricsException`.
- **Increments may be negative**, to correct over-counting; incrementing by `0` does
  nothing.
- **Reading never rolls over.** Every accessor is a plain read with no side effects, so
  any number of reports, dashboards and probes can look without influencing each other.
- **Writes retry.** An update losing a race against a concurrent change is retried on a
  fresh session up to `MAX_ATTEMPTS` (3) before raising `MetricsException`.

## Storage

Each metric is a `metric:Metric` under `/Metrics`, extending `data:Content` like every
other definition-style type — so it carries the shared content properties, can be
[tagged](tags.md), accepts whatever else a deployment adds, and is served by the
standard [JSON serialization](json-serialization.md).

| Property | Holds |
|---|---|
| `label` | **Mandatory.** Display name |
| `description`, `category` | Descriptive metadata |
| `defaultOrder` | LONG, default `0`, negatives allowed |
| `accessLevel` | `public` (default) or `admin` — constrained by the node type |
| `oak:counter` | The count itself |
| `previousValue` | The counter at the last roll-over — the baseline for "this period" |
| `lastDelta` | The amount accumulated in the period that was closed |
| `lastRollover`, `lastUpdated` | When those happened |
| `rolloverSchedule` | Quartz cron expression, if periods close automatically |

The count is maintained by Oak's atomic counter support: committing an `oak:increment`
value adds it to `oak:counter` atomically, so concurrent increments from different
threads or cluster nodes all apply without conflicting or getting lost.

**`mix:atomicCounter` is the one thing not inherited.** It only works when listed
explicitly in a node's `jcr:mixinTypes`, so the manager sets it at creation rather than
declaring it on the type.

Only the `iap-metrics` service user can reach the metric nodes. Everything else goes
through the services above, which enforce `accessLevel` at the HTTP boundary.

## Roll-overs

`rollOver()` closes the current period: the amount accumulated so far is frozen as
`lastDelta`, the current value becomes the new baseline, and the time is recorded. **The
current value is never modified** — the counter keeps growing across roll-overs, and the
period deltas always sum to it.

Metrics declaring a `rolloverSchedule` are rolled over by `MetricRolloverScheduler`,
which keeps one job per metric. The jobs are scheduled with `onLeaderOnly(true)` and
`canRunConcurrently(false)`, so in a cluster each period is closed exactly once.
Schedules are re-read whenever anything under `/Metrics` changes, so new metrics and
edited expressions are picked up without a restart.

**The expression is not validated when the metric is defined.** An invalid one is
refused by the scheduler, logged, and recorded through
[error tracking](error-tracking.md) as a problem — it simply never fires. Cron fields
are seconds, minutes, hours, day of month, month, day of week.

Metrics without a schedule roll over only when `rollOver()` is called, which is the
hook for a reporting job that wants to control its own period boundaries.

## Display order

`getMetrics()` returns metrics in the order they should be shown, and everything
listing them — the endpoint, the status report — follows that order rather than sorting
again:

1. by **category**, alphabetically, **uncategorized last**;
2. by **`defaultOrder`** within the category, lower first;
3. by **name**, so a listing is stable when the first two agree.

Category is compared first, so a low order never lifts a metric out of its group. Leave
gaps when numbering — multiples of ten — so a metric can be slotted in later without
renumbering.

## Reading over HTTP

`GET /Metrics.json` returns `{ "metrics": [ … ] }` in display order, each entry holding
`name`, `label`, `description`, `category`, `defaultOrder`, `accessLevel`,
`currentValue`, `previousValue`, `currentDelta`, `lastDelta`, the
`lastUpdated`/`lastRollover` dates, and `rolloverSchedule` when set.

The endpoint is deliberately reachable without authentication — the servlet registers
`sling.auth.requirements=-/Metrics` — so dashboards can poll it. Metrics with the
`admin` access level are listed only when the administrator is asking.

Metrics also appear in the [status reports](status.md): the **Metrics** reporter (tags
`metrics`, `activity`) lists each as an `INFO` report, grouped by category in display
order:

```
Submissions:
- Submitted proposals: 12 (+3 since 2026-07-20; previous period: +5)
```

The parenthesized part appears only for metrics rolled over at least once, admin-only
metrics are left out of unprivileged reports, and generating a report never changes a
counter.
