# User profiles and settings

What IAP records about a person is **configurable content**, not a fixed set of columns. An
`profile:FieldDefinition` node under `/ProfileFields` declares one thing that may be recorded —
of what type, who may read it, and who may change it — and the profile API projects the catalogue
together with a given account's values and the rights the requester actually has over each of them.

The values themselves belong to the **account**, and live as properties under the user's home node:
`profile/*` for facts about the person, `preferences/*` for how they want the application to behave.
A profile is one-to-one with an account and is never linked across institutions: somebody with two
affiliations authenticates through two identity providers, with two email addresses, and therefore
has two unrelated profiles.

## Defining a field

A definition is a `profile:FieldDefinition` child of the `/ProfileFields` homepage (an
`profile:FieldsHomepage` created by repoinit, world-readable). Modules contribute definitions
through their initial content.

| Property | Type | Meaning |
| --- | --- | --- |
| `name` | String | The identifier the API uses; defaults to the definition node's name |
| `label` | String | Display name, defaults to the field name |
| `description` | String | Explanation shown alongside the field |
| `kind` | String | `profile` (a fact about the person) or `preference` (how they want the application to behave); also decides the default storage location |
| `storage` | String | Where the value lives, as a path inside the account's home node, e.g. `profile/email` or `rep:fullname`; defaults to `profile/<name>` or `preferences/<name>` |
| `dataType` | String | `text`, `long`, `double`, `boolean` or `date` |
| `pattern` | String | Optional regular expression a value must match in full |
| `allowedValues` | String[] | Optional closed set, offered as a choice in the UI |
| `required` | Boolean | Whether a value must be provided |
| `multiple` | Boolean | Whether more than one value may be provided |
| `writableBy` | String | `owner` (the person and user administrators), `admin`, or `nobody` |
| `readableBy` | String | `authenticated` (anyone signed in), `self` (the person and user administrators), or `admin` |
| `idpClaim` | String | The identity provider claim this field is imported from |
| `category` | String[] | Sections the field is displayed under, e.g. `identity`, `contact` |
| `order` | Long | Optional position, lower first, unordered last |
| `system` | Boolean | Maintained by the platform: the API will not change it |

The catalogue documents itself, so `/ProfileFields.doc.md` and `/ProfileFields.doc.json` are the
authoritative description of a given deployment's fields, and no code is needed to read it.

### The vocabularies fail closed

`kind`, `dataType`, `writableBy` and `readableBy` are parsed, not trusted. An **absent** value takes
the default declared by the node type. A value **outside** the accepted set is a broken definition
rather than a synonym for the default: the getter still answers, with that same default, so that
nothing has to cope with a half-read definition, but `isUsable()` turns false,
`getConfigurationProblems()` says what is wrong in terms the person who authored the node can act
on, and the profile API withholds the field entirely — neither readable nor writable — rather than
serve a value under rules nobody stated. A definition asking for something the platform cannot store
is a mistake worth surfacing, not a reason to guess. An invalid `pattern` is reported the same way,
because a regular expression that does not compile would otherwise fail at the moment somebody tries
to save, and so is a storage path that is not inside the account — absolute, or stepping out with
`..` — which would otherwise pass every rule here and then be refused when the change is committed.
That last one is checked against the path the field is actually read and written at, not against the
`storage` property alone: with `storage` unstated the path is derived from the field name, so a name
could otherwise step out of the account just as well.

## Imported from the identity provider

In a production deployment every account except a superadmin comes from an institutional identity
provider, and the provider is the source of truth for the attributes it carries. That is expressed
by `idpClaim`, and it is deliberately **not** a stored flag:

- Whether a *given* person's field is imported is decided at request time, from whether their
  account is external — `DefaultSyncContext.getIdentityRef(Authorizable)` returns the provider
  reference for a synced account, and `null` for a local one.
- So the same catalogue serves both. For a local demo account nothing is imported and everything is
  editable, without a second set of definitions.

Two consequences worth knowing:

- Oak's `DefaultSyncHandler` **blindly overwrites** every path named in `user.propertyMapping` on
  each re-sync, and **removes** the property when the provider stops supplying the claim. A local
  edit to an imported field would therefore last until the next sync at most, which is why the API
  refuses it outright.
- The mapping lives in OSGi configuration (`user.propertyMapping` on the `SyncHandler`) rather than
  being duplicated here, and nothing in the repository can check that content and configuration
  agree: a definition whose `storage` is not the path the synchronisation writes to is served as
  though it were imported while the value never arrives. Comparing the two — a health check reading
  the live `SyncHandler` configuration and warning on drift — is not built yet; see below.

## Permissions

Two mechanisms, each doing something the other cannot.

**Writes are stopped by a commit-time validator, not by ACLs.** Oak grants every user
`jcr:all` on their own home node at creation, and `jcr:all` aggregates `jcr:modifyAccessControl`, so
an ACL cannot protect anything inside a user home from that user — a deny for a group principal is
never even reached, because user entries are evaluated first, and a deny for their own principal can
simply be removed. Oak's `ExternalPrincipalConfiguration` with
`protectExternalIdentities: "Protected"` instead refuses, at commit time, any change inside the
subtree of an account carrying `rep:externalId`, for every session that is not a system session.
Validators run after permission evaluation, so no grant defeats them. The profile service is listed
in `systemPrincipalNames` and is therefore the only writer.

> This does **not** cover local accounts, which have no `rep:externalId`. For the demo superadmin the
> write rules are UI integrity rather than a security boundary. Given that production is
> remote-only, that is an accepted limit.

> Turning protection on also means **administrators can no longer hand-edit a synced user's home**,
> through a repository browser or through `POST /system/userManager/user/<id>.update.html`. Every
> administrative change goes through the profile API. When debugging a synchronisation problem, set
> `protectExternalIdentities: "Warn"`, which logs instead of failing.

**The writer's grant is restricted to the two subtrees, and that is why the containers are created up
front.** `iap-userprofile` is granted `rep:write` on `/home/users` restricted by glob to the `profile`
and `preferences` subtrees, plus `rep:fullname`, so it can maintain what a person's profile says and
nothing else — not their password, not their group membership, not the tokens an identity provider
keeps under the same home. A restriction of that shape cannot cover the containers themselves: adding
a child is authorized against the **parent**, the account's home node, and no glob ending in a
container name matches that. `rep:itemNames` cannot express it either, so the gap is not one a
different restriction closes.

So an [authorizable action](https://jackrabbit.apache.org/oak/docs/security/user/authorizableactionprovider.html)
gives every new account both containers at creation, and the grant stays as narrow as it was. It
implements only the password-carrying `onCreate` overload, which is the one every real account arrives
through — a synced one included, since the synchronisation creates it with no password. Oak reserves
the password-less overload for *system* users, which are service accounts that no profile describes.

**Reads are mediated.** `/home/users` is not readable by everyone: reading anybody else's values goes
through the profile service, which applies `readableBy` per field. A person keeps read access to
their own home through Oak's self-grant, so the self-service page needs nothing special.

## Not yet built

- **The drift health check.** Nothing yet compares each definition's `idpClaim` and `storage` against
  the live `user.propertyMapping` of the identity provider synchronisation, so a definition pointing
  at a path the provider does not write to is only found by noticing that the value never appears.
- **History.** `data:Entity` is versionable but a user home is not; a `profile` node can take
  `mix:versionable` itself, or a person can become a domain entity keyed by authorizable ID. The API
  is a projection rather than a serialization, so neither choice changes it.
- **Condition-based rules.** `writableBy`/`readableBy` are flat vocabularies. `cond:Conditionable`
  would express "editable when …" but `ConditionEvaluator` needs a `data:Content` context, and a user
  home cannot be adapted to one; that needs either a person entity or an `OperandResolver` for
  account properties.
- **Admin configuration UI.** The catalogue is content and already reads itself.
- Avatars or any binary, email-change and verification flows, group administration, and a people
  directory.
