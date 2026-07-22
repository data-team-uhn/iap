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

### The application bar

The app bar is itself a `frameTop` extension, composed of entries on its own point:

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/appBar/entry` | `AppBarEntry` | Controls in the app bar row. `iap:appBarSection` places an entry in the `start`, `middle` (centered), or `end` section; `iap:defaultOrder` orders within the section. |

Current entries: Branding (start), and the dark mode toggle, notifications bell, and user menu
(end). The middle section is reserved for e.g. a future search bar. A high-visibility element
like a maintenance banner should register directly on `frameTop` with `iap:defaultOrder` below
the app bar's (20) to appear above it.

### The footer

The footer (a `pageBottom` extension) displays the affiliated institution's logo, links, and the
platform version + credit. Its links come from their own point:

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/footer/link` | `FooterLink` | Links in the page footer (Terms of use, User manual, FAQ, ...). **Data-only extensions**: no component, just `iap:extensionName` (the label) and `iap:targetURL` — a path navigates within the app, a full URL opens in a new tab. A link whose target page isn't ready can be hidden with `iap:defaultDisabled: true` until it is. |

### The dashboard

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/dashboard/widget` | `DashboardWidget` | Widgets tiled on the homepage dashboard in a responsive grid. Each widget is framed with a title (`iap:extensionName`) and optional subtitle (`iap:subtitle`); optional properties tune the frame: `iap:widgetWidth` (`normal`/`wide`/`full`), `iap:widgetEmphasis`, `iap:widgetBorderless`, `iap:widgetHideHeader`. |

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
