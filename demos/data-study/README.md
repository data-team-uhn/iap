# Demo: retrospective data study

A researcher asks for data that already exists; a steward releases it or refuses.

```
mvn clean install
./start.sh --demo
```

## What it installs

| Path                        | What                                                                     |
|-----------------------------|--------------------------------------------------------------------------|
| `/Catalogues/demoRegistry`  | An invented registry, published twice — `v1` and `v2`                     |
| `/Schemas/dataStudy`        | What a study says about itself, and the data requirement that asks which fields it needs |
| `/Workflows/dataStudy`      | The process: describe and choose → release or refuse                      |
| `demo-researcher`           | Asks for the data. Member of `data-requesters`                            |
| `demo-steward`              | Decides. Member of `data-stewards`, which the schema routes approval to   |

Passwords match the usernames, which is fine precisely because this only ever runs behind `--demo` or the
demo integration-test suite; a real deployment installs none of it.

## Why the registry is published twice

This demo exists for one thing that no other content shows: **a submission holds the catalogue version it
was answered against, and republishing the catalogue cannot move it.**

The two versions differ in both directions, on purpose:

- `v1` (2026-02) has `people/Person/legacyId`, retired since.
- `v2` (2026-08) has `visits/Visit/dischargeDate`, added since.

`v2` is the active one, so a study started today is answered against it. A selection naming `legacyId` can
therefore only have come from `v1` — and a save naming it is refused, because what a selection may hold is
judged against the version it is bound to rather than against whatever is published now. That refusal is the
whole mechanism, visible in one HTTP response.

The registry is invented. Nothing here is a real data dictionary, and nothing should become one: a demo that
needed a real registry to run would prove nothing about the platform, and this repository is public.

## What runs

1. **`demo-researcher` starts a study** — `POST /Submissions` with a `title` and this schema's version path.
   The engine vets the version, creates the submission in its `draft` state and writes it on their behalf;
   they hold no repository rights of their own.
2. **The study goes under its workflow immediately**, because the schema version names one, and parks on
   `fillIn` — the task the researcher now owes, performed by `@creator` and tagged `draft`.
3. **Describing the study is answering questions**, saved one at a time as they are given.
4. **Choosing the data is a workflow event of its own**, `saveDataSelection`, which records the whole
   selection against the requirement and binds it to whichever version the registry is publishing. The first
   save is what binds it; every later one is judged against that same version and leaves the binding alone.
5. **`demo-researcher` sends it** — `POST` to the `fillIn` task with no outcome, because there is nothing to
   decide. The tag on the steward's task moves the study to `submitted`, after which choosing is refused for
   the same reason answering is.
6. **`demo-steward` decides** — `POST` to the task with `outcome=approved` or `rejected`. The decision is
   written down as a review against the approval requirement, the gateway routes on it, and the study ends
   as `approved` or `rejected`.

## Where it is going

- **A screen for choosing.** The requirement projects what it needs — which version to browse, and what has
  been chosen — and the catalogue interface that draws it is built. What is missing is the piece that fetches
  a catalogue version and saves a selection back through the workflow.
- **Publishing a new version through the engine.** Both versions here arrive as initial content, so
  publishing a third means a build. The versioned shape is what makes an engine-driven publication a later
  addition rather than a redesign.
- **Showing a reader what has moved on.** A selection made against `v1` and read while `v2` is current can
  say how many of its fields the current registry no longer offers. The comparison exists
  (`Selection.getMissingFields`); nothing shows it yet.
