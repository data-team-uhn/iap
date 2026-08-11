# UI extension points

The IAP user interface is composed of **extensions** plugged into **extension points**. A feature
contributes UI by shipping a React component and a small JCR node registering it on a point; the
consumer of that point (the page shell, the app bar, the dashboard, ...) renders whatever is
registered, without knowing the contributors. This document catalogues the available extension
points and explains how to plug into them or define new ones.

## How the mechanism works

- An **extension point** is an `iap:ExtensionPoint` node under `/apps/iap/ExtensionPoints/<Name>`,
  carrying an `iap:extensionPointId` (e.g. `iap/coreUI/frameTop`) that extensions target.
- An **extension** is an `iap:Extension` node (conventionally under `/Extensions/<PointName>/`)
  with `iap:extensionPointId` matching the point, an `iap:extensionName`, and usually an
  `iap:extensionRenderURL` naming the React component to render as an `asset:` reference
  (see [the asset chain](#the-asset-name-chain)).
- Consumers fetch a point's enabled extensions as JSON (`/apps/iap/ExtensionPoints/<Name>`) via
  `loadExtensions()` (`@iap/ui-extension/extensionManager`), which also resolves the `asset:`
  properties into loaded components. The loader is resilient: a broken extension is logged and
  omitted, never breaking the page.
- The rendered component receives the parsed extension node as an `extension` prop, so any node
  property (`iap:data`, ...) is readable at runtime.

Properties understood by every point:

| Property | Type | Meaning |
| --- | --- | --- |
| `iap:extensionName` | String | Display name (some consumers show it, e.g. as a widget title) |
| `iap:extensionRenderURL` | String | The component to render, as `asset:<entry>.js`; append `?lazy` to defer loading it until first rendered |
| `iap:defaultOrder` | Long | Display order within the point (lower first, default 0) |
| `iap:defaultDisabled` | Boolean | When true the extension is skipped, without deleting it |
| `iap:personas` | String[] | The [personas](#personas) this extension belongs to; absent means all of them. **Only the dashboard filters on it today** — the other points render every extension whatever it names |

## Extension point catalogue

### The page shell

The overall page layout is a **stable screen frame** around a **scrolling middle**, every region
of which is an extension point (rendered by `PageLayout` in the homepage module):

```
┌──────────── frameTop ─────────────┐   Frame regions: always visible, pinned.
│ f ┌───────── pageTop ─────────┐ f │
│ r │                           │ r │   Page regions: between the rails, scrolling
│ a │      main content         │ a │   with the content.
│ m │   (the routed view)       │ m │
│ e │                           │ e │
│ S │                           │ E │   Naming rule: physical Top/Bottom for the
│ t └─────── pageBottom ────────┘ n │   vertical axis; logical Start/End for the
│ a                               d │   horizontal axis, which mirrors under a
└──────────── frameBottom ──────────┘   right-to-left locale.
```

| Point id | Region | Behaviour |
| --- | --- | --- |
| `iap/coreUI/frameTop` | Full-width bar, top of screen | Pinned; collapses into a pull-tab drawer below its configured screen height |
| `iap/coreUI/frameBottom` | Full-width bar, bottom of screen | Same, pinned to the bottom |
| `iap/coreUI/frameStart` | Side rail, start side (left in LTR) | Fixed width, own scrollbar, spans the band between the bars; collapses into an edge pull-tab drawer below its configured screen width |
| `iap/coreUI/frameEnd` | Side rail, end side | Same, on the end side |
| `iap/coreUI/pageTop` | Above the main content, between the rails | Scrolls with the content |
| `iap/coreUI/pageBottom` | Below the main content, between the rails | Scrolls with the content |

Empty regions render nothing at all — no rail, no bar, no pull tab. Collapse thresholds and rail
widths are configured **per region in the theme**, in the `iapShell` section of
`frontend-commons/src/main/frontend/src/appTheme.ts` (rail `width` and `collapseWidth` — a
breakpoint name or px —, bar `collapseHeight`).

### The routed views

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/coreUI/view` | `Views` | Full main-content views routed by URL: `iap:targetURL` is the path the view is responsible for; the router renders the matching view's component. Use `?lazy` on the render URL so a view is only loaded when navigated to. |

**Every view URL must be backed by a real resource.** Client-side navigation works regardless,
but on a refresh or deep link the browser asks Sling for that URL, and an unresolvable path is a
404 before the SPA ever loads. Views over entities get this for free (the entity node itself
serves the shell); a *virtual* page (like `/admin`) needs a shell-hosting node created in the
owning module's repoinit: `create path (iap:Homepage) /admin`. The node also carries the page's
access control — denying it to `everyone` turns a non-admin deep link into an honest 404.

Requiring a node per virtual page is an interim mechanism: the upcoming virtual resource resolver
serves a view's URL without one, and this whole note goes away with it.

### The application bar

The app bar is itself a `frameTop` extension, composed of entries on its own point:

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/appBar/entry` | `AppBarEntry` | Controls in the app bar row. `iap:appBarSection` places an entry in the `start`, `middle` (centered), or `end` section; `iap:defaultOrder` orders within the section. |

Current entries: Branding (start), and the dark mode toggle, notifications bell, persona switcher,
administration console button (admins only, see [Administration](administration.md)), and user menu
(end). The middle section is reserved for e.g. a future search bar. A high-visibility element
like a maintenance banner should register directly on `frameTop` with `iap:defaultOrder` below
the app bar's (20) to appear above it; the `iap-commons.NoticeBanner` asset renders such a
data-only banner extension (markdown message in `iap:data`, optional `iap:severity`).
Add `iap:visibleBeforeLogin: true` to such a banner to
also show it on the login page, which renders the `frameTop` extensions carrying this opt-in
flag (and nothing else from the frame) above its content — so a notice posted once reaches
users both before and after they sign in. The flag is an interim mechanism: once the upcoming
tags module lets system tags declare where an extension belongs, it is expected to take over
this distinction (the conversion is contained: one client-side filter in the login module's
`PreLoginExtensions`, plus the optional node type property).

### The footer

The footer (a `pageBottom` extension) displays the affiliated institution's logo, links, and the
platform version + credit. Its links come from their own point:

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/footer/link` | `FooterLink` | Links in the page footer (Terms of use, User manual, FAQ, ...). **Data-only extensions**: no component, just `iap:extensionName` (the label) and `iap:targetURL` — a path navigates within the app, a full URL opens in a new tab. A link whose target page isn't ready can be hidden with `iap:defaultDisabled: true` until it is. |

### The login page's sign-in methods

The auth action area of the login page composes the ways of signing in:

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/login/signInMethod` | `SignInMethod` | Sign-in methods on the login page. The first enabled method renders in place; any further methods are collapsed behind a quiet link labelled by their `iap:collapsedLabel` (falling back to `iap:extensionName`), revealed on demand. |

The login module registers the local credentials form as the default method
(`/Extensions/SignInMethod/CredentialsForm`, order 100, collapsed label "Use a local account
instead"). If no method is registered at all — or the point cannot be loaded — the page falls
back to rendering the credentials form directly, so there is always a way to sign in.

An identity-provider integration (e.g. the planned Keycloak module) does not need frontend
code: the generic `iap-login.RedirectSignIn` asset renders a redirect method from a data-only
extension — `iap:targetURL` (the endpoint starting the authentication round trip; the
validated in-app return path is attached as its `resource` parameter), optional
`iap:actionLabel` (button text, default "Continue to sign-in"), and optional `iap:hint` (a
short explanation under the button). Register it with a lower `iap:defaultOrder` than the
credentials form to make it the primary method, and disable the credentials form entirely with
`iap:defaultDisabled: true` where local accounts should not be offered.

### The breadcrumb trail

The breadcrumb trail is a `pageTop` extension (homepage module) rendered above the main content
of every page. It links the current URL's **ancestor** pages — each ancestor path matching a
registered view's `iap:targetURL` becomes a link labeled with that view's `iap:extensionName`.
On a top-level page there are no ancestors and nothing is rendered ("home" is reached through
the logo, and the current page's own title is the page heading, not a crumb). Access control is
inherited from the views themselves: a view the user cannot read never reaches them, so it
doesn't appear in their trail either.

### The dashboard

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/dashboard/widget` | `DashboardWidget` | Widgets tiled on the homepage dashboard in a responsive grid. Each widget is framed with a title (`iap:extensionName`) and optional subtitle (`iap:subtitle`); optional properties tune the frame: `iap:widgetWidth` (`normal`/`wide`/`full`), `iap:widgetEmphasis`, `iap:widgetBorderless`, `iap:widgetHideHeader`, and `iap:actionLabel` (a header action in line with the title — a button with this label leading to the widget's `iap:targetURL`, an in-app path; both must be set). `iap:personas` restricts a widget to some [personas](#personas). |

The layout itself — the responsive grid, the titled widget frames, and the tuning properties
above — is the shared `WidgetDashboard` component (`@iap/frontend-commons/components/WidgetDashboard`),
parameterized by extension point. The homepage dashboard binds it to `iap/dashboard/widget`; the
administration console binds the same layout to its own point, `iap/adminDashboard/entry`, so
widgets behave identically on both — see [Administration](administration.md) for that point and
for what a tool has to register.

## Personas

A user acts as one **persona** at a time — submitter, reviewer, administrator — chosen through the
app bar's persona switcher, and the UI is designed around what that persona does rather than around
the rights the individual happens to hold. `iap:personas` names the personas an extension belongs
to; naming none means all of them, so an extension that never considered personas keeps displaying
exactly where it did before, and adding a persona to the platform changes nothing about existing
extensions. Prefer naming nothing over naming every persona but one, or the extension silently
disappears from the next persona added.

A persona is **presentation only and never an access control**: the repository authorizes every
request independently of it, and a control hidden from a persona must never be the only thing
preventing the action behind it. The catalogue, the active-persona store, and the filter predicate
live in `ui-extension/src/main/frontend/src/personas.ts` and `extensionManager.tsx`
(`visibleInPersona`); `availablePersonas()` is the seam where the choice will be constrained by the
user's roles once IAP has any.

## Adding an extension

Three edits, all in the contributing module:

1. **Component** — a `.tsx` under the module's `src/main/frontend/src/`. It receives the parsed
   extension node as its `extension` prop.
2. **Entry point** — a line in the module's `assets.config`, e.g.
   `['iap-mymodule.MyControl']: './src/MyControl.tsx'`, making the component an independently
   loadable asset.
3. **Node** — a JSON file under the module's Sling-Initial-Content, e.g.
   `SLING-INF/content/Extensions/AppBarEntry/MyControl.json`:

   ```json
   {
     "jcr:primaryType": "iap:Extension",
     "iap:extensionPointId": "iap/appBar/entry",
     "iap:extensionName": "My control",
     "iap:extensionRenderURL": "asset:iap-mymodule.MyControl.js",
     "iap:appBarSection": "end"
   }
   ```

   Make sure the `Extensions/` path is registered in the module's `<Sling-Initial-Content>`
   (pom.xml).

### The asset name chain

The `assets.config` key (`iap-mymodule.MyControl`) → referenced as
`asset:iap-mymodule.MyControl.js` → resolved through `/libs/iap/resources/assets.json`
(content-hashed) at runtime. If a new component doesn't appear, first check that `assets.json`
contains the entry — a missing one means the build didn't pick up the `assets.config` change.

### Trying it against a running instance

Rebuild and hot-deploy the frontend, then post the node directly (a bundle's initial content is
not reliably re-run on redeploy):

```bash
cd aggregated-frontend && mvn clean install -PautoInstallBundle
./tools/dev/extension-manager/post-extension.sh path/to/MyControl.json /Extensions/AppBarEntry
```

## Defining a new extension point

1. Ship an `iap:ExtensionPoint` node under `/apps/iap/ExtensionPoints/` (see any existing one),
   with a unique `iap:extensionPointId` and a descriptive `iap:extensionPointName`. The
   `create_extension_point.py` tool (below) scaffolds this plus a consumer component.
2. Consume it: call `await loadExtensions("<NodeName>")`, and render with `ExtensionList`
   (all from `@iap/ui-extension`). Define any per-extension display properties
   your layout needs (like the app bar's `iap:appBarSection`) and document them here.
3. Follow the naming rule for anything direction-sensitive: physical top/bottom for the vertical
   axis, logical start/end for the horizontal axis.

## Developer tools

Run from the repository root ([`tools/dev/extension-manager/`](../tools/dev/extension-manager/)):

- `list_extension_points.py` — lists every extension point defined in the source tree
  (path + id + name).
- `create_extension_point.py` — scaffolds a new extension point (node JSON + consumer component).
- `post-extension.sh <json> <parent-path>` — imports a node into a running instance (wraps the
  Sling POST servlet's `:operation=import` with the right flags).
