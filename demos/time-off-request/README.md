# Demo: time off request

Someone asks for a day off; their approver accepts or refuses it.

This is the smallest process that is still a whole one, and it is deliberately the *first* thing built
rather than the last. Each platform capability gets proved by something in use here, not only by its own
unit tests.

```
mvn clean install
./start.sh --demo
```

## What it installs

| Path                        | What                                                                     |
|-----------------------------|--------------------------------------------------------------------------|
| `/Schemas/timeOffRequest`   | What a requester fills in — currently one required date question, and a request form generated from it |
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

The schema version does point at its workflow version, so the two halves are already joined.

## The generated request form

`requestForm` is a generated document: the platform fills its `.docx` template in from the answers and
attaches the PDF, which the requester asks for from the request's page. They can download the filled `.docx`,
edit it, and upload it back in its place; the requirement takes only a `.docx` (its `acceptedFileTypes`). It needs the generation worker
running (`generate_compose.py --generation`, or the worker on `localhost:18766` beside `start.py`).

The template is `SLING-INF/content/Schemas/timeOffRequest/v1/requestForm/template`, a plain `.docx` with no
extension, because the node it becomes has to be called `template`. Replace it with any `.docx` of your own,
a letterhead with a logo say, edited in Word. It is a Jinja template (docxtpl), and may use:

- `{{ submission.title }}`, and `{{ submission.created }}`, an ISO date-time (`[:10]` keeps the date)
- `{{ submission.answers.details.day }}`: each answer, nested as the schema nests its questions
- `{{ submitter.name }}`, `{{ submitter.email }}`, `{{ submitter.id }}`

A name the template uses that the request does not have fails the generation with that name, rather than
printing nothing.

## Editing the process

`bpmn.xml`, an `nt:file` child of the workflow version, is the source of truth, and the visual editor is
how it is meant to be edited. It embeds diagram interchange as well as the process, so it renders rather
than arriving as an unpositioned pile of shapes.

Conventions worth keeping if you add to this:

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
