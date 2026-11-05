# UI extension points

**Module:** `modules/ui-extension` · **Package:** `@iap/ui-extension`
(`extensionManager`, `ExtensionList`, `ExtensionPoint`, `personas`) ·
**Tools:** `tools/dev/extension-manager/`

The interface is composed of **extensions** plugged into **extension points**. A
feature contributes UI by shipping a React component plus a small JCR node
registering it on a point; the consumer of that point renders whatever is registered,
knowing nothing about the contributors.

## The mechanism

- **Point:** an `ext:Point` node under `/apps/iap/ExtensionPoints/<Name>`, carrying the
  `ext:pointId` extensions target.
- **Extension:** an `ext:Extension` node (conventionally `/Extensions/<PointName>/`)
  with a matching `ext:pointId`, an `ext:name`, and usually an `ext:renderURL`.
- **Consumption:** `await loadExtensions("<NodeName>")` fetches a point's enabled
  extensions and resolves `asset:` properties into loaded components; render with
  `ExtensionList`. A broken extension is logged and omitted, never breaking the page.
- The component receives the parsed extension node as its `extension` prop, so any
  property (`ext:data`, …) is readable at runtime.

Properties every point understands:

| Property | Type | Meaning |
|---|---|---|
| `ext:name` | String | Display name; some consumers show it, e.g. as a widget title |
| `ext:renderURL` | String | `asset:<entry>.js`; append `?lazy` to defer loading until first render |
| `defaultOrder` | Long | Order within the point, lower first, default 0 |
| `ext:defaultDisabled` | Boolean | Skipped without being deleted |
| `ext:personas` | String[] | Personas this belongs to; absent means all. So far only the dashboards act on it — a first proof of concept, not a limit on where filtering belongs; other points are expected to follow |

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
  what the path means. Real content still wins, and the synthetic resource borrows
  `/admin`'s readability since it has no ACL of its own — which is why an admin tool
  needs no node for `/admin/<tool>`.

A second, generic provider handles the narrower job of addressing an entity by id
regardless of where it is filed. `PrefixTreeResourceProvider` (`java-utils`) resolves
`<mount>/<name>` to the prefix-tree location the node actually occupies, and is
enabled per subtree by configuration rather than being on everywhere:

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

A module wanting deep or varying URLs of its own — the `/Submissions/{id}/*` case —
still needs a provider of its own over its own root, along the lines of the admin
console's.

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

The first enabled method renders in place; further methods collapse behind a quiet
link labelled by `ext:collapsedLabel` (falling back to `ext:name`).

The login module registers the local credentials form as the default
(`/Extensions/SignInMethod/CredentialsForm`, order 100). If no method is registered,
or the point cannot be loaded, the page renders the credentials form directly — there
is always a way to sign in.

An identity-provider integration needs **no frontend code**: the generic
`iap-login.RedirectSignIn` asset renders a redirect method from a data-only
extension — `ext:targetURL` (the endpoint starting the round trip; the validated
in-app return path is attached as its `resource` parameter), optional
`ext:actionLabel` (default "Continue to sign-in") and optional `ext:hint`. Register it
with a lower `defaultOrder` than the credentials form to make it primary, and disable
the credentials form with `ext:defaultDisabled: true` where local accounts shouldn't
be offered.

### Dashboard widgets — `iap/dashboard/widget` (node `DashboardWidget`)

Tiled on the landing page's dashboard in a responsive grid, each framed with a title
(`ext:name`) and optional `ext:subtitle`. Frame tuning: `ext:widgetWidth`
(`normal`/`wide`/`full`), `ext:widgetEmphasis`, `ext:widgetBorderless`,
`ext:widgetHideHeader`, and `ext:actionLabel` (a header action button leading to
`ext:targetURL`, an in-app path — both must be set; the title then links there too).
`ext:personas` restricts a widget, and `ext:widgetGroup` files it under a
[widget group](#widget-groups).
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

A user acts as one persona at a time — submitter, reviewer, administrator — chosen in
the user menu. `ext:personas` names the personas an extension belongs to; **naming none
means all**, so an extension that never considered personas keeps displaying where it
did, and adding a persona to the platform changes nothing about existing extensions.
Prefer naming nothing over naming every persona but one, or the extension silently
disappears from the next persona added.

A persona is **presentation only, never access control**: the repository authorizes
every request independently, and a control hidden from a persona must never be the
only thing preventing the action behind it.

`personas.ts` exports `PERSONAS`, `availablePersonas`, `getActivePersona`,
`setActivePersona`, `personaLabel`, `usePersona`, `subscribeToPersona`;
`extensionManager` exports the `visibleInPersona` predicate alongside `getExtensions`
and `loadExtensions`. `availablePersonas()` is the seam where the choice will be
constrained by the user's roles once IAP has any.

## Adding an extension

Three edits, all in the contributing module.

1. **Component** — a `.tsx` under `src/main/frontend/src/`, receiving the parsed
   extension node as its `extension` prop.
2. **Entry point** — a line in the module's `assets.config`:
   `['iap-mymodule.MyControl']: './src/MyControl.tsx'`.
3. **Node** — JSON under Sling-Initial-Content, e.g.
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
referenced as `asset:iap-mymodule.MyControl.js` → resolved through
`/libs/iap/resources/assets.json` (content-hashed) at runtime. If a component doesn't
appear, check `assets.json` for the entry first — a missing one means the build didn't
pick up the `assets.config` change.

**Against a running instance,** rebuild and hot-deploy, then post the node directly, since
a bundle's initial content is not reliably re-run on redeploy:

```bash
cd aggregated-frontend && mvn clean install -PautoInstallBundle
./tools/dev/extension-manager/post-extension.sh path/to/MyControl.json /Extensions/AppBarEntry
```

## Defining a new point

1. Ship an `ext:Point` node under `/apps/iap/ExtensionPoints/` with a unique
   `ext:pointId` and a descriptive `ext:pointName`.
2. Consume it: `await loadExtensions("<NodeName>")`, render with `ExtensionList`.
   Define any per-extension display properties your layout needs (as the app bar does
   with `ext:appBarSection`) and document them here.
3. For anything direction-sensitive, follow the naming rule: physical top/bottom
   vertically, logical start/end horizontally.

`create_extension_point.py` scaffolds the node and a consumer component.

### Action points

When a point collects the actions available on one kind of thing — a workflow version, a schema
version, a submission — each extension is a component that renders its own button (and any dialog
it needs), and decides for itself whether it applies, rendering nothing when it does not. The page
never learns which actions exist, so another module can add one without touching it.

`getActions("<NodeName>")` from `@iap/frontend-commons/actionsManager` resolves a point's
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
