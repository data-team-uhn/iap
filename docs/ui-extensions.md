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

`PageLayout` (homepage module) renders a stable screen frame around a scrolling
middle, every region a point:

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
| `iap/coreUI/frameTop` | Full-width bar, top | Pinned; collapses into a pull-tab drawer below its configured screen height |
| `iap/coreUI/frameBottom` | Full-width bar, bottom | Same |
| `iap/coreUI/frameStart` | Side rail, start (left in LTR) | Fixed width, own scrollbar, spans between the bars; collapses into an edge pull-tab drawer below its configured width |
| `iap/coreUI/frameEnd` | Side rail, end | Same |
| `iap/coreUI/pageTop` | Above main content, between rails | Scrolls with the content |
| `iap/coreUI/pageBottom` | Below main content, between rails | Scrolls with the content |

Empty regions render nothing — no rail, no bar, no pull tab. Thresholds and widths are
per-region in the theme's `iapShell` section
(`modules/frontend-commons/src/main/frontend/src/appTheme.ts`); currently rails at
200px collapsing at `md` (start) and `xl` (end), bars collapsing below 500px height.

### Routed views — `iap/coreUI/view` (node `Views`)

`ext:targetURL` is the path the view owns, handed to the router as-is, so router
patterns work: a parameter (`/Submissions/:id`) or a trailing splat
(`/Submissions/:id/*`). The breadcrumb trail matches ancestors against the same
patterns. Use `?lazy` so a view loads only when navigated to.

**What a pattern does not do is make its URLs resolvable on the server.** Every view
URL must be backed by a resource: client-side navigation works regardless, but a
refresh or deep link asks Sling for the whole URL, and an unresolvable path 404s
before the SPA loads. `/Submissions/123` resolves (the node serves the shell);
`/Submissions/123/reviews/financial` does not, however the pattern is written.

Two ways to back a URL:

- **A node** in the owning module's repoinit — `create path (app:Homepage) /content`.
  It also carries the page's access control, so denying it to `everyone` makes a deep
  link an honest 404. Fine for one virtual page; cannot cover varying paths.
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

It is active for `/Submissions/by-id` and `/Archive/by-id`. Note it is mounted
*beside* each tree rather than over it: a provider at `/Submissions` would be handed
every create below it and could only refuse, which would stop submissions being
raised. It resolves a single flat segment — no slashes or dots, at least
`PrefixTree.MINIMUM_NAME_LENGTH` (12) characters — so it makes ids addressable, not
arbitrary deep paths.

A module wanting deep or varying URLs of its own — the `/Submissions/{id}/*` case —
still needs a provider of its own over its own root, along the lines of the admin
console's.

### App bar — `iap/appBar/entry` (node `AppBarEntry`)

The app bar is itself a `frameTop` extension composed of entries on its own point.
`ext:appBarSection` places an entry in `start`, `middle` (centered) or `end`;
`defaultOrder` orders within the section.

Current entries: Branding (start); dark mode toggle, notifications bell, persona
switcher, administration console button (admins only), user menu (end). `middle` is
reserved for e.g. a future search bar.

A high-visibility element like a maintenance banner registers directly on `frameTop`
with `defaultOrder` below the app bar's 20 to sit above it. The
`iap-commons.NoticeBanner` asset renders such a data-only extension: markdown message
in `ext:data`, optional `ext:severity`.

Adding `ext:visibleBeforeLogin: true` also shows it on the login page, which renders
`frameTop` extensions carrying that opt-in and nothing else from the frame. Interim
mechanism: once system tags can declare where an extension belongs, that is expected
to take over. The conversion is contained — one client-side filter in the login
module's `PreLoginExtensions`, plus the node type property.

### Footer links — `iap/footer/link` (node `FooterLink`)

The footer is a `pageBottom` extension showing the institution's logo, links and the
platform version. Its links are **data-only extensions**: no component, just
`ext:name` (label) and `ext:targetURL` — a path navigates in-app, a full URL opens in
a new tab. Hide a link whose page isn't ready with `ext:defaultDisabled: true`.

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

Tiled on the homepage dashboard in a responsive grid, each framed with a title
(`ext:name`) and optional `ext:subtitle`. Frame tuning: `ext:widgetWidth`
(`normal`/`wide`/`full`), `ext:widgetEmphasis`, `ext:widgetBorderless`,
`ext:widgetHideHeader`, and `ext:actionLabel` (a header action button leading to
`ext:targetURL`, an in-app path — both must be set). `ext:personas` restricts a
widget.

The layout is the shared `WidgetDashboard`
(`@iap/frontend-commons/components/WidgetDashboard`), parameterized by point. The
administration console binds the same component to `iap/adminDashboard/entry`, so
widgets behave identically on both — see [Administration](administration.md).

### Breadcrumb trail

A `pageTop` extension (homepage module) linking the current URL's **ancestor** pages:
each ancestor path matching a registered view's `ext:targetURL` becomes a link
labelled with that view's `ext:name`. Top-level pages render nothing — home is the
logo, and the current page's title is its heading, not a crumb. Access control is
inherited from the views: one a user cannot read never reaches them.

## Personas

A user acts as one persona at a time — submitter, reviewer, administrator — chosen in
the app bar. `ext:personas` names the personas an extension belongs to; **naming none
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

## Tools

From the repository root, in [`tools/dev/extension-manager/`](../tools/dev/extension-manager/):

| Tool | Does |
|---|---|
| `list_extension_points.py` | Lists every point in the source tree (path + id + name) |
| `create_extension_point.py` | Scaffolds a new point: node JSON + consumer component |
| `post-extension.sh <json> <parent-path>` | Imports a node into a running instance (wraps `:operation=import`) |

