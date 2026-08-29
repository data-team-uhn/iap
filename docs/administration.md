# Administration

**Module:** `modules/admin-console` (start-order 25) · **Extension point:**
`iap/adminDashboard/entry` · **Chrome:** `@iap/admin-console/AdminScreen`

Everything that configures the platform rather than uses it lives behind one door:
the administration console at `/admin`. It is reached through a worded
"Administration" button in the app bar — words rather than an icon, because a gear
reads as personal settings.

The console owns only the door and the chrome. Tools are contributed by the modules
that own the data behind them, so a module's administration ships with the feature
itself.

That contribution happens entirely through [UI extensions](ui-extensions.md), and a
tool is two of them. The **view** is the substance: a full-page interface registered
on `iap/coreUI/view` and routed at `/admin/<tool>`, where the administration actually
happens. The **widget** is the way in: a summary registered on the console's own
point, `iap/adminDashboard/entry`, small enough to sit beside every other tool's,
saying whether this area needs attention and leading to the view.

Both are just nodes. The console holds no code for any particular tool — it renders
whatever it finds registered, without knowing what any of it is. Reading
[ui-extensions.md](ui-extensions.md) first will make the rest of this one much
shorter.

## The `iap/adminDashboard/entry` point

The console's landing page is a dashboard of the extensions registered on this point,
using the same layout and frame as the
[homepage dashboard](ui-extensions.md#the-dashboard) — so all the
[dashboard frame properties](ui-extensions.md#the-dashboard) apply. Beyond those, and
the properties every point understands, it reads:

| Property | Meaning |
|---|---|
| `ext:name`, `ext:subtitle` | Title the widget frame |
| `ext:renderURL` | The summary component |
| `ext:targetURL` | The tool's page, conventionally `/admin/<tool>` |
| `ext:actionLabel` | Header action next to the title. Prefer a label naming the destination (`"Manage categories"`) over a generic "Configure" — it is what tells users a whole tool sits behind the summary |
| `defaultOrder` | Position on the landing page |

A widget should show a live summary of the tool's area — the archive tool counts what
was archived in the last 24 hours, the last 7 days, and in total — rather than only a
link into it.

## Contributing a tool

The view is ordinary application code — it is only the `/admin` path and the
[`AdminScreen`](#the-chrome) wrapper that make it administrative. Registering the two
extensions is the whole of the wiring; both nodes live under `/Extensions/Admin/`:

```json
// Extensions/Admin/AdminDashboard/Categories.json — the console widget
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/adminDashboard/entry",
  "ext:name": "Submission categories",
  "ext:subtitle": "Organize the categories submitters choose from, and bind them to schemas",
  "ext:targetURL": "/admin/categories",
  "ext:actionLabel": "Manage categories",
  "ext:renderURL": "asset:iap-categories.CategoriesWidget.js",
  "defaultOrder": 10
}

// Extensions/Admin/Views/Categories.json — routing for the tool's page
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/coreUI/view",
  "ext:name": "Submission categories",
  "ext:targetURL": "/admin/categories",
  "ext:renderURL": "asset:iap-categories.CategoryManager.js?lazy"
}
```

**No repository node is needed behind the URL.** `AdminViewResourceProvider` overlays
`/admin` and answers any path below it that isn't stored with a synthetic
`app/Homepage` resource, so a refresh or deep link resolves and pages may nest as
deeply as they like. Two conditions in that provider are worth knowing: it declines
paths whose last segment contains a dot (a selector or extension, left to normal
resolution), and it synthesizes nothing at all if `/admin` itself does not resolve —
which is what makes the ACL below an honest 404 rather than a shell that renders for
everyone.

### The chrome

Wrap the page in `AdminScreen`:

```tsx
<AdminScreen title="Submission categories" action={<Button>New category</Button>}>
  {/* tool content */}
</AdminScreen>
```

Both props are optional; with no `title` it heads the page "Administration". The
chrome marks the administrative area as a danger zone — a panel with a 2px
`admin.main` border over a `background.admin` tint, hugging the tool's content, so the
frame visibly belongs to what it encloses and scrolls with it. Title and main action
sit *inside* the panel, since an action on administrative data belongs in the zone.

The chrome adds no wayfinding of its own; that is the shell's
[breadcrumb trail](ui-extensions.md#the-breadcrumb-trail) on `pageTop`. On a nested
page the panel pulls itself up over the main region's top gutter
(`--iap-content-gutter`) plus its own border, so the border lands on the trail's
divider and attaches the trail to the zone visually. Top-level pages keep a normal
margin.

## Access control

Admin-only extension nodes — widgets, their `Views` routing nodes, and the console's
own app-bar entry — live under `/Extensions/Admin/<PointName>/`, denied to `everyone`
by the module's repoinit:

```
create path (sling:Folder) /Extensions/Admin
create path (app:Homepage) /admin
set ACL for everyone
    deny jcr:all on /Extensions/Admin,/admin
end
```

Extensions are queried with the requesting user's own session wherever they live, so
for non-administrators the Administration button, the `/admin` route and every tool
simply do not exist. `/admin` is denied too, so deep-linking it as a non-admin is a
plain 404.

That is the UI half only. **A tool's data must be protected by its own ACLs**, like
any repository content.

## The tools

| Tool | Path | Module | `defaultOrder` |
|---|---|---|---|
| Submission categories | `/admin/categories` | `categories` | 10 |
| Workflows | `/admin/workflows` | `workflows` | 20 |
| Recorded errors | `/admin/errors` | `error-tracking` | 30 |
| Archive | `/admin/archive` | `deletion` | 30 |

## Future work

A console widget is currently a React component, which is more than some tools need.
Two lighter kinds are worth adding:

- **A link and a label**, with no component — enough for a tool whose dashboard
  presence is only a way in.
- **A declared summary**, one line of the state a tool is responsible for: "45
  registered users", "5 installed vocabularies, 2 with new versions available", "1
  planned downtime active". That is what makes the console a status page rather than a
  menu, and it is the contract each tool would implement.

