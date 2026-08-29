# How a URL becomes a page

IAP serves a single-page application, but the pages it is served from are real
repository resources. This document covers the chain a request follows — path to
resource, resource to resource type, resource type to script — and what a module has
to ship to make its own content addressable.

None of the mechanism is IAP's own: resolution, script naming, selectors and HTL are
all stock Apache Sling, and nothing here overrides them. When the question is *how
Sling behaves*, the answer is upstream:

- [Servlets and scripts](https://sling.apache.org/documentation/the-sling-engine/servlets.html)
  — resolution, script naming, the default servlet
- [Sling scripting](https://sling.apache.org/documentation/bundles/scripting.html)
  — how a script is selected from selectors, extension and method
- [HTL scripting engine](https://sling.apache.org/documentation/bundles/scripting/scripting-htl.html)
  and the [HTL specification](https://github.com/adobe/htl-spec/blob/master/SPECIFICATION.md)
  — `data-sly-*` and the Use API

What follows is the conventions on top: which scripts IAP declares, what a page is
expected to look like, and what a new type has to provide.

Most of the interface is React, and none of it lives here. These scripts exist to
answer the first request: emit a document that loads the application, and get out of
the way. **Nothing about a page should be bespoke.** If a script is growing logic, that
logic belongs in a Sling Model or a servlet.

## Resolution

Sling turns a request into a resource, then into a script:

1. **Path → resource.** The path is looked up in the repository. Nothing there means
   nothing to serve, which is why a client-side route with no resource behind it 404s
   on a refresh — see [the note on routed views](ui-extensions.md#routed-views).
2. **Resource → resource type.** Every content node autocreates a `sling:resourceType`
   from its node type — `tag/Homepage`, `sub/Submission`, `app/Homepage`.
3. **Resource type → script.** The type names a folder under `/libs`, and the script
   is chosen there by the request's selectors, extension and method.

### The supertype chain

A node carries the single resource type its node type autocreates. Inheritance is
declared separately, by a `ROOT.json` in the type's `/libs` folder:

```json
// SLING-INF/content/libs/sub/Submission/ROOT.json
{
  "jcr:primaryType": "sling:Folder",
  "sling:resourceSuperType": "data/Entity"
}
```

**Every new node type needs one.** Without it the chain stops at the type itself:
scripts, Sling Models registered for a supertype, and the JSON serialization all fail
to resolve, silently, because nothing links `sub/Submission` to `data/Entity` to
`data/Content`. This is the single most common omission when adding a type.

## Script naming

Scripts live in the resource type's folder and are selected by name:

| File | Serves |
|---|---|
| `html.GET.html` | `GET <path>.html` |
| `json.GET.html` | `GET <path>.json` |
| `null.GET.html` | `GET <path>` — no extension |
| `<selector>.html` | `<path>.<selector>…`, included or requested directly |

`data/Content` declares the base set — `html.GET.html` is left to each page type, while
`json.GET.html`, `null.GET.html`, `header.html` and `footer.html` are inherited by
every content type through the supertype chain. A type only overrides what differs.

### The extensionless case

Visitors do not type `.html`. An authentication redirect to `/login?resource=…`, a
deep link, a bookmark — all arrive without an extension, so `null.GET.html` is the
script that actually runs for a person:

```html
<sly data-sly-use.contentType="io.uhndata.iap.scripting.ContentTypeSetter">${contentType.html}</sly>
<sly data-sly-resource="${'.html' @selectors='forceInclude'}"/>
```

It sets the content type — an extensionless request has nothing to infer one from —
and re-dispatches to the `.html` script, so the two URLs cannot drift apart.

`forceInclude` is a marker selector with no meaning of its own — it is not something
Sling defines. Including a resource into itself trips Sling's recursion detection
(`RecursionTooDeepException`), and changing the selectors is the standard way around
it; any unused selector name would do.

The same shape appears wherever a type's natural rendering is not HTML:
`ext/Extension/null.GET.html` re-dispatches to `.json` with `deep`, so an extension
node fetched without an extension returns its full JSON.

## The shape of a page

Every page is the same four lines: content type, header, a mount point, the entry
script, footer.

```html
<sly data-sly-use.contentType="io.uhndata.iap.scripting.ContentTypeSetter">${contentType.html}</sly>
<sly data-sly-resource="${@selectors='header'}"/>
    <div id="main-container"></div>
    <sly data-sly-use.assets="/libs/iap/resources/assets">
      <script type="module" src="/libs/iap/resources/${assets['iap-homepage.Homepage.js']}"></script>
    </sly>
<sly data-sly-resource="${@selectors='footer'}"/>
```

`header.html` and `footer.html` come from `data/Content` and are pulled in by selector,
so a page type inherits the document shell without repeating it: doctype, metadata,
favicons, fonts, `sling.js`, and the `vendor.js`/`runtime.js` module scripts.

Asset filenames are content-hashed by the build, so they are never written literally.
Webpack emits `assets.json`, `aggregated-frontend` loads it into
`/libs/iap/resources/`, and the script reads it through the Use API —
`assets['iap-homepage.Homepage.js']` yields the hashed filename.

A page type that adds nothing to another can simply include it. A submission's page is
the homepage shell, whose router displays the view registered for `/Submissions/*`:

```html
<sly data-sly-include="/libs/app/Homepage/html.GET.html"/>
```

## Responses that are not pages

A `.html` request gets a page. Everything else is a different script, or not a script
at all.

### JSON

`data/Content` declares `json.GET.html`, inherited by every content type, so any
content resource answers `.json` with no per-type code:

```html
<sly data-sly-use.contentType="io.uhndata.iap.scripting.ContentTypeSetter">${contentType.json}</sly>
<sly data-sly-use.json="jakarta.json.JsonObject">${json.toString @ context='unsafe'}</sly>
```

The whole of it is `resource.adaptTo(JsonObject.class)`. What the JSON *contains* is
decided by a chain of processors selected through the request's selectors —
`.deep.simple.-dereference.json` and so on — described in
[JSON serialization](json-serialization.md). A module shapes its own content's output by
registering a processor, never by writing a script.

`context='unsafe'` is required here: HTL escapes for HTML by default, and JSON that has
been HTML-escaped is not JSON.

### Other content types

`ContentTypeSetter` also offers `${contentType.javascript}` and `${contentType.csv}`.
A type wanting `.csv` ships a `csv.GET.html`, and the same
`<selector>.html` naming gives a variant rendering — `resolved.html` under
`ext/Point`, requested as `.resolved` or included by another script.

### Custom servlets

A script renders a resource. When the answer is *computed* rather than stored — a
listing, a summary, a preflight, an action — write a servlet instead. Bind it to a
resource type, and Sling resolves it exactly as it resolves a script:

```java
@Component(service = { Servlet.class })
@SlingServletResourceTypes(resourceTypes = { TagsHomepage.RESOURCE_TYPE }, methods = { "GET" },
    selectors = { "search" }, extensions = { "json" })
public class TagListServlet extends SlingJakartaSafeMethodsServlet
{
    @Override
    protected void doGet(final SlingJakartaHttpServletRequest request,
        final SlingJakartaHttpServletResponse response) throws IOException
    { … }
}
```

That serves `GET /Tags.search.json` and nothing else. Points worth copying:

- **Bind by resource type**, not by path. `@SlingServletResourceTypes` with a
  `RESOURCE_TYPE` constant from the model keeps the binding and the type together, and
  the servlet then works wherever that type appears.
- **Narrow the binding.** Naming the method, selectors and extension means the servlet
  cannot accidentally take over a request meant for something else.
- **Resource type is also access control.** A servlet bound to `del/ArchiveEntry`
  cannot be reached by a user who cannot see the archive: resolution fails first, and
  they get a plain 404 with no permission check to keep in step.
- **Extend the safe-methods base** (`SlingJakartaSafeMethodsServlet`) for read-only
  servlets, so `POST` and friends are rejected for you.
- `@Reference` fields must be `transient` — a servlet is serializable, an OSGi service
  reference is not.

Servlets are the normal way to add an endpoint here; the deletion, archive, pagination,
autodoc, tag-search and metrics endpoints are all servlets bound this way.

## Helper objects

Four `Use` objects in `io.uhndata.iap.scripting` cover what a script legitimately needs
to do:

| Class | For |
|---|---|
| `ContentTypeSetter` | `${contentType.html}`, `.json`, `.javascript`, `.csv` — set the response content type |
| `StatusCodeSetter` | `${statusCode.ok}`, `.created`, `.notFound`, … — set the response status |
| `ConfigMetadata` | Deployment configuration under `/libs/iap/conf`, used by `header.html` for the page title and `<meta>` tags |
| `ErrorMetadata` | The status code, reason phrase and whether JSON was requested, for the error handlers |

`ConfigMetadata` is why branding is a deployment concern rather than a code one: the
header renders whatever `app/Configuration` nodes the deployment supplies, with no
script change.

More can be written when a script genuinely needs something these do not cover.
A `Use` object is either a Sling Model adapted from the resource, a class implementing
`org.apache.sling.scripting.api.Use`, or a JavaScript file beside the script —
`ext/PointFinder/extensionQuery.js` is an example of the last. The
[HTL Use API](https://sling.apache.org/documentation/bundles/scripting/scripting-htl.html)
documents all three; prefer a Sling Model, since it is testable and reusable outside
scripting.

Before writing one, check that the logic belongs in a script at all. Computing an
answer is a servlet's job, and shaping stored content is a
[serialization processor's](json-serialization.md).

## Error pages

| Script | Handles |
|---|---|
| `/libs/sling/servlet/errorhandler/404.html` | Not found — sets the status itself, then loads the 404 app |
| `/apps/sling/servlet/errorhandler/default.html` | Everything else |

The default handler branches on what the caller asked for: `ErrorMetadata.json` is true
for a JSON request, and the response is then a JSON error body rather than a page. That
is what keeps an API client from receiving HTML when something fails.

Note the search paths. Sling looks in `/apps` before `/libs`, so a script in `/apps`
overrides the platform's equivalent in `/libs` — which is the hook a deployment uses to
replace a page without touching the module that shipped it.

Both handlers borrow `header.html` and `footer.html` from `data/Content` by path rather
than by selector, since an error handler has no resource of its own to resolve a
selector against.

## Making a new type addressable

1. **`ROOT.json`** naming the supertype. Types under `data/Content` inherit
   `null.GET.html`, `json.GET.html`, `header.html` and `footer.html` and need nothing
   further to be serializable.
2. **`html.GET.html`**, if the type is a page — usually a one-line include of
   `/libs/app/Homepage/html.GET.html`.
3. **A view extension** routing the URL client-side, per
   [ui-extensions](ui-extensions.md).
4. **A resource behind every URL the view claims.** A node, or a `ResourceProvider`
   for paths that vary.

A type outside the `data/Content` hierarchy inherits none of this and must declare the
whole set itself — which is why the archive types carry their own one-line
`header.html` and `footer.html` borrowing the shared chrome by path.

Readability decides who may open a page: the resource's own access control is the whole
of the check, and there is nothing script-side to keep in step with it.

## From here, the frontend

Everything above stops at the first response. Once the shell has loaded, the URL means
whatever the client-side router says it means, and the page is assembled from React
components registered on [extension points](ui-extensions.md).

Writing that half — components, extension registration, the asset build, fetching from
these endpoints, and the conventions the UI follows — is covered in *writing frontend
code* (to be written).
