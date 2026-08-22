# Status reporting

The `iap-status` module (`modules/status`) gathers short reports about the state of the system
from pluggable **status reporters** and serves them at **`/system/status`**.

## The endpoint

`GET /system/status` returns a JSON array of reports, each with a `name`, a `status` level
(`DEBUG` < `INFO` < `SUCCESS` < `WARNING` < `ERROR`), and a `text` body; `GET /system/status.txt`
returns just the joined report bodies as plain text. Optional query parameters:

- `targetStatus=<level>` — only include reports at this level or above (`INFO` by default, so
  `DEBUG` reports must be explicitly requested);
- `tags=<tag>` (repeatable) — only run the reporters carrying one of these tags, e.g. only
  `problems` or only `status`.

The endpoint is reachable **without authentication**, so monitoring tools can poll it; report
bodies may contain sensitive details only when the administrator is asking, everyone else gets
the unprivileged variant.

## What a report is not

An `ERROR` here means **somebody should look at this**. It never means the instance is down, and
nothing derived from these reports decides whether it is: a poll that succeeds gets `200` and the
array of whatever the reporters said, however bad that is. The levels are in the payload, never in
the HTTP status, and there is no aggregate verdict anywhere — no single field, and no reporter that
speaks for the others.

**Liveness is a different question, asked of a different endpoint**: the Felix health checks
tagged `systemalive`, at `GET /system/health?tags=systemalive`. Those are what the startup gate
waits for before it stops serving the "starting up" stub, and they are what a load balancer or a
container probe should be pointed at. Wiring a liveness probe to `/system/status` instead — say,
polling `targetStatus=ERROR` and treating a non-empty array as down — takes an instance out of
service because a definition somewhere is mis-authored.

The two are related in one direction only: the health checks are *reported into* the status
report by the Health Check reporter below, so an administrator reading `/system/status` sees them
among everything else. Nothing goes back the other way.

## Providing reports

Implement the `StatusReporter` SPI (`io.uhndata.iap.status.spi`) as an OSGi service: a name, a
set of tags, and a `report(unprivileged)` method returning a `StatusReport` (or `null` when
there is nothing to say). The `unprivileged` flag tells the reporter that the report will be
shown in an unsecure location and must not include confidential details. A reporter that throws
is isolated: it becomes an `ERROR` report instead of breaking the whole listing.

Reports can also be gathered programmatically through the `StatusReportManager` service
(`io.uhndata.iap.status.api`), e.g. by scheduled jobs pushing notifications.

Built-in reporters:

- **System Started** (`iap-status`, tags `status`, `systemStarted`) — the time the system was
  started, as an `INFO` report.
- **Health Check** (`iap-healthcheck`, tags `problems`, `healthcheck`) — runs all the
  [health checks](healthcheck.md) and reports a `SUCCESS` when everything is OK, a `WARNING`
  for warnings, or an `ERROR` for anything worse; the failed checks are named only in
  privileged reports.
- **Metrics** (`iap-metrics`, tags `metrics`, `activity`) — the current values of all the
  [metrics](metrics.md), grouped by category, as an `INFO` report; admin-only metrics are left
  out of unprivileged reports.
- **Logged errors** (`iap-error-tracking-impl`, tags `problems`, `errors`) — the
  [errors recorded](error-tracking.md) by an instance that nobody was there to see: an `ERROR`
  only while a failure nobody has dealt with is *still happening*, a `WARNING` for anything else
  still needing attention, and an `INFO` once it has all been acknowledged. What counts as still
  happening is configurable, and by default excludes anything the instance merely found wrong; see
  [error tracking](error-tracking.md#how-loud-the-report-is) for the reasoning and the settings.
  What broke and how often is reported to anyone; the stack traces, messages and paths only to a
  reader who is logged in.
