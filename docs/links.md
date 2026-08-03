# Links

Some relations between resources are part of a node type's own shape — a submission's
`schemaVersion`, an answer's `question` — and those stay direct REFERENCE properties. Everything
else, the ad-hoc "this is related to that" connections, goes through the **links module**
(`modules/data-model/links`): typed, optionally labeled connections kept in an `iap:links` child
container on the linking resource. A link points either at another resource in the repository, or
at something outside it — an identifier in an external system, recorded as a plain value.

## Link types

Every usable link type must first be *defined* as an `iap:LinkDefinition` node under
**`/LinkTypes`**. The definition is the single source of truth for what the connection means and
how it behaves:

| Property | Meaning |
| --- | --- |
| `label` | Display name for the type (defaults to the node name). |
| `description` | Longer explanation of what this type of link means and when it applies, surfaced by the [self-documentation catalogue](#self-documentation). |
| `displayed` | Whether links of this type appear in the user-facing UI (default `true`). A rendering hint only, not an access control, all links are included when listing them. |
| `external` | This type records a value instead of referencing a resource (see [external links](#external-links)). |
| `weak` | The link holds a weak reference: it may break when the target is deleted, instead of preventing that deletion. |
| `requiredSourceTypes`, `requiredDestinationTypes` | Node types the two ends must have; unrestricted when absent. |
| `resourceLabelTemplate` | Renders a nicer target label, e.g. `{typeLabel}: {name}`. Placeholders: `{name}`, `{property:xyz}` (target's name/property), `{label}` (the link's own label), `{typeLabel}`, `{sourceName}`, `{value}` (external links). |
| `backlink` | Path to another definition; the reverse link is added automatically (see below). A definition may name itself, for a symmetrical pair. |
| `backlinkOnly` | This type is only instantiated as an automatic backlink, never directly. |
| `onDelete` | What happens to the linking resource when the linked one is deleted: `IGNORE` (weak links only), `REMOVE_LINK` (default), `RECURSIVE_DELETE`. Declared in the data model, enforced once the workflow engine handles deletions. |
| `valuePattern`, `urlTemplate` | External links only: a regex recorded values must match, and a template turning the value into a navigable URL. |

## Self-documentation

The link vocabulary documents itself through the platform's
[self-documentation mechanism](autodoc.md): `GET /LinkTypes.doc.md` renders a human-readable
Markdown catalogue — one subsection per link type with its description and the behaviors that
apply to it (external, weak, backlink, deletion policy, type restrictions, value pattern) — and
`GET /LinkTypes.doc.json` returns the same catalogue as JSON. Link types have no categories, so
the catalogue is a flat list. Its heading can be reworded by setting the `title` and
`description` properties on the `/LinkTypes` node.

## Working with links

The `LinkManager` OSGi service (`io.uhndata.iap.links.api`) creates links on a resource and lists
what a resource holds; the Sling Models (`io.uhndata.iap.links.models`: abstract `Link`, concrete
`ResourceLink`/`ExternalLink`, `LinkDefinition`) represent the links themselves —
`resource.adaptTo(Link.class)` yields the concrete model — and carry the operations on an
individual link, like `link.remove(removeBacklink)`.

```java
@Reference
private LinkManager linkManager;
...
linkManager.addLink(submission, otherSubmission, "references", "see also");
linkManager.addExternalLink(submission, "ehrChart", "12345", null);
resolver.commit();
```

Writes — the manager's and the models' alike — are made in memory through the caller's own
resolver and are **the caller's to commit**, with two exceptions: creating a missing `iap:links`
container is committed immediately through the `iap-links` service user (it may require checking
out a versionable resource the caller cannot), and automatic backlink completion commits its own
work. Identical links are deduplicated: adding the same (type, target, label) again returns the
existing link.

`getDefinition(type)` takes no resolver: the definitions are platform vocabulary, world-readable
by design, read with the manager's own `iap-link-types` service user — a user that can read
nothing else — and cached until `/LinkTypes` changes. Restricting an individual definition with
an access control policy therefore has no effect.

The container attaches through each node type's residual child definitions, which all domain
types carry; a strict type that forbids unknown children cannot hold links.

## Backlinks

A definition with a `backlink` guarantees that **every link of that type eventually has its
reverse** on the linked resource:

- When the creating session may write to the linked resource, the reverse is created in the same
  session, so the pair lands atomically in the caller's one commit.
- Otherwise — insufficient rights, or the link was created inside a commit hook where no second
  commit is possible — a change listener completes the pair shortly after the commit, as the
  `iap-links` service user.

Completed pairs are recognized purely from the stored data: two links reverse each other when
their endpoints are swapped and their definitions cross-reference through their `backlink`
properties. This is what keeps the automatic completion from ping-ponging: processing the reverse
link's own creation event finds the pair already complete and stops. It also means the mechanism
is stateless and survives restarts, missed events aside.

`getBacklinks(resource)` — all the links *pointing at* a resource — reads the repository's
reference tracking directly, so it needs no query index.

## External links

An external link records a correspondence to something outside the repository: *this resource is
entity `12345` in that system*. It carries a mandatory `value` (validated against the
definition's `valuePattern`, when set) instead of a reference, and never participates in the
backlink protocol. When the definition sets a `urlTemplate`, the model renders the value as a
navigable address via `getTargetUrl()` — the value is substituted as-is, so templates are
responsible for any encoding their target system needs.

Typical uses: mapping resources to records in an institutional system, and correlating entities
across federated deployments.
