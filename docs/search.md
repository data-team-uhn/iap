# Search

**Module:** `modules/search` (`api` + `impl`) · **Bundles:** `iap-search-api`,
`iap-search-impl` · **API:** `io.uhndata.iap.search.api` (`SearchParameters`,
`SearchParametersFactory`, `SearchUtils`) · **SPI:**
`io.uhndata.iap.search.spi.QuickSearchEngine`

`GET /search.json` runs a query against the repository and returns the results as JSON.
It is the general-purpose counterpart to the per-homepage listing endpoint: where
`/Submissions.paginate.json` answers "the submissions matching these filters", `/search`
answers "whatever matches this query, wherever it is".

The query runs **in the session of whoever sent the request**, so a search never reveals
content that user could not read anyway. It does not limit what the query may *cost* —
see [Unindexed queries](#unindexed-queries).

`/search` is a plain node of type `data:Search`, created world-readable by repoinit, and
`SearchServlet` is bound to the `data/Search` resource type with the `json` extension.
**The extension is not optional**: `/search` without one resolves to the default
rendering of the node itself.

## What to look for

Exactly one of these says what to search for. If more than one is sent, the first
non-empty one in this order wins and the rest are ignored; if none is sent, the response
is an empty result set rather than an error.

| Parameter | Meaning |
| --- | --- |
| `query` | A complete JCR-SQL2 statement, run as it is |
| `fulltext` | Text to look for anywhere in the content, [bar the repository's own bookkeeping](#what-a-full-text-search-does-not-look-at) |
| `quick` | Text to be matched by the registered [quick search engines](#quick-search) |

```
GET /search.json?query=select * from [sub:Submission]&limit=25
GET /search.json?fulltext=diabetes
GET /search.json?quick=diab&allowedResourceTypes=sub:Submission
```

(URL-encode in earnest; the examples are readable rather than literal.)

## Shaping the response

The remaining request parameters decide what comes back rather than what is looked for.
All are optional, and all combine with any of the three modes above unless a row says
otherwise.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `offset` | `0` | How many matches to skip |
| `limit` | `10` | How many matches to return, capped at `1000`; `0` counts without returning any |
| `resourceSelectors` | none | Extra [serialization selectors](json-serialization.md) per match — `deep`, `2`, `dereference`. Ignored by `quick` and by `rawResults` |
| `req` | none | An opaque token echoed back, so a client can discard an out-of-order response |
| `rawResults` | `false` | Return the columns the query selected instead of the serialized nodes. Only useful with `query`, honoured for `fulltext`, ignored by `quick`, and forced for a [reporting query](#asking-about-a-query-instead-of-running-it) |
| `doNotEscapeQuery` | `false` | Treat the `fulltext` input as a full-text expression, operators and all |
| `allowedResourceTypes` | all | Repeatable; the node types a `quick` search may return |

The response is the standard paginated shape, produced by `PaginatedJsonResponse`
(`iap-java-utils`) — the same helper behind the listing endpoints, so the envelope and
every limit below are shared rather than search-specific:

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

Four behaviours worth coding against:

- **`totalrows` is counted result by result**, because the repository does not report a
  total. Counting stops `LOOKAHEAD_PAGES` (100) past the requested page, and
  `totalIsApproximate` then says the real total is larger.
- **No request reads more than `MAX_COUNT` (10 000) matches**, however large a page it
  asks for and however far in it starts. `limit=0` counts up to that ceiling, which is
  what makes it useful for "how many are there?"; a page starting near it comes back
  short, and one starting past it comes back empty. `limit` is capped on its own, but
  `offset` is a client parameter too, and without a ceiling one request asking for a far
  enough page would walk the entire repository.
- **A node reached several times is returned once** — which a join does routinely. For a
  statement with several selectors, the node returned is the one on the **first**
  selector. Raw results are *not* deduplicated: there the rows are what the client asked
  for, and two identical ones may well be intended.
- **A match that cannot be serialized is left out of `rows` but still counted**, so
  `returnedrows` may be smaller than the page.

If reading fails part-way through, the response is still a complete JSON document: the
rows gathered so far, the usual summary, and two extra fields at the end.

```json
{
  "rows": [ … ],
  "offset": 0, "limit": 10, "returnedrows": 3, "totalrows": 3,
  "totalIsApproximate": false,
  "error": "…what went wrong…",
  "partial": true
}
```

`partial` is what lets a client tell an incomplete page from a short one, and it is the
field to check before treating a page as the end of the results. Neither field appears on
a page read in full. The beginning of the response may already have gone out by the time
the failure happens, so it is too late for an error status.

### Raw results

`rawResults=true` returns one object per row holding the path of each selector and the
value of each selected column, instead of the whole node — for a client that needs a
couple of properties or the result of an aggregation and should not pay for full
serialization:

```
GET /search.json?rawResults=true&query=SELECT s.category FROM [sub:Submission] AS s
```

```json
{ "rows": [ { "s": "/Submissions/s1", "s.category": "renal" } ], … }
```

A column holding a binary is reported as `null`. Reading a binary means reading all of
it, and a statement is free to select the data of every file in the repository, so the
response says the column is there and leaves its contents to be fetched from the node.

### Naming a referenced node by its path

A reference property stores a UUID, generated at creation and therefore different on
every instance, so a statement kept in the sources cannot spell one out. Name the node
by its path instead:

```
GET /search.json?query=select * from [sub:Answer] as a
                       where a.question = '/Schemas/Consent/1.0/hasCapacity'
```

`QueryPathResolver` replaces the path with the UUID the property actually holds before
the statement runs. **Only a literal compared against a property the node type declares
as `REFERENCE` or `WEAKREFERENCE` is translated**, which is what leaves alone both a
property whose own value is a path and a path handed to a function, as in
`isdescendantnode(n, '/Submissions')`.

Anything that cannot be made sense of is left exactly as sent, so the statement runs as
written: an unregistered node type, an unqualified property in a multi-selector
statement, a path with no node at it, or a target that is not `mix:referenceable` and so
cannot be a reference value. The last three are logged, because the statement then
matches nothing and the response cannot say why.

### Asking about a query instead of running it

A `query` may start with `explain`, returning the plan the repository would run, or
`measure`, returning how much it had to scan. Both combine, as `explain measure …`.

```
GET /search.json?query=explain select * from [sub:Submission]
```

```json
{ "rows": [ { "n": null, "plan": "[sub:Submission] as [n] /* traverse … */",
              "statement": "select * from [sub:Submission]" } ], … }
```

Such a row is about the query rather than a node, so there is nothing to serialize and
the raw format is used whether or not `rawResults` was asked for. The selector is
reported with no path, which is what the repository gives for a row with no node behind
it. A reporting query is not asked for a plan of its own: prefixing an `explain` with
another is not a statement the repository will parse, and the plan of a `measure` is the
plan it already reports on.

> **When working on this:** the repository answers `getColumnNames()` for a reporting
> query with the columns of the statement being *reported on*, and only names the columns
> its rows really hold once the rows have been asked for. `writeRawResults` therefore
> reads the rows first, and `SearchServletTest` reproduces the order dependency so that
> swapping the two lines back fails the build rather than quietly returning nothing.

## Full-text search

`fulltext=…` becomes:

```sql
select n.* from [nt:base] as n where contains(n.*, '…')
  and not issamenode(n, '/jcr:system') and not isdescendantnode(n, '/jcr:system')
```

By default the input is escaped so it is found verbatim, operators and all.
`doNotEscapeQuery=true` leaves the full-text operators alone, so a user can write
`heart -failure` or `diabet*` and mean it.

Input handling in both modes:

- **Leading and trailing space is stripped.** A full-text expression has to start with a
  term, so a space left in front by a paste or an autocompletion would otherwise come
  back as `400`. Space *between* words is the expression's own separator and is left
  alone.
- **Quotes are escaped either way** — the double quote that opens a phrase and the
  apostrophe that does the same, which the statement's own escaping would otherwise hand
  straight to the full-text parser. They delimit the string in the statement, so leaving
  them to the client would let it write the rest of the query.
- **Whitespace and `OR` are not yet escaped by default**, so `cats OR dogs` still reads
  as two alternatives rather than as that text. Making it literal means quoting each
  term, which changes what multi-word searches match, so it waits for a caller whose
  expectations can be checked against.

### What a full-text search does not look at

This is the only mode spanning every node type, so it is the only one that reaches
`/jcr:system`, and it is kept out. Two things there would otherwise crowd out the
results:

- **Version storage.** `data:Entity` is `mix:versionable` and the Sling POST servlet
  checks versionable nodes in automatically, so every edit leaves a frozen copy of all
  the node's properties under `/jcr:system/jcr:versionStorage`. A submission edited
  twenty times would answer a search for its own text twenty-one times over, on paths the
  client can do nothing with.
- **The node type registry.** Every type, property definition and child definition under
  `/jcr:system/jcr:nodeTypes` is a node with a searchable name. Measured on Oak 2.4.0, a
  search for `versionable` in a repository holding one matching submission returned thirty
  rows, twenty-nine of them node type definitions.

The `/jcr:system` node itself is excluded alongside its descendants: `isdescendantnode`
is strictly about descendants, and the node carries a `rep:system` primary type that
answers a search for `system`.

**The exclusion costs nothing.** Measured on Oak 2.4.0 with a Lucene full-text index,
the query plan is byte for byte the same with it and without — Oak picks the same index
and applies the path restriction to the rows it returns.

Neither other mode needs this. A `query` runs exactly as sent, version storage and all: a
client writing its own JCR-SQL2 asked for what it asked for. A `quick` search is whatever
its engines make it, and a typed query cannot reach version storage by accident — a
frozen node takes `nt:frozenNode` as its own primary type and records the original's in a
property, so it never matches the type its original would.

## Quick search

A quick search answers "what do I have that mentions this?" while the user is still
typing. Unlike the other two modes it does not run one query: it asks every registered
`QuickSearchEngine` supporting at least one of the requested node types, and stops as
soon as enough results have been collected. An engine is expected to look wherever the
user would expect a match to be — including in descendants of the content it returns —
and to describe each match, so the client can show why the result is there.

**No engines are registered yet.** The extension point exists; until something
implements it, a `quick` search returns nothing.

### Implementing an engine

```java
@Component(service = QuickSearchEngine.class)
public class SubmissionQuickSearchEngine implements QuickSearchEngine
{
    @Override
    public List<String> getSupportedTypes() { return List.of("sub:Submission"); }

    @Override
    public Results quickSearch(final SearchParameters query, final ResourceResolver resolver)
    {
        // query.getResourceTypes() is the subset of the above that this request asked for
        // query.getMaxResults() is how many results can still be used — bound the query with it
        …
    }
}
```

`Results` is an `Iterator<JsonObject>` with two additions: `skip()`, for moving past a
result without building its JSON, and `close()`. The servlet calls `skip()` for every
match it only has to count — those before the offset and those past the page — so an
engine that can make skipping cheaper than `next()` should. `Results.empty()` is there
for an engine with nothing to say.

`SearchUtils` has the rest:

| Helper | For |
| --- | --- |
| `escapeLikeText`, `escapeQueryArgument` | Getting the user's input safely into a query. A `like` pattern needs both, in that order |
| `getMatch`, `getMatchFromArray` | Finding which of a property's values matched |
| `addMatchMetadata` | Describing the match, under the `MATCH_KEY` (`data:queryMatch`) property |

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

`before` and `after` carry up to eight characters of context each, elided with `...` when
there was more. `text` is the matched text as stored, which may differ in case from what
the user typed.

## Unindexed queries

A statement with no index to work with makes the repository walk the content. Since the
statement comes from the client, `/search.json` logs a warning naming the statement and
its plan when that happens, so an expensive query is attributable to this endpoint rather
than only to the repository's own traversal warnings. **The query still runs**: IAP's
`oak:index` coverage is thin, and failing such queries outright would reject legitimate
ones.

Obtaining the plan means planning the query twice, deliberately: planning is cheap next
to a traversal, which is exactly the case this exists to report. The statement the plan is
asked for is not quite the one the client sent, so the client's own statement is parsed
first — that way a syntax error is reported against what was actually sent.

The logged statement is put on one line and cut to `MAX_LOGGED_STATEMENT` (500)
characters. In `fulltext` mode it is built around the text the user typed, and a line
break there would otherwise let a client write log entries of its own choosing. Nothing
here can fail a request: obtaining a plan is diagnostics, and a request the repository
would have served is served.

## Errors

| Status | When |
| --- | --- |
| `400` | The statement cannot be parsed, or the repository cannot make sense of it — a malformed full-text expression, say — with the repository's message |
| `500` | The query could not be executed |

Both are a JSON object with a single `error` property, and both are for a request that
failed before any result was written. A failure part-way through cannot use them, because
the response has already started; it is reported in the summary instead, as under
[Shaping the response](#shaping-the-response).

## Future work

- **Nothing calls this yet.** No engine implements `QuickSearchEngine`, so `quick` has
  nothing to call, and nothing in the frontend calls `/search.json`.
- **A `lucene` mode**, running a native Lucene query, was deliberately left out: it needs
  Lucene index definitions IAP does not have yet.
- **The cost of a query is still unbounded.** How many results a request *reads* is
  capped, but a statement with no index still makes the repository walk the content to
  find the first ten thousand matches. Oak's own `queryLimitReads`, `queryLimitInMemory`
  and `failTraversal` are not configured anywhere in `packaging/`, and would bound it at
  the source.
- **Restricting `query` to administrators** would close off arbitrary JCR-SQL2 entirely,
  if the frontend turns out not to need it.
- **Adding a `storedQuery` mode** that only allows searching using one of the predefined
  queries, as a safe way to allow running JCR queries without the risks of the full
  `query` mode.
- **Two more non-content trees are still in reach of a `fulltext` search.** `/oak:index`
  answers a search for the property names its definitions list, and
  `/rep:security/rep:authorizables` puts every user and group account in reach of a search
  for a name. The user store is the one worth deciding about: either it is content and a
  search should find people, or it is not and it belongs in the same exclusion.
- **`resourceSelectors` is only honoured when whole nodes are serialized.** Passing it
  through to the engines, as a field on `SearchParameters`, would let `quick` results
  respect it too.
- **Making the default `fulltext` mode literal** for whitespace and `OR`.
