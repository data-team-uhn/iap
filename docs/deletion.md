# Deletion

**Module:** `modules/deletion` · **Bundle:** `iap-deletion` (start-order 27) ·
**Packages:** `…deletion.api` (`DeletionService`), `…deletion.spi` (`DeletionVeto`)

Deleting a resource is more than removing a node: other resources may reference it,
link to it, or depend on it. The deletion service first works out everything a
deletion affects. Then it either refuses with an explanation, or deletes it all at
once. By default nothing is removed for good: deleted resources move into the
**archive** at `/Archive`, to be **restored** or **purged** later.

## What a deletion affects

Starting from the requested resource, the service follows:

- **Containment.** A deleted node takes its whole subtree.
- **Incoming references.** A `REFERENCE` or `WEAKREFERENCE` to a deleted node blocks
  a deletion that is not recursive. The blocking resources are reported grouped by
  type ("referenced by 3 submissions (S-1, S-2, S-3) and 1 schema (Onboarding)"). A
  recursive deletion deletes them too.
- **[Links](links.md).** A link to a deleted resource follows its definition's
  `onDelete`:

| Policy | Effect |
|---|---|
| `REMOVE_LINK` | Removes the link, and its backlink if it has one |
| `RECURSIVE_DELETE` | Deletes the resource holding the link too; blocks the deletion like a reference when it is not recursive |
| `IGNORE` | Leaves a weak link pointing at nothing. **Not allowed on a hard link**, which is removed as `REMOVE_LINK` with a warning |

A link never outlives its definition: deleting a `link:Definition` removes its links,
whatever the policy.

Each node is scanned once, so the walk ends even when resources reference each other,
such as a link and its backlink whose definitions both say `RECURSIVE_DELETE`. A
resource reached twice is deleted once.

## Java API

```java
@NotNull DeletionImpact  analyze(Resource item, DeletionOptions options);   // dry run
@NotNull DeletionResult  delete(Resource item, DeletionOptions options);
@NotNull DeletionResult  deleteWithin(Resource item, DeletionOptions options); // left to commit
@NotNull RestoreResult   restore(Resource archiveEntry);
@NotNull DeletionResult  purge(Resource archiveEntry);

// What would block a restore or a purge now, checked as the operations check it
@NotNull List<RestoreConflict> checkRestore(Resource archiveEntry);
@NotNull List<Veto>            checkPurge(Resource archiveEntry);
```

The options are `DeletionOptions.recoverable()` or `DeletionOptions.of(recursive,
permanent)`. `recursive` deletes the referencing resources too, and `permanent` skips
the archive. Each flag has named values, `DELETE_RECURSIVELY`/`REFUSE_IF_REFERENCED`
and `DELETE_PERMANENTLY`/`MOVE_TO_ARCHIVE`, so a call reads
`DeletionOptions.of(REFUSE_IF_REFERENCED, MOVE_TO_ARCHIVE)`. `DeletionImpact` lists the
subtrees to delete, the links to remove, the vetoes, the blocking referrers, and a
sentence describing the referrers.

`DeletionResult.Status` is `ARCHIVED` (with the entry path), `DELETED`, `VETOED`,
`REQUIRES_CONFIRMATION` (blocking referrers) or `DENIED` (missing permissions). After
`deleteWithin`, `getCheckedOut()` lists the versionable nodes it checked out, for the
caller to check back in after its commit.
**A refusal is a return value, never an exception.** `DeletionException` reports a
failure: a repository error, a missing service user.

`DeletionService` also holds the path and node type constants (`ARCHIVE_PATH`,
`ENTRY_NODETYPE`, `UNDELETABLE_MIXIN`, `DELETED_BY_PROPERTY`, …). Use those rather
than string literals.

**Two sessions are in play.** Every operation checks permissions against the session
of the resource passed in. The requesting user needs `remove` on every node leaving
the live tree, and `add_node` at the original paths for a restore. The `iap-deletion`
service user scans for referrers, including those the requesting user cannot see, and
writes the archive. `deleteWithin` writes through the resolver the item came from
instead, which needs write access to the archive. It commits nothing: the deletion
commits or rolls back with the caller's other changes. Removing a link needs no write
access to the resource that holds it.

## HTTP API

| Request | Meaning |
|---|---|
| `DELETE <path>` | Archive it, refusing if anything other than a removable link references it |
| `DELETE <path>?recursive=true` | Also delete the referencing resources |
| `DELETE <path>?permanent=true` | Skip the archive, remove for good |
| `DELETE <path>?dryRun=true` | Report what would happen, change nothing |
| `POST <entry>.restore.json` | Restore an archive entry |
| `DELETE <entry>` | Purge an archive entry |
| `GET /Archive.entries.json` | List entries, paged, filtered, sorted |
| `GET /Archive.adminSummary.json` | Counts for the last day, the last week, and in total |
| `GET <entry>.entry.json` | Describe one entry, and whether a restore and a purge would succeed |

The deletion endpoint is bound to `data/Content`, so it serves every content
resource. The listing and the counts are bound to `del/Archive`, and the other archive
endpoints to `del/ArchiveEntry`. A user who cannot read the archive gets a 404 from
Sling's resource resolution, with no permission check of its own.

Every command answers in JSON with `status.code`, a `status` word a client can switch
on, and `status.message` when there is something to explain:

| Code | Statuses |
|---|---|
| 200 | `archived` (with `archiveEntry`, `items`, `removedLinks`), `deleted`, `dryRun` (the full impact, and `executable`), `restored` (the restored paths) |
| 409 | `referenced` (`referrers` by type, the `inaccessibleReferrers` count, the sentence), `vetoed` (each veto's path, reason and guard name), `conflict` (each `originalPath` with `PARENT_MISSING`, `OCCUPIED` or `NO_RIGHTS`) |
| 401/403 | `denied`: the requesting user may not delete everything the deletion affects |
| 400 | `invalid`: the target cannot be deleted at all, such as content inside `/Archive`, which is purged rather than deleted |
| 404 | `missing`: the resource has no JCR node |
| 500 | `failed`, with the exception's message |

A confirmation dialog sends `dryRun=true` first and shows the impact. Or it sends the
plain `DELETE`, and on a 409 lists the consequences and retries with
`recursive=true`.

## From a workflow

A workflow deletes its target with the `delete` service task, which goes through the
same service. It archives unless the activity sets `permanent`, and it refuses
whatever the service refuses. It never deletes recursively, since a workflow may
delete only the entity it targets. Referenced or vetoed content stays, and the event
is answered with a 409 carrying the service's explanation. Discarding a schema is one
such workflow: its only step is `delete`.

The step deletes with `deleteWithin`, on the engine's session. The engine commits the
deletion with the rest of the delivery, or reverts it with it. The nodes the step
checks out are checked back in after that commit.

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

Every guard is asked about every node the deletion removes, with the kind of deletion
(`ARCHIVE`, `PERMANENT`, `PURGE`). **A guard that throws counts as a veto**, so the
data stays when a guard cannot decide.

A guard whose `judgesWholeOperation()` is `true` enforces a policy about the whole
deletion. It is asked only about the requested resource. Asked about every node, it
would raise one identical objection per node of every subtree.

The requester's session says *who* is deleting: `getUserID()`, and
`JackrabbitSession.getBoundPrincipals()` for their groups and roles. The node itself is
read through the `iap-deletion` session, and the requesting user may not be able to
see it. Their own rights are checked separately, node by node, before any guard is
asked. **A guard must not write through either session.**

> Use bound principals, not a `UserManager` membership lookup. This platform enables
> `user.dynamicMembership`, so an identity provider's roles reach Oak as principals
> with **no local group node**. `getAuthorizable(id).memberOf()` then reports that a
> Keycloak role's members belong to nothing. Bound principals cover local groups and
> identity provider roles alike.

Three guards ship with the module. Other modules add their own, such as the schemas
module's `ConditionDependencyVeto` (see [schemas.md](schemas.md)).

**`UndeletableVeto`**: the `del:Undeletable` mixin marks a resource that must never
be deleted: not archived, not deleted for good, and not purged if it is in the
archive. Only removing the mixin lifts the protection. A marked node *anywhere* in
what the deletion removes, descendants included, blocks the whole deletion. Each
objection is reported with its path and reason.

**`PermanentDeletionVeto`** (`Permanent deletion policy`) refuses the two deletions
that leave nothing to restore. Archiving stays open, so a user refused here can still
delete the resource into the archive.

| Setting | Default | Meaning |
|---|---|---|
| `preventPermanentDeletion` | `false` | Ban `PERMANENT` **and** `PURGE`. Banning only `PERMANENT` would let anybody archive, then purge |
| `allowedPrincipals` | `{}` | User ids and principal names exempt from the ban, groups and identity provider roles included. **An exemption, never a grant**: an exempt user still needs the rights any deletion needs. With the ban off, the list does nothing. Empty with the ban on refuses everybody |

Its `judgesWholeOperation()` is `true`, so a refusal is one objection against the
requested resource.

**`ArchiveRetentionVeto`** (`Archive retention`) sets `minimumRetentionDays`, the
number of calendar days an entry must spend in the archive before it may be purged.
The default, `0`, sets no minimum. Nothing purges automatically, so this only ever
prevents a purge. It judges the archive entry by its `jcr:created`, since the content
archived in it keeps its own.

## The archive

The subtrees a deletion removes **move** into a new `del:ArchiveEntry`, each wrapped
in a `del:DeletedItem` recording its `originalPath`. The entry records `deletedBy`,
since `jcr:createdBy` names the service user that wrote it, and `requestedPath`.

Entries are not direct children of `/Archive`. Each is named with a UUID and filed by
`PrefixTree` (`iap-java-utils`) under buckets named after its first characters:
`/Archive/3f/a9/1c/3fa91c48-…`. No single node holds every deletion ever made. The
buckets are `del:Archive` nodes too. **Nothing should assume the depth**: use the
entry path the service returns, or query entries by node type.

An entry also answers at **`/Archive/by-id/<uuid>`**, through the
`PrefixTreeResourceProvider` mounted there. What comes back is the stored resource,
with its stored path: the short form is an address, not an identity. A resource whose
`getPath()` differed from the path of its node would break everything that adapts it
to a `Node`. So `path` in the API is the stored path, and `shortPath` carries the
address to link a reader to.

### Identity is preserved

A move keeps node identifiers, so references between archived resources stay intact.
So do references *into* the archive from resources archived earlier. That is what
makes a restore possible. Access control hides archived content: `/Archive` grants
nothing to `everyone`, which keeps it out of ordinary reads *and query results*.

> **Caution for service code:** a service user that can read everything also reads
> the archive, where content keeps its node types. Its queries should exclude it, such
> as with `AND NOT ISDESCENDANTNODE([/Archive])`.

Two consequences:

- A resource that archived content references can be archived. Deleting it **for
  good** is refused, since it would break the reference in somebody's archive entry.
  These blockers are counted, never named ("… and 2 other items you cannot see"),
  because their content is not the requesting user's to see.
- An archived *link* to a resource deleted for good does not block it. A hard one is
  removed, so that Oak accepts the commit.

### Restore and purge

A restore moves every archived item back to its original path and removes the emptied
entry. **All or nothing**: if any item's original parent is gone, its path is taken,
or the user may not create it there, nothing changes and every conflict is reported.
Links removed by the deletion are **not** recreated.

A purge removes the entry and everything in it for good. The guards are asked again,
with the `PURGE` mode.

### Reading the archive

A restore and a purge take the entry's path, and nothing outside the archive can work
that path out: an entry is named with a random UUID and filed under buckets derived
from it. A client reads the archive to find it. The listing and the counts are bound
to `del/Archive`, so they answer on the archive root and on any bucket under it.

`entries.json` pages with `offset`/`limit`, filters on `requestedPath` or `deletedBy`
(`filter`, ignoring case), and sorts by `jcr:created`, `deletedBy` or `requestedPath`
with `descending`. **The answer says which sort it used**, since an unknown column
falls back to the default rather than failing the request. Each row carries `path`,
`requestedPath`, `deletedBy`, `created`, `itemCount`, the `originalPaths` of everything
in the entry, and `shortPath`.

`adminSummary.json` returns three counts, `last24Hours`, `lastWeek` and `total`, for
a caller that wants the size of the archive without its rows.

`entry.json` is a **preflight**: the fields of a listing row, plus `restorable` with
the `restoreConflicts` that would block a restore, and `purgeable` with the
`purgeVetoes` that would block a purge. It runs the checks the operations run.
`checkRestore` shares `ArchiveOperations.evaluateRestore` with `restore`, and
`checkPurge` asks the guards as `purge` does. A preflight and the operation therefore
agree about what is possible.

A preflight holds only until something changes. Another deletion can occupy a path,
and an archived resource can gain `del:Undeletable`, so the operations check again. A
refusal after a clean preflight is ordinary, and the entry's page reads the entry
again when one arrives.

A listing and the counts stop counting at `ArchiveSearch.MAX_SCAN` (10000) entries,
and report `approximate` or `totalIsApproximate` when they stopped there. Both run
with the **requesting user's own** session, so they show only what that user can read.

### UI

Three extensions in `modules/deletion/src/main/frontend`, with no pages of their own:

| Component | Point | Shows |
|---|---|---|
| `ArchiveWidget` | `iap/adminDashboard/entry` | The three `adminSummary.json` counts. The dashboard frame's header action, from `ext:actionLabel`/`ext:targetURL`, leads to the archive's page |
| `ArchiveBrowser` | `iap/coreUI/view` at `/admin/archive` | A table to filter and sort, with a restore and a purge for each entry, in `AdminScreen` |
| `ArchiveEntryView` | `iap/coreUI/view` at `/admin/archive/*` | One entry, what it holds, and in words whether each item can be restored |

**The console route and the endpoint path are different strings.** An entry's page is
at `/admin/archive/<entry>`, while its endpoints answer at `/Archive/by-id/<entry>`.
`archiveApi.ts` holds both roots and the two conversions, so that a route never
fetches from a path that does not exist. A route that names no entry gets an error
message, rather than an empty entry.

The administration console serves every route under `/admin` with the application
shell, so the archive's pages can be opened directly as well as navigated to. Who may
read the archive's content is decided by its access control.

The widget needs no persona restriction: the console is reached only by those who can
read its extensions. A user who reaches the console but cannot read the archive is
told the archive is unavailable, rather than shown three zeros.

### Telling a reader that a resource was deleted

A link to an archived resource is a dead link. For a reader who can open the archive
entry, or who deleted the resource, the 404 page says it was deleted.
`DeletionMetadata` (`io.uhndata.iap.deletion.scripting`) is the HTL helper that the
error handler uses to add `data-deleted-at`, `data-deleted-by` and `data-entry-url` to
the page.

**It is split in two.** `DeletionMetadata` reads the request. `DeletedPathDisclosure`,
an OSGi service implemented in this bundle, reads the archive and decides what this
reader may be told.

The lookup reads the archive through the `iap-deletion` service user's session:

- **A reader who can read the archive entry** gets `deletedAt`, `deletedBy` and a link
  to the entry's page.
- **The user who deleted the resource**, if they cannot read the entry, gets
  `deletedAt` and no link.
- **Anybody else** gets the plain "not found" page.

The client half is `PageNotFound`, in `frontend-commons`. It renders what the entry
point read off the container, and knows nothing about deletion beyond those three
attributes. A page carrying none of them is the plain "not found".
