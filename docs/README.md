# IAP documentation

How the platform works, one document per mechanism. Building, running and deploying an instance are
covered in the [top-level README](../README.md); everything here is about what the platform *does*
once it is up.

The documents explain the reasoning as much as the mechanics — why a thing is shaped the way it is,
and which alternatives were rejected — so that a change to one of them can be made deliberately.

## Start here

**[overview.md](overview.md)** — what the platform is meant to be, and the two paradigms almost
every structural decision follows from: everything the platform knows is content, and everything
that changes it is a workflow. The pages below each go one mechanism deep; this one explains what
they have in common.

## Writing code

The conventions a contribution is expected to follow, and the traps worth knowing before hitting
them.

| Document | What it covers |
| --- | --- |
| [java-development.md](java-development.md) | Backend code: OSGi components, dependencies between services, configuration, service users, bundle assembly — and the habits the code is meant to follow |
| [frontend-development.md](frontend-development.md) | React, TypeScript and MUI: what a module ships, talking to the server, reporting failures, the shared components and the theme |
| [page-rendering.md](page-rendering.md) | How a URL becomes a page: resource resolution, display scripts, the responses that are not pages, and custom servlets |
| [json-serialization.md](json-serialization.md) | Serving content as JSON, the selectors a caller shapes it with, and the processor SPI a module extends it through |
| [http-requests.md](http-requests.md) | The shared client for calling an external service, and what it does and does not throw |

## The domain model

How content is shaped, related, and moved through its process.

| Document | What it covers |
| --- | --- |
| [workflows.md](workflows.md) | The process content is put through: BPMN 2.0 diagrams stored as a graph of nodes, instances, tokens, tasks and the engine that moves them |
| [categories.md](categories.md) | The tree submitters choose from, in their own language, so nobody has to know which schema applies; a category carries the schema version that governs what they are asked for |
| [conditions.md](conditions.md) | The shared vocabulary for content that only applies *sometimes* — a question shown only when an earlier answer calls for it, a review needed only when a submission is sensitive |
| [tags.md](tags.md) | Named markers like `incomplete` or `sensitive`, defined under `/Tags` before they may be used, and propagated up and down the tree |
| [links.md](links.md) | Ad-hoc typed connections between resources, for the relations that are not part of a node type's own shape |
| [deletion.md](deletion.md) | Deleting a resource together with everything that depends on it: the impact analysis, the archive at `/Archive`, restoring, and purging |

## The user interface

| Document | What it covers |
| --- | --- |
| [ui-extensions.md](ui-extensions.md) | The extension points the UI is composed of, and how to contribute an extension or define a point of your own |
| [administration.md](administration.md) | The administration console at `/admin`: one door for everything that configures the platform rather than uses it |
| [autodoc.md](autodoc.md) | How configurable parts of the platform document themselves at runtime, straight from the running system, instead of drifting out of date |

## Identity and access

| Document | What it covers |
| --- | --- |
| [keycloak-oidc.md](keycloak-oidc.md) | Sign-in delegated to an external Keycloak over OpenID Connect, and how a realm role becomes an Oak group principal that ACLs are written against |

## Operating an instance

Finding out what a running instance is doing, and being told when it needs attention.

| Document | What it covers |
| --- | --- |
| [status.md](status.md) | Short reports gathered from pluggable reporters and served at `/system/status` |
| [healthcheck.md](healthcheck.md) | The Apache Felix health checks the platform ships and the IAP-specific ones added to them |
| [metrics.md](metrics.md) | Named usage counters kept in the repository, so counts survive restarts and are shared across a cluster |
| [error-tracking.md](error-tracking.md) | Errors a running instance could not deal with, recorded under `/LoggedErrors` so they outlive the log file that would have rotated away |
| [notifications.md](notifications.md) | Telling people about the instance: something gathers what is worth saying, a channel delivers it |

## Packaging

| Document | What it covers |
| --- | --- |
| [docker.md](docker.md) | The `iap/iap` Docker image, and the two flavors that differ in how much of the artifact repository is baked in |
