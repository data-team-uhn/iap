# UI extension points

**Module:** `modules/ui-extension` · **Package:** `@iap/ui-extension`
(`extensionManager`, `extensionLoader`, `ExtensionList`, `ExtensionPoint`,
`personas`) · **Tools:** `tools/dev/extension-manager/`

The interface is made of **extensions** plugged into **extension points**. A module
adds to the interface by shipping a React component and a JCR node that registers it
on a point. The page that shows the point renders whatever is registered there,
without knowing which module contributed it.

## The mechanism

- **Point:** an `ext:Point` node under `/apps/iap/ExtensionPoints/<Name>`. Its
  `ext:pointId` is the identifier extensions name.
- **Extension:** an `ext:Extension` node, usually under `/Extensions/<PointName>/`,
  with the point's `ext:pointId`, an `ext:name`, and usually an `ext:renderURL`.
- **Showing a point:** `await loadExtensions("<NodeName>")` fetches a point's enabled
  extensions and loads the components their `asset:` properties name. `ExtensionList`
  renders them. An extension whose assets fail to load is logged and left out.
- The component gets the extension node as its `extension` prop, to read any of its
  properties, such as `ext:data`.

Properties of every extension:

| Property | Type | Meaning |
|---|---|---|
| `ext:name` | String | The extension's name, which a point may show, such as a widget's title |
| `ext:renderURL` | String | `asset:<entry>.js`. With `?lazy` appended, the asset loads only when first rendered |
| `defaultOrder` | Long | The position within the point, lowest first, 0 by default |
| `ext:defaultDisabled` | Boolean | `true` leaves the extension out, without deleting it |
| `ext:personas` | String[] | The personas the extension is for; absent means all. The dashboards show an extension only to its personas, and other points ignore the property |

## Catalogue

### Page shell

`PageLayout` (homepage module) renders a frame that stays on the screen, around a
middle that scrolls. Each region is an extension point:

```
┌──────────── frameTop ─────────────┐   Frame regions: always visible, pinned.
│ f ┌───────── pageTop ─────────┐ f │
│ r │                           │ r │   Page regions: between the rails, scrolling
│ a │      main content         │ a │   with the content.
│ m │   (the routed view)       │ m │
│ e │                           │ e │
│ S │                           │ E │   Naming rule: physical Top/Bottom vertically;
│ t └─────── pageBottom ────────┘ n │   logical Start/End horizontally, which mirrors
│ a                               d │   under a right-to-left locale.
└──────────── frameBottom ──────────┘
```

| Point id | Region | Behaviour |
|---|---|---|
| `iap/coreUI/frameTop` | Full-width bar, top | Pinned. On a screen shorter than its configured height, a pull tab at the edge opens it in a drawer |
| `iap/coreUI/frameBottom` | Full-width bar, bottom | Same |
| `iap/coreUI/frameStart` | Side rail, start (left in LTR) | Fixed width, its own scrollbar, between the bars. On a screen narrower than its configured width, a pull tab at the edge opens it in a drawer |
| `iap/coreUI/frameEnd` | Side rail, end | Same |
| `iap/coreUI/pageTop` | Above the main content, between the rails | Scrolls with the content |
| `iap/coreUI/pageBottom` | Below the main content, between the rails | Scrolls with the content |

An empty region renders nothing: no rail, no bar and no pull tab. Thresholds and widths
are per region, in the theme's `iapShell` section
(`modules/frontend-commons/src/main/frontend/src/appTheme.ts`). The defaults are 200px
rails, collapsing below `md` (start) and `xl` (end), and bars collapsing below a height
of 500px.

### Routed views — `iap/coreUI/view` (node `Views`)

`ext:targetURL` is the path the view shows, and the router reads it as a pattern. It
can have a parameter, such as `:id`, or end in a splat, as `/Submissions/*` does. The
breadcrumb trail matches ancestors against the same patterns, except those with a
splat. Use `?lazy` so that a view loads only when it is first shown.

**A pattern does not make its URLs resolvable on the server.** Every view URL must be
backed by a resource. Navigating inside the application works regardless, but a
refresh or a deep link asks Sling for the whole URL, and an unresolvable path is a 404
before the application loads. A submission's own path resolves, since its node serves
the shell. A path below it, such as `<submission>/reviews/financial`, does not,
whatever the pattern.

Two ways to back a URL:

- **A node** in the owning module's repoinit, such as the admin console's
  `create path (app:Homepage) /admin`. Its access control is the page's, so denying it
  to `everyone` makes a deep link a 404. A node backs one fixed path, not varying ones.
- **A `ResourceProvider`**, synthesizing them. `AdminViewResourceProvider`
  (`admin-console`) covers the whole `/admin` subtree in `overlay` mode: anything
  below the console not in the repository is answered with a synthetic resource
  carrying the console's resource type, so the shell renders and the router decides
  what the path means. Real content still wins. The synthetic resource has no ACL of
  its own and is readable when `/admin` is, so an admin tool needs no node for
  `/admin/<tool>`.

A second provider addresses an entity by its id, wherever it is filed.
`PrefixTreeResourceProvider` (`java-utils`) resolves `<mount>/<name>` to the node's
place in the prefix tree. A configuration turns it on for one subtree:

```json
"io.uhndata.iap.utils.internal.PrefixTreeResourceProvider~submissions": {
  "provider.root": "/Submissions/by-id",
  "provider.mode": "overlay"
}
```

It is active for `/Submissions/by-id` and `/Archive/by-id`. It is mounted *beside*
each tree, not over it. Mounted at `/Submissions` in overlay mode, it would be handed
every write below it and refuse it, and no submission could be created. It resolves a
single segment, with no slash or dot and at least `PrefixTree.MINIMUM_NAME_LENGTH` (6)
characters. It makes ids addressable, not deeper paths.

A module with deeper or varying URLs, such as `/Submissions/<id>/*`, needs a provider
of its own over its own root. `AdminViewResourceProvider` is one.

### App bar — `iap/appBar/entry` (node `AppBarEntry`)

The app bar is a `frameTop` extension, and its entries are extensions on a point of its
own. `ext:appBarSection` places an entry in the `start`, `middle` or `end` section; an
entry without one is at the start. The `middle` section is centered between the other
two. `defaultOrder` orders the entries within a section.

The entries are the logo (start), then at the end the Administration button for
administrators, the dark mode toggle, the notifications bell, and the user menu with
the persona choice. No entry uses `middle`.

A banner, such as a maintenance notice, is an extension on `frameTop` itself, with a
`defaultOrder` below the app bar's 20 to sit above it. The `iap-commons.NoticeBanner`
asset renders such a data-only extension: the markdown message in `ext:data`, and an
optional `ext:severity`.

With `ext:visibleBeforeLogin: true`, it shows on the login page too. The login page
shows the `frameTop` extensions that carry it, and nothing else of the frame.

### Footer links — `iap/footer/link` (node `FooterLink`)

The footer is a `pageBottom` extension. It shows the institution's logo, the links,
the platform's name and version, and the DATA credit. Its links are **data-only
extensions**, with the label in `ext:name` and the target in `ext:targetURL`. A URL
with a scheme, such as `https:`, opens in a new tab, and any other target is an in-app
path. Hide a link whose page is not ready with `ext:defaultDisabled: true`.

### Sign-in methods — `iap/login/signInMethod` (node `SignInMethod`)

The first enabled method is shown in place. Each further method is a link that opens
it, labelled by its `ext:collapsedLabel`, or else its `ext:name`.

The login module registers the local credentials form as the default
(`/Extensions/SignInMethod/CredentialsForm`, order 100). If no method is enabled, or
the point cannot be loaded, the page shows the credentials form anyway. There is always
a way to sign in.

An identity provider needs **no frontend code**. The `iap-login.RedirectSignIn` asset
renders a data-only extension as a button leading to its `ext:targetURL`, the endpoint
that starts the sign-in. The page to return to goes along in the `resource` query
parameter, which the endpoint may ignore. `ext:actionLabel` sets the button's text,
"Continue to sign-in" by default, and `ext:hint` adds a line under it. Give the
extension a lower `defaultOrder` than the credentials form to make it the first method.
Where local accounts should not be offered, disable the credentials form with
`ext:defaultDisabled: true`.

### Dashboard widgets — `iap/dashboard/widget` (node `DashboardWidget`)

Tiled on the landing page's dashboard in a responsive grid, each framed with a title
(`ext:name`) and optional `ext:subtitle`. The frame is set by `ext:widgetWidth`
(`normal`/`wide`/`full`), `ext:widgetEmphasis`, `ext:widgetBorderless`,
`ext:widgetHideHeader`, and `ext:actionLabel`. The last is a button in the header
leading to `ext:targetURL`, an in-app path, and the title then links there too. It
needs both properties. `ext:personas` restricts a widget, and `ext:widgetGroup` files
it under a [widget group](#widget-groups).
Each column gets an equal share of the dashboard's width, regardless of how wide a
widget's content is, so the dashboard always fits on a phone's screen. When a
widget's content is wider than the widget itself, such as a table with many columns,
the content scrolls sideways inside the widget.

The layout is the shared `WidgetDashboard`
(`@iap/frontend-commons/components/WidgetDashboard`), which takes the point as a prop.
The administration console renders the same component for `iap/adminDashboard/entry`,
so widgets are laid out the same way on both; see [Administration](administration.md).

### Widget groups

Any dashboard can arrange its widgets into **widget groups**: runs of widgets under a
muted title across the whole row, with no border or surface, that collapse and expand.
A collapsed group's title shows how many widgets it holds, as in `Configuration (3)`.
Its widgets are unmounted, so they stop fetching whatever they summarize.

A group is a data-only extension on the **same point as the widgets**, marked as a
group:

```json
// Extensions/Admin/AdminDashboard/Groups/Configuration.json
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/adminDashboard/entry",
  "ext:name": "Configuration",
  "ext:isWidgetGroup": true,
  "defaultOrder": 10
}
```

A widget joins it by naming the group's **node name** in `ext:widgetGroup`
(`"ext:widgetGroup": "Configuration"`). `ext:name` is only the displayed title, so a
deployment can relabel a group without touching the widgets that reference it.

| Property | On | Meaning |
|---|---|---|
| `ext:isWidgetGroup` | group | `true` marks the extension as a group, not a widget. A widget with no `ext:renderURL` is still a widget |
| `ext:name` | group | The title displayed |
| `defaultOrder` | group | Order among the groups. Widgets keep their own `defaultOrder` within a group |
| `ext:personas` | group | The personas the group, and so every widget in it, shows to; absent means all |
| `ext:widgetGroup` | widget | The node name of the group it is listed under |

The rules:

- **Within a group, the usual layout applies.** The column count follows the group's
  own widget count, so a group of two sits side by side, as a dashboard of two would.
- **Ungrouped widgets come last, under "Other".** So does a widget naming a group that
  does not exist because it was renamed, disabled or never installed.
- **When no group has any widget to show, the "Other" title is left out** as well. A
  dashboard that defines no groups shows its widgets with no title at all.
- **A group appears only when at least one of its widgets is visible in the current
  persona.** `ext:personas` on a group node limits the group and everything in it: a
  widget shows only if both it and its group belong to the persona. A widget hidden
  with its group is not moved to "Other". The reason a widget is hidden may therefore
  be on its group rather than on the widget itself.
- **Collapsed groups are remembered in the browser's `localStorage`**
  (`iap.widgetDashboard.<point>.collapsed`, where `<point>` is the point's node name). A
  dashboard then looks the same when you return to it from a tool. Without storage,
  every group starts expanded.
- If two group nodes share a node name, the first in `defaultOrder` wins.

Keep group nodes in a `Groups/` folder beside the point's widgets
(`/Extensions/Admin/AdminDashboard/Groups/`). Membership is by `ext:pointId`, so the
location does not matter to the query. The folder keeps a group's node name from
clashing with a widget's, such as a `Workflows` group beside the `Workflows` widget.
Groups are flat: no group nests inside another.

### Breadcrumb trail

A `pageTop` extension (homepage module) linking the current URL's **ancestor** pages.
Each ancestor that a view's `ext:targetURL` matches is a link, labelled with that
view's `ext:name`. A target with a splat matches none. The root is not a crumb, since
the logo links there, and nor is the current page, whose title is its heading. A
top-level page therefore has no crumbs of its own.

A page can add crumbs after those, for what its path alone cannot name:
`usePageCrumbs([{ path, label }])` from `frontend-commons/pageCrumbs` adds them while
the page is shown. A schema version's page, `/admin/schemas/<schema>/<version>`, adds
its schema under the schema's title, since no view names a path below
`/admin/schemas`. Access control comes from the views: a user is not served a view
they cannot read, and gets no crumb for it.

## Personas

A user acts as one persona at a time, chosen in the user menu: submitter, reviewer or
administrator. `ext:personas` names the personas an extension is for, and **naming none
means all**. An extension that names no persona is therefore shown to a persona added
later. Prefer naming none over naming every persona but one, or the extension is hidden
from the next persona added.

A persona is **presentation only, never access control**. The server authorizes every
request, whatever the persona, and hiding a control from a persona must never be the
only thing preventing its action.

`personas.ts` exports `PERSONAS`, `availablePersonas`, `getActivePersona`,
`setActivePersona`, `personaLabel`, `usePersona` and `subscribeToPersona`.
`extensionManager` exports the `visibleInPersona` test, beside `getExtensions` and
`loadExtensions`. `availablePersonas()` decides which personas a user may choose, and
it offers every persona to every user.

## Adding an extension

Three edits, all in the contributing module.

1. **Component:** a `.tsx` under `src/main/frontend/src/`, which gets the extension
   node as its `extension` prop.
2. **Entry point:** a line in the module's `assets.config`, such as
   `['iap-mymodule.MyControl']: './src/MyControl.tsx'`.
3. **Node:** JSON under Sling-Initial-Content, such as
   `SLING-INF/content/Extensions/AppBarEntry/MyControl.json`:

```json
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/appBar/entry",
  "ext:name": "My control",
  "ext:renderURL": "asset:iap-mymodule.MyControl.js",
  "ext:appBarSection": "end"
}
```

Check that the `Extensions/` path is registered in the module's
`<Sling-Initial-Content>` (pom.xml).

**The asset name chain:** the `assets.config` key (`iap-mymodule.MyControl`) →
referenced as `asset:iap-mymodule.MyControl.js` → looked up at runtime in
`/libs/iap/resources/assets.json`, which gives the file name with its content hash. If
a component does not appear, look for its entry in `assets.json` first. A missing
entry means the build did not pick up the `assets.config` change.

**Against a running instance,** rebuild and deploy the frontend, then post the node:

```bash
cd aggregated-frontend && mvn clean install -PautoInstallBundle
./tools/dev/extension-manager/post-extension.sh path/to/MyControl.json /Extensions/AppBarEntry
```

## Defining a new point

1. Ship an `ext:Point` node under `/apps/iap/ExtensionPoints/` with a unique
   `ext:pointId` and a descriptive `ext:pointName`.
2. Show it: `await loadExtensions("<NodeName>")`, and render with `ExtensionList`.
   Define the properties your layout reads on each extension, such as the app bar's
   `ext:appBarSection`, and document them here.
3. For anything direction-sensitive, follow the naming rule: physical top/bottom
   vertically, logical start/end horizontally.

`create_extension_point.py` scaffolds the node and a consumer component.

### Action points

A point can collect the actions available on one kind of thing, such as a schema or a
workflow version. Each of its extensions is a component that renders its own button,
and any dialog it needs. It decides for itself whether it applies, and renders nothing
when it does not. The page never learns which actions exist, so another module can add
one without touching it.

`getActions("<NodeName>")` from `@iap/frontend-commons/actionsManager` loads a point's
components. It reads the point once per page load, however many rows ask. A broken
extension is skipped, and an unreadable point gives no actions. `ActionBar` renders the
components in order, with the props the host passes it. Each host defines those props,
typically the thing acted on, a `reload`, and a `removed` for an action that takes the
thing away.

Where the same actions are shown in more than one place, such as a list of things and
each thing's own page, the host names its place: `getActions("<NodeName>", "<place>")`,
or `ActionBar`'s `place`. An extension says where it belongs with `ext:places`
(String[]). It is shown only in the places it names, and an extension naming none is
shown everywhere. A host that names no place shows only the extensions that name none.
An action that belongs in one place says so in its content, not its code, so a
deployment can move it.

## Tools

From the repository root, in [`tools/dev/extension-manager/`](../tools/dev/extension-manager/):

| Tool | Does |
|---|---|
| `list_extension_points.py` | Lists every point in the source tree (path + id + name) |
| `create_extension_point.py` | Scaffolds a new point: node JSON + consumer component |
| `post-extension.sh <json> <parent-path>` | Imports a node into a running instance (wraps `:operation=import`) |
