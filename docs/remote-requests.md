# Requests from another instance

An IAP instance accepts requests from another instance whose public key it holds. The
[token module](../modules/authentication/token/) mints and verifies the JWTs that say
who is calling, and this module acts on them.

It serves one request for now, as a working example: `POST /system/remote` with a valid
token creates an empty node under `/remote`.

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

{"status": "created", "path": "/remote/a1b2c3"}
```

The token may stand alone or follow a `Bearer ` prefix, in any letter case.

| Status | When                                                                                            |
| ------ | ----------------------------------------------------------------------------------------------- |
| `201`  | The node was created; `Location` and the body carry its path                                    |
| `400`  | The body is not a JSON object, has no `path`, or `path` is not 1 to 64 ASCII letters and digits |
| `401`  | No token, a token that does not verify, or one that has expired or has no `exp` claim           |
| `409`  | A node already exists at that path                                                              |
| `500`  | The service user or `/remote` is missing, or the write failed                                   |

An error answers `{"status": "error", "error": "<reason>"}`.

## What guards it

Sling requires no session on this path (`sling.auth.requirements=-/system/remote`). The
token is the only credential, and three checks stand before the repository:

- The token must verify against a key this instance holds: its own, or a registered
  peer's.
- The name must be one segment of ASCII letters and digits.
- The write is done by a service user that can only write under `/remote`.

The endpoint checks who signed the token, never what the token's subject may do. Any
peer with a valid token may create a node. A request that does more than this one will
have to check the subject first.

## Registering a peer

An instance trusts a peer whose public key it stores at
`/jcr:system/iap-jwt/<fingerprint>`. The node holds the key in a `verify` property, and
the issuer the peer will claim in an `iss` property. The fingerprint is the hexadecimal
SHA-256 of the key's BASE64 X.509 encoding. The peer puts it in the `kid` header of its
tokens.

The token module writes that node through `POST /system/jwt/peers`:

```
POST /system/jwt/peers
Content-Type: application/json

{"issuer": "https://peer.example.org", "key": "<BASE64, PEM armour optional>"}
```

```
201 Created

{"status": "registered", "kid": "9f86d081884c7d65..."}
```

Only the `admin` user may call it. The key must be an RSA public key of at least 2048
bits. It is parsed and re-encoded before hashing, so its fingerprint does not depend on
how it was pasted.

The `issuer` must equal the token's `iss` claim exactly: `https://peer.example.org` and
`https://peer.example.org/` are different issuers. Registration refuses an issuer that:

- is blank, or longer than 2048 characters;
- starts or ends with whitespace;
- contains a control character;
- contains a `:` but is not an absolute URI.

For an IAP peer, register the `identity` property of its **JWT token manager**
configuration, normally its public base URL. An instance puts its identity in the `iss`
of every token it mints. It accepts only a token whose `aud` names its identity, so a
peer addresses its tokens to that value. The feature sets the identity from the
`IAP_PUBLIC_URL` environment variable, which also gives the Keycloak sign-in its
callback. Without that variable the identity is `http://localhost:8080`, which suits
only a development instance.

Several keys may share an issuer, so an old and a new key can coexist. Registration only
adds: a key already registered answers `409`. To rotate a peer's key, register the new
one, then delete the old node by hand. No endpoint removes one.
