# Deletion

**Module:** `modules/deletion` · **Bundle:** `iap-deletion` (start-order 24) ·
**Packages:** `…deletion.api` (`DeletionService`), `…deletion.spi` (`DeletionVeto`)

Deleting a resource is more than removing a node: others may reference it, link to
it, or depend on it. The deletion service resolves the complete impacted set first,
then either refuses with an explanation or carries the whole set over in one
operation. By default nothing is removed outright — deleted resources move into the
**archive** at `/Archive`, to be **restored** or **purged** later.

## What a deletion impacts

From the requested resource, the service follows transitively:

- **Containment** — a deleted node takes its whole subtree.
- **Incoming references** — any `REFERENCE` or `WEAKREFERENCE` pointing at a deleted
  node drags its holder in. Non-recursively those referrers **block** the deletion and
  are reported grouped by type ("referenced by 3 submissions (S-1, S-2, S-3) and 1
  schema (Onboarding)"); with `recursive` they are deleted too.
- **[Links](links.md)** — a link at a deleted resource follows its definition's
  `onDelete`:

| Policy | Effect |
|---|---|
| `REMOVE_LINK` | Removes just the link, with its backlink if the pair is complete |
| `RECURSIVE_DELETE` | Deletes the linking resource too; reported like a referrer when non-recursive |
| `IGNORE` | Leaves a weak link dangling. **Illegal on a hard link** — downgraded to `REMOVE_LINK` with a warning |

Links never outlive their definition: deleting a `link:Definition` removes the links
of that type whatever the policy.

The analysis is cycle-safe — mutually referencing resources, such as a completed
backlink pair whose definitions both cascade, resolve in one bounded pass — and the
set of deleted subtrees is kept maximal, so a resource dragged in twice is processed
once.

## Java API

```java
@NotNull DeletionImpact  analyze(Resource item, DeletionOptions options);   // dry run
@NotNull DeletionResult  delete(Resource item, DeletionOptions options);
@NotNull RestoreResult   restore(Resource archiveEntry);
@NotNull DeletionResult  purge(Resource archiveEntry);

// Preflights, sharing their evaluation with the operations above
@NotNull List<RestoreConflict> checkRestore(Resource archiveEntry);
@NotNull List<Veto>            checkPurge(Resource archiveEntry);
```

`DeletionOptions.recoverable()` or `DeletionOptions.of(recursive, permanent)`:
`recursive` cascades over referring resources, `permanent` skips the archive.
`DeletionImpact` carries the deleted subtrees, removed links, vetoes, blocking
referrers and a human-readable summary.

`DeletionResult.Status` is `ARCHIVED` (with the entry path), `DELETED`, `VETOED`,
`REQUIRES_CONFIRMATION` (blocking referrers) or `DENIED` (missing permissions).
**Business outcomes are return values, never exceptions**; `DeletionException`
signals actual failures — repository errors, a missing service user.

`DeletionService` also holds the path and node type constants (`ARCHIVE_PATH`,
`ENTRY_NODETYPE`, `UNDELETABLE_MIXIN`, `DELETED_BY_PROPERTY`, …); use those rather
than string literals.

**Two sessions are in play.** Every operation authorizes against the session of the
resource passed in: the requester needs `remove` on every node leaving the live tree,
and `add_node` at the original locations for a restore. The scan and all writes are
performed by the `iap-deletion` service user, which is what finds referrers hidden
from the requester and writes the archive. Link removal is a platform side effect and
does **not** require the requester to have write access to the resources holding the
links, mirroring backlink completion.

## HTTP API

| Request | Meaning |
|---|---|
| `DELETE <path>` | Archive it, refusing if referenced by more than links |
| `DELETE <path>?recursive=true` | Also delete the referring resources |
| `DELETE <path>?permanent=true` | Skip the archive, remove for good |
| `DELETE <path>?dryRun=true` | Report what would happen, change nothing |
| `POST <entry>.restore.json` | Restore an archive entry |
| `DELETE <entry>` | Purge an archive entry |
| `GET /Archive.entries.json` | List entries, paged, filtered, sorted |
| `GET /Archive.summary.json` | Counts for the last day, last week, and total |
| `GET <entry>.entry.json` | Describe one entry, and whether restoring or purging would work |

The deletion endpoint is bound to `data/Content`, i.e. every content resource; the
archive endpoints to `del/ArchiveEntry`, so users who cannot see the archive get a
plain 404 from resource resolution rather than a permission check.

All responses are JSON with `status.code`, a machine-readable `status` word, and
`status.message` where there is something to explain:

| Code | Statuses |
|---|---|
| 200 | `archived` (with `archiveEntry`, `items`, `removedLinks`), `deleted`, `dryRun` (full impact + `executable`), `restored` (restored paths) |
| 409 | `referenced` (`referrers` by type, `inaccessibleReferrers` count, summary), `vetoed` (each veto's path, reason, guard name), `conflict` (each `originalPath` with `PARENT_MISSING`, `OCCUPIED` or `NO_RIGHTS`) |
| 401/403 | The requester may not delete everything impacted |
| 400 | Unprocessable target — deleting `/Archive` content directly instead of purging, restoring a non-entry |
| 500 | Unexpected failure, with the exception's message |

A confirmation dialog is expected to send the plain `DELETE` first and, on a 409,
offer the listed consequences and retry with `recursive=true` — or start with
`dryRun=true` and present the impact up front.

## Vetoes

Any bundle may refuse a deletion by registering a `DeletionVeto`:

```java
public interface DeletionVeto
{
    @NotNull String getName();

    // Returns the reason to refuse, or null to allow
    @Nullable String veto(Node node, DeletionMode mode, Session requester) throws RepositoryException;

    default boolean judgesWholeOperation() { return false; }
}
```

Every impacted node is offered to every guard, with the kind of deletion under
consideration (`ARCHIVE`, `PERMANENT`, `PURGE`). **A veto that throws counts as a
veto** — when a guard cannot decide, the data stays.

`judgesWholeOperation() == true` marks a guard enforcing a blanket policy rather than
judging resources one by one; it is then asked only about the resource whose deletion
was requested. Without it, a policy refusing everything reports one identical
objection per node of every impacted subtree.

The requester's session is what lets a guard answer "*who* may do this" as opposed to
"*what* may be done": `getUserID()`, and `JackrabbitSession.getBoundPrincipals()` for
what the session acts as. The node itself is read through the privileged
`iap-deletion` session and may well be invisible to the requester, whose own rights
are checked separately, node by node, before any guard is consulted. **Guards must not
write through either session.**

> Prefer bound principals to a `UserManager` membership lookup. With
> `user.dynamicMembership` enabled — which is how this platform is configured — an
> identity provider's roles reach the repository as principals with **no local group
> node**, so `getAuthorizable(id).memberOf()` reports that a Keycloak role's members
> belong to nothing at all. Bound principals cover local groups and provider-supplied
> roles alike.

Three guards ship with the module.

**`UndeletableVeto`** — the `del:Undeletable` mixin marks a resource that must never
be deleted: not archived, not permanently removed, not purged if it somehow reaches
the archive. Lifted only by removing the mixin. A marked node *anywhere* in the
impacted set, descendants included, blocks the whole operation, and every objection is
reported with its path and reason.

**`PermanentDeletionVeto`** (`Permanent deletion policy`) refuses the two deletions
that leave nothing to restore, while leaving archiving untouched — a user refused here
can still delete the resource recoverably.

| Setting | Default | Meaning |
|---|---|---|
| `preventPermanentDeletion` | `false` | Ban `PERMANENT` **and** `PURGE`. Guarding only `PERMANENT` would leave the ban defeatable in two ordinary steps: archive, then purge |
| `allowedPrincipals` | `{}` | User ids and principal names exempt, groups and IdP principals included. An **exemption, never a grant** — an exempt user still needs the rights any deletion requires, and with the ban off the list means nothing. Empty with the ban on refuses everybody |

It judges the operation, so it declares `judgesWholeOperation()` and a refusal reports
one objection against the requested resource.

**`ArchiveRetentionVeto`** (`Archive retention`) sets `minimumRetentionDays`, the
minimum age in calendar days an entry must reach before it may be purged. Default `0`
imposes no floor. Raising it only ever prevents destruction — nothing purges anything
automatically, so this is a floor under purging, not a schedule for it. Unlike the
policy above it keys on the archive entry's own type, because only the entry carries
the timestamp it needs.

## The archive

Impacted subtrees are **moved** into a new `del:ArchiveEntry`, wrapped in one
`del:DeletedItem` per subtree recording its `originalPath`. The entry records
`deletedBy` (the writes are performed by the service user, so `jcr:createdBy` cannot
say) and `requestedPath`.

Entries are not direct children of `/Archive`. Each is named with a UUID and filed by
`PrefixTree` (`iap-java-utils`) under buckets named after its first characters —
`/Archive/3f/a9/1c/3fa91c48-…` — which keeps one parent from holding every deletion
ever performed. The buckets are `del:Archive` nodes themselves. **Nothing should assume
the depth**: use the entry path the service returns, or look entries up by node type.

An entry is also addressable as **`/Archive/by-id/<uuid>`**, translated back to its
bucket path by the generic `PrefixTreeResourceProvider` mounted there. What comes back
is the real resource, with its real path: the short form is an address, not an
identity, and a resource whose `getPath()` disagreed with the node behind it would trap
everything that adapts to a `Node`. So `path` in the API stays the stored one and a
second field, `shortPath`, carries the address to link a reader to.

### Identity is preserved

A move preserves node identifiers, so references between archived resources — and
references *into* the archive from resources archived earlier — stay intact. That is
what makes restoring possible. Hiding archived content is therefore access control's
job, not broken references': `/Archive` grants nothing to `everyone`, which keeps it
out of regular reads *and query results*.

> **Caution for service code:** privileged sessions with broad read access see
> archived content, which keeps its original node types. Queries under such sessions
> should exclude it, e.g. `AND NOT ISDESCENDANTNODE([/Archive])`.

Two consequences:

- Archiving a resource that archived content still references is fine, both ways.
  **Permanently** deleting a resource referenced from the archive is refused, since
  honoring the reference would quietly mutilate somebody's archive entry. Such
  blockers are counted, never named ("… and 2 other items you cannot see"), because
  their contents are not the requester's to see.
- Archived *links* pointing at a permanently deleted resource are the exception: a
  link is bookkeeping, not content, so hard ones are removed to let the deletion
  commit.

### Restore and purge

Restoring moves every archived item back to its recorded original path and removes the
emptied entry. **All-or-nothing**: if any item's original parent is gone, its path is
occupied, or the user may not create it there, nothing changes and every conflict is
reported. Links removed by the original deletion are **not** recreated.

Purging removes the entry and everything in it permanently. Guards are consulted again
with the `PURGE` mode.

### Reading the archive

Restore and purge both take the entry's path, and that path is unpredictable: an entry
is named with a random UUID and buried under buckets derived from it. A client cannot
work out where an entry lives, so it has to read the archive to find out. The read
endpoints are bound to `del/Archive`, hence addressable on the archive root or on any
bucket under it; listing a bucket is occasionally useful when diagnosing and costs
nothing to support.

`entries.json` pages with `offset`/`limit`, filters on `requestedPath` or `deletedBy`
(case-insensitive `filter`), and sorts by `jcr:created`, `deletedBy` or
`requestedPath` with `descending`. **The effective sort is echoed back**, because an
unrecognised column falls back to the default rather than failing the request. Rows
carry `path`, `requestedPath`, `deletedBy`, `created`, `itemCount`, the `originalPaths`
of everything in the entry, and `shortPath`.

`summary.json` returns just three counts — `last24Hours`, `lastWeek` and `total` —
for callers that want the size of the archive without fetching rows they will not
display.

`entry.json` is a **preflight**: the same fields as a listing row, plus `restorable`
with the `restoreConflicts` that would block a restore and `purgeable` with the
`purgeVetoes` that would block a purge. It runs the very evaluations the operations
run — `checkRestore` shares `ArchiveOperations.evaluateRestore` with `restore`, and
`checkPurge` shares the veto sweep with `purge` — so a preflight and the operation
cannot disagree about what is possible.

A preflight is a snapshot, not a promise: another deletion can occupy a path and a
retention floor expires, so the operations evaluate again rather than trusting it. A
refusal after a clean preflight is ordinary, and the entry page re-reads itself when
one arrives.

Listings count with a bound (`ArchiveSearch.MAX_SCAN`, 10000) and report
`approximate`/`totalIsApproximate` when they stopped early, so an archive grown without
limit costs a fixed amount to page through. Both run under the **requester's own**
session, so they can only show what that user could already read — which is what makes
the endpoints' 404 rule hold here too, with no separate permission check to keep in
step.

### UI

Three extensions in `modules/deletion/src/main/frontend`, no pages of their own:

| Component | Point | Shows |
|---|---|---|
| `ArchiveWidget` | `iap/adminDashboard/entry` | The three `summary.json` counts. The way through is the dashboard frame's header action, declared by `ext:actionLabel`/`ext:targetURL` rather than drawn by the widget |
| `ArchiveBrowser` | `iap/coreUI/view` at `/admin/archive` | Filterable, sortable table with per-entry restore and purge, in `AdminScreen` chrome |
| `ArchiveEntryView` | `iap/coreUI/view` at `/admin/archive/*` | One entry, what it holds, and the preflight per item in words |

**The console route and the repository path are different strings, deliberately.** The
browser sits at `/admin/archive/<entry>` while the endpoints answer on
`/Archive/by-id/<entry>`. `archiveApi.ts` owns both constants and the two conversions,
so they cannot drift into a route that navigates somewhere real and fetches from
somewhere that is not. That route also covers the prefix-tree buckets, which are not
entries; the endpoint says so and the page reports it rather than rendering an empty
entry.

`/Archive` and each entry are real nodes carrying the scripts that serve the
application shell, which is what lets these views be opened directly as well as
navigated to. Nothing about the page is bespoke — the shell decides what it looks
like, and the resource's readability decides who may open it.

The widget needs no persona restriction: the console is reached only by those who can
read its extensions. Reaching the console is still not the same as being allowed to
read the archive, so a user whose rights do not match is told the archive is
unavailable rather than shown three zeros.

### Telling a reader that a resource was deleted

A link to something that has been archived is a dead link, and a bare "this page does not exist" is
both unhelpful and untrue. The platform's 404 page says what became of the path instead, and it says
it in the response the reader was already getting: `DeletionMetadata`
(`io.uhndata.iap.deletion.scripting`) is a HTL Use-API that the error handler declares, which looks
the requested path up while the page is being rendered and leaves the answer on the mount container
as `data-deleted-at`, `data-deleted-by` and `data-entry-url`. HTL drops an attribute whose value is
empty, so a path that was never there carries none of the three.

The lookup runs through the deletion service session, because the readers it exists for are exactly
the ones who cannot resolve `/Archive` at all, and the helper decides for itself what to disclose.
**Any authenticated reader** is told that the path was deleted and when. **A reader who can read the
archive entry** additionally gets `deletedBy` and a link through to the entry's own page. The test
for the second is a plain read of the entry through the requester's own session, so there is no
second notion of who may see the archive to keep in step with the repository's.

Nothing here offers to restore anything. The entry's own page already states what a restore or a
purge would do before either is attempted, and that is where the decision belongs; the 404 page
links to it rather than growing a second, unguarded copy of the action.

The client half is `PageNotFound`, in `frontend-commons` — a pure component that renders what the
entry point read off the container. It knows nothing about deletion beyond those three attributes,
a page carrying none of them is the plain "not found".
