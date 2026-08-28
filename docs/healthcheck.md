# Health checks

**Module:** `modules/healthcheck` · **Bundle:** `iap-healthcheck` ·
**Package:** `…healthcheck.internal` · **Generic checks configured in:**
`packaging/slingfeature/src/main/features/healthcheck.json`

IAP plugs into
[Apache Felix Health Checks](https://felix.apache.org/documentation/subprojects/apache-felix-healthchecks.html).
The platform ships the generic Felix and Sling checks — memory, CPU, thread usage,
disk space, framework start, started bundles, bundle content loaded, services, DS
components, scheduler — configured in the packaging feature, and this module adds
platform-specific ones.

| Request | Runs |
|---|---|
| `GET /system/health` | Everything, as HTML |
| `GET /system/health.json` | Everything, as JSON |
| `GET /system/health?tags=iap` | Only the IAP-specific checks |

Results also appear in the web console's Sling Health Check tab, and the overall
outcome is published as a [status report](status.md) named `Health Check`: `ERROR`
when any check reached `TEMPORARILY_UNAVAILABLE` or worse, `WARNING` for anything
lesser that failed, naming the failing checks.

## The checks

Four are tagged `iap` and are configuration-driven or environmental:

| Name | Verifies |
|---|---|
| `IAP duplicate jars` | No two installed bundles share a symbolic name — usually two versions of the same jar deployed side by side |
| `IAP properties present` | Required repository properties exist and, optionally, hold an expected value |
| `IAP services active` | Required OSGi services are registered |
| `IAP query counts` | JCR-SQL2 queries return a result count satisfying a condition |

A fifth, `Login Page Ready Check`, is tagged **`systemalive`** rather than `iap`,
because it is not a diagnostic but a gate condition. It renders `/login` internally
through `SlingRequestProcessor` — no HTTP round trip — under the `healthcheck`
subservice, and passes only on a 200; anything else is
`TEMPORARILY_UNAVAILABLE`. The path is deliberately extensionless, because that is
what visitors actually hit: the authentication handler redirects to
`/login?resource=…`, which renders through the extensionless scripts.

> **Do not rename that check.** `StartupGateFilter` (module
> `startup-customization`) holds requests until a named set of checks passes, and
> `"Login Page Ready Check"` is one of the five string literals in its
> `REQUIRED_CHECKS` — with `OSGi Framework Ready Check`, `Bundles Started`,
> `Authentication Handler Ready Check` and `Services Ready Check`, whose names the
> packaging feature pins with an explicit `hc.name` so an upstream default cannot
> change them underneath. The gate names checks rather than trusting the tag, so
> adding one to `systemalive` does not silently make it a startup prerequisite:
> `Bundle Content Loaded` carries the tag and is not in the gate's list.

## Configuring the checks

Three of the `iap` checks are driven by configuration nodes under
`/libs/iap/healthcheck/`. The module ships only the empty containers; other modules
contribute checks through their own initial content, and `test-data` provides a demo
set for each.

### `requiredProperties/`

| Property | Meaning |
|---|---|
| `propertyPath` | Full JCR path of the property that must exist |
| `requiredValue` | Optional expected value, compared through its string representation |

### `requiredServices/`

| Property | Meaning |
|---|---|
| `serviceClass` | Fully qualified name of the service that must be registered |
| `osgiFilter` | Optional OSGi service filter the registration must match |

### `queryCountChecks/`

| Property | Meaning |
|---|---|
| `query` | A JCR-SQL2 select query |
| `comparator` | One of `<`, `<=`, `=`, `>=`, `>`, `!=` |
| `compareAgainst` | The expected count; the check passes when `actualCount comparator compareAgainst` holds |

The query is capped at `compareAgainst + 1` rows, since nothing beyond that changes
the outcome of any comparator.

Query strings may carry date placeholders resolved at execution time —
`${yesterday}`, `${today}`, `${tomorrow}`, each that date at the server's
midnight — usable inside date literals:

```sql
SELECT * FROM [sub:Submission] AS submission
 WHERE submission.[jcr:created] > '${today}'
```

## Adding a check in code

A check is an OSGi component implementing Felix's `HealthCheck`, named and tagged
through service properties:

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

Tag `iap` for a diagnostic. Reserve `systemalive` for a condition that should hold
requests at startup — and if a new check belongs in the gate, add its name to
`REQUIRED_CHECKS` deliberately, since the tag alone will not put it there.
