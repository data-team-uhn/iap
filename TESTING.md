# Writing tests

How to write and run the tests in this repository: the rules every test is held to, the
repository a Java test should run against, and the shared fixtures that save each module
from writing its own stand-ins for tags, service users, workflow tasks and the server.

Two things are covered elsewhere.
[integration-tests/README.md](integration-tests/README.md) covers the end-to-end suites
that drive a launched instance through a browser, and the Tests section of
[docs/frontend-development.md](docs/frontend-development.md) explains how Vitest shares
processes between test files and what it clears between them.

## Running them

Tests are off by default, so a plain `mvn clean install` runs none of them and still
reports `BUILD SUCCESS`. The `tests` profile turns them on.

| What | Command |
| --- | --- |
| One module's Java tests, with its coverage check | `mvn -Ptests clean verify -pl modules/<module> -am` |
| Everything but the integration tests | `mvn -Ptests clean install` |
| Everything, as CI runs it | `mvn -Ptests -PintegrationTests clean install` |
| The frontend tests, with coverage | `pnpm test`, from `aggregated-frontend/src/main/frontend` |
| The frontend lint and typecheck | `pnpm lint`, `pnpm typecheck`, from the same directory |
| The Python tests of document processing | `mvn -Ptests test -pl modules/documents/processing` |

-  **Use `verify`, not `test`.** The coverage check is bound to `verify`, so a module
  that passes `test` can still fail its floor.
-  **`-am` builds what a module depends on, never what depends on it.** A change to an
  interface passes its own module and breaks an implementation elsewhere. Add `-amd`, or
  build the whole reactor.
-  **`-Pquick` with `-Ptests` runs the tests and skips every other check**: the
  enforcer, Checkstyle, SpotBugs, JaCoCo, the licence and content checks, the dependency
  analysis, and the frontend typecheck and lint. The log still prints each skipped
  plugin's heading, so trust a check's own verdict line, such as
  `All coverage checks have been met`.
-  **A module's frontend tests do not run in its own build.** Every module's frontend is
  copied into `aggregated-frontend` and tested there. `pnpm test` and `pnpm lint` make
  that copy afresh; running `vitest` or `eslint` directly tests whatever copy is already
  there.

Every module must cover all of its Java instructions: `coverage.instructionRatio` is
`1.00`. A module can set a lower floor in its own pom, and should do so only for a good
reason. The frontend floor is 97% of statements, branches, functions and lines across
all modules. When a module misses its floor, JaCoCo names only the module. After the
run, `mvn jacoco:report -pl modules/<module>` writes the per-method counts to
`target/site/jacoco/jacoco.xml`.

## What every test is held to

Tests are held to the same standard as the code they test. Checkstyle checks the Java
tests with its own rule set, and the lint checks the frontend tests.

-  **One behaviour per test, named for it.** `flagsTheRequestTheEventArrivedOn`, not
  `testExecute2`. The body reads as build, operate, check.
-  **Set-up lives in named fixtures.** A test full of resource creation hides what it is
  about. A fixture class in the test tree (`TimeOffRequests`, `LinkTypesFixture`) gives
  the set-up a name, and a test reads as the case it covers.
-  **No reflection into private state.** Register a component through the test context,
  so that its references are wired as the framework wires them (see below). Never set a
  field by reflection.
-  **The date is read from the clock.** Build dates from `LocalDate.now()` in the test,
  and do not add a `today` parameter to production code for the test's sake. Compare
  calendar days with `plusDays`, never in milliseconds: a day is 23 or 25 hours long
  around a change of clocks, and such a test then fails twice a year.
-  **A test must be able to fail.** Put the defect back, one at a time, and check that a
  test notices. A bound written in terms of the constant it pins moves with the constant
  and can never fail, so write the literal.
-  **Wait on a fence, not on a count.** A latch counting side effects bets on how many
  there will be. A component that batches its work produces fewer, and the test waits
  out its timeout. Drain a single-threaded executor instead:
  `executor.submit(() -> { }).get(5, SECONDS)` returns once everything queued before it
  has run.
-  **No comment explaining that `src/test` mirrors `src/main`.** Say what a test name
  cannot: why a fixture is shaped as it is, or what a mock cannot do.

## Java tests

JUnit Jupiter, Mockito, and the Sling mocks: a test builds a `SlingContext`, fills its
repository, registers the services it needs and calls the code under test.

### Choosing a repository

The repository a `SlingContext` runs on decides which rules a test sees enforced. Pick
the lightest one that enforces what the test is about.

| Type | What it is | Use it for |
| --- | --- | --- |
| default (`RESOURCERESOLVER_MOCK`) | Resources in memory, no JCR | Models, servlets and services that read through the resource API |
| `JCR_MOCK` | An in-memory JCR without node types or access control | Code that needs a `Node`, and references made with `Referenceables` |
| `JCR_OAK` | A real Oak repository | Queries, node type rules (mandatory and protected properties), access control, versioning, the workflow engine |

`JCR_OAK` is slower to start, so a test class pays for it only when the rule it checks
is one only Oak enforces. A test asserting that a user cannot read a node must run on
Oak, and even there the context's own resolver is an administrator, who bypasses access
control: log in as the user being tested.

### Wiring a component

```java
private final SlingContext context = new SlingContext();

this.context.registerService(Scheduler.class, this.scheduler,
    Constants.SERVICE_RANKING, Integer.MAX_VALUE);
this.sweep = this.context.registerInjectActivateService(NightlyUrgencySweep.class);
```

`registerInjectActivateService` reads the component's descriptor, binds its references
to what the context holds, and activates it. The build writes the descriptors into
`target/test-classes` before the tests run. Register what a component references before
the component: a static reference binds what exists when the component activates, and
never looks again. To replace a service the context already holds, register the stand-in
ranked higher.

### Shared fixtures

Stand-ins needed by more than one module live beside the code they stand in for, under
`src/testFixtures/java`, and ship in that module's test jar.

| Fixture | Test jar | What it does |
| --- | --- | --- |
| `TestResolverFactory` | `iap-java-utils` | Serves the code under test's service logins with resolvers the test chooses |
| `Referenceables` | `iap-java-utils` | Gives `JCR_MOCK` nodes the `jcr:uuid` Oak gives them, and points REFERENCE properties at them |
| `PropertyTagging` | `iap-tags-api` | Makes every resource taggable, keeping its tags in its own `tags` property |
| `RecordingTaskContext` | `iap-workflows` | The context a service task handler runs in, recording what the handler leaves behind |
| `WorkflowPlugins.ENGINE` | `iap-workflows` | The real workflow engine and its services, for a `JCR_OAK` context |
| `WorkflowPlugins.MOCK_ENGINE` | `iap-workflows` | A Mockito mock of the engine, ranked above any other |

A test jar is built only when the tests run, so a module asks for it in its `tests`
profile. Asked for unconditionally, it would fail every build that skips the tests.

```xml
<profile>
  <id>tests</id>
  <dependencies>
    <dependency>
      <groupId>io.uhndata.iap</groupId>
      <artifactId>iap-workflows</artifactId>
      <version>${project.version}</version>
      <type>test-jar</type>
      <scope>test</scope>
    </dependency>
  </dependencies>
</profile>
```

**Service users.** Sling-mock's own service logins see only what a test has committed.
`TestResolverFactory.serving(context.resourceResolver())` hands every service user the
test's own resolver, which also sees what the test has not committed.
`withoutSubservice("name")` makes one login fail as a missing mapping does,
`withoutServiceUser()` makes every login fail, and `answeredBy(login -> …)` counts
logins or answers them differently.

**Tags.** The tags service does not run under sling-mock, so without
`PropertyTagging.installIn(context)` a model reading tags reports none. It follows the
service's rules where a test can see them: an undefined tag is refused, a replacement
retires the tags sharing its category, and an inheritable tag on an ancestor counts.
`PropertyTagging.builder()` declares categories, inheritable tags and refused ones.

### Workflow handlers and the engine

A service task handler is tested on its own, with a `RecordingTaskContext` in place of
the engine. The context records the variables, roles and events the handler leaves, and
runs the after-commit work and the rollback actions only when the test asks.

```java
PropertyTagging.installIn(this.context);
final Resource request = requests.request(REQUEST_PATH,
    Map.of("tags", new String[] {TimeOffUrgency.SUBMITTED_TAG}));

this.handler.execute(RecordingTaskContext.aimedAt(request).build());

assertTrue(requests.tagsOf(REQUEST_PATH).contains(TimeOffUrgency.URGENT_TAG));
```

The builder sets the event and its payload (`forEvent`), the actor (`actingFor`), the
activity whose properties configure the handler (`performing`), and what an earlier step
left: its variables (`withVariable`) and the resource it created (`havingCreated`),
which the handler then acts on instead of the target.

Code that sends events to the engine takes the mock engine, and the test verifies what
it was sent:

```java
private final SlingContext context = new SlingContextBuilder()
    .plugin(WorkflowPlugins.MOCK_ENGINE)
    .build();

Mockito.verify(this.context.getService(WorkflowEngine.class))
    .receiveEvent(targets.capture(), events.capture());
```

A test of a whole workflow runs the real engine on Oak, which it needs for
authorization. Notifications and history deliveries are recorded rather than sent, and
read back with `context.getService(RecordedNotifications.class)` and
`context.getService(RecordedDeliveries.class)`. No handler is registered: the test
registers the ones its workflows name.

```java
private final SlingContext context =
    new SlingContextBuilder(ResourceResolverType.JCR_OAK)
        .plugin(WorkflowPlugins.ENGINE)
        .build();
```

## Frontend tests

Vitest and Testing Library. Tests live under each module's `src/test/frontend/src/` and
import what they test through the `@iap/<module>/…` alias, as other modules do.

-  **Query by role and name, as a person finds things.** A text query matches any
  element with that text. A `queryByText("Columns")` waiting for a panel to close also
  finds the tooltip of the button that opened it, since focus returns there. Ask for the
  panel's heading instead.
-  **Assert identity with `toBe`.** `toHaveBeenCalledWith(promise)` compares structure,
  and a promise has none, so it passes for any promise.
-  **A `vi.mock` factory runs once per file.** `vi.resetModules()` does not run it
  again, so a test that needs a different mock registers it with `vi.doMock` inside the
  test.

Stand-ins a test loads as if they were application code are named `*.fixture.ts` or
`*.fixture.tsx`, and are left out of the coverage count. These are shared through
`@iap/frontend-commons`:

| Fixture | What it does |
| --- | --- |
| `fetch.fixture` | Replaces the global `fetch` with routes answering real `Response`s, and records the requests |
| `location.fixture` | `LocationProbe` shows where the router is, so a test asserts on navigation without mocking it |
| `phone.fixture` | `stubPhone()` makes every `max-width` media query match |
| `resizeObserver.fixture` | `stubElementWidth(width)` lays out every measured element at that width |

A request no route answers is rejected with an error naming it, so a test learns about a
request it did not expect. The session endpoint answers that somebody is signed in,
unless a route names it.

```ts
stubFetch(() => jsonResponse(treeJson));                          // one answer for all
stubFetch({ "POST /Categories/x": jsonResponse({ ok: true }) });  // by method and URL
stubFailingFetch(500);                                            // every request fails

expect(sentRequests().map(request => request.url)).toContain("/Categories/x");
```

Unstub after each test with `afterEach(() => vi.unstubAllGlobals())`.

## Python tests

The document processing daemon in `modules/documents/processing` has a pytest suite
under `src/test/python`. `run_check.py` provisions its own virtual environment from
`requirements-test.txt`, so the build needs only a Python 3 interpreter. The two files
that need Docling itself, `test_docling_config.py` and `test_docling_runtime.py`, skip
when it is not installed, and the build installs none: run them where Docling is.

## Integration tests

[integration-tests/README.md](integration-tests/README.md) covers how the suites are
launched, how to run one, and how to write a spec. Two traps it does not name:

-  **A date is judged in the server's time zone.** `new Date().toISOString()` answers in
  UTC, so "tomorrow" computed that way is today for part of every evening. Build a date
  from the local calendar.
-  **The suites share their instance.** Every spec of a suite runs against one
  repository, so an assertion about what the repository holds depends on which specs ran
  before it. Make a test's own content unique, with `uniqueTitle` from
  `support/titles.ts`, and assert about that.
