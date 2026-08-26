# Search

`GET /search.json` runs a query against the repository and returns the results as JSON. It is the
general-purpose counterpart to the per-homepage listing endpoint: where `GET /Submissions.paginate.json`
answers "the submissions matching these filters", `/search` answers "whatever matches this query,
wherever it is".

The query runs in the session of whoever sent the request, so a search never reveals content that
user could not read anyway. It does *not* limit what the query may cost, though — see
[Unindexed queries](#unindexed-queries).

The endpoint is a plain repository node, `/search`, of type `data:Search`, world-readable, served by
`SearchServlet`, registered on the `data/Search` resource type with the `json` extension. The extension is
not optional: `/search` without one resolves to the default rendering of the node itself, not to the
search.

## What to look for

Exactly one of these parameters says what to search for. If more than one is sent, the first one in
this order that isn't empty wins and the others are ignored; if none is sent, the response is an
empty result set rather than an error.

| Parameter | Meaning |
| --- | --- |
| `query` | A complete JCR-SQL2 statement, run as it is |
| `fulltext` | A text to look for anywhere in the content, [bar the repository's own bookkeeping](#what-a-full-text-search-does-not-look-at) |
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
| `resourceSelectors` | none | Extra selectors used when serializing each match, e.g. `deep` or `2` for children, `dereference`; ignored by `quick`, and by `rawResults` |
| `req` | none | An opaque token echoed back, so a client can discard an out-of-order response |
| `rawResults` | `false` | Return the columns the query selected instead of serializing the matched nodes; only useful with `query`, the one mode where the client chooses the columns, though it is honoured for `fulltext` too; ignored by `quick`, and used whether or not it was asked for by a [reporting query](#asking-about-a-query-instead-of-running-it) |
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
stops a hundred pages past the requested one, and `totalIsApproximate` then says the real total is
larger. A match that cannot be serialized is left out of `rows` but still counted, so `returnedrows`
may be smaller than the page.

No request reads more than **10 000 matches**, however large a page it asks for and however far into
the results it starts. `limit=0` counts up to that ceiling, which is what makes it useful for asking
"how many are there?"; a page starting near it comes back short, and one starting past it comes back
empty. The `limit` is capped on its own, but the `offset` is a request parameter too, and without a
ceiling a single request asking for a far enough page would walk the entire repository.

The same node reached several times — which a query with a join does routinely — is returned once.
For a query with more than one selector, the node returned is the one on the **first** selector.
Raw results are not deduplicated: there, the rows are what the client asked for, and two identical
ones may well be intended.

If reading the results fails part-way through, the response is still a complete JSON document: the
rows gathered so far, the usual summary, and an `error` alongside `"partial": true`. By then the
beginning of the response may already have gone out, so it is too late for an error status.

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

A column holding a binary is reported as `null`. Reading a binary means reading all of it, and a
statement is free to select the data of every file in the repository, so the response says the
column is there and leaves its contents to be fetched from the node itself.

### Naming a referenced node by its path

A reference property stores the UUID of the node it points to, and a UUID is generated when the node
is created, so it differs between instances. A statement kept in the sources cannot spell one out,
and names the node by its path instead:

```
GET /search.json?query=select%20*%20from%20%5Bsub%3AAnswer%5D%20as%20a%20where%20a.question%20%3D%20'%2FSchemas%2FConsent%2F1.0%2FhasCapacity'
```

The path is replaced by the UUID that the property actually holds before the statement runs. Only a
literal compared against a property the node type declares as a `REFERENCE` or `WEAKREFERENCE` is
translated, which is what leaves alone both a property whose own value is a path and a path given to
a function, as in `isdescendantnode(n, '/Submissions')`.

Anything that cannot be made sense of is left exactly as it was sent, so the statement runs as
written: an unregistered node type, an unqualified property in a statement that declares more than
one selector, a path with no node at it, or a target that is not `mix:referenceable` and therefore
cannot be the value of a reference. The last three are logged, because the statement then matches
nothing and the response cannot say why.

### Asking about a query instead of running it

A `query` may start with `explain`, which returns the plan the repository would run, or `measure`,
which returns how much it had to scan. Both may be combined, as `explain measure …`.

```
GET /search.json?query=explain%20select%20*%20from%20%5Bsub%3ASubmission%5D
```

```json
{ "rows": [ { "n": null, "plan": "[sub:Submission] as [n] /* traverse … */",
              "statement": "select * from [sub:Submission]" } ], … }
```

Such a row is about the query rather than about a node, so there is nothing to serialize from it and
the raw format is used whether or not `rawResults` was asked for. The selector is reported with no
path, which is what the repository gives for a row that has no node behind it. A reporting query is
not asked for a plan of its own either: prefixing an `explain` with another one is not a statement
the repository will parse, and the plan of a `measure` is the plan it already reports on.

One thing to know when working on this: the repository answers `getColumnNames()` for a reporting
query with the columns of the statement being reported on, and only names the columns its rows
really hold once the rows have been asked for. `writeRawResults` therefore reads the rows first, and
`SearchServletTest` reproduces the same order dependency so that swapping the two lines back fails
the build rather than quietly returning nothing.

## Full-text search

`fulltext=…` becomes

```sql
select n.* from [nt:base] as n where contains(n.*, '…')
  and not issamenode(n, '/jcr:system') and not isdescendantnode(n, '/jcr:system')
```

By default the input is escaped so that it is found verbatim, operators and all.
`doNotEscapeQuery=true` leaves the full-text operators alone, so a user can write `heart -failure`
or `diabet*` and mean it.

### What a full-text search does not look at

This is the only mode that spans every node type, so it is the only one that reaches the
repository's own bookkeeping under `/jcr:system`, and it is kept out of it. Two things live there
that would otherwise crowd out the results:

- **Version storage.** `data:Entity` is `mix:versionable` and the Sling POST servlet is configured
  to check versionable nodes in automatically, so every edit leaves a frozen copy of all the node's
  properties under `/jcr:system/jcr:versionStorage`. A submission edited twenty times would answer
  a search for its own text twenty-one times over, on paths the client can do nothing with.
- **The node type registry.** Every type, property definition and child definition under
  `/jcr:system/jcr:nodeTypes` is a node with a searchable name. Measured against Oak 2.4.0, a
  search for `versionable` in a repository holding a single matching submission returned thirty
  rows, twenty-nine of them node type definitions.

The `/jcr:system` node itself is excluded alongside its descendants: `isdescendantnode` is strictly
about the descendants, and the node carries a `rep:system` primary type that answers a search for
`system`.

The exclusion costs nothing. Measured against Oak 2.4.0 with a Lucene full-text index, the query
plan is byte for byte the same with it and without it — Oak picks the same index and applies the
path restriction to the rows it returns.

Neither of the other two modes needs this. A `query` is run exactly as it was sent, version storage
and all: a client writing its own JCR-SQL2 asked for what it asked for. A `quick` search is
whatever its engines make it, and a typed query cannot reach version storage by accident — a frozen
node takes `nt:frozenNode` as its own primary type and records the original's in a property, so it
never matches the type its original would.

The text is stripped first, in both modes: a full-text expression has to start with a term, so a
leading space — which a paste or an autocompletion routinely leaves in front of what was typed —
would otherwise come back as a `400`. Space *between* the words is the expression's own separator
and is left alone.

Quotes are escaped either way — both the double quote that opens a phrase and the apostrophe that
does the same, which the statement's own escaping would otherwise hand straight to the full-text
parser — since they delimit the string in the statement, and leaving them to the client would let
it write the rest of the query.

One thing the default does not yet escape is whitespace and the `OR` keyword, so a search for
`cats OR dogs` is still read as two alternatives rather than as that text. Making it literal means
quoting each term, which changes what multi-word searches match, so it is left for when there is a
caller whose expectations can be checked against.

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
  "data:queryMatch": {
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

The statement is put on a single line and cut down to 500 characters before it is logged. In
`fulltext` mode it is built around the text the user typed, and a line break in that text would
otherwise let a client write log entries of its own choosing. Nothing here can fail a request:
obtaining a plan is diagnostics, and a request the repository would have served is served.

## Errors

| Status | When |
| --- | --- |
| `400` | The statement cannot be parsed, or the repository cannot make sense of it — a malformed full-text expression, say — with the repository's message |
| `500` | The query could not be executed |

Both are a JSON object with a single `error` property.

These are for a request that failed before any result was written. A failure part-way through the
results cannot use them, because the response has already started; it is reported in the summary
instead, as described under [Shaping the response](#shaping-the-response).

## Future work

- No engine implements `QuickSearchEngine` yet, so the `quick` mode has nothing to call.
- Nothing in the frontend calls `/search.json` yet.
- A `lucene` mode, running a native Lucene query, was deliberately left out: it needs Lucene index
  definitions that IAP does not have yet.
- How many results a request reads is now bounded, but the cost of the *query itself* is not: a
  statement with no index still makes the repository walk the content to find the first ten
  thousand matches. Oak's own `queryLimitReads`, `queryLimitInMemory` and `failTraversal` settings
  are not configured anywhere in `packaging/`, and would bound it at the source.
- If arbitrary JCR-SQL2 turns out not to be needed by the frontend, restricting the `query` mode to
  administrators would close that off entirely.
- `/jcr:system` is kept out of a `fulltext` search, but the two other trees that are not content
  are not: `/oak:index` answers a search for the property names its definitions list, and
  `/rep:security/rep:authorizables` puts every user and group account in reach of a search for a
  name. The user store is the one worth deciding about — either it is content, and a search should
  find people, or it is not, and it belongs in the same exclusion.
- `resourceSelectors` is only honoured when whole nodes are serialized. Passing it through to the
  engines, as a field on `SearchParameters`, would let `quick` results respect it too.
- Making the default `fulltext` mode literal for whitespace and `OR`, as noted under
  [Full-text search](#full-text-search).
