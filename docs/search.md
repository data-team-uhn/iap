# Search

**Module:** `modules/search` (`api` + `impl`) · **Bundles:** `iap-search-api`,
`iap-search-impl` · **API:** `io.uhndata.iap.search.api` (`SearchContext`,
`SearchContextFactory`, `SearchUtils`) · **SPI:**
`io.uhndata.iap.search.spi.QuickSearchEngine`

`GET /search.json` searches the repository and returns the results as JSON. A search can
be a JCR-SQL2 statement, a text to find anywhere, or a text for the quick search
engines.

The query runs **in the session of whoever sent the request**, so a search returns only
what that user may read. Nothing limits what a query may *cost*; see [Unindexed
queries](#unindexed-queries).

`POST /search.json` runs the same search, with the parameters in a form-encoded body
instead of the query string. Neither changes anything. What someone searches for can be
as sensitive as what they find, and a query string is written to access logs and kept in
browser history. The caller chooses the method.

`/search` is a node of type `data:Search`, which repoinit creates readable by every
user. `SearchServlet` is registered on the `data/Search` resource type with the `json`
extension. **The extension is required**: `/search` without one is the node's default
rendering.

## What to look for

One of these says what to search for. If more than one is sent, the first that is not
blank, in this order, is used. If none is sent, the result is empty.

| Parameter | Meaning |
| --- | --- |
| `query` | A JCR-SQL2 statement, run as it is |
| `fulltext` | A text to look for anywhere in the content, [except in the repository's own records](#what-a-full-text-search-does-not-look-at) |
| `quick` | A text for the registered [quick search engines](#quick-search) to match |

```
GET /search.json?query=select * from [sub:Submission]&limit=25
GET /search.json?fulltext=diabetes
GET /search.json?quick=diab&allowedResourceTypes=sub:Submission
```

The examples are not URL-encoded, for readability.

## Shaping the response

The other parameters decide what comes back. All are optional, and all work with each of
the three searches unless the table says otherwise.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `offset` | `0` | How many matches to skip |
| `limit` | `10` | How many matches to return, at most `1000`; `0` counts them without returning any |
| `resourceSelectors` | none | Extra [serialization selectors](json-serialization.md) for each match, such as `deep`, `2` or `dereference`. Not applied by `quick` or `rawResults` |
| `req` | none | An opaque token echoed back, so that a client can discard an out-of-order response |
| `rawResults` | `false` | Return the columns the query selected instead of the serialized nodes. Applies to `query` and `fulltext`, not to `quick`, and always on for a [reporting query](#asking-about-a-query-instead-of-running-it) |
| `doNotEscapeQuery` | `false` | Read the `fulltext` input as a full-text expression, operators and all |
| `allowedResourceTypes` | all | Repeatable: the node types a `quick` search may return |

The response is the standard paginated shape, written by `PaginatedJsonResponse`
(`iap-java-utils`):

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

Four rules shape the numbers:

- **`totalrows` is counted one result at a time**, because Oak's own count is exact only
  for small results. Counting stops at the end of the batch of `LOOKAHEAD_PAGES` (100)
  pages that holds the requested page. `totalIsApproximate` then says the real total is
  larger.
- **No request counts more than `MAX_COUNT` (10 000) matches**, however large a page it
  asks for and however far in it starts. `limit=0` counts up to that ceiling. A page
  starting near the ceiling comes back short, and one starting past it comes back empty.
- **A node a `query` or `fulltext` search reaches several times is returned once.** A
  join does this routinely. For a statement with several selectors, the node returned is
  the one on the **first** selector. Raw results are *not* deduplicated, since two
  identical rows may both be meant.
- **A match that cannot be serialized is left out of `rows` but still counted**, so
  `returnedrows` can be smaller than the page.

If reading fails part-way through, the response is still a complete JSON document: the
rows gathered so far, the usual summary, and two more fields at the end.

```json
{
  "rows": [ … ],
  "offset": 0, "limit": 10, "returnedrows": 3, "totalrows": 3,
  "totalIsApproximate": false,
  "error": "…what went wrong…",
  "partial": true
}
```

`partial` tells an incomplete page from a short one. Check it before treating a page as
the end of the results. Neither field appears on a page read in full. The start of the
response may already have gone out when the failure happens, so it cannot change the
status.

### Raw results

`rawResults=true` returns one object per row, holding the path of each selector and the
value of each selected column, instead of the whole node. A client that needs a few
properties does not pay for serializing whole nodes:

```
GET /search.json?rawResults=true&query=SELECT s.title FROM [sub:Submission] AS s
```

```json
{ "rows": [ { "s": "/Submissions/s1", "s.title": "Annual leave" } ], … }
```

A column holding a binary is reported as the string `BINARY_VALUE`, and the client
fetches its contents from the node. A `null` means the row has no value for the column.

### Naming a referenced node by its path

A reference property stores a UUID. The UUID is generated when the node is created, so
it differs between instances, and a statement kept in the sources cannot spell one out.
Name the node by its path instead:

```
GET /search.json?query=select * from [sub:Answer] as a
                       where a.question = '/Schemas/consent/v1/hasCapacity'
```

`QueryPathResolver` replaces the path with the UUID the property holds before the
statement runs. **Only a literal compared with a property the node type declares as
`REFERENCE` or `WEAKREFERENCE` is translated.** A property whose own value is a path is
left alone, and so is a path passed to a function, as in `isdescendantnode(n,
'/Submissions')`.

Whatever cannot be resolved is left as sent, and the statement runs as written: an
unregistered node type, an unqualified property in a statement with several selectors, a
path with no node at it, or a target that is not `mix:referenceable`. The last two are
logged, because the statement then matches nothing and the response does not say why.

### Asking about a query instead of running it

A `query` may start with `explain`, which returns the plan the repository would run, or
`measure`, which returns how much it had to scan. Both together are written `explain
measure …`.

```
GET /search.json?query=explain select * from [sub:Submission] as n
```

```json
{ "rows": [ { "n": null, "plan": "[sub:Submission] as [n] /* traverse … */",
              "statement": "select * from [sub:Submission] as n" } ], … }
```

Such a row is about the query, not about a node, so it always comes back raw, whether or
not `rawResults` was asked for. The selector has no path, since no node is behind the
row. A reporting query gets no [plan check](#unindexed-queries) of its own.

## Full-text search

`fulltext=…` becomes:

```sql
select n.* from [nt:base] as n where contains(n.*, $text)
  and not issamenode(n, '/jcr:system') and not isdescendantnode(n, '/jcr:system')
  and not issamenode(n, '/SystemWorkflows')
  and not isdescendantnode(n, '/SystemWorkflows')
```

The text is **bound to `$text`**, not written into the statement. The statement is the
same whatever the input, and nothing a user types becomes JCR-SQL2.

By default the input is escaped, so that it is found as it is, operators and all.
`doNotEscapeQuery=true` leaves the full-text operators alone, so a user can write
`heart -failure` or `diabet*`. This escaping is for the full-text grammar, which applies
to whatever the variable holds. Binding does not do it.

Input handling, with or without escaping:

- **Leading and trailing space is stripped.** A full-text expression has to start with a
  term, and a leading space would come back as `400`. Space *between* words separates
  the terms, and is left alone.
- **Both quote characters are full-text operators.** The double quote and the apostrophe
  each open a phrase. The default escapes them, and `doNotEscapeQuery=true` leaves them
  alone, along with everything else.
- **Whitespace and `OR` are not escaped**, even by default, so `cats OR dogs` reads as
  two alternatives, not as that text.

### What a full-text search does not look at

A full-text search spans every node type, so it is kept out of two trees that are not
content. In `/jcr:system`, two things would crowd out the results:

- **Version storage.** Each check-in of a versionable node, such as a `data:Entity`,
  leaves a frozen copy of all its properties under `/jcr:system/jcr:versionStorage`. A
  submission checked in twenty times would answer a search for its own text twenty-one
  times, on paths the client can do nothing with.
- **The node type registry.** Every type, property definition and child definition under
  `/jcr:system/jcr:nodeTypes` is a node with a searchable name. A search for
  `versionable` returns mostly node type definitions.

`/SystemWorkflows` defines how the platform handles its own content. Every user may read
it, and its labels, such as "Create the workflow", would answer searches for content.

Each tree's own node is excluded as well as its descendants. `isdescendantnode` does not
match the node itself, and `/jcr:system` has a `rep:system` primary type that answers a
search for `system`.

The other searches do not exclude these trees. A `query` runs exactly as sent, version
storage and all. A `quick` search is whatever its engines make it. A typed query does
not reach version storage by accident: a frozen node has `nt:frozenNode` as its primary
type, and keeps the original's type in a property, so it never matches the original's
type.

## Quick search

A quick search answers "what do I have that mentions this?" while the user is still
typing. It asks each registered `QuickSearchEngine` that supports one of the requested
node types, and stops once enough results have been collected. An engine looks wherever
the user would expect a match, including in descendants of the content it returns. It
describes each match, so that the client can show why the result is there.

Several engines can search the same node type, and each of them is asked. Two engines
can know different things about one type: one can match a submission's own answers, and
another the text extracted from its attached files. Their results are not compared with
each other, since an engine returns a serialized match, not a path. Two engines that
find the same node both report it.

**No engine is registered yet**, so a `quick` search returns nothing.

### Implementing an engine

```java
@Component(service = QuickSearchEngine.class)
public class SubmissionQuickSearchEngine implements QuickSearchEngine
{
    @Override
    public List<String> getSupportedTypes() { return List.of("sub:Submission"); }

    @Override
    public Results quickSearch(final SearchContext context)
    {
        // getResourceTypes(): the supported types this request asked for
        // getMaxResults(): how many results the caller can use; bound the query with it
        // getResourceResolver(): the requesting user's; read through it alone
        …
    }
}
```

The context holds everything about the search, including whose it is. It belongs to one
request and must not be kept: the resolver in it closes when the request ends.

`Results` is an `Iterator<JsonObject>` with two more methods: `skip()`, which moves past
a result without building its JSON, and `close()`. The servlet calls `skip()` for each
match it only counts, before the offset or past the page. An engine that can skip more
cheaply than `next()` should. `Results.empty()` is for an engine that found nothing.

`SearchUtils` has the rest:

| Helper | For |
| --- | --- |
| `escapeLikeText`, `escapeQueryArgument` | Writing the user's input into a query. A `like` pattern written into a statement needs both, in that order |
| `getMatch`, `getMatchFromArray` | Finding which of a property's values matched |
| `addMatchMetadata` | Describing the match, under the `MATCH_KEY` (`data:queryMatch`) property |

**Prefer a bind variable to either escape.** An engine writing `where n.[title] like
$text` and calling `Query.bindValue("text", …)` never puts the user's input into the
statement, so there is no string literal to escape it for. The full-text search above
works this way. A bound `like` pattern still needs `escapeLikeText`, since the pattern
grammar applies to whatever the variable holds.

```json
{
  "…": "the serialized node",
  "data:queryMatch": {
    "label": "Project title",
    "@path": "/Submissions/s1",
    "before": "... type 2 ",
    "text": "diabetes",
    "after": " in adul..."
  }
}
```

`before` and `after` hold up to eight characters of context each, with `...` where more
was cut off. `text` is the matched text as stored, which can differ in case from what
the user typed.

## Unindexed queries

A statement with no index to use makes the repository walk the content. When that
happens, `/search.json` logs a warning naming the statement and its plan. **The query
still runs**: IAP's `oak:index` coverage is thin, and refusing such queries would reject
legitimate ones.

It is also recorded as a problem under `/LoggedErrors`. The recorded phrase is fixed in
code, and the statement is attached as a detail, so the records count missing indexes,
not distinct searches; see [error tracking](error-tracking.md).

The check plans the query a second time, which is cheap next to a traversal. The plan is
asked for with `explain` in front of the statement, so the client's own statement is
parsed first, and a syntax error quotes what the client sent.

The logged statement is put on one line and cut to `MAX_LOGGED_STATEMENT` (500)
characters. In `query` mode it is the statement the client sent, and a line break in it
would let the client write log entries of its own. A full-text statement holds no search
term, since the term is bound, and bound values are never logged. A failure to get a
plan fails nothing: the request is served as the repository would serve it.

## Errors

| Status | When |
| --- | --- |
| `400` | The statement cannot be parsed, or the repository cannot make sense of it, as with a malformed full-text expression. The body quotes the repository's message |
| `500` | The query could not be executed |

The body is `{"status": "error", "error": "…"}`. Both are for a request that failed
before any result was written. A failure part-way through is too late for a status, and
is reported in the summary instead; see [Shaping the response](#shaping-the-response).

## Future work

- **Nothing implements `QuickSearchEngine` yet**, so `quick` has no engine to ask.
- **A `lucene` mode**, running a native Lucene query, is left out: it needs Lucene index
  definitions IAP does not have yet.
- **The cost of a query is unbounded.** How many results a request *reads* is capped,
  but a statement with no index still makes the repository walk the content to find the
  first ten thousand matches. Oak's own `queryLimitReads`, `queryLimitInMemory` and
  `failTraversal` are not configured anywhere in `packaging/`, and would bound it at the
  source.
- **Restricting `query` to administrators** would close off arbitrary JCR-SQL2. The
  candidate list of a reference field, in `frontend-commons`
  (`fields/referenceCandidates.ts`), sends a `query`.
- **A `storedQuery` mode** would run only one of a set of predefined queries. It would
  allow JCR queries without the risks of `query`.
- **Two more trees that are not content are still in reach of a `fulltext` search.**
  `/oak:index` answers a search for the property names its definitions list, and
  `/rep:security/rep:authorizables` puts every user and group account in reach of a
  search for a name. The user store needs a decision: either it is content and a search
  should find people, or it is not and it belongs with the other exclusions.
- **`resourceSelectors` applies only when whole nodes are serialized.** A field on
  `SearchContext` would pass it to the engines, so that `quick` results respect it too.
- **Making the default `fulltext` search literal** for whitespace and `OR`. That means
  quoting each term, which changes what a search of several words matches.
