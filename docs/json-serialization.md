# JSON serialization

**Module:** `modules/serialization/json` · **SPI:**
`io.uhndata.iap.serialization.spi.ResourceJsonProcessor` · **Driver:**
`ResourceToJsonAdapterFactory`

Any resource in the repository can be served as JSON, and the caller decides what the
JSON contains. There is no per-type serialization code: one adapter factory walks the
node, and a chain of **processors** shapes what comes out. A module teaches the
serializer about its own content by registering a processor; the serializer never
learns about the module.

```
GET /Submissions/S-1.deep.simple.-dereference.json
```

## Requesting a serialization

The mechanism is a Sling adapter from `Resource` to `jakarta.json.JsonObject`, so it is
reached from a script, from Java, or over HTTP.

```java
final JsonObject json = resource.adaptTo(JsonObject.class);
```

Over HTTP it is the `.json` extension, served by `data/Content`'s `json.GET.html`,
which is inherited by every content type. What shapes the output is the **selectors**
between the path and the extension.

### Selector syntax

| Form | Means |
|---|---|
| `.<name>` | Enable the processor called `<name>` |
| `.-<name>` | **Disable** it, for processors that are on by default |
| `.<n>` | Serialize `<n>` levels of descendants; `.infinity` for no limit |
| `.<name>:<key>=<value>` | Pass an option to a processor |

Selectors are separated by dots, so a value containing a dot cannot simply be written
into the path. `SelectorUtils` (`iap-java-utils`) does the parsing — `parseSelectors`,
`parseDepth`, `parseOptions` and `parseOptionsToMap` — and it does understand a
backslash-escaped dot, but **do not rely on that in a URL**: an encoded backslash is
treated as a dangerous character and rejected by default under the servlet
specification, and support for it is being removed.

Use the query-parameter form instead. Selectors may be passed as repeated `selector`
parameters, and for any value containing a literal dot that is the form to use:

```
GET /Submissions/S-1.json?selector=deep&selector=dataFilter:after=2026-01-01T00:00:00.000-05:00
```

A query parameter is not split on dots, so a date, a path or any other dotted value goes
in as-is — no escaping, nothing to URL-encode twice. The two forms can be mixed: what is
in the path and what arrives as parameters are combined into one list, so the plain
selectors can stay in the path and only the awkward one moves.

### Depth

A numeric selector follows Sling's convention: `.0` is the node alone, `.1` adds its
children, `.infinity` is unlimited. Anything beyond the requested depth is emitted as
its **path string** rather than omitted, so a consumer can always tell that something
is there and go fetch it.

The same substitution protects against cycles: a node already being serialized higher
up the stack is emitted as its path instead of recursing forever.

Note that a depth selector other than `.0` implicitly enables `deep` — the processor
that descends at all — so Sling-style URLs like `.1.json` work without naming it.

## The processors

| Name | Default | Priority | Does |
|---|---|---|---|
| `properties` | **on** | 0 | Serializes all node properties. The base of every serialization |
| `files` | **on** | 5 | Renders `nt:file` children as a download descriptor — path, name, content type, size, modified — instead of descending into the binary |
| `identify` | **on** | 10 | Adds `@path` and `@name` |
| `dereference` | **on** | 10 | Replaces a `REFERENCE`, `WEAKREFERENCE` or `PATH` value with the serialization of the node it points at |
| `deep` | on with a depth selector | 10 | Includes descendants |
| `simple` | off | 25 | Drops the properties that describe how content is *stored* rather than what it holds |

`simple` is worth understanding before designing an endpoint. It removes every
`sling:` property, which only say which scripts render the resource, and every `jcr:`
property except the few identifying content rather than administering it — its type,
its identifier, and who created and last changed it. On a versionable type the
remainder is a large share of each node: version history, base version, predecessors
and the checked-out flag are repeated everywhere. A tree of entities shrinks
considerably without losing anything a reader of the content would recognise.

The combination that matters in practice is `deep` plus `simple`: the whole subtree,
without the storage bookkeeping. `.deep.simple.json` is what the category manager
fetches, because `.deep.json` alone would pull a bound schema's entire requirement
subtree and a BPMN document along with it.

Disabling a default is equally common. `.-dereference.json` leaves references as
identifiers, which is what you want when the caller already has the targets or is
about to write them back.

## Writing a processor

A processor is an OSGi component implementing `ResourceJsonProcessor`. Every method
except the first two has a default, so a processor implements only the hooks it needs.

```java
@Component
public class SimpleSchemaVersionProcessor implements ResourceJsonProcessor
{
    @Override
    public String getName() { return "simple"; }

    @Override
    public int getPriority() { return 25; }
}
```

### Identity and ordering

`getName()` is what a selector enables. **Several processors may share a name**, and
enabling that name enables all of them — which is how one selector can mean the right
thing for several resource types, each implementation handling its own.

`getPriority()` orders the chain, ascending, each receiving the previous one's output.
Priority `0` is the base, where `properties` serializes the raw properties; anything
that transforms a value has to run after whatever produced it. `files` runs at 5, ahead
of `deep` at 10, so `deep` finds the file already serialized and leaves it alone.

`isEnabledByDefault(resource)` is consulted per resource, not once — which is how
`deep` turns itself on when it sees a depth selector. `canProcess(resource)` limits a
processor to the resource types it understands, and is the right place for
"only runs on submissions".

### The hooks

The chain is called around a depth-first walk of the node tree:

| Hook | Called |
|---|---|
| `start(resource)` | Once, before anything is serialized. Set up state here |
| `enter(node, json, serializeNode)` | On entering each node, before its properties |
| `processProperty(node, property, input, serializeNode)` | Per property. Return the value to use, or `null` to drop it |
| `processPropertyName(node, property, input)` | Per property. Rename it, or `null` to drop it |
| `processChild(node, child, input, serializeNode)` | Per child. Replace or drop its serialization |
| `leave(node, json, serializeNode)` | On leaving each node, after its children — add computed fields here |
| `end(resource)` | Once, after everything. Tear down state |

Three rules follow from the shape:

- **`input` is what the previous processor produced**, not the raw value. Return it
  unchanged to pass it along; that is what every default does.
- **Returning `null` removes** the property, name or child from the output.
- **`serializeNode` is the recursion**, handed in rather than reached for. Use it to
  serialize something the walk would not otherwise reach — a referenced node, a
  computed child — and it inherits the current depth accounting rather than escaping
  it.

State belongs in the processor between `start` and `end`, and nowhere else: a
processor instance is a shared OSGi service, so anything held across serializations is
a race.

### Adding fields versus replacing them

Prefer `leave` for adding: the node's own content is already in the builder, so a
computed field lands beside it without anyone having to reproduce what was there.
`enter` is for state that later hooks need, and for fields that should precede the
content.

`processProperty` replacing a value is the mechanism behind `dereference` — the
identifier goes in, the target's serialization comes out — and it is the pattern for
any "render this the way a human would read it" processor.

## Serialization elsewhere

Two related mechanisms produce JSON without going through this chain, and it is worth
knowing which one you are looking at:

- **Sling Models** may build their own JSON. `DocumentedItem.documentationJsonBuilder()`
  composes a `JsonObjectBuilder` from a model's own accessors — see
  [autodoc](autodoc.md). That is the right choice when the shape is a contract of the
  model rather than a view of the node.
- **Servlets** returning a computed or generated response as JSON — a listing, a
  summary, a preflight — build it directly, because what they return is not a node. See
  [how a URL becomes a page](page-rendering.md#custom-servlets).

Use this chain when the response *is* content and the caller should be able to choose
how much of it they get.
