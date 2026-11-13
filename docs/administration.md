# Administration

**Module:** `modules/admin-console` (start-order 25) · **Extension point:**
`iap/adminDashboard/entry` · **Chrome:** `@iap/admin-console/AdminScreen`

The administration console at `/admin` holds everything that configures the platform
rather than uses it. The app bar's "Administration" button leads there.

The console provides only the way in and the frame of each page. The tools come from
the modules that own the data behind them, so a module's administration ships with the
feature itself.

A tool is two [UI extensions](ui-extensions.md). The **view** is the tool's page,
registered on `iap/coreUI/view` and routed at `/admin/<tool>`. The **widget** is a
summary of the tool's area on the console's landing page, registered on
`iap/adminDashboard/entry`, and it leads to the view.

Both are nodes. The console has no code for any tool, and renders whatever is
registered. This page assumes [ui-extensions.md](ui-extensions.md).

## The `iap/adminDashboard/entry` point

The console's landing page is a dashboard of the extensions on this point. It has the
same layout and frame as the
[dashboard](ui-extensions.md#dashboard-widgets--iapdashboardwidget-node-dashboardwidget)
on the application's landing page, and all the
[dashboard frame properties](ui-extensions.md#dashboard-widgets--iapdashboardwidget-node-dashboardwidget)
apply. A tool's widget uses these:

| Property | Meaning |
|---|---|
| `ext:name`, `ext:subtitle` | The widget's title, and the line under it |
| `ext:renderURL` | The summary component |
| `ext:targetURL` | The tool's page, by convention `/admin/<tool>`. It is used only with `ext:actionLabel` |
| `ext:actionLabel` | The button in the header that leads to `ext:targetURL`. It is one verb for what the tool is for, such as `"Manage"`, `"Triage"` or `"Browse"`. The title beside it names the area, and the button's accessible name includes the title |
| `ext:widgetGroup` | The widget group it is listed under; see [below](#widget-groups) |
| `defaultOrder` | Its position in its group |

### Widget groups

The landing page arranges the tools into collapsible
[widget groups](ui-extensions.md#widget-groups). The console defines two, in
`admin-console` under `/Extensions/Admin/AdminDashboard/Groups/`:

| Group | Order | For |
|---|---|---|
| `Configuration` | 10 | Setting up how the platform behaves: the schemas, categories, workflows and models it runs on |
| `Operations` | 20 | Keeping an instance healthy day to day: faults to triage, archived items, caught mail |

A tool names its group with `"ext:widgetGroup": "Configuration"`. Choose by what the
administrator is doing when they reach for the tool, not by which module owns it. A
tool that fits neither leaves the property out, and is listed under "Other". A module
or a deployment can add a group of its own, as another group node on
`iap/adminDashboard/entry`. By convention it is under `/Extensions/Admin/`, so that
non-administrators cannot read it.

### Summary figures

A widget should show a live summary of the tool's area, not only a link into it. The
archive tool, for one, counts what was archived in the last 24 hours, the last 7 days,
and in total.

Figures go through
[`WidgetStatList`](../modules/frontend-commons/src/main/frontend/src/components/WidgetStatList.tsx),
so every tool's summary reads the same way. Each figure shows its value, then what it is
a figure for. The values share one column, so the labels line up however many digits the
values have. The widget names the resource to summarize and what to call it, as in
`<WidgetStatList url="/Archive" name="archive" />`. The list fetches
`<url>.adminSummary.json` itself, and shows a placeholder while it waits. A refusal (403
or 404) says the summary is not available to this reader; any other failure says it could
not be loaded. The widget holds nothing about the tool's figures.

The summary answers with a dictionary of figures, in the order they are displayed:

```json
{
  "needingAttention": { "label": "Needing attention", "value": 3, "important": "nonzero" },
  "total": { "label": "Recorded in total", "value": 41 }
}
```

A numeric `value` is a count. A boolean `value` is a state, shown as On or Off. A `null`
value is a count that could not be taken, shown as "?". `approximate` marks a count that
stopped at a bound, shown with a "+". `important` says which count is a problem, and
colours it: `"nonzero"` any count above zero, `"zero"` a count of zero. `path` names the
repository path the figure is about. The label links there when the widget passes a
`hrefFor` that maps a path to a console route.

Most tools need no servlet of their own: a `data:EntityHomepage` already answers
`adminSummary.json` with one figure per homepage of its kind the reader can see, counted
on their own session. A homepage that cannot be counted keeps its figure with a `null`
value, and the summary fails only when none can be. A tool whose summary is more than a
count of its entities registers its own servlet for the same selector on its own
resource type, which Sling prefers over the generic one. It extends
[`AdminSummaryServlet`](../modules/java-utils/src/main/java/io/uhndata/iap/utils/summary/AdminSummaryServlet.java),
and builds each figure with its `count`, `unknown` and `state` methods.
`AdminSummaryServlet` writes the answer. If the summary cannot be read, it answers `500`
with an error body.

## Contributing a tool

The view is ordinary application code. Only its `/admin` path and the
[`AdminScreen`](#the-chrome) frame make it administrative. The two extensions are the
whole of the wiring, and both nodes are under `/Extensions/Admin/`:

```json
// Extensions/Admin/AdminDashboard/Categories.json, the widget
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/adminDashboard/entry",
  "ext:name": "Submission categories",
  "ext:subtitle": "Organize the categories submitters choose from, and bind them to schemas",
  "ext:targetURL": "/admin/categories",
  "ext:actionLabel": "Manage",
  "ext:renderURL": "asset:iap-categories.CategoriesWidget.js",
  "ext:widgetGroup": "Configuration",
  "defaultOrder": 10
}

// Extensions/Admin/Views/Categories.json, the view
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/coreUI/view",
  "ext:name": "Submission categories",
  "ext:targetURL": "/admin/categories",
  "ext:renderURL": "asset:iap-categories.CategoryManager.js?lazy"
}
```

**No repository node is needed behind the URL.** `AdminViewResourceProvider` is
mounted on `/admin` in overlay mode. A path below `/admin` that nothing is stored at
resolves to a synthetic `app/Homepage` resource. A refresh or a deep link therefore
resolves, however deep the path. The provider declines a path whose last segment has a
dot. Sling then resolves the path before the dot, and reads the rest as selectors and
an extension. A user who cannot read `/admin` gets no synthetic resource, and so a 404
rather than the shell. The provider lists no children, so nothing stored below
`/admin` can be listed.

### The chrome

Wrap the page in `AdminScreen`:

```tsx
<AdminScreen title="Submission categories" action={<Button>New category</Button>}>
  {/* tool content */}
</AdminScreen>
```

Every prop is optional. Without a `title`, the page is headed "Administration". With
one, the heading also has a small "Admin" chip. The `action` is on the heading's row,
and the `description` of what the tool is for is under the heading.
A page about something that belongs to something else names the owner in
`titlePrefix`, before the title in regular weight. A schema version's page, for one,
is headed with its schema's title, then "Version 1.0". The `status` follows the title,
and shows the state of the page's subject, such as its tags.
The content is on a `Panel`, a paper surface on the page's background. Content with
surfaces of its own passes `disablePanel` to sit directly on the page. The console's
landing page does, since its widgets are panels themselves.

The way back is the shell's
[breadcrumb trail](ui-extensions.md#breadcrumb-trail) on `pageTop`. When a page's path
does not name its owner, the page adds the owner as a crumb itself, with
`usePageCrumbs`.

## Access control

The administrative extension nodes are under `/Extensions/Admin/<PointName>/`: the
widgets, their views, and the console's own app bar entry. The module's repoinit denies
that folder and `/admin` to `everyone`:

```
create path (sling:Folder) /Extensions/Admin
create path (app:Homepage) /admin
set ACL for everyone
    deny jcr:all on /Extensions/Admin,/admin
end
```

Extensions are read with the user's own session, wherever they are. A user who cannot
read these is not served the Administration button, the `/admin` view or any tool. A
deep link to `/admin`, or to a page below it, is a 404 for them.

That protects only the user interface. **A tool's data needs ACLs of its own**, as any
repository content does.

## The tools

| Tool | Path | Module | Group | `defaultOrder` |
|---|---|---|---|---|
| Schemas | `/admin/schemas` | `schemas/impl` | Configuration | 5 |
| Submission categories | `/admin/categories` | `categories` | Configuration | 10 |
| Workflows | `/admin/workflows` | `workflows` | Configuration | 20 |
| LLM configuration | `/admin/llm` | `llm` | Configuration | 30 |
| Recorded errors | `/admin/errors` | `error-tracking` | Operations | 30 |
| Archive | `/admin/archive` | `deletion` | Operations | 30 |
| Caught mail | `/admin/mail` | `email-catcher` | Operations | 40 |

Caught mail is a development tool. It ships everywhere and is off until somebody
switches it on. See [catching mail](notifications.md#catching-mail-in-development).

## Future work

A console widget is a React component, which is more than some tools need. Two lighter
kinds would need none:

- **A link and a label**, for a tool whose widget is only a way in.
- **A summary resource named on the widget node**, whose
  [figures](#summary-figures) the dashboard shows by itself. Today a widget is a
  component even when all it renders is a `WidgetStatList`.

