# Keycloak / OIDC sign-in

IAP can sign users in through an external **Keycloak** instance over OpenID Connect.
Keycloak holds the users and their **realm roles**. Each role becomes an Oak group
principal that ACLs can name.

The Apache Sling OAuth client (`org.apache.sling.auth.oauth-client`, pinned to
**0.1.6**) does the work. It carries an Oak `ExternalIdentityProvider`,
`OidcIdentityProvider`. Oak's own `DefaultSyncHandler` provisions each user who signs in
through Keycloak.

No configuration creates `OidcIdentityProvider`, and it has no component descriptor.
`OidcAuthenticationHandler.activate()` registers it under the handler's `idp` property.
The IdP exists **only while that handler is active**, and the handler needs a
`CryptoService`. When the handler cannot activate, the only symptom is a login loop,
with Oak logging `No IDP found with name keycloak` on every login attempt. See
[Troubleshooting](#troubleshooting-no-idp-found-with-name-keycloak).

Almost everything is configuration. The only Java is the logout handling in the
`iap-oidc-support` module (see [Sign-out flow](#sign-out-flow)). Keycloak sign-in is
**opt-in** (see [Enabling Keycloak sign-in](#enabling-keycloak-sign-in)). The pieces:

| Concern                                                                                                                                                           | Where                                                                                                           |
| ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| Bundles + all OSGi config (connection, handler, claim mapping, crypto, Oak sync, external login module, logout) **and** the `/oidc-login` trigger node (repoinit) | [oidc/support feature.json](../modules/authentication/oidc/support/src/main/features/feature.json)              |
| `ExternalPrincipalConfiguration` added to the security provider's required services                                                                               | [oak/oak_base.json](../packaging/slingfeature/src/main/features/oak/oak_base.json)                              |
| The "Institutional account" sign-in method: a button leading to `/oidc-login`, drawn by the login module's `RedirectSignIn`                                       | [Keycloak.json](../modules/keycloak/src/main/resources/SLING-INF/content/Extensions/SignInMethod/Keycloak.json) |

## Sign-in flow

A signed-out user still lands on the branded `/login` page. Its "Continue with
institutional credentials" button leads to Keycloak. Sling's authentication core asks
its handlers in one order: the most **specific** path first, then the highest service
ranking. It asks each in turn to `requestCredentials` (start a login) until one does. It
asks each in turn to `extractCredentials` (read an existing session) until one finds
credentials.

The OIDC handler is registered at **both `/` and `/oidc-login`**, with a **low
`service.ranking`**:

- **A signed-out request for any page:** at `/` the form handler outranks the OIDC
  handler. It starts the login, and the user sees `/login`.
- **The button, leading to `/oidc-login`:** the OIDC handler's `/oidc-login` path is
  more specific than the form handler's `/`. The OIDC handler starts the Keycloak round
  trip, whatever its ranking.
- **After sign-in:** the form handler finds no credentials of its own, so the OIDC
  handler reads the `sling.oidcauth` session cookie (set at `Path=/`). The sign-in holds
  on **every page**.

```
/login ──button──▶ GET /oidc-login ─(OIDC requestCredentials)─▶ Keycloak
                                                                   │
        GET / (session cookie honoured app-wide) ◀── /system/sling/oauth/callback
                        ▲                                (OIDC extractCredentials: token exchange,
                        │                                 provisioning, sets sling.oidcauth cookie)
             /oidc-login is a sling:redirect → / that bounces the now-authenticated user to the app
```

`/oidc-login` is a `sling:redirect` node (target `/`, readable by everyone) created by
the OIDC support feature's repoinit. The OAuth client returns the user to the path that
started the login, and this node redirects them on to the home page.

## Sign-out flow

The OAuth client's `dropCredentials` does nothing. `/system/sling/logout`, the Sign Out
target, ends the Sling session but leaves the `sling.oidcauth` cookie in place.
Keycloak's own SSO session also outlives the logout. The next "Continue with
institutional credentials" click would sign the user straight back in with no password
prompt. Two pieces in the OIDC support bundle end both:

- **`OidcLogoutAuthenticationHandler`**, registered at `/`. Sling calls its
  `dropCredentials` on every logout. When the `sling.oidcauth` cookie is present, the
  user signed in through Keycloak. The handler then expires the cookie and ends
  Keycloak's SSO session **back-channel**: it reads the user's stored refresh token and
  posts it to Keycloak, server to server. A Keycloak that cannot be reached delays the
  logout by up to 10 seconds, and one that does not answer by up to 30, before the
  fallback. A local logout never involves Keycloak.
- **`OidcEndSessionServlet`**, at `/system/sling/oauth/logout`, which needs no
  authentication. The **fallback**, used only when the back-channel call fails. It
  redirects the browser to Keycloak's `end_session_endpoint` with `client_id` and a
  registered `post_logout_redirect_uri`.

The redirect can be **abandoned**. Without an `id_token_hint`, Keycloak asks the user to
confirm the logout. A user who closes the tab there stays signed in to Keycloak, and the
next sign-in click logs them straight back in. The back-channel call asks nothing of the
user, and Keycloak's session is gone before the logout's response is written. The OAuth
client does not store the ID token, so the redirect cannot send an `id_token_hint`.

```
Sign Out ──▶ GET /system/sling/logout
   └─ OidcLogoutAuthenticationHandler.dropCredentials  (only when the cookie was present)
        ├─ expire sling.oidcauth
        ├─ read + decrypt the refresh token from the user's home
        └─ POST {BACKEND_KEYCLOAK_REALM_URL}/protocol/openid-connect/logout
             ?client_id=…&client_secret=…&refresh_token=…
             ├─ accepted ──▶ SSO session ended; Sling's default post-logout redirect applies
             └─ failed ──▶ set resource=/system/sling/oauth/logout, and
                  redirectAfterLogout ──▶ OidcEndSessionServlet
                       ──▶ {FRONTEND_KEYCLOAK_REALM_URL}/protocol/openid-connect/logout
                            ?client_id=…&post_logout_redirect_uri={IAP_PUBLIC_URL}/login
                            └─ Keycloak prompts to end the SSO session ──▶ GET /login
```

**The back-channel call needs the stored refresh token.** Two settings store it.
`storeRefreshToken` on the OAuth client's UserInfo processor encrypts it into the
credentials. The `oauth/refresh_token=refresh_token` entry in the sync handler's
`user.propertyMapping` copies it onto the user's home node. Without the mapping the
token is dropped at sync, and every logout takes the fallback path without a word. The
handler reads the token from the path in its `refreshTokenPath`, which must match the
mapping.

The two calls use different Keycloak URLs. IAP makes the back-channel POST itself,
through **`BACKEND_KEYCLOAK_REALM_URL`**. The browser follows the fallback redirect,
through **`FRONTEND_KEYCLOAK_REALM_URL`**.

The handler sets the request's `resource` attribute to the servlet's path. Sling
redirects to that path once the handlers have dropped their credentials. Sling's
`AuthUtil.isRedirectValid` refuses any target containing `://`, and Sling then redirects
to `/` instead. The servlet makes the redirect to Keycloak's host.

The fallback's `post_logout_redirect_uri` (`{IAP_PUBLIC_URL}/login`) must be registered
on the Keycloak client as a **Valid post logout redirect URI**. Otherwise Keycloak
refuses it, and shows its own logout page instead of returning to IAP.
`keycloak_setup.sh` registers it, in the client's `post.logout.redirect.uris` attribute.

## Enabling Keycloak sign-in

Keycloak sign-in comes from two features, `iap-oidc-support` and `iap-keycloak`. They
are not in the `core_*` aggregates that `start.py` and Docker launch, so an instance
shows only the local credentials form unless asked for them:

- **Docker:** set `KEYCLOAK_ENABLED=true` in the container's environment, and
  `docker_entry.sh` adds both features to the launcher.
- **Dev (`start.sh`/`start.py`):** pass `--keycloak`.

The `test_tar` and `demo_tar` aggregates include both features. The integration tests
that run against them see the Keycloak button.

The "Institutional account" sign-in method is enabled by default. It is ordered before
the local credentials form, so loading the features makes Keycloak the primary way to
sign in. Set the [runtime environment variables](#runtime-environment-variables) as
well, since the OIDC handler needs them.

## Runtime environment variables

The configadmin interpolation plugin, declared in `boot.json`, resolves them. Set them
wherever the Sling process runs, such as a Docker or Kubernetes environment or a systemd
unit:

| Variable                        | Example                                   | Notes                                                                                                                                                                                   |
| ------------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FRONTEND_KEYCLOAK_REALM_URL`   | `https://keycloak.example.org/realms/iap` | Realm root as the **browser** reaches it: the issuer, and the base for the authorization and end-session (logout) endpoints.                                                            |
| `BACKEND_KEYCLOAK_REALM_URL`    | `http://keycloak:8080/realms/iap`         | Realm root as **IAP** reaches it in-network: the base for the token, JWKS, userinfo, and back-channel logout endpoints. Same as the frontend URL when IAP and Keycloak share a network. |
| `KEYCLOAK_CLIENT_ID`            | `iap-sling`                               | The confidential client below.                                                                                                                                                          |
| `KEYCLOAK_CLIENT_SECRET`        | (secret)                                  | The client's secret; never commit it. Must match the client's Credentials in Keycloak exactly.                                                                                          |
| `IAP_PUBLIC_URL`                | `https://iap.example.org`                 | Public base URL of IAP, without a trailing slash.                                                                                                                                       |
| `IAP_OAUTH_ENCRYPTION_PASSWORD` | (secret)                                  | Key for encrypting any OAuth token stored in the user's JCR home. Any non-empty value in dev. If unset, the crypto service fails to activate, and so does the OIDC handler.             |

## Keycloak realm setup

For a running Keycloak,
[`tools/dev/keycloak/keycloak_setup.sh`](../tools/dev/keycloak/keycloak_setup.sh)
automates all of the steps below (realm, client, roles, and the `groups` mapper) and
prints the `KEYCLOAK_*` env block to paste into IAP's environment. Run
`keycloak_setup.sh --help` for its options. The steps it implements:

1. **Realm**: create a realm, such as `iap`. Its name is the realm segment in the
   `*_KEYCLOAK_REALM_URL` values.
2. **Client**: create a confidential client:
   - Client ID: `iap-sling` (match `KEYCLOAK_CLIENT_ID`).
   - Client authentication: **On** (confidential). Copy the secret into
     `KEYCLOAK_CLIENT_SECRET`.
   - Standard flow: **enabled** (authorization code flow).
   - **Valid redirect URI**: `<IAP_PUBLIC_URL>/system/sling/oauth/callback`. It must
     match the `callbackUri` in the OIDC support feature exactly.
3. **Roles**: define realm roles for your access tiers, such as `reader`, `writer` and
   `admin`, and assign them to users or groups.
4. **The groups claim (required)**: by default Keycloak puts realm roles under
   `realm_access.roles`, with no flat `groups` claim. The OAuth client's UserInfo
   processor reads a flat list from the claim named by `groupsClaimName` (`groups`). Add
   a client or client-scope protocol mapper that copies realm roles into a top-level
   `groups` claim:
   - Mapper type: **User Realm Role** (`oidc-usermodel-realm-role-mapper`).
   - Token Claim Name: `groups`
   - Multivalued: **On**; Add to ID token: **On**. The groups are read from the ID token
     (`groupsInIdToken: true`).

   > For a different claim name, change `groupsClaimName` in the OIDC support feature to
   > match.

## Running locally with Docker Compose

[`tools/deploy/generate_compose.py`](../tools/deploy/generate_compose.py) writes a Compose file
running IAP alongside whatever it needs to talk to; `--keycloak` adds a Keycloak container and sets
the environment IAP reaches it with. See [tools/deploy/README.md](../tools/deploy/README.md) for the
other options.

Keycloak has to be running before its realm can be created, so it comes up ahead of everything else:

```bash
cd tools/deploy
python3 generate_compose.py --storage postgres --keycloak
docker compose up -d keycloak
ENV_FILE=$PWD/.env ../dev/keycloak/keycloak_setup.sh --write-env
docker compose up -d
```

Until step 3 has run, IAP starts but no one can sign in. `keycloak_setup.sh` still lives in
`tools/dev/keycloak/`; `ENV_FILE` points it at the `.env` the generator wrote, where `--write-env`
fills in the client id, secret and `FRONTEND_KEYCLOAK_REALM_URL` — so there is no
copy-the-secret-by-hand step. It leaves `BACKEND_KEYCLOAK_REALM_URL` alone, the in-network URL it
cannot infer, which the generator has already set.

The Keycloak admin console is at `localhost:8084` as `admin`, with the password in `.env`. An
existing `.env` is only ever topped up, never rewritten, so re-running the generator will not
clobber the secret written in step 3. `docker compose down` stops everything and keeps the
repository; `./cleanup.sh` throws it all away, realm included.

The two realm URLs in `.env` encode the front/back-channel split: `BACKEND_KEYCLOAK_REALM_URL` is
IAP's in-network view (`http://keycloak:8080/...`, Keycloak's **container** port) while
`FRONTEND_KEYCLOAK_REALM_URL` is the browser's view and the issuer (`http://localhost:8084/...`,
the **host-published** port). Getting the back-channel port wrong (using `8084`) is a common trip-up
— that port only exists on the host, not inside the Docker network.

The Keycloak comes with a test user by default (username/password test/test). This can be disabled by
generating a `docker-compose.yml` file via running `python tools/deploy/generate_compose.py --keycloak`
and setting the environment variable KEYCLOAK_GENERATE_TEST_USER to 0 before running `keycloak_setup.sh`.

## How the names line up

Three names tie the configurations together. The feature uses `keycloak` for all three:

- **connection** `keycloak`: the OIDC provider connection (`OidcConnectionImpl.name`,
  `OidcAuthenticationHandler.defaultConnectionName`,
  `SlingUserInfoProcessorImpl.connection`). The handler uses `defaultConnectionName`
  when the request names no connection.
- **idp** `keycloak`: the `ExternalIdentityProvider` the handler registers
  (`OidcAuthenticationHandler.idp` = `ExternalLoginModuleFactory.idp.name` =
  `SlingLoginCookieManager.idpName`).
- **sync handler** `keycloak`: links the JAAS login module to the sync handler
  (`ExternalLoginModuleFactory.sync.handlerName` = `DefaultSyncHandler.handler.name`).

## Authorization: ACLs keyed on Keycloak roles

Sync runs with **dynamic membership** (`user.dynamicMembership: true`). The user's
Keycloak roles are stored as `rep:externalPrincipalNames`, not as local group nodes.
`ExternalPrincipalConfiguration` exposes them as principals, so ACLs can name them.
Synced users live under `/home/users/oidc`.

`idpNameInPrincipals` is **false**, so a role principal is the plain role name. Write
ordinary resource-based ACLs, for example in a repoinit such as
[base-repoinit.txt](../packaging/slingfeature/src/main/features/base-repoinit.txt):

```
set ACL on /content/studies/cardiology
    allow jcr:read for "reader"
    allow jcr:read,rep:write for "writer"
end
```

`importBehavior` is `besteffort` (see `oak_base.json`), so an ACE can name a role
principal before anyone carrying the role has signed in. **Decide `idpNameInPrincipals`
once.** Changing it renames every principal, and the existing ACLs stop matching.

> Note: principal-_based_ authorization covers only `/home/users/system/sling`, the
> service users. It does **not** apply to Keycloak users, who need resource-based ACLs
> as above.

## Local login fallback

The external login module registers as `SUFFICIENT` (Oak default), so a local username/password
login it can't handle falls through to Oak's `LoginModuleImpl`. The login page keeps the local
credentials form behind the "Use a local account instead" link.

To stop offering the form, set `ext:defaultDisabled` to `true` on
`/Extensions/SignInMethod/CredentialsForm`. This only hides the form. Sling's form
authentication handler still accepts a local username and password posted to
`/j_security_check`.

## Verification

1. Bring up Keycloak. Create realm `iap`, client `iap-sling`, the `groups` mapper, and a
   test user with a role, such as `test`/`test` with `reader`. `keycloak_setup.sh` does
   all of this.
2. Set the six env vars and start IAP: `mvn clean install`, then
   `./start.sh --keycloak`.
3. In Keycloak's token inspector, or by decoding the ID token, confirm that the `groups`
   claim is a flat list holding the role. Check this **before** anything else: it is
   where setups most often fail.
4. Visit `/` signed out. You get the branded `/login` page, with "Continue with
   institutional credentials" as its main button. Click it and sign in at Keycloak. You
   land on the home page.
5. Confirm the session holds on every page: in `/system/sling/info.sessionInfo.json`,
   `userID` is the synced Keycloak user, not `anonymous`. A `sling.oidcauth` cookie at
   path `/` is set.
6. Confirm provisioning: the user appears under `/home/users/oidc`, with a
   `rep:externalPrincipalNames` property listing the role.
7. Confirm authorization once ACLs are written: the user can read or write the granted
   path. A user without the role gets `403`, not a redirect to the login page.
8. Regression: local login still works through "Use a local account instead".
9. Confirm logout: open `/system/sling/logout`, and check that Keycloak shows no prompt.
   Then re-check `/system/sling/info.sessionInfo.json`: the user is back to `anonymous`.
10. Confirm the back-channel logout: in Keycloak's admin console, open the `iap` realm's
    users (by default `http://localhost:8084/admin/master/console/#/iap`). Open the test
    user, and check that its Sessions tab lists no session.
11. Check it again from the `/login` page: click "Continue with institutional
    credentials", and confirm that Keycloak asks for your credentials again.

## Troubleshooting: `No IDP found with name keycloak`

```
*ERROR* ...external.impl.ExternalLoginModule No IDP found with name keycloak. Will not be used for login.
```

Sign-in loops back to `/login`. Keycloak authenticates and the callback sets
`sling.oidcauth`, but `extractCredentials` cannot produce a session without the IdP, and
Sling sends the request back to the login page.

The IdP has no configuration, so it cannot be _misconfigured_. The message means
`OidcAuthenticationHandler` did not activate. The cause is earlier in the log, from
startup: a reference the handler could not satisfy.

```
OidcAuthenticationHandler(...) : Error during instantiation of the implementation object:
    Unable to get service for reference $005
```

`$005` is the mandatory `CryptoService`. The usual reason is a Jasypt stack failing to
activate:

```
JasyptStandardPbeStringCryptoService(...) : The activate method has thrown an exception
    (java.lang.RuntimeException: environment variable 'SLING_COMMONS_CRYPTO_PASSWORD' not set)
```

**Read the variable name in that message carefully.** `IAP_OAUTH_ENCRYPTION_PASSWORD` is
this feature's variable. `SLING_COMMONS_CRYPTO_PASSWORD` belongs to
`iap-email-notifications`, which configures a second, independent Jasypt stack. That
module is in the default build, so the two stacks run side by side whenever Keycloak
sign-in is enabled. An untargeted `1..1` reference binds the service with the highest
`service.ranking`, and among equals the lowest `service.id`. The `service.id` follows
the order the features install in. Two kinds of reference are therefore pinned:

- **Which `PasswordProvider` and generators each crypto service uses:**
  `passwordProvider.target`, `ivGenerator.target` and `saltGenerator.target`, on both
  stacks.
- **Which `CryptoService` each consumer uses:** the OAuth client's references have
  generated names (`$000`, `$005`, …) that can change on upgrade, so they cannot be
  targeted. Instead, `~iapoauth` has a `service.ranking` that draws them to it.
  `SimpleMailService`'s `cryptoService` reference is _greedy_, and would follow that
  ranking and decrypt the SMTP password with the wrong key. `iap-email-notifications`
  pins it with `cryptoService.target`.

When a `CryptoService` reference binds the wrong stack, `JcrUserHomeOAuthTokenStore`,
`OAuthCallbackServlet` and `OAuthEntryPointServlet` sit at `failed activation` in
`/system/console/components.json`. `OidcAuthenticationHandler` stays `satisfied` and
never reaches `active`. Keep the filters and the ranking when adding a third crypto
stack or copying one of these configurations.

## Caveats

- `org.apache.sling.auth.oauth-client` is at 0.1.x. Its configuration may change between
  versions: re-check the property names against any new version before upgrading.
- **After sign-in the user always lands on `/`.** `/oidc-login` is a static
  `sling:redirect` to `/`. A user sent to `/login` from a deep link lands on the home
  page, not on that link.
- No automated test here exercises dynamic membership. Check steps 6 and 7 against a
  live instance. To use full sync instead, set `user.dynamicMembership: false` in the
  OIDC support feature, and remove the `ExternalPrincipalConfiguration` entries from
  that feature and from `oak_base.json`.
- The profile entries of `user.propertyMapping` (`name`, `email`) may need adjusting:
  check them against a real token. They do not affect authentication or authorization.
  Removing the `oauth/refresh_token` entry breaks the back-channel logout, which then
  always takes the redirect (see [Sign-out flow](#sign-out-flow)).
- The refresh token is stored, encrypted, on the user's home node. It is a credential at
  rest, so `IAP_OAUTH_ENCRYPTION_PASSWORD` must be a real secret in production. Changing
  that password leaves every stored token undecryptable, and logout takes the redirect
  until users sign in again.
- The OIDC handler covers `/`, so Sling asks it to read the session cookie on every
  request the form handler finds no credentials in.
