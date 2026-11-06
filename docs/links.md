# Links

**Module:** `modules/links` · **Bundle:** `iap-links-api` · **API:**
`io.uhndata.iap.links.api` (`LinkManager`), `io.uhndata.iap.links.models`

A relation that is part of a node type's own shape, such as a submission's
`schemaVersion` or an answer's `question`, is a REFERENCE property of that type. Any
other relation is a link: a typed connection, with an optional label, kept in the
`link:links` child of the content that holds it. A link points either at content in
the repository or at an identifier in another system.

## Link types

Every link type is defined under `/LinkTypes`, as one of two **kinds**. Where the
target lives decides which settings mean anything, so each kind declares its own:

- **`link:InternalDefinition`**: the target is content in this repository. The
  settings about what a link may point at, and what happens when it is deleted, are
  here.
- **`link:ExternalDefinition`**: the link records a value that names something
  elsewhere (see [external links](#external-links)). Its settings are about that
  value.

`link:Definition` is their abstract supertype. A new kind of link, such as one into
another institution's repository, is a new subtype.

| Property | Kind | Meaning |
|---|---|---|
| `label` | both | The name people read; the node name if unset |
| `description` | both | What this type means and when it applies, shown in the catalogue |
| `displayed` | both | Whether a user interface should show links of this type (default `true`). A hint, **not** access control: listing returns every link |
| `requiredSourceTypes` | both | NAME[], the node types the content holding the link must have, any one of them; unrestricted when unset |
| `targetLabelTemplate` | both | Such as `{typeLabel}: {name}`. Placeholders `{name}`, `{property:xyz}`, `{label}`, `{typeLabel}`, `{sourceName}`, `{value}` |
| `weak` | internal | The link holds a weak reference, which lets the target be deleted, rather than a hard one, which stops the deletion |
| `requiredDestinationTypes` | internal | NAME[], the node types the linked content must have, any one of them; unrestricted when unset |
| `backlink` | internal | PATH of another internal definition; the reverse link is added automatically. May name itself, for a pair of links of the same type |
| `backlinkOnly` | internal | Links of this type are only created as the reverse of another link |
| `onDelete` | internal | `IGNORE` (weak only), `REMOVE_LINK` (default), `RECURSIVE_DELETE`, applied by the [deletion service](deletion.md) |
| `valuePattern` | external | A regular expression every recorded value must match in full, checked when the link is created |
| `urlTemplate` | external | Turns the recorded value into an address, with `{value}` standing for it |

**Nothing enforces the split.** Every IAP node type has residual property
definitions, and a subtype inherits them, so the repository accepts a `backlink` on an
external definition. Nothing reads it: the external kind's model has no such setting.

An internal definition's `getOnDeletePolicy()` returns `REMOVE_LINK` when `onDelete` is
unset *or not one of the three values*. A typo therefore falls back to the default.

### Shipped types

The links module ships the link types whose meaning is the same everywhere, under
`/LinkTypes`:

| Type | Means | Behaviour |
|---|---|---|
| `copiedFrom` | The content this started as a copy of, such as a schema version made from another | Internal and weak, ignored on delete: the source can be deleted, and the copy stays as it is. No backlink, so adding the link writes nothing on the source |

Workflows link content with the `addLink` service task ([workflows](workflows.md)).

## Node types

```
link:Linkable      mixin, autocreates the link:links container
link:Links         holds link:Link / link:WeakLink / link:ExternalLink children
link:Link          type (REFERENCE, mandatory) + reference (REFERENCE, mandatory) + label
├── link:WeakLink  same, but reference is a WEAKREFERENCE
link:ExternalLink  type (REFERENCE, mandatory) + value (STRING, mandatory) + label
```

`type` is a **REFERENCE to the definition**, not a type name. The API takes names and
resolves them. A stored link points at its definition, so renaming the definition's
node keeps its links.

A weak link and a hard link have **different primary types**, chosen from the
definition's `weak` flag when the link is created. Changing `weak` on a definition
does not convert the existing links.

## Working with links

The API is all Sling Models, never resources. `Linkable` reads any content as content
with links, and it is where every operation starts:

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

A link has its own operations: `getDefinition()`, typed by kind
(`InternalLinkDefinition` on an `InternalLink`), `getLabel()`, `getSource()`,
`getTargetLabel()`, `remove(removeBacklink)`, plus
`getDestination()`/`isWeak()`/`getBacklink()`/`addBacklink()`/`isReverseOf()`/`isSymmetric()`
on `InternalLink` and `getValue()`/`getTargetUrl()` on `ExternalLink`.

`LinkManager` only resolves the link types: `getDefinition(type)`, and the
`LINK_TYPES_PATH` and `CONTAINER_NAME` constants.

Three behaviours to code against:

- **Reads degrade, writes throw.** Without the links service, `getLinks` and the other
  reads return empty lists, while `addLink` and `addExternalLink` throw
  `IllegalStateException`. `addLink` also throws `IllegalArgumentException` when the
  definition is unknown, external or `backlinkOnly`, when either end is not of a
  required type, or when the destination is not referenceable.
- **`Link.toLink(resource)` reads a link node as its kind's model.** It recognizes the
  kind by the resource type or by the primary type.
- **A link is not added twice.** Adding a link of the same type to the same target with
  the same label returns the existing link. A `null` label matches a link with any
  label.

### Committing

Writes go into the session the model was read with, and **the caller commits them**.
Two writes commit on their own, as the `iap-links` service user. A missing `link:links`
container is created and committed at once, since adding it may mean checking out a
versionable resource the caller cannot. When the service user cannot see the content,
such as content not committed yet, the caller's session creates the container instead.
The other is the automatic completion of backlinks.

`getDefinition(type)` takes no resolver. Everybody may read the definitions, and the
manager reads them with its own `iap-link-types` service user, which can read nothing
else. They are cached until `/LinkTypes` changes. **An access control policy that hides
a definition from a user does not stop the manager resolving it for that user.**

## Marking content linkable

The `link:Linkable` mixin declares the `link:links` container, kept out of the
content's versions. A node type lists the mixin among its supertypes (`data:Entity`
does, so every entity can hold links), and a single node gets it with `addMixin`.

The mixin is not a gate. Content whose type allows the container some other way, such
as through the residual child definitions every IAP type has, can hold links too. The
`Linkable` model adapts any content. Only a type that refuses unknown children, and
does not carry the mixin, cannot hold links.

## Backlinks

A definition with a `backlink` means **every link of that type gets its reverse** on
the linked content:

- When the session that adds the link may add nodes under the linked content, the
  reverse goes into the same session. The pair lands in the caller's one commit.
- Otherwise `AutocreateBacklinksListener` adds the reverse after the commit, as the
  `iap-links` service user.

Two links reverse each other when their endpoints are swapped and one's definition
names the other's as its `backlink`. Both facts are in the stored links. The listener
also sees the reverse being created, finds the pair complete, and adds nothing.

`getBacklinks()` asks Oak for the references to the content's node. It returns
`InternalLink`s only, since external links point at nothing in the repository.

`remove(true)` deletes the reverse too, in the same session, when that session can
read it.

## External links

An external link records that content corresponds to something outside the
repository: *this content is entity `12345` in that system*. In place of a reference
it has a mandatory `value`, checked against the definition's `valuePattern` when one is
set.

With a `urlTemplate`, `getTargetUrl()` turns the value into an address. The value goes
in **as it is**, so the template must handle whatever encoding its system needs.

Typical uses: mapping content to records in an institutional system, and matching
entities across federated deployments.

## Self-documentation

`GET /LinkTypes.doc.md` and `.doc.json` describe the link types through the
[autodoc mechanism](autodoc.md). Each type gets a subsection with its description and
the behaviours that apply: external, weak, backlink, deletion policy, type
restrictions, value pattern. In JSON, each type names its `kind`, `internal` or
`external`, and carries only that kind's settings. Link types have no categories, so
the catalogue has no section headings. Its title and introduction are the autocreated
`title` and `description` of `/LinkTypes` ("Link types" and "All the link types defined
in this instance."), which a deployment can edit.
