# Demo: retrospective data study

A researcher asks for data that already exists; a steward releases it or refuses.

```
mvn clean install
./start.sh --demo
```

The demo is built on the data requirement module (`modules/data-requirement`). That module is optional, so
the core distributions leave it out. `--demo` and the demo integration-test suite load it with the demo.

## What it installs

| Path                        | What                                                                     |
|-----------------------------|--------------------------------------------------------------------------|
| `/Catalogues/demoRegistry`  | An invented registry, published twice: `v1` tagged `retired`, `v2` tagged `active` |
| `/Schemas/dataStudy`        | What a study says about itself, and the data requirement that asks which fields it needs |
| `/Workflows/dataStudy`      | The process, as BPMN: described and data chosen → sent and marked submitted → released or refused |
| `demo-researcher`           | Asks for the data. Member of `data-requesters`                            |
| `demo-steward`              | Decides. Member of `data-stewards`, which the schema routes approval to   |

Passwords match the usernames. Only `--demo` and the demo integration-test suite load this; a real
deployment installs none of it.

## Why the registry is published twice

A submission holds the catalogue version it was answered against, and republishing the catalogue does not
move it. No other content shows this.

The two versions differ in both directions:

- `v1` (2026-02) has `people/Person/legacyId`, which `v2` dropped.
- `v2` (2026-08) has `visits/Visit/dischargeDate`, which `v1` lacks.

A study's first save binds its selection to the version tagged `active`, which is `v2`. Every later save is
judged against that binding, so a save naming `legacyId` is refused with a 400 that names the field. The
binding outliving a republication is not visible here, because publishing a third version needs a build.

The registry is invented. It must never become a real data dictionary: this repository is public.

## What runs

The stories in `integration-tests/src/test/e2e/specs/demo/stories/dataStudy.story.spec.ts` walk through
steps 1, 4 and 5.

1. **`demo-researcher` starts a study** with `POST /Submissions`, giving a `title` and this schema's version
   path. They hold no repository rights of their own: the engine vets the version and writes the
   submission on their behalf. It starts as a `draft`, with an empty part for each item the schema asks,
   the data requirement's still unbound selection among them.
2. **The study goes under its workflow immediately**, because the schema version names one. It parks on
   `fillIn`, the task the researcher now owes, performed by `@creator`.
3. **Describing the study is answering questions**, saved one at a time as they are given.
4. **Choosing the data is a workflow event of its own**: a `POST` to `<study>.saveDataSelection.json`
   naming the requirement and repeating `fields` once per chosen field. Each save replaces the whole
   selection. The first save binds it to the active catalogue version; every later one is checked against
   that version and leaves the binding alone. A field the bound version does not offer answers 400, and
   anybody but the researcher choosing answers 403.
5. **`demo-researcher` sends it** with a `POST` to the `fillIn` task and no outcome, because there is
   nothing to decide. A tag step after it marks the study `submitted`. It is no longer a draft, so saving
   answers or a selection after that answers 409.
6. **`demo-steward` decides** with a `POST` to the `releaseData` task carrying `outcome=approved` or
   `rejected`. The decision is written down as a review against the approval requirement, the gateway
   routes on the outcome, and the study ends tagged `approved` or `rejected`.

## Where it is going

- **Publishing a new version through the engine.** Both versions arrive as initial content, so publishing
  a third means a build.
- **Showing a reader what has moved on.** A selection made against `v1` and read while `v2` is current can
  say which of its fields the current registry no longer offers. `Selection.getMissingFields` computes
  that; nothing shows it yet.
