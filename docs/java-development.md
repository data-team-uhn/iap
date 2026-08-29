# Writing Java code

Backend code in IAP is OSGi Declarative Services running inside Apache Sling. This
document covers the principles the code is meant to follow, then the conventions and
the traps: what a component looks like, how to depend on other services, how to
configure one, and how a module gets assembled.

## What the code should look like

IAP is a platform rather than an application. A deployment turns it into a specific
tool — a research ethics board's, a leave-request system's — by supplying schemas,
workflows, categories and branding, and the code has to leave room for that. Three
habits follow, and most of the conventions further down are consequences of them.

### Modular, extensible, configurable

Prefer a mechanism a deployment can drive over a behaviour compiled in. A rule
expressed as content can be changed by an administrator, documented at runtime,
secured by the same ACLs as everything else, and varied between institutions. The same
rule expressed as a Java `if` requires a release.

That is why tags, link types, categories, conditions, workflows and UI extensions are
all definitions in the repository rather than enumerations in code, and why every
configurable component ships a working default so an unconfigured instance still runs.
Before adding a branch on a specific case, ask what would have to be *defined* for the
general case to cover it.

Extensibility has a matching rule: attach a mechanism to a base type, not to a domain
type. Tagging works on `data:Content`, deletion vetoes on any node, JSON serialization
on any resource — so a new domain gets all of it for free and no mechanism grows a
branch per module. A feature that has to be taught about each new type is a feature
that will be wrong for the next one.

### Interfaces, not implementations

What other modules use is an **interface in `api`**; the class implementing it lives in
`internal` and is never exported. Callers get it from the service registry, so an
implementation can be replaced, decorated or moved between bundles without touching a
consumer.

Where the extension runs the other way — other modules supplying behaviour rather than
consuming it — declare an **SPI** in `spi`, and consume every registered
implementation. `OperandResolver` adds an operand source to conditions,
`DeletionVeto` refuses a deletion, `TagProcessor` contributes computed tags,
`ResourceJsonProcessor` shapes serialization, `StatusReporter` contributes to the
status page. In each case the consuming module knows nothing about the contributors,
and a new one arrives by registering a component.

The test for a new capability: could a module the author has never seen add to it
without a change here? If the answer needs a `switch` on a known set of names, it
should have been an SPI.

### A domain model, not nodes

Content is stored in JCR, but code should not read it as nodes and properties. Every
content node adapts to a **Sling Model**, and the models are where the domain lives:

```java
final Submission submission = resource.adaptTo(Submission.class);
final SchemaVersion version = submission.getSchemaVersion();   // resolved, not a UUID
final List<Review> reviews = submission.getReviews();          // typed, filtered
```

The gain is not brevity. A model gives the concept a name and a place to hang
behaviour, so `isLeaf()`, `isRetiredHere()` or `appliesTo(content)` are written and
tested once rather than reimplemented as property checks by each caller. It resolves
references into models rather than leaving identifiers for callers to chase. It filters
children by resource type before adapting, which raw traversal does not. And it keeps
the storage shape — which property, which child, which mixin — inside the model, so
changing it is one edit.

Reserve raw `Node` and `NodeState` access for the places that genuinely cannot use
models: Oak commit hooks and editors, which run below the resource layer.

## Module layout

A module owns a slice of the platform end to end — its node types, the models over
them, the services acting on them, and its content. Java lives under
`io.uhndata.iap.<module>`:

| Package | Exported | Holds |
|---|---|---|
| `api` | yes | Service interfaces and value types callers use |
| `spi` | yes | Interfaces other modules **implement** to plug in |
| `models` | yes | Sling Models over the module's node types |
| `internal` | **no** | Implementations, servlets, editors, configuration classes |

`internal` is not exported, so nothing outside can depend on it — which is what lets
an implementation change without a coordinated release.

Modules large enough to have consumers split the contract from the implementation
into separate submodules (`modules/error-tracking/{api,impl}`,
`modules/submissions/{api,impl}`). That is what lets any module depend on
`iap-error-tracking-api` to record a failure without dragging in the recording
machinery, and without creating a dependency cycle.

**No module may depend on another module's `impl` submodule.** A dependency goes on
the `api` submodule; the implementation is reached at runtime, through the service
registered under the interface it declares. Depending on an `impl` directly is what
recreates the cycle the split exists to break — and it couples a module to code that
is free to change without warning, since only the `api` is a contract.

## A component

```java
@Component(service = DeletionVeto.class)
public class ArchiveRetentionVeto implements DeletionVeto
{
    @Override
    public String getName() { return "Archive retention"; }
}
```

`service` names what the component is registered as. Omit it and DS registers the
component under **every interface the class directly implements** — which is why most
components here, like the `OperandResolver` implementations, carry a bare
`@Component`. A class implementing no interfaces at all, such as
`ScheduledSlackNotification`, is then registered as no service, which is right for
something that only needs activating.

Name `service` explicitly when the class implements more than one interface and only
some of them are the service — and note the default covers *directly* implemented
interfaces only, so one inherited from a superclass needs naming.

Register a component under **the interface callers use**, not its own class. A
consumer referencing the implementation class defeats the point of the split above.

Service properties carry registration metadata, and their type is part of the string:

```java
@Component(service = HealthCheck.class, property = {
    HealthCheck.NAME + "=IAP duplicate jars",
    HealthCheck.TAGS + "=iap"
}, immediate = true)
```

```java
@Component(service = Filter.class, property = {
    // Ahead of any filter that reads selectors itself, so that all of them see the same ones
    "service.ranking:Integer=1000",
    "sling.filter.scope=REQUEST"
})
```

Note `:Integer` — without it the value is a String and every numeric comparison
against it silently does the wrong thing. This is the most common OSGi property bug.

`immediate = true` activates the component as soon as its dependencies are satisfied,
rather than waiting for the first consumer. Use it for anything with a side effect at
activation — a scheduled job, a listener, a component that only exists to register
something.

### Injecting dependencies

Prefer **constructor injection**, which makes the component testable without a
container:

```java
@Activate
public LoginPageReadyCheck(@Reference final ResourceResolverFactory resolverFactory,
    @Reference final SlingRequestProcessor slingRequestProcessor)
{
    this.resolverFactory = resolverFactory;
    this.slingRequestProcessor = slingRequestProcessor;
}
```

Field injection (`@Reference private Scheduler scheduler;`) is fine for a component
with one or two mandatory dependencies and no test that needs to supply them.

## Trap: dynamic lists of services

An SPI is consumed as a list of every registered implementation, and that list changes
while the system runs — bundles start, stop, and get redeployed. Getting this wrong
produces bugs that only appear after a hot deploy.

Three things are required, and all three matter:

```java
@Reference(cardinality = ReferenceCardinality.MULTIPLE,
    policy = ReferencePolicy.DYNAMIC,
    fieldOption = FieldOption.REPLACE)
private volatile List<DeletionVeto> vetoes;
```

- **`MULTIPLE`** — zero or more, and the component activates with none. An SPI with no
  implementations yet is normal, not an error.
- **`DYNAMIC`** — the field is updated in place as services come and go. Without it
  (the default, `STATIC`), SCR **deactivates and reactivates your component** every
  time an implementation appears or disappears, which for something holding state or
  registered elsewhere is a much larger event than it looks.
- **`volatile`** — DS writes the field from another thread. Without it, a reader thread
  may never see the new list.

Then read it **once into a local**, because the field can be replaced between two
reads of it:

```java
final List<StatusReporter> current = this.reporters;
if (current == null) {                    // no implementations registered yet
    return List.of();
}
for (final StatusReporter reporter : current) { … }
```

That null check is not defensive noise: with `FieldOption.REPLACE` the field stays
null until the first service arrives.

### REPLACE or UPDATE

`FieldOption.REPLACE` (the default with `DYNAMIC`) hands you a **new immutable list**
each time the set changes. Safe to iterate, but a reference captured earlier goes
stale — which is why you re-read the field on every use rather than caching it.

`FieldOption.UPDATE` mutates the list you initialized, so a long-lived holder keeps
seeing the current set:

```java
// Updated in place as producers come and go, so the scheduled task sees the current
// set rather than the one that existed when it was scheduled.
@Reference(cardinality = ReferenceCardinality.MULTIPLE, fieldOption = FieldOption.UPDATE,
    policy = ReferencePolicy.DYNAMIC)
private volatile List<SlackNotificationProducer> producers = new CopyOnWriteArrayList<>();
```

With `UPDATE` you **must** initialize the field with a thread-safe collection —
`CopyOnWriteArrayList`. DS mutates it while your code iterates it, and a plain
`ArrayList` there is a `ConcurrentModificationException` waiting for the next
deployment.

Rule of thumb: `REPLACE` and re-read the field when the list is consumed inside a
method call; `UPDATE` with `CopyOnWriteArrayList` when something captured the
reference and outlives the call — a scheduled task, a listener.

## Trap: picking the highest-priority implementation

Where several implementations could serve and only one should, OSGi already ranks
them. **Do not sort the list yourself** — `service.ranking` is not on the interface,
so a `List<T>` injection gives you no way to see it, and the order of an injected list
is not something to rely on.

A single-cardinality reference binds the highest-ranked service:

```java
@Reference(cardinality = ReferenceCardinality.MANDATORY,
    policyOption = ReferencePolicyOption.GREEDY)
private volatile Formatter formatter;
```

`ReferencePolicyOption.GREEDY` is the part people miss. The default, `RELUCTANT`,
means "once bound, stay bound": if a *better* implementation registers later, DS
leaves you on the one you already have. On a running instance where bundles come and
go, that produces a component quietly using the wrong implementation until the next
restart. `GREEDY` rebinds to the better one.

Use `OPTIONAL` instead of `MANDATORY` where the component should still work with none
bound; the field is then null and must be checked.

For a genuine list where order matters — a chain of processors, a set of filters —
rank by something the SPI itself declares, so the ordering is visible in the interface
rather than hidden in registration metadata. `TagProcessor.getPriority()` and
`TagDefinition.DISPLAY_ORDER` both do this.

## Configuration

Configurable components declare an OCD interface and point at it with `@Designate`:

```java
@ObjectClassDefinition(name = "Archive retention",
    description = "Minimum age an archive entry must reach before it may be purged.")
public @interface ArchiveRetentionConfiguration
{
    @AttributeDefinition(name = "Minimum retention period",
        description = "In calendar days. Zero, the default, imposes no floor at all.")
    int minimumRetentionDays() default 0;
}
```

```java
@Component(service = DeletionVeto.class)
@Designate(ocd = ArchiveRetentionConfiguration.class)
public class ArchiveRetentionVeto implements DeletionVeto
{
    @Activate
    void activate(final ArchiveRetentionConfiguration config) { … }
}
```

Every default must be a **working default**, because a component with no configuration
is the normal case and has to behave sensibly.

- `factory = true` on `@Designate` allows many configured instances of the same
  component — one per Slack notification, one per prefix tree.
- `configurationPolicy = ConfigurationPolicy.REQUIRE` on `@Component` means the
  component does not exist until configured. `PrefixTreeResourceProvider` uses this: a
  generic provider with no root to mount at should not be running.

Write the `description` for an administrator reading it in the web console, not for
the next developer. It is UI text.

## Repository access

Component code has no user session. Read and write through a **service user**, mapped
in the module's `feature.json`:

```json
"org.apache.sling.serviceusermapping.impl.ServiceUserMapperImpl.amended~iapdeletion": {
  "user.mapping": [ "io.uhndata.iap.deletion:deletion=[iap-deletion]" ]
}
```

The mapping reads *bundle symbolic name* `:` *subservice name* `=` *repository user*.
Ask for it by subservice name, and always in a try-with-resources:

```java
try (ResourceResolver resolver = this.resolverFactory
    .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, "deletion"))) {
    …
    resolver.commit();
}
```

A resolver not closed is a leaked session; sessions are finite.

One bundle may map several subservices with different rights, which is how a module
keeps a broadly privileged operation apart from a narrow one — the tags module maps
`tags` and `tagrepair` separately, and the repair user may modify properties but never
create or remove a node.

**Grant the least the code needs.** A service user is exempt from the access control
that protects everything else, so its ACL is the whole of its containment.

Where an operation acts on a user's behalf, authorize against **their** session and do
the work with the service user — see [deletion](deletion.md), which checks the
requester's `remove` rights node by node before the privileged scan begins.

## Bundle assembly

Each module ships a `feature.json` declaring its bundle and start order, aggregated by
the packaging module into what `start.sh` launches:

```json
{
  "bundles": [
    { "id": "${project.groupId}:${project.artifactId}:${project.version}",
      "start-order": "25" }
  ],
  "configurations": { … }
}
```

Two rules for `start-order`:

- **Stay under 30.** Felix treats 30 as "the system is ready"; a bundle at or above it
  starts after the platform has already declared itself up.
- **At least the highest of your dependencies.** For features included in the package
  this is checked at build time — the build fails on dependencies out of order — so
  getting it wrong is usually a broken build rather than a mystery at runtime. Optional
  features are not checked, and there a too-low order surfaces as a bundle that fails
  to resolve on a cold start and works on a warm one.

The numbers have no meaning beyond that ordering — they are not tiers. Pick the lowest
value that satisfies both rules.

Configurations belong in `feature.json` when they are part of how the platform is
assembled, and in the repository when a deployment or an administrator should change
them without a rebuild.

## Failures

Log through SLF4J as usual. Additionally record through
[error tracking](error-tracking.md) when a system administrator has to know hours
later and nobody would find out otherwise:

```java
ErrorLogger.logError(e, ErrorContext.of(MyComponent.class, "readDefinitions")
    .about(resource));
```

The static facade is the normal way in, deliberately: a mandatory
`@Reference ErrorLoggerService` would stop a working component from starting because
the thing that records its failures is missing. Before the module starts and after it
stops, the call does nothing.

Distinguish the two kinds of outcome. **Business outcomes are return values** — a
deletion refused because something references the target is a `DeletionResult` with a
status, not an exception. Reserve exceptions for actual failures: a repository error,
a missing service user.

Fail closed where a failure has a safe side. An unknown comparator in a condition
means *not satisfied*, so content behind a broken rule stays hidden; a veto that
throws counts as a veto, so the data stays.
