# Submission transfer

A submission can be handed over whole to another IAP instance, which owns it from then on. The
instance it leaves keeps it only in its [archive](deletion.md#the-archive), tagged
`transferred`. This is how a frontdoor instance, where submissions are filled in, passes each
one to the site responsible for reviewing it.

Both instances must trust each other's tokens first; see
[registering a peer](remote-requests.md#registering-a-peer).

## Sending

```
POST /Submissions/ab/cd/<name>.transfer.json
  destination=https://site-b.example.org
```

The `transferSubmission` system workflow answers it, for `iap-administrators` only, and only for
a submission not already tagged `transferred`. Its steps:

1. **Send** the submission to the destination, and wait for its answer.
2. **Tag** it `transferred`, replacing its other lifecycle tags.
3. **Archive** it, as a [deletion](deletion.md) would.

Nothing changes here unless the destination takes the submission: a refusal stops the workflow
at the first step, and the submission stays as it was.

The `destination` must be the identity of a registered peer, exactly as it was registered,
so that an event cannot send a submission anywhere else. The submission's `createdBy` is the
submitter it is handed over for, and they must have an email: that is how the other instance
knows them.

## What travels

The submission and everything under it, node by node: its answers, its documents with every
version and every file, and its feedback. Left behind are what the repository maintains, the
workflow instances, which the receiving instance starts afresh, and whatever a local copy
leaves out, such as computed tags and links. The submission's history stays behind too.

Three kinds of value are translated on the way:

| Kind | Travels as | On arrival |
|---|---|---|
| Reference | The path of its target: relative inside the submission, absolute outside it | Points at whatever is at that path. A missing target refuses the submission, unless the reference is weak, in which case it is dropped |
| User (`createdBy`, `author`, `reviewer`, `checkedBy`, `jcr:lastModifiedBy`) | Their email | Whoever has that email. **Someone with no account there keeps their email** in place of an id |
| Creation time | `created` on each node | Kept in `originalCreated` |

References travel as paths because schemas and other shared content have the same paths on
every instance, but not the same identifiers. The submission itself keeps its name, which is
its identifier on both instances.

`jcr:created` cannot be set, so `Content.getCreated()` prefers `originalCreated` where there is
one, and the `originalCreated` [serialization processor](json-serialization.md#the-processors)
serves it as `jcr:created`. A node with no `createdBy` of its own sends its `jcr:createdBy` in
that role, unless that was a service user.

## The wire format

```
POST /system/remote/submissions
Authorization: Bearer <jwt>
Content-Type: multipart/form-data

manifest  application/json          {"version": 1, "root": NODE}
b0        application/octet-stream  the first binary
b1        application/octet-stream  ...
```

Each node of the manifest carries its name, type, mixins, creation time, properties and
children; a binary property names the part that carries it. `TransferFormat` documents the
grammar. A change an older reader would misread needs a new `version`, which that reader
refuses.

The token is minted for the destination only (`aud`), lasts five minutes, and carries the
submitter's email in an `email` claim. **Where the submission comes from, and whom it is for,
are read only from the token**: its `iss` and its `email`. The manifest names neither, so the
body cannot contradict what was signed.

## Receiving

The endpoint verifies the token, then:

1. finds the local user whose `profile/email` is the token's `email`;
2. checks whether a submission by that name is already here (see [sending again](#sending-again));
3. sends a `receive` event to `/Submissions` **on behalf of that user**, as the `iap-remote-peer`
   service user.

`iap-remote-peer` is a member of `iap-delegates`, which lets it
[act on behalf of someone else](workflows.md#acting-on-behalf-of-someone-else). It can read
accounts, but not their passwords, and submissions, but nothing inside them. It writes nothing
itself.

The `receiveSubmission` system workflow answers the event. Its first step refuses anyone but
`iap-remote-peer`, so that the event cannot be sent over ordinary HTTP. Its steps:

1. **Rebuild** the submission from the manifest and its parts, recording the sending instance
   in `origin`.
2. **Create parts** for anything the local schema version asks for that the submission lacks.
3. **Mark completeness.**
4. **Start the workflow** of the submission's schema version.

The engine records the submitter as the submission's `createdBy`, as it does for any content
created on someone's behalf. The submission keeps the tags it had when it was sent. History
records both ends, with the instance named in the role: `sent to <destination>` on the sending
instance, `received from <origin>` on the receiving one.

## Answers

The endpoint answers in the [usual shape](java-development.md#answering-a-request):

| Status | `status` | When | The sender |
|---|---|---|---|
| `201` | `created` | Taken in; `path` says where | Tags and archives its copy |
| `409` | `alreadyReceived` | Already here, from the same instance; `path` says where | Tags and archives its copy |
| `409` | `error` | Nobody here has the submitter's email; a different submission by that name is here; the workflows refused it as it stands | Refuses, with the destination's reason (409) |
| `400`, `403` | `error` | The request or the manifest is malformed, or the workflows refused the sender | Refuses, with the destination's reason (400) |
| `401` | `error` | No token, or one that does not verify or has expired | Refuses (400) |
| `500` | `error` | The destination failed; the reason is in its log | Fails (500) |

The sender also fails when the destination cannot be reached, and refuses before sending
anything when the destination is not a registered peer (400), or the submission has no submitter
or the submitter has no email (409).

### Sending again

The destination commits the submission before the sender tags and archives its own copy. If the
sender then fails to commit, both instances hold the submission, and the sender's copy is
neither tagged nor archived. **Sending it again finishes the job**: the destination answers
`alreadyReceived`, without taking it in a second time, and the sender tags and archives as though
it had been taken.

The destination tells a resend from a new submission by the name and the `origin` recorded on
the one it has. The same name from a different instance is refused as a different submission.

## Known gaps

- **Peers are trusted completely.** A registered peer can hand over a submission for anyone
  whose email it knows, and the endpoint does not ask whether that person should be submitting
  to it.
- **Sorting by creation time uses the arrival time.** Queries read `jcr:created`, not
  `originalCreated`, so the submissions grid sorts and filters a transferred submission by when
  it arrived.
- **One request, thirty seconds.** The whole submission goes in one request, under
  [the shared client's](http-requests.md) timeout. The format is versioned so that it can be
  split into one request per document later.
- **Delegation through Keycloak roles is untried.** If `iap-administrators` comes to be filled
  from a Keycloak role, it is not yet known whether its membership of `iap-delegates` still counts.
