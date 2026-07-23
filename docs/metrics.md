# Metrics

The `iap-metrics` module (`modules/metrics`) tracks named usage counters — how many proposals were
submitted, how many notification emails were sent — persistently, in the repository, so counts
survive restarts and are shared by every node of a cluster.

## The data model

Each metric is an `iap:Metric` node under `/Metrics`, holding descriptive metadata (`label`,
`description`, `category`, `accessLevel`) next to its counter state. The count itself lives in the
`oak:counter` property, maintained by the repository's atomic counter support: committing an
`oak:increment` value on the node atomically adds it to the counter, so concurrent increments from
different threads or cluster nodes are all applied without conflicting or getting lost.

Beside the ever-growing current value, each metric tracks reporting periods, closed by
**roll-overs**: `previousValue` remembers the counter value at the last roll-over (the baseline
for "how much this period" computations), `lastDelta` freezes the amount accumulated in the period
that was closed, `lastRollover` records when that happened, and the optional `rolloverSchedule`
holds a Quartz cron expression for closing periods automatically. `lastUpdated` records the last
increment.

Only the `iap-metrics` service user can access the metric nodes; everything else goes through the
services described below, which enforce each metric's access level (`public` or `admin`) at the
HTTP boundary.

## Recording metrics

Get the `MetricsManager` service (`io.uhndata.iap.metrics.api`) injected, define the metric once —
typically at component activation, since the definition is idempotent — then use the returned
`Metric` handle:

```java
@Reference
private MetricsManager metricsManager;

// On activation: creates the metric, or just refreshes its metadata if it already exists
this.metric = this.metricsManager.createMetric("submittedProposals")
    .withLabel("Submitted proposals")
    .withDescription("How many research proposals were submitted for review")
    .withCategory("Submissions")
    .withAccessLevel(Metric.AccessLevel.ADMIN) // PUBLIC unless restricted
    .withRolloverSchedule("0 0 0 * * ?")       // nightly periods; manual-only if not set
    .create();

// When the counted event happens:
this.metric.increment();
```

The manager only handles defining (`createMetric(name).…create()`) and looking up (`getMetric`,
`getMetrics`) metrics; reads and updates are done on the `Metric` handle itself, with a separate
accessor for each piece of state: `getCurrentValue()`, `getPreviousValue()`, `getCurrentDelta()`
(current - previous, the running count of the current period), `getLastDelta()`,
`getLastRollover()`, `getLastUpdated()`, `getRolloverSchedule()`, plus `increment(amount)` and
`rollOver()`. Handles are lightweight and never stale — each call opens a fresh service session —
so they can be kept for the lifetime of a component.

A few deliberate behaviors:

- `increment` never throws: a failure to count is logged and swallowed, so recording a metric can
  never break the operation being counted. Everything else (`rollOver`, reads, `create`) reports
  failures as an unchecked `MetricsException`.
- Increments may be negative, to correct over-counting; incrementing by `0` does nothing.
- Reading never rolls over: every accessor is a plain read with no side effects, so any number of
  reports, dashboards and probes can look at the metrics without influencing what the others see.
- Updates that lose a race against a concurrent change (e.g. a roll-over racing an increment) are
  retried on a fresh session a few times before giving up.

## Periodic roll-overs

`rollOver()` closes the current period: the amount accumulated so far is frozen as the *last
delta*, the current value becomes the new baseline, and the roll-over time is recorded. The
current value itself is never modified — the counter keeps growing across roll-overs, and summing
all the period deltas always adds up to it.

Metrics declaring a `rolloverSchedule` are rolled over automatically: a scheduler service keeps
one scheduled job per metric, following its Quartz cron expression (e.g. `0 0 0 * * ?` for
"nightly at midnight" — seconds, minutes, hours, day of month, month, day of week). The schedules
are re-read whenever anything under `/Metrics` changes, so new metrics and edited schedules are
picked up on the fly, without restarting anything. In a cluster the jobs run only on the leader,
so each period is closed exactly once. The expression is not validated when the metric is defined:
an invalid one is reported in the logs by the scheduler and never fires.

Metrics without a schedule only roll over when `rollOver()` is explicitly invoked, e.g. by a
custom reporting job that wants to control its own period boundaries.

## Reading metrics over HTTP

`GET /Metrics.json` returns a JSON object with a `metrics` array, each entry holding `name`,
`label`, `description`, `category`, `accessLevel`, `currentValue`, `previousValue`,
`currentDelta` and `lastDelta`, plus the `lastUpdated`/`lastRollover` dates and the
`rolloverSchedule` when present. The endpoint is reachable without authentication so dashboards
can poll it, but metrics with the `admin` access level are only listed when the administrator is
asking.

Metrics are also part of the [status reports](status.md): the **Metrics** reporter (tags
`metrics`, `activity`) lists every metric as an `INFO` report, grouped by category, e.g.:

```
Submissions:
- Submitted proposals: 12 (+3 since 2026-07-20; previous period: +5)
```

The parenthesized part only appears for metrics that have been rolled over at least once, and
admin-only metrics are left out of unprivileged reports. Generating a report never changes the
counters.
