# Metrics

**Module:** `modules/metrics` · **Bundle:** `iap-metrics` ·
**API:** `io.uhndata.iap.metrics.api` (`MetricsManager`, `Metric`)

Named counts of things that happen, such as research proposals submitted. They are
kept in the repository, so a count survives a restart and every node of a cluster
shares it.

## Recording

Inject `MetricsManager`, define the metric at activation, and keep the `Metric` it
returns. Defining a metric that already exists replaces its definition and keeps its
count.

```java
@Reference
private MetricsManager metricsManager;

this.metric = this.metricsManager.createMetric("submittedProposals")
    .withLabel("Submitted proposals")
    .withDescription("How many research proposals were submitted for review")
    .withCategory("Submissions")
    .withDefaultOrder(10)                      // order within the category; 0 if unset
    .withAccessLevel(Metric.AccessLevel.ADMIN) // PUBLIC unless restricted
    .withRolloverSchedule("0 0 0 * * ?")       // every midnight; on request if unset
    .create();

// when the counted event happens
this.metric.increment();
```

The manager defines metrics (`createMetric(name)…create()`) and finds them
(`getMetric`, `getMetrics`). The rest is on the metric:

```java
long          getCurrentValue();     // the count since the metric was created
long          getPreviousValue();    // the count at the last roll-over
long          getCurrentDelta();     // current − previous: this period so far
long          getLastDelta();        // the last closed period
ZonedDateTime getLastUpdated();
ZonedDateTime getLastRollover();
String        getRolloverSchedule();
void          increment();           // + increment(long amount)
void          rollOver();
```

### From a workflow

The `incrementMetric` service task counts in a metric as part of a workflow. The activity
names the metric in `metric`, and may give a whole-number `amount` other than 1:

```json
"count": {
  "jcr:primaryType": "wf:Activity",
  "handler": "incrementMetric",
  "metric": "submittedProposals",
  "jcr:reference:flowNodeType": "/WorkflowTypes/ServiceTask"
}
```

The count lands after the delivery commits, so a delivery that fails counts nothing. A
metric that does not exist refuses the definition rather than starting a counter: define
it first, with its label and audience.

### Handles and staleness

A metric from `create()` or `getMetric(name)` reads the repository on every call. It
always reports current values, and a component can keep it for as long as it runs.

A metric from `getMetrics()` reports the values read for the listing, and reading them
opens no session. Reading each value from the repository would cost a service login
per value.

Both kinds write to the repository. A roll-over closes the period against the count in
the repository, never against the count a listed metric reports.

### Behaviours to count on

- **`increment` never throws.** A failure is logged and the amount goes uncounted, so
  counting cannot break the operation it counts. `rollOver`, reads and `create` throw
  an unchecked `MetricsException` instead.
- **An increment may be negative**, to correct an over-count. Incrementing by `0` does
  nothing.
- **A failed save is tried again.** A change that fails to save is tried again on a new
  session, up to `MAX_ATTEMPTS` (3) attempts in all, before `MetricsException`.

## Storage

Each metric is a `metric:Metric` node under `/Metrics`. The type extends
`data:Content`, so a metric has the properties all content has, can be
[tagged](tags.md), and takes any other property a deployment adds.

| Property | Holds |
|---|---|
| `label` | **Mandatory.** The name people read |
| `description`, `category` | What the metric counts, and the group it is shown in |
| `defaultOrder` | Its order within the category: a LONG, `0` by default, possibly negative |
| `accessLevel` | `public` by default, or `admin`; the node type allows no other value |
| `oak:counter` | The count |
| `previousValue` | The count at the last roll-over, where the current period started |
| `lastDelta` | The amount counted in the last closed period |
| `lastRollover`, `lastUpdated` | When the metric was last rolled over, and last incremented |
| `rolloverSchedule` | When periods close, as a Quartz cron expression; absent if only on request |

Oak keeps the count. Saving an `oak:increment` value adds it to `oak:counter`, so
increments made at the same time, on any node of a cluster, do not overwrite each
other.

**The type does not declare `mix:atomicCounter`.** Oak counts only on a node that lists
the mixin in its `jcr:mixinTypes`, so the manager lists it when it creates the node.

Only the `iap-metrics` service user is granted access to the metrics' nodes. People
read the metrics through `/Metrics.json` and the status report, which apply each
metric's `accessLevel`.

## Roll-overs

`rollOver()` closes the current period. The amount counted in it becomes `lastDelta`,
the count becomes `previousValue`, and the time becomes `lastRollover`. **The count
itself never changes**, and the amounts of all the periods add up to it.

`MetricRolloverScheduler` rolls over each metric that has a `rolloverSchedule`, with
one job per metric. The jobs run with `onLeaderOnly(true)` and
`canRunConcurrently(false)`, so in a cluster each period is closed once. The scheduler
reads the schedules again whenever anything under `/Metrics` changes, so a new metric
or a changed expression needs no restart.

**The expression is not checked when the metric is defined.** The scheduler refuses an
invalid one. The refusal is logged and recorded through
[error tracking](error-tracking.md) as a problem, and the metric is never rolled over
on schedule. The scheduler tries the expression again at every later change under
`/Metrics`. Cron fields are seconds, minutes, hours, day of month, month, day of week.

A metric without a schedule rolls over only when `rollOver()` is called, so a reporting
job can set its own period boundaries.

## Display order

`getMetrics()` returns the metrics in the order to show them. `/Metrics.json` and the
status report keep that order:

1. by **category** name, uppercase before lowercase, with **no category last**; a
   blank category counts as none;
2. by **`defaultOrder`** within the category, lower first;
3. by **name**.

Leave gaps when numbering, such as multiples of ten, so a metric can go between two
others later without renumbering.

## Reading over HTTP

`GET /Metrics.json` returns `{ "metrics": [ … ] }` in display order. Each entry holds
`name`, `label`, `description`, `category`, `defaultOrder`, `accessLevel`,
`currentValue`, `previousValue`, `currentDelta` and `lastDelta`, then the
`lastUpdated`/`lastRollover` dates and the `rolloverSchedule` when they are set. A
missing description or category is an empty string.

The endpoint needs no login, so a monitoring tool can poll it: the servlet registers
`sling.auth.requirements=-/Metrics`. A metric with the `admin` access level is listed
only for the `admin` user.

The [status report](status.md) shows the metrics too. Its **Metrics** report (tags
`metrics`, `activity`) is one `INFO` report that lists them by category, in display
order:

```
Submissions:
- Submitted proposals: 12 (+3 since 2026-07-20; previous period: +5)

Uncategorized:
- Logins: 230
```

The part in parentheses appears only for a metric that was rolled over. The category
headings appear only when there is more than one group. An unprivileged report leaves
out the metrics with the `admin` access level.
