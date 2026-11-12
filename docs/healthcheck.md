# Health checks

**Module:** `modules/healthcheck` · **Bundle:** `iap-healthcheck` ·
**Package:** `…healthcheck.internal` · **Generic checks configured in:**
`packaging/slingfeature/src/main/features/healthcheck.json`

Health checks say whether an instance is working. IAP uses
[Apache Felix Health Checks](https://felix.apache.org/documentation/subprojects/apache-felix-healthchecks.html).
The packaging feature configures the generic Felix and Sling checks: memory, CPU,
thread usage, disk space, framework start, started bundles, bundle content loaded,
services, DS components and the scheduler. This module adds the checks specific to IAP.

| Request | Runs |
|---|---|
| `GET /system/health` | Every check, as HTML |
| `GET /system/health.json` | Every check, as JSON |
| `GET /system/health?tags=iap` | Only the checks tagged `iap` |

The web console shows the results too, on its Health Check page
(`/system/console/healthcheck`). A [status report](status.md) named `Health Check`
sums up every check that has a tag. It is a `SUCCESS` when they all pass. Otherwise the
worst result decides: `WARNING` for a `WARN`, and `ERROR` for `TEMPORARILY_UNAVAILABLE`
or worse. Only a privileged report names the failed checks.

## The checks

Four checks are tagged `iap`. They find problems in the configuration and the
environment:

| Name | Verifies |
|---|---|
| `IAP duplicate jars` | No two installed bundles have the same symbolic name. Two such bundles are usually two versions of one jar, deployed side by side |
| `IAP properties present` | The required repository properties exist and, where a value is required, have that value |
| `IAP services active` | The required OSGi services are registered |
| `IAP query counts` | Each configured JCR-SQL2 query returns a number of rows that satisfies its condition |

A fifth check, `Login Page Ready Check`, is tagged **`systemalive`** instead of `iap`,
since the startup gate waits for it. It renders `/login` inside Sling, through
`SlingRequestProcessor`, as the `healthcheck` subservice. A request over HTTP would
wait behind the gate, which waits for this check. The check passes on a 200. Any other
status, or a failure to render, makes it `TEMPORARILY_UNAVAILABLE`. The path has no
extension, like the one visitors reach: Sling's form authentication handler sends them
to `/login?resource=…`. That rendering includes the `.html` one, so a 200 covers both.

> **Do not rename that check.** `StartupGateFilter`, in the `startup-customization`
> module, lists five checks by name in `REQUIRED_CHECKS`, and `"Login Page Ready Check"`
> is one of them. The gate waits for each of the five to register and pass.

## Configuring the checks

Three of the `iap` checks read their configuration from nodes under
`/libs/iap/healthcheck/`, one child node for each thing to check. The module ships the
three containers empty. A module adds the checks it needs as children, in its own
initial content. `test-data` adds one example to each.

### `requiredProperties/`

| Property | Meaning |
|---|---|
| `propertyPath` | The absolute path of a property that must exist, such as `/libs/iap/conf/Media/logoLight` |
| `requiredValue` | Optional. The value the property must have, compared as a string, so `true` matches a boolean property |

### `requiredServices/`

| Property | Meaning |
|---|---|
| `serviceClass` | The fully qualified name a service must be registered under |
| `osgiFilter` | Optional. An OSGi filter the service must also match |

### `queryCountChecks/`

| Property | Meaning |
|---|---|
| `query` | A JCR-SQL2 select query |
| `comparator` | One of `<`, `<=`, `=`, `>=`, `>`, `!=` |
| `compareAgainst` | The number the row count is compared with, from 0 to `Long.MAX_VALUE - 1`. The query passes when `row count <comparator> compareAgainst` holds; any other number fails that query alone, as a configuration error |

The check reads at most `compareAgainst + 1` rows of a query, since no comparison needs
more.

A query may contain the placeholders `${yesterday}`, `${today}` and `${tomorrow}`.
Before running the query, the check replaces each with midnight at the start of that
day, in the server's time zone, such as `2026-11-05T00:00:00.000-05:00`. They go inside
a date literal:

```sql
SELECT * FROM [sub:Submission] AS submission
 WHERE submission.[jcr:created] > '${today}'
```

## Adding a check in code

A check is an OSGi component that implements Felix's `HealthCheck`. Its service
properties give its name and its tags:

```java
@Component(service = HealthCheck.class, property = {
    HealthCheck.NAME + "=IAP duplicate jars",
    HealthCheck.TAGS + "=iap"
})
public class DuplicateJarsHealthCheck implements HealthCheck
{
    @Override
    public Result execute()
    {
        return new Result(Result.Status.OK, "…");
    }
}
```

Tag a diagnostic `iap`. Tag a check `systemalive` only if requests must wait for it at
startup: the gate holds requests while any registered `systemalive` check fails, for up
to five minutes. Never tag a check whose remedy needs somebody to log in, since the gate
would keep that person out.

The tag alone does not make the gate wait for a check to register. For that, add its
name to `REQUIRED_CHECKS` too. A check that does not register on every start must stay
off that list, or the gate waits the full five minutes on each start where it is
missing.
