# Links

**Module:** `modules/links` · **Bundle:** `iap-links` · **API:**
`io.uhndata.iap.links.api` (`LinkManager`), `io.uhndata.iap.links.models`

Relations that are part of a node type's own shape — a submission's `schemaVersion`,
an answer's `question` — stay direct REFERENCE properties. Everything else, the ad-hoc
"this is related to that", goes through this module: typed, optionally labeled
connections in a `link:links` child container on the source, pointing either at
content in the repository or at an identifier in an external system.

## Link types

Every usable type must be defined under `/LinkTypes`, as one of two **kinds**. Where
the target lives decides which settings mean anything, so each kind declares its own:

- **`link:InternalDefinition`**: the target is content in this repository, and
  everything about referential integrity belongs here.
- **`link:ExternalDefinition`**: the link records a value that means something elsewhere
  (see [external links](#external-links)), so its settings are about that value.

`link:Definition` is their abstract supertype. Adding a kind, a link into another
institution's repository say, is a new subtype, not another flag on a shared one.

| Property | Kind | Meaning |
|---|---|---|
| `label` | both | Display name; defaults to the node name |
| `description` | both | What this type means and when it applies; surfaced in the catalogue |
| `displayed` | both | Whether links appear in the user-facing UI (default `true`). A rendering hint, **not** access control — listing includes everything |
| `requiredSourceTypes` | both | NAME[], node types the linking content must have; unrestricted when absent |
| `targetLabelTemplate` | both | e.g. `{typeLabel}: {name}`. Placeholders `{name}`, `{property:xyz}`, `{label}`, `{typeLabel}`, `{sourceName}`, `{value}` |
| `weak` | internal | Holds a weak reference: may break when the target is deleted instead of preventing deletion |
| `requiredDestinationTypes` | internal | NAME[], node types the linked content must have; unrestricted when absent |
| `backlink` | internal | PATH of another internal definition; the reverse is added automatically. May name itself, for a symmetrical pair |
| `backlinkOnly` | internal | Only ever instantiated as an automatic backlink |
| `onDelete` | internal | `IGNORE` (weak only), `REMOVE_LINK` (default), `RECURSIVE_DELETE`, enforced by the [deletion service](deletion.md) |
| `valuePattern` | external | A regular expression recorded values must fully match, checked when the link is created |
| `urlTemplate` | external | Turns the recorded value into a navigable address, `{value}` standing for it |

**The split is stated, not enforced.** Every IAP node type carries residual property
definitions, and a subtype inherits them, so the repository accepts a `backlink` written
on an external definition. Nothing reads it: the external kind's model has no such
setting.

An internal definition's `getOnDeletePolicy()` falls back to `REMOVE_LINK` for an unset
*or unrecognized* value, so a typo in `onDelete` degrades to the default rather than
failing.

## Node types

```
link:Linkable      mixin, autocreates the link:links container
link:Links         holds link:Link / link:WeakLink / link:ExternalLink children
link:Link          type (REFERENCE, mandatory) + reference (REFERENCE, mandatory) + label
├── link:WeakLink  same, but reference is a WEAKREFERENCE
link:ExternalLink  type (REFERENCE, mandatory) + value (STRING, mandatory) + label
```

Note `type` is a **REFERENCE to the definition**, not a type name string. The API
takes names and resolves them; stored links point at their definition, so renaming a
definition node does not orphan its links.

Weak versus strong is a **different primary type**, chosen from the definition's
`weak` flag at creation. Flipping `weak` on a definition does not convert existing
links.

## Working with links

The whole API is Sling Models, never raw resources. `Linkable` is the links-aware
view of any content and the entry point:

```java
final Linkable linkable = submission.as(Linkable.class);

List<Link>         all      = linkable.getLinks();
List<Link>         someType = linkable.getLinks("references");
List<InternalLink> incoming = linkable.getBacklinks();

InternalLink link = linkable.addLink(otherSubmission, "references", "see also");
ExternalLink ext  = linkable.addExternalLink("ehrChart", "12345", null);
int removed       = linkable.removeLinks(otherSubmission, "references", null);
resolver.commit();
```

Individual links carry their own operations: `getDefinition()`, typed by kind
(`InternalLinkDefinition` on an `InternalLink`), `getLabel()`, `getSource()`,
`getTargetLabel()`, `remove(removeBacklink)`, plus
`getDestination()`/`isWeak()`/`getBacklink()`/`addBacklink()`/`isReverseOf()`/`isSymmetric()`
on `InternalLink` and `getValue()`/`getTargetUrl()` on `ExternalLink`.

`LinkManager` is only the vocabulary service — `getDefinition(type)`, and the
`LINK_TYPES_PATH`/`CONTAINER_NAME` constants.

Three behaviours to code against:

- **Reads degrade, writes throw.** With the links service unavailable, `getLinks` and
  friends return empty lists while `addLink`/`addExternalLink` throw
  `IllegalStateException`. `addLink` also throws `IllegalArgumentException` when the
  definition is unknown, external, or `backlinkOnly`, or when either end fails the
  required types.
- **Use `Link.toLink(resource)` on freshly created nodes.** The generic
  `adaptTo(Link.class)` dispatch runs on `sling:resourceType`, which is autocreated
  and therefore not materialized before commit; `toLink` picks the concrete model from
  the node's own type instead.
- **Identical links deduplicate.** Adding the same (type, target, label) returns the
  existing link.

### Committing

Writes are made in memory through the resolver the model was read with and are **the
caller's to commit**, with two exceptions that commit their own work: creating a
missing `link:links` container (through the `iap-links` service user, since it may
require checking out a versionable resource the caller cannot) and automatic backlink
completion.

`getDefinition(type)` takes no resolver. Definitions are platform vocabulary,
world-readable by design, read with the manager's own `iap-link-types` service user —
which can read nothing else — and cached until `/LinkTypes` changes. **Restricting an
individual definition with an access control policy therefore has no effect.**

## Marking content linkable

The `link:Linkable` mixin declares the `link:links` container once, so a node type
opts in by listing the mixin among its supertypes (`data:Entity` does, so every
entity is linkable), and an individual node by a plain `addMixin`.

It is a declaration aid, not a gate: content whose type allows the container some
other way — through residual child definitions, which every IAP type has — holds
links just as well, and the `Linkable` model adapts any content regardless. Only a
strict type forbidding unknown children and not carrying the mixin cannot hold links.

## Backlinks

A definition with a `backlink` guarantees that **every link of that type eventually
has its reverse** on the linked content:

- When the creating session may write to the linked content, the reverse is created in
  the same session, so the pair lands atomically in the caller's one commit.
- Otherwise — insufficient rights, or creation inside a commit hook where no second
  commit is possible — `AutocreateBacklinksListener` completes the pair shortly after
  the commit, as the `iap-links` service user.

Completed pairs are recognized purely from stored data: two links reverse each other
when their endpoints are swapped and their definitions cross-reference through
`backlink`. That is what stops the automatic completion from ping-ponging — processing
the reverse link's own creation event finds the pair already complete and stops — and
it makes the mechanism stateless across restarts, missed events aside.

`getBacklinks()` reads the repository's reference tracking directly, so it needs no
query index. It returns `InternalLink`s only; external links never participate.

## External links

An external link records a correspondence to something outside the repository: *this
content is entity `12345` in that system*. Mandatory `value`, validated against the
definition's `valuePattern` when set, instead of a reference.

With a `urlTemplate`, `getTargetUrl()` renders the value as a navigable address. The
value is substituted **as-is**, so the template is responsible for whatever encoding
its target system needs.

Typical uses: mapping content to records in an institutional system, and correlating
entities across federated deployments.

## Self-documentation

`GET /LinkTypes.doc.md` and `.doc.json` render the vocabulary through the
[autodoc mechanism](autodoc.md) — one subsection per type with its description and
the behaviours that apply (external, weak, backlink, deletion policy, type
restrictions, value pattern); the JSON names its `kind`, `internal` or `external`,
and carries only that kind's settings. Link types have no categories, so the
catalogue is flat. The heading comes from the autocreated `title` and `description`
on `/LinkTypes` ("Link types" / "All the link types defined in this instance."),
both editable by a deployment.
