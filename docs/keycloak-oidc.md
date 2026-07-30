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
| Bundles + all OSGi config (connection, handler, claim mapping, crypto, Oak sync, external login module) | [core/oidc.json](../packaging/slingfeature/src/main/features/core/oidc.json) |
| `ExternalPrincipalConfiguration` added to the security provider's required services | [oak/oak_base.json](../packaging/slingfeature/src/main/features/oak/oak_base.json) |
| Entry-point + callback exempted from the auth requirement | [core/sling-configuration.json](../packaging/slingfeature/src/main/features/core/sling-configuration.json) |
| "Institutional account" sign-in button on the login page | [Keycloak.json](../modules/login/src/main/resources/SLING-INF/content/Extensions/SignInMethod/Keycloak.json) (renders the existing `RedirectSignIn`) |

## Runtime environment variables

Resolved by the configadmin interpolation plugin (declared in `boot.json`). Set them wherever the
Sling process runs (Docker/K8s env, systemd unit, etc.):

| Variable | Example | Notes |
| --- | --- | --- |
| `KEYCLOAK_BASE_URL` | `https://keycloak.example.org/realms/iap` | Realm root; endpoints are read from its OIDC discovery document. |
| `KEYCLOAK_CLIENT_ID` | `iap-sling` | The confidential client below. |
| `KEYCLOAK_CLIENT_SECRET` | (secret) | The client's secret; never commit it. |
| `IAP_PUBLIC_URL` | `https://iap.example.org` | Public base URL of IAP; used to build the callback URI. |
| `IAP_OAUTH_ENCRYPTION_PASSWORD` | (secret) | Key for encrypting any OAuth token stored in the user's JCR home. |

## Keycloak realm setup

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

## How the names line up

Three independent names are wired across the configs; the defaults use `keycloak` for all three:

- **connection** `keycloak` — the OIDC provider connection (`OidcConnectionImpl.name`,
  `OidcAuthenticationHandler.defaultConnectionName`, `SlingUserInfoProcessorImpl.connection`, and
  the entry-point query parameter `?c=keycloak`).
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

1. `docker compose up` Keycloak; create realm `iap`, client `iap-sling`, the `groups` mapper, and a
   test user with role `writer`.
2. Set the five env vars and start IAP.
3. In Keycloak's token inspector (or decode the ID token), confirm the `groups` claim is a flat
   list containing `writer` **before** wiring anything else — this is the most common failure point.
4. Visit a protected path unauthenticated → the login page's "Continue with institutional
   credentials" button (or the auth-required redirect) sends you to Keycloak → sign in → you return
   to where you were headed.
5. Confirm provisioning: the user authorizable appears under `/home/users/oidc` with a
   `rep:externalPrincipalNames` property listing `writer`.
6. Confirm authorization: the user can read/write `/content/studies/cardiology`; a user without the
   role gets `403`.
7. Regression: local login still works via "Use a local account instead".

## Caveats

- `org.apache.sling.auth.oauth-client` is early-stage (0.1.x). Pin the version; expect config churn
  on upgrade and re-verify the property names against the bundle you move to.
- Dynamic membership is **not** exercised by the module's own integration test (which does full
  sync). It is the higher-risk part of this setup — validate steps 5–6 against a live instance. To
  fall back to full sync, set `user.dynamicMembership: false` in `core/oidc.json` and remove the
  `ExternalPrincipalConfiguration` entries from `core/oidc.json` and `oak_base.json`.
- `user.propertyMapping` claim names (`name`, `email`) are best-effort profile mapping; verify
  against a real token and adjust. They do not affect authentication or authorization.
