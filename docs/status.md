# Status reporting

**Module:** `modules/status` · **Bundle:** `iap-status` ·
**SPI:** `io.uhndata.iap.status.spi` (`StatusReporter`, `StatusReport`) ·
**API:** `io.uhndata.iap.status.api` (`StatusReportManager`)

Short reports about the state of an instance, gathered from pluggable reporters and
served at `/system/status`. Anything that knows something an administrator would want to
hear contributes one; nothing has to know who is listening.

## The endpoint

| Request | Returns |
|---|---|
| `GET /system/status` | A JSON array of reports, each with `name`, `status` and `text` |
| `GET /system/status.txt` | The report bodies joined as plain text |

| Parameter | Default | Means |
|---|---|---|
| `targetStatus=<level>` | `INFO` | Only reports at this level or above, so `DEBUG` must be asked for |
| `tags=<tag>` | all | Repeatable; only run reporters carrying one of these tags |

An unrecognized `targetStatus` is a **400** with a JSON error body, rather than a silent
fallback — a monitor asking the wrong question should be told, not quietly given
something else.

The endpoint is registered with `sling.auth.requirements=-/system/status`, so it is
reachable **without authentication** and a monitoring tool can poll it. Report bodies
carry sensitive detail only when the administrator is asking; everyone else gets the
unprivileged variant.

## What a report is not

An `ERROR` here means **somebody should look at this**. It never means the instance is
down, and nothing derived from these reports decides whether it is: a poll that succeeds
gets `200` and whatever the reporters said, however bad that is. The levels are in the
payload, never in the HTTP status, and there is no aggregate verdict anywhere — no
single field, and no reporter speaking for the others.

**Liveness is a different question, asked of a different endpoint**: the Felix
[health checks](healthcheck.md) tagged `systemalive`, at
`GET /system/health?tags=systemalive`. Those are what the startup gate waits for before
it stops serving the "starting up" stub, and what a load balancer or container probe
should be pointed at.

Wiring a liveness probe to `/system/status` instead — polling `targetStatus=ERROR` and
treating a non-empty array as down — takes an instance out of service because a
definition somewhere is mis-authored.

The two are related in one direction only: the health checks are *reported into* the
status report by the Health Check reporter, so an administrator reading
`/system/status` sees them among everything else. Nothing goes back the other way.

## Providing reports

Implement `StatusReporter` as an OSGi component:

```java
@NotNull  String            getName();
@NotNull  Set<String>       getTags();
@Nullable StatusReport      report(boolean unprivileged);
```

`StatusReport` is a name, a `Status` and a text body. The levels are ordered
`DEBUG` < `INFO` < `SUCCESS` < `WARNING` < `ERROR`, which is what `targetStatus`
filters on.

Three points of contract:

- **Return `null` when there is nothing to say.** A reporter with no news should not
  produce an empty report.
- **`unprivileged` means the report will be shown in an unsecure location.** It is the
  reporter's job to leave confidential detail out — paths, user names, stack traces,
  anything naming content. The endpoint does not redact on a reporter's behalf.
- **A reporter that throws is isolated.** The manager catches it, logs a warning, and
  substitutes an `ERROR` report named after the reporter saying it failed to compute, so
  one broken reporter cannot cost the whole listing.

Tags decide who a reporter answers for: `problems` for anything wanting attention,
`status` for a description of the instance, and whatever narrower tag lets a caller ask
for one reporter alone.

### Reading reports in code

`StatusReportManager` gathers them without going through HTTP, which is how the
[chat notification producer](notifications.md) builds its message:

```java
List<StatusReport> getReports(boolean unprivileged);   // INFO and above, all tags
List<StatusReport> getReports(boolean unprivileged, StatusReport.Status level, Set<String> tags);
```

## The reporters

| Reporter | Bundle | Tags | Says |
|---|---|---|---|
| System Started | `iap-status` | `status`, `systemStarted` | When the instance started, as `INFO` |
| Health Check | `iap-healthcheck` | `problems`, `healthcheck` | `SUCCESS` when every [health check](healthcheck.md) passes, `WARNING` for warnings, `ERROR` for worse. Failed checks are named only to a privileged reader |
| Metrics | `iap-metrics` | `metrics`, `activity` | Current [metric](metrics.md) values grouped by category, as `INFO`. Admin-only metrics are left out of unprivileged reports |
| Logged errors | `iap-error-tracking-impl` | `problems`, `errors` | `ERROR` while a failure nobody has dealt with is still happening, `WARNING` for anything else needing attention, `INFO` once everything is acknowledged |

The logged-errors reporter is the one with settings: what counts as *still happening* is
configurable, and by default excludes anything the instance merely found wrong rather
than failed at — see [error tracking](error-tracking.md) for the reasoning. What broke
and how often is reported to anyone; stack traces, messages and paths only to a reader
who is logged in.
