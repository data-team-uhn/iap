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
