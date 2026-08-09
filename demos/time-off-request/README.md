# Demo: time off request

Someone asks for a day off; their approver accepts or refuses it.

This is the smallest process that is still a whole one, and it is deliberately the *first* thing built
rather than the last. Each platform capability gets proved by something in use here, not only by its own
unit tests — and the same thing doubles as what you show in a meeting.

```
mvn clean install
./start.sh --demo
```

## What it installs

| Path                        | What                                                                     |
|-----------------------------|--------------------------------------------------------------------------|
| `/Schemas/timeOffRequest`   | What a requester fills in — currently one required date question         |
| `/Workflows/timeOffRequest` | The process, as BPMN: submitted → approve → approved or refused          |
| `demo-requester`            | Asks for the day off. Member of `time-off-requesters`                    |
| `demo-approver`             | Decides. Member of `time-off-approvers`, which the schema routes approval to |

Passwords match the usernames. That is fine precisely because this only ever runs behind `--demo` or the
demo integration-test suite; a real deployment installs none of it.

## The engine does not exist yet

So today this defines the process without executing it. That is still worth having: it is the target the
engine gets built against, and `integration-tests/src/test/e2e/specs/demo/` already asserts that the
schema, the workflow and the accounts are installed and correct. As the engine lands, the tests that raise
a request and approve it belong in that same suite, and this file should stop making this excuse.

The schema version does point at its workflow version, so the two halves are already joined: initial
content can write a REFERENCE by path with a `jcr:reference:` prefixed property —
`"jcr:reference:workflow": "/Workflows/timeOffRequest/v1"` — no UUID needed. The target has to exist first,
which is why the workflow directory is listed ahead of the schema one in `Sling-Initial-Content`.

## Editing the process

`bpmn.xml`, an `nt:file` child of the workflow version, is the source of truth, and the visual editor is
how it is meant to be edited. It embeds diagram interchange as well as the process, so it renders rather
than arriving as an unpositioned pile of shapes.

Four conventions worth keeping if you add to this:

- **The diagram is a file, not a property.** A whole XML document has no business in a node's property
  set: as a file it can be downloaded and re-uploaded as one, it carries its own content type and size,
  and it stays out of every serialization of the version. In initial content that means the descriptor
  `timeOffRequest.json` and a sibling directory `timeOffRequest/v1/` holding `bpmn.xml` — the loader
  applies both to the same tree, and `v1` keeps the type the descriptor gives it. The `.xml` extension
  is load-bearing: Sling derives the content type from the file name, so an extensionless diagram is
  served as an untyped binary. It also makes `ignoreImportProviders:="xml"` on the
  `Sling-Initial-Content` entries mandatory, not decorative — `.xml` is a content-descriptor extension,
  so without it the loader offers every diagram to its `XmlReader` first and stores the file only
  because that reader happens to find nothing it recognises. It recognises the *local* names `node`,
  `property`, `value`, `name`, `type`, `primaryNodeType` and `mixinNodeType` in any namespace, and
  `property` is a real BPMN 2.0 element: adding one `<bpmn:property/>` aborts the whole bundle's content
  load, taking the schema down with the workflow and leaving the instance permanently unhealthy.
- **Version nodes are named `v1`, not `1.0`.** A dot in a node name breaks Sling path resolution —
  `/Schemas/timeOffRequest/1.0.json` parses as the resource `.../1` with selector `0`, and 404s. The
  readable label lives in the `version` property. That is not an argument against `bpmn.xml`: a dot only
  bites when what follows it is an extension something else renders, or reads as a version number. Since
  nothing renders a workflow version as `xml`, a missing `bpmn.xml` still answers a clean 404.
- **References are written by path, not by UUID.** Prefix the property with `jcr:reference:` and give the
  target's path. Order the `Sling-Initial-Content` entries so the target loads first. Note that the JSON
  serializer embeds the referenced node rather than emitting the identifier; add the `-dereference`
  selector when you want the raw UUID back.
- **Initial content names a *directory*.** `SLING-INF/content/Schemas;path:=/Schemas` with the descriptor
  at `SLING-INF/content/Schemas/timeOffRequest.json`: the filename gives the node name, the directory gives
  the target tree. Naming the file instead is allowed and its descriptor is read correctly, but `path` is
  then ignored for placement and the node lands at the repository root.

## Where it is going

The feature list this grows into, each item chosen because it forces a platform capability into existence:

- More questions, with display conditions — half day or full day, start and end dates, absence type.
- An approver looked up automatically rather than named by hand.
- A doctor's note required only when the absence is sick leave, and validated by AI.
- Requests over 30 days needing a second approval; a check against an external time-off budget service.
- Unapproved requests flagged after two days; email on creation, approval and staleness.
- Raising a request by email as well as through the UI.

The budget-check service is meant to stay specific to this demo. It exists to prove that a project can
bring its own Java code, and each item above should identify an extension point the core needs to expose
rather than becoming a core feature itself.
