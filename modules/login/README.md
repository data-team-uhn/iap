# IAP login page

The landing page served to unauthenticated visitors at `/login`, and the only page they can
reach — everything else requires a session (`sling.auth.requirements` in
`packaging/slingfeature/src/main/features/core/sling-configuration.json`, with this module's
repoinit granting `everyone` read on `/login`). Sling's form authentication is pointed at it
through the `form.login.form` property in this module's `feature.json`; the form submits to
`/j_security_check` and then navigates to the validated `?resource=` return path.

The page is a split landing page: a muted brand panel introducing the platform, and a paper
panel with the sign-in action. The auth action area is composed from the
`iap/login/signInMethod` extension point (see
[Sign-in methods](#sign-in-methods)), so an identity provider integration replaces or joins
the credentials form through content alone.

## Configuration reference

All texts are content, delivered as meta tags through the standard `/libs/iap/conf` pipeline:

| Property | Node | Renders as |
| --- | --- | --- |
| `tagline` | `/libs/iap/conf/LoginPage` | Small uppercase line under the logo |
| `introText` | `/libs/iap/conf/LoginPage` | The description paragraph (markdown) |
| `signInLabel` | `/libs/iap/conf/LoginPage` | Eyebrow label above the sign-in heading |
| `signInHeading` | `/libs/iap/conf/LoginPage` | The sign-in heading |
| `title` (AppName) | `/libs/iap/conf/AppName` | The display heading of the brand panel |
| `logoLight`/`logoDark`, `affiliationLogo*` | `/libs/iap/conf/Media` | Logo top-start; affiliation in the footer credits |

`tagline`, `signInLabel`, and `signInHeading` are seeded as repoinit defaults; `introText`
ships as initial content (`SLING-INF/content/libs/iap/conf/LoginPage.json`).

### Sign-in methods

The ways of signing in are `iap/login/signInMethod` extensions, rendered in their configured
order: the first enabled method in place, any further ones collapsed behind a quiet link
labelled by their `iap:collapsedLabel`. This module registers the local credentials form as
the default method; if no method is registered (or the point fails to load), the page falls
back to the credentials form directly, so there is always a way to sign in.

An identity-provider integration (e.g. the planned Keycloak module) is content-only on the
frontend side — register the generic redirect method with a lower order than the credentials
form (100), which then collapses into its "Use a local account instead" link:

```json
{
  "jcr:primaryType": "iap:Extension",
  "iap:extensionPointId": "iap/login/signInMethod",
  "iap:extensionName": "Institutional sign-in",
  "iap:extensionRenderURL": "asset:iap-login.RedirectSignIn.js",
  "iap:defaultOrder": 10,
  "iap:targetURL": "/goto-external-login",
  "iap:actionLabel": "Continue to sign-in",
  "iap:hint": "You will be redirected to your institution's sign-in page."
}
```

The redirect method navigates to `iap:targetURL` with the validated in-app return path
attached as its `resource` parameter, for the authentication endpoint to round-trip. Where
local accounts should not be offered at all, disable the credentials form with
`iap:defaultDisabled: true` on its extension node.

A demo registration of the redirect method (pointing at a placeholder endpoint) ships with
the test data (`test-data/.../Extensions/SignInMethod/ExternalDemo.json`), so instances
started with `./start.sh --test` present the two-method composition out of the box.

### Participating institutions

A front-door deployment can display the strip of participating institutions under the sign-in
form by creating the `/libs/iap/ParticipatingInstitutions` registry — one child node per
institution, in display order:

```json
{
  "jcr:primaryType": "nt:unstructured",
  "label": "Participating institutions",
  "uhn": {
    "jcr:primaryType": "nt:unstructured",
    "name": "University Health Network",
    "logoLight": "/libs/iap/resources/media/uhn/logo-light.png",
    "logoDark": "/libs/iap/resources/media/uhn/logo-dark.png",
    "url": "https://www.uhn.ca"
  }
}
```

The registry node must be an orderable type (`nt:unstructured`, not `sling:Folder`) for the
institutions to display in the declared order. The `label` property (optional) overrides the
strip heading. An institution without logos is shown by name; a sample registry (UHN and
CAMH) ships with the test data, so `./start.sh --test` displays the strip. The registry deliberately lives outside `/libs/iap/conf`: `ConfigMetadata`
flattens everything below `conf` into one meta map, where an institution's `logoLight` would
collide with the application logo's. Single-institution deployments simply don't create the
registry, and nothing is rendered.

### Pre-login banners

The page renders `iap/coreUI/frameTop` extensions that opt in with `iap:visibleBeforeLogin:
true` (e.g. a maintenance notice) above its content — see
[docs/ui-extensions.md](../../docs/ui-extensions.md).
