# Requests from another instance

Two IAP instances that know each other's public keys can act on each other's behalf. The
[token module](../modules/authentication/token/) mints and verifies the JWTs that say who is
calling; this module is the first thing on the receiving side that does anything with one.

What it does today is deliberately small: `POST /system/remote` with a valid token creates an
empty node under `/remote`. The point is not the node. It is to have a working example of a
request that arrives with no session, is authenticated by a token alone, and is still confined to
a corner of the repository — the shape every later remote-triggered operation has to fit.

## The endpoint

```
POST /system/remote
Authorization: <jwt>
Content-Type: application/json

{"path": "a1b2c3"}
```

```
201 Created
Location: /remote/a1b2c3

{"path": "/remote/a1b2c3"}
```

The `Bearer ` scheme prefix is accepted and ignored; a bare token works too, since the peer is a
machine and not a browser.

| Status | When |
| --- | --- |
| `201` | The node was created; `Location` and the body carry its path |
| `400` | The body is not a JSON object, has no `path`, or `path` is not a usable name |
| `401` | No token, a token that does not verify, or one that has expired |
| `409` | Something already exists at that path |
| `500` | The service user or `/remote` is missing, or the write failed |

A refusal is a JSON body with a single `error` entry. Nothing distinguishes an unknown signer from
a bad signature or an expired token: all three are `401`, because a caller that cannot authenticate
has no business learning which of its assumptions was wrong.

## Why it is safe to leave open

Sling is told not to require a session for this path (`sling.auth.requirements=-/system/remote`),
so the request arrives anonymous and the token is the whole of the authentication. Three things
stand between that and the repository.

**The token must verify against a key this instance already holds.** `TokenManager.parse` resolves
the signer from the token's `kid` header — this instance's own key, or a peer's under
`/jcr:system/iap-jwt/` — and then checks that the `iss` claim matches what that key node says the
peer calls itself and that this instance is among the `aud`. A correctly signed token from a peer
nobody has registered gets nowhere, because there is no key to check it against.

**The name must be one alphanumeric segment.** `/remote` is concatenated with the requested name,
so the validation is what keeps the result inside the subtree. Rejecting everything
non-alphanumeric takes out the slash that would address another subtree, the period that `..` is
built from, and the colon a JCR namespace prefix needs, all at once — and it leaves nothing that
would need escaping later.

**The write is done by a service user that can only reach `/remote`.** This is the part that does
not depend on the other two being correct. `iap-remote-requests` holds `jcr:read,jcr:write` on
`/remote` and nothing else, so a flaw in the name checking still cannot touch content that matters.
Nobody else is granted anything on `/remote` at all: the endpoint is the only way in.

Authentication is not authorization, and this endpoint has no authorization to speak of — a peer
holding a valid token may do the one thing it does. A remote operation that could affect real
content will need to ask who the token's subject is before acting, not just whether it verifies.

## Registering a peer

A peer is trusted by storing its public key under `/jcr:system/iap-jwt/<fingerprint>`, with the
key in a `verify` property and the issuer it will claim in an `iss` property. The fingerprint is
the SHA-256 of the BASE64-encoded key, which is also what the peer puts in its tokens' `kid`
header — that is how the locator finds the right node without trusting anything in the token
beyond a lookup key it validates as alphanumeric first.
