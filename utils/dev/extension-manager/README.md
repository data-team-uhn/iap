# Extension Manager utilities

Developer helpers for working with IAP's UI **extension points** and **extensions** (see the
"UI extension mechanism" section in the repository `CLAUDE.md` for the underlying model).

They fall into two groups: tools that edit the **source tree** (scaffold / discover definitions
you then commit and build) and a tool that talks to a **running instance** (deploy content without
a rebuild). Run all of them from the repository root.

## Source-tree tools

### `list_extension_points.py`

Lists every extension point defined in the codebase by finding the `iap:ExtensionPoint` node
definitions modules ship as Sling-Initial-Content under
`.../SLING-INF/content/apps/iap/ExtensionPoints/`. For each, it prints the JSON file plus its
`iap:extensionPointId` and human-readable name.

```bash
python3 utils/dev/extension-manager/list_extension_points.py
```

### `create_extension_point.py`

Scaffolds a new extension point: it writes the `iap:ExtensionPoint` node definition into the
chosen module's initial content, warns if that module's `pom.xml` won't load it, and generates a
client-side consumer component (from `templates/ExtensionPointUserTemplate.tsx`) that renders
whatever extensions plug into the point. It is interactive — it prompts for the name, id,
description, owning module, and the frontend file to place the consumer next to.

```bash
python3 utils/dev/extension-manager/create_extension_point.py
```

This edits source files only; afterwards, rebuild and (re)deploy the owning module.

## Running-instance tool

### `post-extension.sh`

Imports a content JSON file — typically an `iap:Extension` — straight into a running instance via
the Sling POST servlet's `:operation=import`, without a rebuild or restart. The parent path must
already exist.

```bash
./utils/dev/extension-manager/post-extension.sh \
  modules/homepage/src/main/resources/SLING-INF/content/Extensions/DashboardWidget/RandomNumber.json \
  /Extensions/DashboardWidget
```

Defaults to `http://localhost:8080` as `admin:admin`; override with `-u`/`-p` (or the `IAP_URL` /
`ADMIN_PASSWORD` environment variables). Run `./utils/dev/extension-manager/post-extension.sh --help`
for the full options.

> **Note:** the import passes `:replaceProperties=true` so typed properties (e.g.
> `iap:defaultOrder`) overwrite the node type's autocreated defaults instead of silently keeping
> them — the one way the POST importer diverges from a bundle's initial-content loader.

## Typical loop for a new dashboard widget

1. `cd aggregated-frontend && mvn clean install -PautoInstallBundle` — build and hot-deploy the JS
   asset.
2. `./utils/dev/extension-manager/post-extension.sh <extension.json> /Extensions/DashboardWidget` —
   post the extension node.
3. Hard-refresh the dashboard.
