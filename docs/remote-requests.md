# Requests from another instance

Two IAP instances that know each other's public keys can act on each other's behalf. The
[token module](../modules/authentication/token/) mints and verifies the JWTs that say who is
calling; this module is the first thing on the receiving side that does anything with one.

Currently we only have a working example of the request: `POST /system/remote` with a valid token
creates an empty node under `/remote`. Later work will expand on remote access rights, etc.

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

| Status | When                                                                                  |
| ------ | ------------------------------------------------------------------------------------- |
| `201`  | The node was created; `Location` and the body carry its path                          |
| `400`  | The body is not a JSON object, has no `path`, or `path` is not a usable name          |
| `401`  | No token, a token that does not verify, or one that has expired or has no `exp` claim |
| `409`  | Something already exists at that path                                                 |
| `500`  | The service user or `/remote` is missing, or the write failed                         |

Errors appear as a JSON body with a single `error` entry.

## Why it is (currently) safe to leave open

Sling is told not to require a session for this path (`sling.auth.requirements=-/system/remote`),
so the token is the whole of the authentication. Three things stand between that and the repository.

- The token must verify against a key this instance already holds.
- The name must be one alphanumeric segment.
- The write is done by a service user that can only reach `/remote`.

Authentication is not authorization, and this endpoint has no authorization to speak of — a peer
holding a valid token may do the one thing it does. Further work with the remote endpoint
will need to ask who the token's subject is before acting, not just whether it verifies.

## Registering a peer

A peer is trusted by storing its public key under `/jcr:system/iap-jwt/<fingerprint>`, with the
key in a `verify` property and the issuer it will claim in an `iss` property. The fingerprint is
the SHA-256 of the BASE64-encoded key, which is also what the peer puts in its tokens' `kid`
header.

That node is written through `POST /system/jwt/peers`, which belongs to the token module rather
than this one, because trusting an instance is not the same thing as letting it do something:

```
POST /system/jwt/peers
Content-Type: application/json

{"issuer": "https://peer.example.org", "key": "<BASE64, PEM armour optional>"}
```

```
201 Created

{"kid": "9f86d081884c7d65..."}
```

This endpoint is **administrator only**. The fingerprint is computed from the submitted key,
and the key is parsed and re-encoded before hashing.

The `issuer` is compared with the token's `iss` claim exactly, with no normalization:
`https://peer.example.org` and `https://peer.example.org/` are treated as different issuers.
Issuer names must not contain any of the following:

- blank values, and values over 2048 characters;
- leading or trailing whitespace
- control characters
- a value containing a `:` (absolute URIs only).

For an IAP peer, the issuer to register is that peer's configured identity: the `identity`
property of its **JWT token manager** configuration, normally its public base URL. An instance
uses that one value both as the `iss` of every token it mints and as the `aud` a token must name
for it to accept it, so it is also the value peers must address their tokens to. It is taken
from the `IAP_PUBLIC_URL` environment variable, the same public base URL the Keycloak sign-in
builds its callback from, and falls back to `http://localhost:8080` when that is unset, which only
suits a development instance.

Several keys may be registered with the same issuer; that is what lets an old and a new key coexist
during rotation. Registration is add-only. To rotate a peer's key, register the new one and
remove the old node by hand (there is no endpoint for removal yet).
