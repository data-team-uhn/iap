# Integration tests

End-to-end tests that drive a real IAP instance through a browser, with [Playwright].

Unit tests check a class or a component in isolation; these check that a built, launched, fully wired IAP
actually works. They are correspondingly slow — an instance boot plus a browser — so they do not run in an
ordinary build.

```
mvn clean install                        # does not build this module at all
mvn clean install -PintegrationTests     # builds everything, then runs these
```

## How it fits together

Each suite gets **its own instance, launched from its own aggregated feature**, on its own reserved port:

| Suite       | Feature    | Instance                                                       |
|-------------|------------|----------------------------------------------------------------|
| `platform`  | `core_tar` | The bare platform, with no content beyond what the modules ship |
| `test-data` | `test_tar` | The platform plus the sample content, so every feature is exercisable |
| `demo`      | `demo_tar` | The platform plus the demo projects — a polished, working process |

The instances are started by the [Sling feature launcher Maven plugin][launcher] — the same launcher
`start.py` uses, pointed at the same published artifacts, so what the tests drive is the real runtime
rather than a lookalike assembled for testing.

`test_tar` and `demo_tar` exist because the launcher plugin can only be pointed at **one** feature, and these tests need
the platform *and* the sample content. Rather than wait for multi-feature support, the two are combined
into a single aggregate in `packaging/slingfeature`. That turns out to be the better answer anyway: what a
test run exercised is one versioned coordinate, and `analyse-features` validates the combination at build
time instead of it failing at startup.

Ports are reserved by `build-helper-maven-plugin` rather than hard-coded, so concurrent builds — and
anything already sitting on 8080 — do not collide. Maven passes each instance's URL to Playwright through
the environment, and `playwright.config.ts` turns those into one project per instance.

Each suite is actually **two** projects: `<suite>-<browser>` for its specs, and
`<suite>-stories-<browser>` for the story tests below, which runs only once the first has finished.
**Specs observe, stories mutate.** Much of what a spec asserts is what a *freshly launched* deployment
looks like: an empty instance, and a story that creates content has no business being in flight while
any of that is being checked.

The cost is that a failing spec skips its suite's stories. That is the right way round: the build is
already failing, and a story that fails only because the instance was not in the state it says it starts
from is worse than no result at all.

## Running one suite

Turn the others off, which skips both the launch and the corresponding Playwright project:

```
mvn clean install -PintegrationTests -Dit.platform.skip=true
```

This is also how a CI matrix should run them: one instance per job keeps peak memory to a single instance
instead of all of them at once.

## Watching it, debugging it, picking browsers

Everything the Playwright CLI takes can be passed through the build with `-Dplaywright.args`, so none of
these need you to leave Maven:

```
mvn install -PintegrationTests -Dplaywright.args=--headed                        # a real browser window
mvn install -PintegrationTests -Dplaywright.args=--debug                         # the Playwright Inspector
mvn install -PintegrationTests -Dplaywright.args=--ui                            # time-travelling UI mode
mvn install -PintegrationTests -Dplaywright.args="--grep @smoke"                 # only tagged tests
mvn install -PintegrationTests -Dplaywright.args="--project=test-data-chromium"  # one suite/engine
mvn install -PintegrationTests -Dplaywright.browsers="chromium firefox"          # download and run both
```

The first three want a display, and two of them want someone to drive — they are for iterating locally,
not for CI, where a build sitting on the Inspector is sitting on you. `mvn` swallows a bare `-Dkey=a b`,
so anything containing a space needs the quotes above.

`playwright.browsers` is the single source of truth: the same list decides which engines are downloaded
and which are run, so an engine can never be installed and then quietly ignored. Each one gets a project
per suite, named `<suite>-<browser>` — the suffix is there even with one browser, so that a `--project=`
written down today keeps meaning the same thing after a second engine is added. Unknown names are
rejected rather than silently treated as Chromium.

## Running against an instance you already have

The suites only need a URL, so an instance started by hand works just as well, and iterating this way is
far quicker than a Maven round trip:

```
cd src/test/e2e
pnpm install
pnpm exec playwright install chromium
IAP_TESTDATA_URL=http://localhost:8080 pnpm test --project=test-data-chromium
IAP_TESTDATA_URL=http://localhost:8080 pnpm test:ui           # watch mode, time-travel debugging
IAP_TESTDATA_URL=http://localhost:8080 pnpm test:headed       # a real browser window
IAP_TESTDATA_URL=http://localhost:8080 pnpm test:debug        # the Playwright Inspector
IAP_TESTDATA_URL=http://localhost:8080 PLAYWRIGHT_BROWSERS="chromium firefox" pnpm test
```

## Browsers need system libraries

Downloading Chromium is not enough; it needs about ten shared libraries that a minimal container image
will not have. Playwright says so clearly when they are missing:

```
sudo pnpm exec playwright install-deps        # or the apt-get line Playwright prints
```

This needs root, so it belongs in the CI image build rather than the test run. Only Chromium is installed
and run by default (`-Dplaywright.browsers="chromium firefox"` to widen it, as above) — the other engines
multiply both the download and the system packages for very little extra signal on an internal
application.

## Story tests

Most specs check one thing. A **story test** does what a person does: signs in, works through a whole
piece of business to its end, and signs out — one narrative, in order, on one page. They live in
`specs/<suite>/stories/` and are named `<subject>.story.spec.ts`.

They earn their cost by catching what no single-purpose spec can: the seams. Every step here already
works on its own, and the story is the only thing that asks whether they fit — whether what one step
leaves behind is what the next one needs, and whether a person can actually get from each to the next
without being told a path that only a test knows.

**Write the story first, in prose, and let it become the test.** A story test opens with the whole
narrative as a comment, in plain language, naming people rather than fixtures. The steps that the
platform can perform are written underneath as `test.step`s, in the story's own words; the steps it
cannot are left as prose, saying **what capability each one waits on**. So the file is a working test
and an honest account of what is still missing, and it is the same document either way — as each gap
is filled, its paragraph turns into steps and the prose gets shorter.

Two consequences worth knowing:

- **Order matters, so they are serial.** `test.describe.configure({ mode: 'serial' })`: each step
  depends on what the last one left behind, which is the opposite of how ordinary specs are written.
- **A story is not the place to assert everything.** It follows the path a person takes and checks that
  each step lands; the exhaustive cases belong in the suite beside it. When a story and a spec would
  assert the same thing, the story asserts that it *happened*, and the spec asserts what it *means*.

## Writing tests

- **Specs live under `specs/<suite>/`**, and only run against that suite's instance. Everything shared —
  page objects, fixtures, helpers — lives outside those directories and is imported.
- **Locate things the way a person finds them**: by label, by role, by the text on the button. That keeps
  the tests honest about accessibility and stops a restyle from breaking them. `pages/login.page.ts` is
  the pattern to follow.
- **Prefer an API-level assertion where one is equally meaningful.** It runs anywhere, needs no browser,
  and fails faster. The participating-institutions checks exist in both forms deliberately: the browser
  one proves the strip renders, the API one proves the instance really is the one it claims to be.
- **A suite's readiness is part of the suite.** The launcher only waits for the OSGi framework, and the
  frontend fetches most content once on mount without ever retrying — so a page that loads before its
  content is installed renders permanently without it, and no amount of assertion retrying recovers.
  Name what a suite depends on in `Instance.readyPaths` (probed anonymously, exactly as the browser reads
  it) rather than hoping.
- A failing run leaves a trace, a screenshot and a video in `target/e2e-report`. Open it with
  `pnpm report` — for anything that only fails on CI, that is the difference between a diagnosis and a
  guess.

## Typechecking and linting

These sources are held to the same standard as the application frontend, and both checks gate the build
— they run at `generate-resources`, before the instances boot and before Chromium is downloaded, so a
type error or a lint violation is reported in seconds rather than after a full launch:

```
pnpm typecheck        # tsc --noEmit
pnpm lint             # eslint .
pnpm lint --fix       # for the formatting and import-order ones
```

`eslint.config.mjs` is the frontend's configuration with the React-only parts removed; the rule set that
remains is deliberately identical, so a rule tightened there is worth tightening here. `support/*.mjs`
runs under bare Node rather than through Playwright's transpiler, so it is linted without the type-aware
rules, which would have no type information to work from.

## Why the test run does not fail the build directly

The run is deferred and the verdict enforced at `verify` by `support/check-results.mjs`, the same way
`maven-failsafe-plugin` defers its own. This is not ceremony: a failure during `integration-test` skips
`post-integration-test`, and the launcher plugin's shutdown hook kills the launcher *script* without
killing the JVM underneath it. Every failing run would otherwise orphan a full Sling instance, reparented
to init and still holding its port — which then silently pushes the next run onto a different port.

[Playwright]: https://playwright.dev/
[launcher]: https://github.com/apache/sling-feature-launcher-maven-plugin
