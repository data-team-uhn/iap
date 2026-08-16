# Deletion

Deleting an IAP resource is more than removing one node: other resources may reference it, link to
it, or depend on it. The **deletion service** resolves the complete set of impacted resources
first, then either refuses with an explanation, or carries the whole set over in one operation. By
default nothing is removed outright: deleted resources are moved into the **archive** at
`/Archive`, from where they can later be **restored** to their original places or **purged** for
good. Resources marked as **undeletable** cannot be deleted at all.

## What a deletion impacts

Starting from the requested resource, the service follows, transitively:

- **Containment**: a deleted node takes its whole subtree with it.
- **Incoming references**: any `REFERENCE` or `WEAKREFERENCE` property pointing at a deleted node
  drags its holder into the analysis. In the default, non-recursive mode such referencing
  resources *block* the deletion, and are reported grouped by type, e.g. *"This item is referenced
  by 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)."*; with `recursive`, they are
  deleted along with the target instead.
- **Links** (see [links](links.md)): a link pointing at a deleted resource follows its
  definition's `onDelete` policy — `REMOVE_LINK` removes just the link (with its backlink, if the
  pair is complete), `RECURSIVE_DELETE` deletes the linking resource too (reported like a
  referrer in non-recursive mode), and `IGNORE` leaves a weak link dangling. A hard link cannot
  ignore its target's deletion, so an illegal `IGNORE` on a hard link is downgraded to
  `REMOVE_LINK`, with a warning. Links never outlive their definition: deleting an
  `iap:LinkDefinition` removes the links of that type, regardless of policy.

The analysis is cycle-safe — mutually referencing resources, like a completed backlink pair whose
definitions both cascade, resolve in one bounded pass — and the set of deleted subtrees is kept
maximal, so a resource dragged in twice is processed once.

## Never delete

The `iap:Undeletable` mixin marks a resource that must never be deleted: not archived, not
permanently removed, and not purged if it somehow ends up in the archive. The protection is
lifted only by explicitly removing the mixin. A marked node *anywhere* in the impacted set —
including descendants of the deleted resource — blocks the whole operation, and every objection
is reported with its path and reason.

The mixin check is one instance of the pluggable **`DeletionVeto`** SPI
(`io.uhndata.iap.deletion.spi`): any bundle may register a veto service that gets asked about
every impacted node, with the kind of deletion under consideration (`ARCHIVE`, `PERMANENT`,
`PURGE`) and **the requesting user's session**. A veto that fails is counted as a veto — when a
guard cannot decide, the data stays. A typical future use is protecting workflow versions that have
ever been activated.

A guard enforcing a blanket policy rather than judging resources one by one overrides
`judgesWholeOperation()` to return `true`, and is then asked only about the resource whose deletion
was requested. Without that, a policy that refuses everything reports one identical objection per
node of every impacted subtree; with it, the report carries one objection naming the resource the
user actually asked about.

The requester's session is what lets a guard answer "*who* may do this", as opposed to "*what* may
be done": identity through `getUserID()`, and what the session acts as through
`JackrabbitSession.getBoundPrincipals()`. Note the two sessions in play — the node is read through
the privileged `iap-deletion` session and may well be invisible to the requester, whose own rights
are checked separately, node by node, before any guard is consulted. Guards must not write through
either.

> Prefer bound principals to a `UserManager` membership lookup when a guard asks what a user
> belongs to. With `user.dynamicMembership` enabled — which is how this platform is configured —
> an identity provider's roles reach the repository as principals with **no local group node**
> behind them, so `getAuthorizable(id).memberOf()` reports that a Keycloak role's members belong
> to nothing at all. Bound principals cover local groups and provider-supplied roles alike.

### Permanent deletion

`Permanent deletion policy` (`PermanentDeletionConfiguration`) can refuse the two deletions that
leave nothing to restore — `PERMANENT`, which never reaches the archive, and `PURGE`, which removes
what is already in it — while leaving archiving untouched: a user refused here can still delete the
resource in the ordinary, recoverable way. It is off by default, leaving the decision to access
control alone.

**Both modes, deliberately.** Guarding only `PERMANENT` would leave the ban defeatable in two
ordinary steps: delete to the archive, then purge the entry. Note this is a property of *this*
policy, not of the retention floor below, which keys on the archive entry's own type precisely
because only the entry carries the timestamp it needs.

`allowedPrincipals` lists user ids and principal names exempt from the ban, group and
identity-provider principals included. It is an **exemption, never a grant**: a veto can only
refuse, so an exempt user still needs exactly the access rights any deletion requires, and with
the ban off the list means nothing. An empty list with the ban on refuses everybody.

This guard judges the operation rather than the resource, so it declares
`judgesWholeOperation() == true` and a refusal reports **one** objection, against the resource whose
deletion was requested — not one per node of the subtree.

## The archive

Unless a permanent deletion is requested, the impacted subtrees are *moved* into a new
`iap:ArchiveEntry` under `/Archive`, wrapped in one `iap:ArchivedItem` per subtree recording its
`originalPath`. The entry records who requested the deletion (`deletedBy` — the actual writes are
performed by the `iap-deletion` service user, so `jcr:createdBy` cannot) and which resource was
targeted (`requestedPath`).

Entries are not direct children of `/Archive`. Each one is named with a UUID and filed by
`PrefixTree` (`io.uhndata.iap.utils`, in `iap-java-utils`) under buckets named after the first
characters of that UUID — `/Archive/3f/a9/1c/3fa91c48-…` — which keeps a parent from holding every
deletion ever performed. The buckets are `iap:Archive` nodes themselves; see the utility's javadoc
for the layout and its contract. Nothing should assume the depth: use the entry path returned by
the service, or look entries up by node type.

The tree is a storage concern, and it does not have to reach the people reading a URL: an entry can
also be addressed as **`/Archive/<uuid>`**, without its buckets. `PrefixTreeResourceProvider`
(`io.uhndata.iap.utils.internal`, in `iap-java-utils`) translates the short form back, and it needs
no lookup, index or state to do it, because `PrefixTree.pathFor` computes a bucket path from the
node's own name. Registered in overlay mode, so stored paths always win and nothing under the
archive changes; anything that is not a direct, extension-less child of the root and long enough to
be filed in the tree is left alone, which is also what keeps a two-character bucket from being read
as a node.

It is not specific to the archive: it serves **any** prefix tree, one instance per factory
configuration, and reads the root back from the `provider.root` service property it is mounted at,
so the path it translates under and the path it is registered for cannot drift apart. The archive
declares one in `modules/deletion`, and `modules/submissions` declares another for `/Submissions`,
whose submissions are filed the same way.

**What comes back is the real resource, with its real path.** The short form is an address, not an
identity: a resource whose `getPath()` disagreed with the node behind it would be a trap for
everything that adapts to a `Node`. So `path` in the API is still the stored one, and a second
field, `shortPath`, carries the address to link a reader to.

A move preserves node identifiers, so all references between archived resources — and references
*into* the archive from resources archived earlier — remain intact. That is exactly what makes
restoring possible. Hiding archived content is therefore the job of access control, not of broken
references: `/Archive` grants nothing to `everyone`, which keeps it, and everything in it, out of
regular users' reads *and query results*. Administrators bypass access control and can see it.

> **Caution for service code**: privileged sessions with broad read access see archived content,
> which keeps its original node types. Queries running under such sessions should exclude it,
> e.g. with `AND NOT ISDESCENDANTNODE([/Archive])`.

Consequences of the identifier-preserving design worth knowing:

- Archiving a resource that archived content still references is fine, in both directions;
  **permanently** deleting a resource referenced from the archive is refused instead, since
  honoring the reference would quietly mutilate somebody's archive entry. Such blockers are
  counted, never named ("… and 2 other items you cannot see"), because their contents are not the
  requester's to see.
- Archived *links* pointing at a permanently deleted resource are the exception: a link is
  bookkeeping, not content, so the hard ones are removed to let the deletion commit.

### Restore

Restoring an entry moves every archived item back to its recorded original path and removes the
emptied entry. It is all-or-nothing: if any item's original parent is gone, its path is occupied,
or the requesting user may not create it there, nothing is changed and every conflict is
reported. Links removed by the original deletion are **not** recreated.

### Purge

Purging an entry removes it and everything in it, permanently. The guards are consulted again
(with the `PURGE` mode), so protected content blocks the purge.

#### Retention period

`Archive retention` (`ArchiveRetentionConfiguration`) sets a **minimum age**, in calendar days, that
an entry must reach before it may be purged. It defaults to `0`, which imposes no floor: an entry
may be purged the moment it is created. Raising it only ever prevents destruction — nothing purges
anything automatically, so this is a floor under purging rather than a schedule for it.

### Viewing the archive

Restoring and purging both act on an entry's path, and entries are filed under a prefix tree of
buckets, so no client can construct one: they have to be listed. Two read endpoints do that, both
bound to `iap/Archive` and therefore addressable on the archive root or on any bucket under it —
listing a bucket is occasionally useful when diagnosing, and costs nothing to support.

- `GET /Archive.entries.json` returns one page of entries, newest first. `offset` and `limit` page
  it, `filter` keeps the entries whose `requestedPath` or `deletedBy` contains it (ignoring case),
  and `sortBy` (`jcr:created`, `deletedBy` or `requestedPath`) with `descending` orders it. The
  effective sort is echoed back, because an unrecognised column falls back to the default rather
  than failing the request. Each row carries the entry's `path`, `requestedPath`, `deletedBy`,
  `created`, `itemCount` and the `originalPaths` of everything archived in it, plus `shortPath` —
  the address to link to, as opposed to `path`, which is where it is stored.
- `GET /Archive.summary.json` returns `last24Hours`, `lastWeek` and `total` — the three counts the
  administration console widget shows, without fetching rows nothing displays.
- `GET /Archive/<xx>/<yy>/<zz>/<entry>.entry.json` describes one entry **and what would happen to
  it**: the same fields as a listing row, plus `restorable` with the `restoreConflicts` that would
  block a restore, and `purgeable` with the `purgeVetoes` that would block a purge.

That last one is a preflight, and it exists because both actions can fail for reasons that are
knowable in advance — a restore whose original parent is gone or whose path has been taken, a purge
a guard refuses. It runs the very evaluations the operations run before they change anything:
`DeletionService.checkRestore` shares `ArchiveOperations.evaluateRestore` with `restore`, and
`checkPurge` shares the veto sweep with `purge`, so a preflight and the operation cannot disagree
about what is possible. It is the same idea the deletion endpoint's own `dryRun` serves.

A preflight is a snapshot, not a promise: another deletion can occupy a path and a retention floor
expires, so the operations evaluate again rather than trusting it. A refusal arriving after a clean
preflight is therefore ordinary, and the entry page re-reads itself when one does.

The listing endpoints count with a bound (`ArchiveSearch.MAX_SCAN`) and report `approximate`/`totalIsApproximate`
when they stopped early, so an archive that has grown without limit costs a fixed amount to page
through rather than however much has been deleted over the years. Both run the query with the
**requester's own** session, so they can only ever show what that user could already read — which
is what makes the "everyone else gets a 404" rule of the action endpoints hold here too, with no
separate permission check to keep in step.

A property index on `jcr:created`, declared for `iap:ArchiveEntry`, ships with the module so the
listing is served by an index rather than by traversing the archive.

The UI is in `modules/deletion/src/main/frontend`, and both halves are extensions rather than pages:

- `ArchiveWidget` is registered on `iap/adminDashboard/entry`, so it sits on the administration
  console beside the other administrative tools rather than on everybody's homepage. It shows the
  three counts; the way through to the archive is the dashboard frame's own header action, declared
  by the extension's `iap:actionLabel` and `iap:targetURL` rather than drawn by the widget.
- `ArchiveBrowser` is registered on `iap/coreUI/view` with `iap:targetURL` `/admin/archive` — a
  filterable, sortable table with per-entry restore and purge actions, wrapped in the console's
  `AdminScreen` chrome like every other administrative tool. Each row links through to its entry.
- `ArchiveEntryView` is registered the same way for `/admin/archive/*`: one entry, what it holds,
  and whether each item could go back where it came from — the preflight above, per item and in
  words.

Both are pages of the administration console rather than standalone pages, so they carry no HTL of
their own: `/admin/**` is served by the console's own resource provider, which renders the
application shell for any path under it.

**The console route and the repository path are different strings, deliberately.** The browser sits
at `/admin/archive/<entry>` while the endpoints answer on `/Archive/<entry>`; `archiveApi.ts` owns
both constants and the two conversions between them, so they cannot drift into a route that
navigates somewhere real and fetches from somewhere that is not.
  That route also covers the prefix-tree buckets, which are not entries; the endpoint says so and
  the page reports it rather than rendering an empty entry.

`/Archive` and each entry are real nodes, which is what lets those views be opened directly as well
as navigated to: `html.GET.html` includes the shell's own script and `null.GET.html` re-dispatches
the extensionless URL to it, the same pair `iap:Content` declares for its subtypes, declared on both
`iap/Archive` and `iap/ArchiveEntry`. The archive types deliberately do not extend `iap:Content`, so
they also carry one-line `header.html` and `footer.html` borrowing the shared chrome by path. Nothing about the page is bespoke — the shell
decides what a page looks like, and the resource's readability decides who may open it.

The widget needs no persona restriction, because the administration console is reached only by
those who can read its extensions — access control is repository-side. Reaching the console is still
not the same as being allowed to read the archive, so a user whose rights do not match is told the
archive is unavailable rather than shown three zeros.

## The Java API

The `iap-deletion` bundle exposes **`DeletionService`** (`io.uhndata.iap.deletion.api`, an OSGi
service):

- `analyze(resource, options)` — a dry run: the complete `DeletionImpact` (deleted subtrees,
  removed links, vetoes, blocking referrers, a human-readable summary), changing nothing.
- `delete(resource, options)` — performs the deletion; `DeletionOptions` selects `recursive`
  (cascade over referencing resources) and `permanent` (skip the archive). The result reports
  what happened: `ARCHIVED` (with the entry path), `DELETED`, or a refusal — `VETOED`,
  `REQUIRES_CONFIRMATION` (blocking referrers), `DENIED` (missing permissions).
- `restore(archiveEntry)` / `purge(archiveEntry)`.

Every operation authorizes against the session of the resource passed in: the requesting user
needs `remove` rights on every node that would leave the live tree (and `add_node` rights at the
original locations, for a restore). The actual scan and all writes are performed by the
`iap-deletion` service user, which is what finds referrers hidden from the requester and writes
the archive. Link removal is a platform side effect: it does not require the requester to have
write access to the resources holding the links, mirroring how backlink completion works.

Business outcomes are return values, never exceptions; `DeletionException` signals actual
failures (repository errors, missing service user).

## The HTTP API

| Request | Meaning |
| --- | --- |
| `DELETE /path/to/resource` | Archive the resource, refusing if referenced by more than links |
| `DELETE /path/to/resource?recursive=true` | Also delete the referencing resources |
| `DELETE /path/to/resource?permanent=true` | Skip the archive, remove for good |
| `DELETE /path/to/resource?dryRun=true` | Only report what would happen, change nothing |
| `POST /Archive/<xx>/<yy>/<zz>/<entry>.restore.json` | Restore an archive entry |
| `DELETE /Archive/<xx>/<yy>/<zz>/<entry>` | Purge an archive entry |
| `GET /Archive.entries.json` | List archive entries, paged, filtered and sorted |
| `GET /Archive.summary.json` | Count the entries archived in the last day, last week, and in total |
| `GET /Archive/<xx>/<yy>/<zz>/<entry>.entry.json` | Describe one entry, and whether restoring or purging it would work |

Every endpoint addressing an entry also accepts the short form, e.g. `POST /Archive/<uuid>.restore.json`.

The deletion endpoint is bound to the `iap/Content` resource type, i.e. every content resource;
the archive endpoints are bound to `iap/ArchiveEntry`, and are implicitly restricted to users who
can see the archive — everyone else gets a plain 404 from resource resolution. All responses are
JSON carrying `status.code`, a machine-readable `status` word, and `status.message` when there is
something to explain:

- **200** — `{"status": "archived", "archiveEntry": "/Archive/<xx>/<yy>/<zz>/<uuid>", "items": [...],
  "removedLinks": [...]}`, or `"deleted"`, `"dryRun"` (with the full impact and an `executable`
  flag), `"restored"` (with the restored paths).
- **409** — `"referenced"` (with `referrers` grouped by type, an `inaccessibleReferrers` count,
  and the summary sentence), `"vetoed"` (with each veto's path, reason and guard name), or
  `"conflict"` for a blocked restore (with each conflict's `originalPath` and `reason`:
  `PARENT_MISSING`, `OCCUPIED` or `NO_RIGHTS`).
- **401/403** — the requester may not delete everything the deletion would impact.
- **400** — the target cannot be processed at all, e.g. deleting `/Archive` content directly
  instead of purging, or restoring something that is not an archive entry.
- **500** — an unexpected failure, with the exception's message.

A confirmation dialog is expected to first send the plain `DELETE`, and on a 409 offer the listed
consequences and retry with `recursive=true` — or start with `dryRun=true` and present the impact
up front.
