# Keycloak / OIDC sign-in

IAP delegates authentication to an external **Keycloak** instance over OpenID Connect. Keycloak
is the source of truth for users and their **realm roles**; a role becomes an Oak group principal
that content ACLs are written against.

This is implemented with the Apache Sling OAuth client
(`org.apache.sling.auth.oauth-client`, pinned to **0.1.6**). That bundle ships an Oak
`ExternalIdentityProvider` (`OidcIdentityProvider`), so a user who signs in through Keycloak is
provisioned into Oak by Oak's own `DefaultSyncHandler` — no custom identity-provider code.

Everything is configuration; there is no new Java or Maven module. The pieces:

| Concern | Where |
| --- | --- |
| Bundles + all OSGi config (connection, handler, claim mapping, crypto, Oak sync, external login module) **and** the `/oidc-login` trigger node (repoinit) | [core/oidc.json](../packaging/slingfeature/src/main/features/core/oidc.json) |
| `ExternalPrincipalConfiguration` added to the security provider's required services | [oak/oak_base.json](../packaging/slingfeature/src/main/features/oak/oak_base.json) |
| "Institutional account" sign-in button (targets `/oidc-login`, renders login's generic `RedirectSignIn`) — **disabled by default**, see [Enabling the sign-in button](#enabling-the-institutional-sign-in-button) | [Keycloak.json](../modules/keycloak/src/main/resources/SLING-INF/content/Extensions/SignInMethod/Keycloak.json) |

## Sign-in flow

The branded `/login` page stays the default gate for unauthenticated users, and Keycloak is reached
by clicking its "Continue with institutional credentials" button. The mechanism relies on two facts
about Sling's authentication core: `requestCredentials` (which starts a login) is chosen by path
**specificity** first, then service ranking; but `extractCredentials` (which reads an existing
session) runs on **every** handler that covers the request path, regardless of ranking.

The OIDC handler is therefore registered at **both `/` and `/oidc-login`**, with a **low
`service.ranking`**:

- **Unauthenticated `/anything`** → the form handler outranks the OIDC handler at `/`, so it wins
  `requestCredentials` and shows `/login`. The gate is preserved.
- **The button → `/oidc-login`** → the OIDC handler's `/oidc-login` holder is *more specific* than
  the form handler's `/`, so it wins `requestCredentials` regardless of ranking and starts the
  Keycloak round trip.
- **After login** → the OIDC handler covers `/`, so its `extractCredentials` validates the
  `sling.oidcauth` session cookie (set at `Path=/`) on every request → the login is recognised
  **app-wide**.

```
/login ──button──▶ GET /oidc-login ─(OIDC requestCredentials)─▶ Keycloak
                                                                   │
        GET / (session cookie honoured app-wide) ◀── /system/sling/oauth/callback
                        ▲                                (OIDC extractCredentials: token exchange,
                        │                                 provisioning, sets sling.oidcauth cookie)
             /oidc-login is a sling:redirect → / that bounces the now-authenticated user to the app
```

`/oidc-login` is a `sling:redirect` node (target `/`, readable by everyone) created by the repoinit
in `core/oidc.json`. It exists because the OAuth client returns the user to the path that triggered
login; that landing path redirects on to the app.

## Enabling the institutional sign-in button

The "Institutional account" method ships **disabled** (`iap:defaultDisabled: true` in `Keycloak.json`),
so a deployment that hasn't configured Keycloak — including the bare-platform smoke tests — shows only
the local credentials form. Keycloak's plumbing (`core/oidc.json`) is part of the default `core_tar`
build, so the button's visibility can't be driven by which features are present; it's a deliberate
per-deployment switch, flipped once Keycloak is actually wired up.

Enable it with a Sling POST, **after IAP is running and Keycloak is configured** (it can't be done
during `keycloak_setup.sh`, which runs before IAP exists):

```bash
curl -u admin:admin \
  -F 'iap:defaultDisabled@TypeHint=Boolean' -F 'iap:defaultDisabled=false' \
  http://localhost:8080/Extensions/SignInMethod/Keycloak
```

The POST is idempotent, so a deployment can fold it into a provisioning step. **Re-apply it whenever
the login module's content is reloaded** — a fresh repository, a redeploy of the login bundle, or a
`mvn clean install` — because `overwriteProperties` resets the node to disabled. To hide the button
again, POST the same property as `true`.

## Runtime environment variables

Resolved by the configadmin interpolation plugin (declared in `boot.json`). Set them wherever the
Sling process runs (Docker/K8s env, systemd unit, etc.):

| Variable | Example | Notes |
| --- | --- | --- |
| `KEYCLOAK_BASE_URL` | `https://keycloak.example.org/realms/iap` | Realm root; endpoints are read from its OIDC discovery document. |
| `KEYCLOAK_CLIENT_ID` | `iap-sling` | The confidential client below. |
| `KEYCLOAK_CLIENT_SECRET` | (secret) | The client's secret; never commit it. Must match the client's Credentials in Keycloak exactly. |
| `IAP_PUBLIC_URL` | `https://iap.example.org` | Public base URL of IAP; used to build the callback URI. |
| `IAP_OAUTH_ENCRYPTION_PASSWORD` | (secret) | Key for encrypting any OAuth token stored in the user's JCR home. Any non-empty value in dev; if unset, the crypto service fails to activate and the OIDC handler won't come up. |

## Keycloak realm setup

For a running Keycloak, [`tools/dev/keycloak/keycloak_setup.sh`](../tools/dev/keycloak/keycloak_setup.sh)
automates all of the steps below (realm, client, roles, and the `groups` mapper) and prints the
`KEYCLOAK_*` env block to paste into IAP's environment. Run `keycloak_setup.sh --help` for its
options. The manual steps are documented here as the reference the script implements:

1. **Realm**: create a realm (e.g. `iap`) — this is the path segment in `KEYCLOAK_BASE_URL`.
2. **Client**: create a confidential client:
   - Client ID: `iap-sling` (match `KEYCLOAK_CLIENT_ID`).
   - Client authentication: **On** (confidential). Copy the secret into `KEYCLOAK_CLIENT_SECRET`.
   - Standard flow: **enabled** (authorization code flow).
   - **Valid redirect URI**: `<IAP_PUBLIC_URL>/system/sling/oauth/callback` — must match the
     `callbackUri` in `core/oidc.json` exactly.
3. **Roles**: define realm roles that map to your access tiers (e.g. `reader`, `writer`, `admin`)
   and assign them to users/groups.
4. **The groups claim (required)**: Keycloak does *not* emit a flat `groups` claim by default — it
   puts realm roles under `realm_access.roles`. The OAuth client's UserInfo processor reads a flat
   list from the claim named by `groupsClaimName` (`groups`). Add a client (or client-scope)
   protocol mapper that projects realm roles into a top-level `groups` claim:
   - Mapper type: **User Realm Role** (`oidc-usermodel-realm-role-mapper`).
   - Token Claim Name: `groups`
   - Multivalued: **On**; Add to ID token: **On** (we read groups from the ID token —
     `groupsInIdToken: true`).

   > If you prefer a different claim name, change `groupsClaimName` in `core/oidc.json` to match.

## Running locally with Docker Compose

[`tools/dev/keycloak/`](../tools/dev/keycloak/) has a Dockerised dev stack — two Compose files (kept
separate so Keycloak runs detached while IAP runs in the foreground) plus an env template:

- `docker-compose.keycloak.yml` — Keycloak on the shared `iap` network, published to the host at
  `127.0.0.1:8084`, with `KC_HOSTNAME` pinned so the issuer/front-channel URL is stable and
  `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true` so IAP can reach it in-network (see the front/back-channel
  split under [Caveats](#caveats) below).
- `docker-compose.iap.yml` — IAP on the same network.
- `.env.example` — the environment both files read; `keycloak_setup.sh --write-env` fills in a `.env`
  from it (client id, secret, front-channel URL). The real `.env` is gitignored — it holds the secret.

```bash
docker network create iap                              # once
cd tools/dev/keycloak
docker compose -f docker-compose.keycloak.yml up -d    # 1. Keycloak
./keycloak_setup.sh --write-env                        # 2. realm/client/roles; writes .env (secret + URLs)
docker compose -f docker-compose.iap.yml up            # 3. IAP (foreground; Ctrl+C to stop, `down` to remove)
```

`--write-env` creates `.env` from `.env.example` if absent and updates the client id, secret, and
`FRONTEND_KEYCLOAK_REALM_URL` in place — so there's no copy-the-secret-by-hand step. It leaves
`BACKEND_KEYCLOAK_REALM_URL` alone (the in-network URL it can't infer), which the template already
sets to `http://keycloak:8080/realms/iap`.

The two realm URLs in `.env` encode the front/back-channel split: `BACKEND_KEYCLOAK_REALM_URL` is
IAP's in-network view (`http://keycloak:8080/...`, Keycloak's **container** port) while
`FRONTEND_KEYCLOAK_REALM_URL` is the browser's view and the issuer (`http://localhost:8084/...`,
the **host-published** port). Getting the back-channel port wrong (using `8084`) is a common trip-up
— that port only exists on the host, not inside the Docker network.

## How the names line up

Three independent names are wired across the configs; the defaults use `keycloak` for all three:

- **connection** `keycloak` — the OIDC provider connection (`OidcConnectionImpl.name`,
  `OidcAuthenticationHandler.defaultConnectionName`, `SlingUserInfoProcessorImpl.connection`). The
  handler falls back to `defaultConnectionName` when no connection is specified on the request.
- **idp** `keycloak` — the `ExternalIdentityProvider` the handler registers
  (`OidcAuthenticationHandler.idp` = `ExternalLoginModuleFactory.idp.name` =
  `SlingLoginCookieManager.idpName`).
- **sync handler** `keycloak` — links the JAAS login module to the sync handler
  (`ExternalLoginModuleFactory.sync.handlerName` = `DefaultSyncHandler.handler.name`).

## Authorization: ACLs keyed on Keycloak roles

Sync runs with **dynamic membership** (`user.dynamicMembership: true`): the user's Keycloak roles
are stored as `rep:externalPrincipalNames` rather than as local group nodes, and
`ExternalPrincipalConfiguration` exposes them as principals so ACLs can reference them. Synced
users live under `/home/users/oidc`.

Because `idpNameInPrincipals` is **false**, a role principal is the plain role name. Write ordinary
resource-based ACLs (e.g. in a repoinit, like [base-repoinit.txt](../packaging/slingfeature/src/main/features/base-repoinit.txt)):

```
set ACL on /content/studies/cardiology
    allow jcr:read for "reader"
    allow jcr:read,rep:write for "writer"
end
```

`importBehavior` is `besteffort` (see `oak_base.json`), so you can author ACEs for a role principal
before anyone has logged in carrying it. **Decide `idpNameInPrincipals` once** — flipping it later
changes every principal name and invalidates existing ACLs.

> Note: principal-*based* authorization is scoped to `/home/users/system/sling` (service users)
> here, so it does **not** apply to human Keycloak users — use resource-based ACLs as above.

## Local login fallback

The external login module registers as `SUFFICIENT` (Oak default), so a local username/password
login it can't handle falls through to Oak's `LoginModuleImpl`. The login page keeps the local
credentials form behind the "Use a local account instead" link. To disable local login entirely,
remove the form's sign-in method (`Extensions/SignInMethod/CredentialsForm.json`).

## Verification

1. Bring up Keycloak; create realm `iap`, client `iap-sling`, the `groups` mapper, and a test user
   with a role (e.g. `test`/`test` with `reader`) — `keycloak_setup.sh` does all of this.
2. Set the five env vars and start IAP (`mvn clean install` then `./start.sh`).
3. In Keycloak's token inspector (or decode the ID token), confirm the `groups` claim is a flat
   list containing the role **before** wiring anything else — this is the most common failure point.
4. Enable the institutional sign-in button (see [Enabling the institutional sign-in button](#enabling-the-institutional-sign-in-button)),
   then visit `/` unauthenticated → you get the branded `/login` page. Click "Continue with
   institutional credentials" → Keycloak → sign in → you land back on the app.
5. Confirm the session is app-wide: open `/system/sling/info.sessionInfo.json` — `userID` should be
   the synced Keycloak user, not `anonymous`. A `sling.oidcauth` cookie at path `/` should be present.
6. Confirm provisioning: the user authorizable appears under `/home/users/oidc` with a
   `rep:externalPrincipalNames` property listing the role.
7. Confirm authorization once ACLs are written: the user can read/write the granted path; a user
   without the role gets `403` (not a login bounce).
8. Regression: local login still works via "Use a local account instead".

## Caveats

- `org.apache.sling.auth.oauth-client` is early-stage (0.1.x). Pin the version; expect config churn
  on upgrade and re-verify the property names against the bundle you move to.
- **Post-login always lands on `/`.** `/oidc-login` is a static `sling:redirect` to `/`, so a deep
  link that first bounced the user to `/login` will not return them to that exact page — they land
  on the home page. Honouring the original destination would need a small servlet that reads the
  `resource` parameter instead of the static redirect node.
- Dynamic membership is **not** exercised by the module's own integration test (which does full
  sync). It is the higher-risk part of this setup — validate steps 6–7 against a live instance. To
  fall back to full sync, set `user.dynamicMembership: false` in `core/oidc.json` and remove the
  `ExternalPrincipalConfiguration` entries from `core/oidc.json` and `oak_base.json`.
- `user.propertyMapping` claim names (`name`, `email`) are best-effort profile mapping; verify
  against a real token and adjust. They do not affect authentication or authorization.
- The OIDC handler's `extractCredentials` runs on every request (it covers `/`) to validate the
  session cookie. That's an intended cost of app-wide session recognition with a scoped login gate.
