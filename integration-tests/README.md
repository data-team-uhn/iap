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

The instances are started by the [Sling feature launcher Maven plugin][launcher] — the same launcher
`start.py` uses, pointed at the same published artifacts, so what the tests drive is the real runtime
rather than a lookalike assembled for testing.

`test_tar` exists because the launcher plugin can only be pointed at **one** feature, and these tests need
the platform *and* the sample content. Rather than wait for multi-feature support, the two are combined
into a single aggregate in `packaging/slingfeature`. That turns out to be the better answer anyway: what a
test run exercised is one versioned coordinate, and `analyse-features` validates the combination at build
time instead of it failing at startup.

Ports are reserved by `build-helper-maven-plugin` rather than hard-coded, so concurrent builds — and
anything already sitting on 8080 — do not collide. Maven passes each instance's URL to Playwright through
the environment, and `playwright.config.ts` turns those into one project per instance.

## Running one suite

Turn the others off, which skips both the launch and the corresponding Playwright project:

```
mvn clean install -PintegrationTests -Dit.platform.skip=true
```

This is also how a CI matrix should run them: one instance per job keeps peak memory to a single instance
instead of all of them at once.

## Running against an instance you already have

The suites only need a URL, so an instance started by hand works just as well, and iterating this way is
far quicker than a Maven round trip:

```
cd src/test/e2e
yarn install
yarn playwright install chromium
IAP_TESTDATA_URL=http://localhost:8080 yarn test --project=test-data
IAP_TESTDATA_URL=http://localhost:8080 yarn test:ui        # watch mode, time-travel debugging
```

## Browsers need system libraries

Downloading Chromium is not enough; it needs about ten shared libraries that a minimal container image
will not have. Playwright says so clearly when they are missing:

```
sudo yarn playwright install-deps        # or the apt-get line Playwright prints
```

This needs root, so it belongs in the CI image build rather than the test run. Only Chromium is installed
by default (`-Dplaywright.browsers="chromium firefox"` to widen it) — the other engines multiply both the
download and the system packages for very little extra signal on an internal application.

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
  `yarn report` — for anything that only fails on CI, that is the difference between a diagnosis and a
  guess.

## Why the test run does not fail the build directly

The run is deferred and the verdict enforced at `verify` by `support/check-results.mjs`, the same way
`maven-failsafe-plugin` defers its own. This is not ceremony: a failure during `integration-test` skips
`post-integration-test`, and the launcher plugin's shutdown hook kills the launcher *script* without
killing the JVM underneath it. Every failing run would otherwise orphan a full Sling instance, reparented
to init and still holding its port — which then silently pushes the next run onto a different port.

[Playwright]: https://playwright.dev/
[launcher]: https://github.com/apache/sling-feature-launcher-maven-plugin
