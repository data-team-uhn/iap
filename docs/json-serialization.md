# JSON serialization

**Module:** `modules/serialization/json` · **SPI:**
`io.uhndata.iap.serialization.spi.ResourceJsonProcessor` · **Driver:**
`ResourceToJsonAdapterFactory`

Any resource in the repository can be served as JSON, and the caller decides what the
JSON holds. One adapter factory walks the node, and a chain of **processors** shapes
what comes out. A module teaches the serializer about its own content by registering a
processor. The serializer never learns about the module.

```
GET /Submissions/S-1.deep.simple.-dereference.json
```

## Requesting a serialization

The mechanism is a Sling adapter from `Resource` to `jakarta.json.JsonObject`, so Java
code, a script and an HTTP request all reach it.

```java
final JsonObject json = resource.adaptTo(JsonObject.class);
```

Over HTTP it is the `.json` extension. `data/Content`'s `json.GET.html` serves it, and
every content type inherits that script. The **selectors** between the path and the
extension shape the output.

### Selector syntax

| Form | Means |
|---|---|
| `.<name>` | Enables the processor named `<name>` |
| `.-<name>` | **Disables** it, for a processor enabled by default |
| `.<n>` | Serializes `<n>` levels of descendants; `.infinity` sets no limit |
| `.<name>:<key>=<value>` | Passes an option to a processor |

None of the processors [below](#the-processors) reads options.

Dots separate the selectors, so a value containing a dot cannot simply go into the
path. `SelectorUtils` (`iap-java-utils`) parses them, with `parseSelectors`,
`parseDepth`, `parseOptions` and `parseOptionsToMap`. It reads a backslash before a dot
as an escape, but **do not rely on that in a URL**: by default, Jetty refuses a
backslash in a path, literal or encoded.

Use the query-parameter form instead. A repeated `selector` parameter passes one
selector each, and a value containing a dot goes this way. Here a processor named
`dataFilter` gets the option `after`:

```
GET /Submissions/S-1.json?selector=deep&selector=dataFilter:after=2026-01-01T00:00:00.000-05:00
```

A query parameter is not split on dots, so a date, a path or any other dotted value goes
in as it is, with nothing to escape. The two forms mix. The selectors in the path come
first and the parameters after them, in one list. The plain selectors can stay in the
path, and only the dotted one moves.

### Depth

A numeric selector follows Sling's convention: `.0` is the node alone, `.1` adds its
children, and `.infinity` or a negative number sets no limit. Where several numbers
appear, the last one counts. A node past the requested depth is written as its **path
string**, not left out, so a consumer can tell it is there and fetch it. The limit
counts referenced nodes as well as children.

A node already being serialized higher up is written as its path too, so a cycle ends
there.

A depth selector other than `.0` enables `deep`, the processor that serializes
children, so Sling-style URLs such as `.1.json` work without naming it.

## The processors

| Name | Default | Priority | Does |
|---|---|---|---|
| `properties` | **on** | 0 | Writes the value of each property as it is stored |
| `files` | **on** | 5 | Writes a `jcr:content` child of type `nt:resource` as its path, so the binary stays out of the JSON, and copies its content type, size and modification date onto the `nt:file` |
| `identify` | **on** | 10 | Adds `@path` and `@name` |
| `dereference` | **on** | 10 | Replaces a `REFERENCE`, `WEAKREFERENCE` or `PATH` value with the serialization of the node it points at. A `jcr:` reference, such as `jcr:baseVersion`, gives the target's path |
| `deep` | on with a depth selector other than `.0` | 10 | Includes the children |
| `order` | off | 10 | Adds `@order` to a node that keeps its children in order: their names, in that order |
| `active` | **on** | 20 | Leaves retired schemas and inactive schema versions out of a schema listing (contributed by `schemas`) |
| `simple` | off | 25 | Drops the properties that say how content is *stored* rather than what it holds. `schemas` adds a second `simple`, at 50, for a schema version |
| `frozen` | on when the resource requested is a past state | 30 | Writes a past state from version storage with the original's type and `jcr:uuid`, not the copy's (see [history](history.md#a-past-state-does-not-describe-itself)) |
| `schemaPath` | **on** | 50 | Writes a submission's `schema` as the schema's path rather than the whole schema (contributed by `submissions`) |
| `events` | off | 50 | Adds `@events`, the workflow events the requesting user could send to the node (contributed by `workflows`, see [asking without sending](workflows.md#asking-without-sending)) |
| `fields` | off | 50 | Adds `@fields`, the fields the requesting user's `update` event could change on the node (contributed by `workflows`, see `updateContent` in [built-in service tasks](workflows.md#built-in-service-tasks)) |
| `creatable` | off | 50 | Adds `@creatable`, the types of content the requesting user's `create` event could add inside the node, each with the fields it starts with (contributed by `workflows`, see `createContent` in [built-in service tasks](workflows.md#built-in-service-tasks)) |

`order` is for a reader that needs the children in their stored order. The keys of a
JSON object cannot carry that order. Nothing promises that a reader keeps them in order,
and JavaScript lists keys that look like whole numbers first, in numeric order. A child
named `2` therefore comes before one named `10`, whatever order they were stored in.

`simple` removes every `sling:` property, `sling:resourceType` included. Of the `jcr:`
properties, it keeps `jcr:primaryType`, `jcr:uuid`, and the four that say who created
and last changed the content, and when. It drops the rest, which on a versionable type
includes the version history, base version, predecessors and checked-out flag of every
node. **A reader that tells nodes apart by `sling:resourceType` finds nothing in a
`simple` answer**, and has to match `jcr:primaryType` instead.

`.deep.simple.json` gives the whole subtree without the storage bookkeeping.

`.-dereference.json` leaves each reference as its stored `jcr:uuid`, and each path as
stored. Use it when the caller already has the targets, or will write the values back.

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
    public int getPriority() { return 50; }
}
```

### Identity and ordering

`getName()` is what a selector enables. **Several processors may share a name**, and the
name enables them all. One selector can thus mean the right thing for several node
types, each processor handling its own.

`getPriority()` orders the chain, ascending, and each processor receives what the one
before it returned. `properties` writes the stored values at priority `0`, and a
processor that transforms a value runs after whatever produced it. `files` runs at 5,
ahead of `deep` at 10, so `deep` finds the file's content already written and leaves it
alone.

`isEnabledByDefault(resource)` and `canProcess(resource)` are asked once per
serialization, about the requested resource. `deep` enables itself this way when it
sees a depth selector. `canProcess` limits a processor to the requested resources it
understands, such as a schema listing. **Neither is asked about the nodes inside.** A
processor for a node type that can appear nested checks each node's type in its hooks.

### The hooks

The chain is called around a depth-first walk of the node tree:

| Hook | Called |
|---|---|
| `start(resource)` | Once, before anything is serialized |
| `enter(node, json, serializeNode)` | On entering each node, before its properties |
| `processProperty(node, property, input, serializeNode)` | For each property. Returns the value to write, or `null` to leave the property out |
| `processPropertyName(node, property, input)` | For each property. Returns the key to write it under, or `null` to leave it out |
| `processChild(node, child, input, serializeNode)` | For each child. Returns the value to write, or `null` to leave the child out |
| `leave(node, json, serializeNode)` | On leaving each node, after its children. Add computed keys here |
| `end(resource)` | Once, after everything, even when serializing failed. Release what `start` set up |

Three rules follow from the shape:

- **`input` is what the processor before returned**, not the stored value. Return it
  unchanged to pass it along, as every default does.
- **Returning `null` removes** the property, key or child from the output.
- **`serializeNode` serializes another node within the same serialization.** Use it for
  a node the walk would not reach, such as a referenced node or a computed child. The
  node counts toward the depth limit. It comes back as its path past that limit, or if
  it is already being serialized higher up.

One processor instance serves every serialization, and several serializations can run
at once. A field that `start` sets is therefore shared by all of them. `end` runs
however the serialization ended, so what `start` set up is always released.

### Adding fields versus replacing them

Prefer `leave` for adding: the node's own content is already in the builder, so a
computed key lands beside it. `enter` is for keys that should come before the node's
content.

`processProperty` replaces a value. `dereference` works this way: the identifier goes
in, and the target's serialization comes out.

## Serialization elsewhere

Two other mechanisms produce JSON without going through this chain:

- **Sling Models** may build their own JSON. `DocumentedItem.documentationJsonBuilder()`
  builds a `JsonObjectBuilder` from a model's own accessors; see [autodoc](autodoc.md).
  It suits JSON whose shape is a contract of the model rather than a view of the node.
- **Servlets** that return a computed response, such as a listing, a summary or a
  preflight, build their JSON directly, since what they return is not a node. See
  [how a URL becomes a page](page-rendering.md#custom-servlets).

Use this chain when the response *is* content, and the caller should choose how much of
it to get.
