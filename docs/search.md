# Search

`GET /search.json` runs a query against the repository and returns the results as JSON. It is the
general-purpose counterpart to the per-homepage listing endpoint: where `GET /Submissions.paginate.json`
answers "the submissions matching these filters", `/search` answers "whatever matches this query,
wherever it is".

The query runs in the session of whoever sent the request, so a search never reveals content that
user could not read anyway. It does *not* limit what the query may cost, though — see
[Unindexed queries](#unindexed-queries).

The endpoint is a plain repository node, `/search`, of type `iap:Search`, world-readable, served by
`SearchServlet`, registered on the `iap/Search` resource type with the `json` extension. The extension is
not optional: `/search` without one resolves to the default rendering of the node itself, not to the
search.

## What to look for

Exactly one of these parameters says what to search for. If more than one is sent, the first one in
this order that isn't empty wins and the others are ignored; if none is sent, the response is an
empty result set rather than an error.

| Parameter | Meaning |
| --- | --- |
| `query` | A complete JCR-SQL2 statement, run as it is |
| `fulltext` | A text to look for anywhere in the repository |
| `quick` | A text to be matched by the registered [quick search engines](#quick-search) |

```
GET /search.json?query=select%20*%20from%20%5Bsub%3ASubmission%5D&limit=25
GET /search.json?fulltext=diabetes
GET /search.json?quick=diab&allowedResourceTypes=sub:Submission
```

## Shaping the response

| Parameter | Default | Meaning |
| --- | --- | --- |
| `offset` | `0` | How many matches to skip |
| `limit` | `10` | How many matches to return, capped at `1000`; `0` counts without returning any |
| `resourceSelectors` | none | Extra selectors used when serializing each match, e.g. `deep` or `2` for children, `dereference` |
| `req` | none | An opaque token echoed back, so a client can discard an out-of-order response |
| `rawResults` | `false` | Return the columns the query selected instead of serializing the matched nodes; only meaningful with `query` |
| `doNotEscapeQuery` | `false` | Treat the `fulltext` input as a full-text expression, operators and all |
| `allowedResourceTypes` | all | Repeatable; the node types a `quick` search may return |

The response is the standard paginated shape:

```json
{
  "rows": [ … ],
  "req": "17",
  "offset": 0,
  "limit": 10,
  "returnedrows": 10,
  "totalrows": 42,
  "totalIsApproximate": false
}
```

`totalrows` is counted result by result, because the repository doesn't report a total. Counting
stops ten pages past the requested one, and `totalIsApproximate` then says the real total is larger.
A match that cannot be serialized is left out of `rows` but still counted, so `returnedrows` may be
smaller than the page.

The same node reached several times — which a query with a join does routinely — is returned once.
Raw results are not deduplicated: there, the rows are what the client asked for, and two identical
ones may well be intended.

### Raw results

`rawResults=true` returns one object per row holding the path of each selector and the value of
each column the statement selected, instead of the whole node. This is what a client that only
needs a couple of properties, or the result of an aggregation, uses instead of paying for full
serialization:

```
GET /search.json?rawResults=true&query=SELECT%20s.category%20FROM%20%5Bsub%3ASubmission%5D%20AS%20s
```

```json
{ "rows": [ { "s": "/Submissions/s1", "s.category": "renal" } ], … }
```

## Full-text search

`fulltext=…` becomes `select n.* from [nt:base] as n where contains(n.*, '…')`. By default the
input is escaped so that it is found verbatim, operators and all. `doNotEscapeQuery=true` leaves
the full-text operators alone, so a user can write `heart -failure` or `diabet*` and mean it.

Quotes are escaped either way: they delimit the string in the statement, so leaving them to the
client would let it write the rest of the query.

## Quick search

A quick search answers "what do I have that mentions this?" while the user is still typing. Unlike
the other two modes it doesn't run one query: it asks every registered `QuickSearchEngine` that can
search at least one of the requested node types, and stops as soon as enough results have been
collected. An engine is expected to look wherever the user would expect a match to be — including
in descendants of the content it returns — and to describe each match, so the client can show why
the result is there.

**No engines are registered yet.** The extension point exists; until something implements it, a
`quick` search returns nothing.

### Implementing an engine

Register an OSGi component providing `io.uhndata.iap.search.spi.QuickSearchEngine`:

```java
@Component(service = QuickSearchEngine.class)
public class SubmissionQuickSearchEngine implements QuickSearchEngine
{
    @Override
    public List<String> getSupportedTypes()
    {
        return List.of("sub:Submission");
    }

    @Override
    public Results quickSearch(final SearchParameters query, final ResourceResolver resolver)
    {
        // query.getResourceTypes() is the subset of the above that this request asked for,
        // query.getMaxResults() is how many results can still be used — bound the query with it
        …
    }
}
```

`Results` is an `Iterator<JsonObject>` with one addition: `skip()`, for moving past a result
without building its JSON. The servlet calls `skip()` for every match it only has to count — those
before the offset, and those past the page — so an engine that can make skipping cheaper than
`next()` should.

`SearchUtils` has the helpers an engine needs: `escapeLikeText` and `escapeQueryArgument` for
getting the user's input safely into a query (a `like` pattern needs both, in that order),
`getMatch` for finding which of a property's values matched, and `addMatchMetadata` for describing
the match:

```json
{
  "…": "the serialized node",
  "iap:queryMatch": {
    "label": "Project title",
    "@path": "/Submissions/s1/title",
    "before": "…study of ",
    "text": "diabetes",
    "after": " in adult…"
  }
}
```

`before` and `after` carry up to eight characters of context each, elided with `...` when there was
more. `text` is the matched text as stored, which may differ in case from what the user typed.

## Unindexed queries

A statement with no index to work with makes the repository walk the content instead. Since the
statement comes from the client, `/search.json` logs a warning naming the statement and its plan when
that happens, so an expensive query is attributable to this endpoint rather than only to the
repository's own traversal warnings. The query still runs: IAP's `oak:index` coverage is thin, and
failing such queries outright would reject legitimate ones.

Obtaining the plan means planning the query twice. That is deliberate: planning is cheap next to a
traversal, which is exactly the case this exists to report. The statement the plan is asked for is
not quite the one the client sent, so the client's own statement is parsed first — that way a
syntax error is reported against what was actually sent.

## Errors

| Status | When |
| --- | --- |
| `400` | The statement cannot be parsed, with the repository's message |
| `500` | The query could not be executed |

Both are a JSON object with a single `error` property.

## Future work

- No engine implements `QuickSearchEngine` yet, so the `quick` mode has nothing to call.
- Nothing in the frontend calls `/search.json` yet.
- A `lucene` mode, running a native Lucene query, was deliberately left out: it needs Lucene index
  definitions that IAP does not have yet.
- The cost of a query is unbounded. If arbitrary JCR-SQL2 turns out not to be needed by the
  frontend, restricting the `query` mode to administrators would close that off entirely.
