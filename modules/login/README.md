# IAP login page

The landing page served to unauthenticated visitors at `/login`, and the only page they can
reach — everything else requires a session (`sling.auth.requirements` in
`packaging/slingfeature/src/main/features/core/sling-configuration.json`, with this module's
repoinit granting `everyone` read on `/login`). Sling's form authentication is pointed at it
through the `form.login.form` property in this module's `feature.json`; the form submits to
`/j_security_check` and then navigates to the validated `?resource=` return path.

The page is a split landing page: a muted brand panel introducing the platform, and a paper
panel with the sign-in action. The credentials form (`LoginForm.tsx`) is the swappable "auth
action" area, to be replaced with an identity-provider redirect once authentication is
delegated to Keycloak.

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

### Participating institutions

A front-door deployment can display the strip of participating institutions under the sign-in
form by creating the `/libs/iap/ParticipatingInstitutions` registry — one child node per
institution, in display order:

```json
{
  "jcr:primaryType": "sling:Folder",
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

The `label` property (optional) overrides the strip heading. An institution without logos is
shown by name. The registry deliberately lives outside `/libs/iap/conf`: `ConfigMetadata`
flattens everything below `conf` into one meta map, where an institution's `logoLight` would
collide with the application logo's. Single-institution deployments simply don't create the
registry, and nothing is rendered.

### Pre-login banners

The page renders `iap/coreUI/frameTop` extensions that opt in with `iap:visibleBeforeLogin:
true` (e.g. a maintenance notice) above its content — see
[docs/ui-extensions.md](../../docs/ui-extensions.md).
