# Health checks

IAP plugs into the [Apache Felix Health Checks](https://felix.apache.org/documentation/subprojects/apache-felix-healthchecks.html)
system: the platform ships the generic Felix checks (memory, CPU, disk space, started bundles,
scheduler...) configured in the packaging feature, and the `iap-healthcheck` module
(`modules/healthcheck`) adds platform-specific checks, all tagged `iap`:

- **IAP duplicate jars** — warns when two installed bundles share the same symbolic name, which
  usually means two versions of the same jar were deployed side by side.
- **IAP properties present** — verifies that required repository properties exist and,
  optionally, hold an expected value.
- **IAP services active** — verifies that required OSGi services are registered.
- **IAP query counts** — runs JCR-SQL2 queries and verifies that the number of results
  satisfies an expected condition.

Results are served by the Felix executor at `/system/health` (append `.json` for JSON output,
`?tags=iap` to run only the IAP-specific checks), and shown in the web console's Sling Health
Check tab when running with `--dev`.

## Configuring the checks

The last three checks are driven by configuration nodes in the repository, under
`/libs/iap/healthcheck/`; the module only ships the (empty) containers, and other modules
contribute the actual checks through their initial content. The test-data module provides a
demo set for each.

### `requiredProperties/`

| Property | Meaning |
| --- | --- |
| `propertyPath` | Full JCR path of the property that must exist |
| `requiredValue` | Optional expected value, compared through its string representation |

### `requiredServices/`

| Property | Meaning |
| --- | --- |
| `serviceClass` | Fully qualified name of the service that must be registered |
| `osgiFilter` | Optional OSGi service filter the registration must match |

### `queryCountChecks/`

| Property | Meaning |
| --- | --- |
| `query` | A JCR-SQL2 select query to execute |
| `comparator` | One of `<`, `<=`, `=`, `>=`, `>`, `!=` |
| `compareAgainst` | The expected count; the check passes when `actualCount comparator compareAgainst` holds |

For performance, the query only fetches `compareAgainst + 1` rows. The query string may contain
date placeholders resolved at execution time — `${yesterday}`, `${today}`, `${tomorrow}`, each
the respective date at the server's midnight — usable in date literals, e.g.
`WHERE submission.[jcr:created] > '${today}'`.

The checks read the repository through the `healthcheck` subservice of the
`io.uhndata.iap.healthcheck` bundle, mapped to the `sling-readall` service user.
