# Status reporting

**Module:** `modules/status` · **Bundle:** `iap-status` ·
**SPI:** `io.uhndata.iap.status.spi` (`StatusReporter`, `StatusReport`) ·
**API:** `io.uhndata.iap.status.api` (`StatusReportManager`)

Status reports are short reports on the state of an instance, served at
`/system/status`. Each one comes from a reporter, and any module can register one.

## The endpoint

| Request | Returns |
|---|---|
| `GET /system/status` | A JSON array of reports, each with `name`, `status` and `text` |
| `GET /system/status.txt` | The reports' texts alone, separated by blank lines |

| Parameter | Default | Means |
|---|---|---|
| `targetStatus=<level>` | `INFO` | Only the reports at this level or above. The level is in capitals, and `DEBUG` reports appear only when asked for |
| `tags=<tag>` | all | May be repeated. Only the reporters that have one of these tags run |

A `targetStatus` that names no level gets a **400**, with a JSON error body that lists
the levels.

The endpoint is registered with `sling.auth.requirements=-/system/status`, so anybody
can read it **without logging in**, and a monitoring tool can poll it. Only the `admin`
user gets the privileged reports. Everyone else, logged in or not, gets the
unprivileged ones.

## What a report is not

An `ERROR` here means **somebody should look at this**. It never means the instance is
down. A poll that succeeds gets a `200` and whatever the reporters said, however bad.
The levels are in the payload, never in the HTTP status. No field sums up the reports,
and no reporter speaks for the others.

**Whether the instance is alive is a question for the Felix
[health checks](healthcheck.md)** tagged `systemalive`, at
`GET /system/health?tags=systemalive`. The startup gate serves its "starting up" page
until they pass. A load balancer or a container probe should ask them too.

Do not point a liveness probe at `/system/status`, polling `targetStatus=ERROR` and
treating a non-empty array as down. With the default settings, one failure logged in
the last hour makes an `ERROR`, and the probe would take a working instance out of
service.

The health checks reach the status reports, but not the other way round. The
`Health Check` reporter runs them, so an administrator reading `/system/status` sees
them among the other reports.

## Providing reports

Implement `StatusReporter` as an OSGi component:

```java
@NotNull  String            getName();
@NotNull  Set<String>       getTags();
@Nullable StatusReport      report(boolean unprivileged);
```

`StatusReport` is a name, a `Status` and a text. The levels are ordered
`DEBUG` < `INFO` < `SUCCESS` < `WARNING` < `ERROR`, and `targetStatus` filters on that
order.

The contract has three parts:

- **Return `null` when there is nothing to say**, not an empty report.
- **`unprivileged` means a reader who must not see confidential details may read the
  report.** The reporter leaves out whatever quotes content: paths, user names,
  messages and stack traces. The endpoint does not redact anything itself.
- **An exception from `report` costs only that reporter's report.** The manager logs a
  warning and puts an `ERROR` report in its place, named after the reporter. Its text
  quotes the exception's message, in the unprivileged report too.

A reader selects reporters by tag. `problems` marks a reporter about something that
needs attention, and `status` one that describes the instance. Each reporter also has
a tag of its own, such as `healthcheck`, to select it alone.

### Reading reports in code

`StatusReportManager` gathers the reports without going through HTTP:

```java
List<StatusReport> getReports(boolean unprivileged);   // INFO and above, all tags
List<StatusReport> getReports(boolean unprivileged, StatusReport.Status level, Set<String> tags);
```

## The reporters

| Reporter | Bundle | Tags | Says |
|---|---|---|---|
| System Started | `iap-status` | `status`, `systemStarted` | When the instance started, as `INFO`, to every reader |
| Health Check | `iap-healthcheck` | `problems`, `healthcheck` | `SUCCESS` when every tagged [health check](healthcheck.md) passes, `WARNING` for a `WARN`, `ERROR` for worse. Only a privileged report names the failed checks |
| Metrics | `iap-metrics` | `metrics`, `activity` | The [metric](metrics.md) values grouped by category, as `INFO`. An unprivileged report leaves out the metrics with the `admin` access level. There is no report when no metric is left to show |
| Email Catcher | `iap-email-catcher` | `problems`, `email` | `WARNING` while outgoing email is caught instead of delivered, and `DEBUG` otherwise. Only a privileged report says where the caught email is kept |
| Logged errors | `iap-error-tracking-impl` | `problems`, `errors` | `ERROR` for an unacknowledged failure seen recently, `WARNING` for any other unacknowledged error, and `INFO` once all of them are acknowledged |

The logged-errors reporter has two settings. One says how recently a failure must have
been seen to make an `ERROR`, an hour by default. The other says whether a problem,
which nothing was thrown for, can make an `ERROR` too. By default it cannot.
[Error tracking](error-tracking.md) describes both. Every reader sees what failed and
how often. Through the endpoint, only the `admin` user sees the stack traces, messages
and paths.
