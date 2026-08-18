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

Because guards are asked about every impacted node, a refused deletion reports one objection per
node rather than one for the operation; they carry the same reason and differ only in path.

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
