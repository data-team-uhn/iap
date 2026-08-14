# Administration

Everything that configures the platform rather than uses it lives behind one door: the
**administration console**, a landing page at `/admin` presenting the available administrative
tools. It is reached through a worded "Administration" button in the app bar — words rather than an
icon, because a gear or similar glyph reads as personal settings.

The console itself (the `admin-console` module) owns only the door and the chrome. The tools are
contributed by the modules that own the data they manage, so a module ships its administration
alongside the feature it administers.

## The console

Each tool is a **widget** on the landing page, using the same layout and frame as the [homepage
dashboard](ui-extensions.md#the-dashboard): a live summary of the tool's area (the category tool,
for instance, lists the current top-level categories) leading into the tool's own page.

| Point id | Node name | Purpose |
| --- | --- | --- |
| `iap/adminDashboard/entry` | `AdminDashboard` | Administrative tool widgets. `iap:extensionName` and `iap:subtitle` title the widget frame, `iap:extensionRenderURL` names the summary component, and `iap:targetURL` is the tool's page — typically exposed as a header action next to the title via `iap:actionLabel`; prefer a label naming the destination (`"Manage categories"`) over a generic "Configure", since the label is what tells users a whole tool sits behind the widget's summary. All the [dashboard frame properties](ui-extensions.md#the-dashboard) apply. |

## Contributing a tool

A tool registers **two** [extension](ui-extensions.md) nodes: the `iap/adminDashboard/entry` widget,
and an `iap/coreUI/view` extension routing its page, conventionally under `/admin/<tool>`. As for
any routed view, that URL has to be backed by a resource so a refresh or a deep link resolves — see
[the note on routed views](ui-extensions.md#the-routed-views).

Wrap the page's content in the shared `AdminScreen` chrome (`@iap/admin-console/AdminScreen`), which
provides the page heading and an optional main action slot, and marks the administrative area as a
danger zone: a panel bordered and tinted with the theme's `admin` palette, hugging the tool content.
The chrome adds no wayfinding of its own — that is left to the shell (the [breadcrumb
trail](ui-extensions.md#the-breadcrumb-trail) on `pageTop`).

## Access control convention

Admin-only extension nodes — the tiles, their `Views` routing nodes, and the console's own app-bar
entry — all live under `/Extensions/Admin/<PointName>/`, a folder denied to `everyone` by repoinit
(in the `admin-console` module). Extensions are queried by point id with the requesting user's
session regardless of where they live, so for non-administrators the Administration button, the
`/admin` route, and every tool simply don't exist. The `/admin` shell node itself is denied too, so
deep-linking it as a non-admin yields a plain 404.

Restricting the extension nodes is the UI half; the tool's *data* must be protected by its own ACLs,
like any repository content.

## The tools

- [Category manager](categories.md#managing-the-tree) (`/admin/categories`, from the `categories`
  module) — organizes the tree of submission categories.

## Future work

A console tile is currently a React component, which is more than most tools need. Two kinds are
worth adding once there is a second tool to design them against:

- **A link and a label**, with no component at all — enough for a tool whose dashboard presence is
  only a way in.
- **A summary**, one line of the state a tool is responsible for: "45 registered users", "5
  installed vocabularies, 2 with new versions available", "1 planned downtime active". That is what
  makes the console a status page rather than a menu, and it is the contract each tool would
  implement.
