# IAP login page

The page at `/login`, where a visitor without a session signs in. It is the only page
such a visitor can reach. The `-/login` entry of `sling.auth.requirements`, in
`packaging/slingfeature/src/main/features/core/sling-configuration.json`, lets a request
for it through without a session, and this module's repoinit grants `everyone` read on
`/login`. The `form.login.form` property in this module's `feature.json` makes Sling's
form authentication send visitors here. The form posts the credentials to
`/j_security_check`, then goes to the path in the `?resource=` parameter, if it is a
path on this site.

The page has two panels: a muted one introducing the platform, and one for signing in.
The ways of signing in are extensions on the `iap/login/signInMethod` point (see
[Sign-in methods](#sign-in-methods)). An identity provider replaces or joins the
credentials form with content alone.

## Configuration reference

The texts are content under `/libs/iap/conf`, and reach the page as `<meta>` tags:

| Property | Node | Renders as |
| --- | --- | --- |
| `tagline` | `/libs/iap/conf/LoginPage` | A short uppercase line under the logo |
| `introText` | `/libs/iap/conf/LoginPage` | The paragraph introducing the platform, in markdown |
| `signInLabel` | `/libs/iap/conf/LoginPage` | A small label above the sign-in heading |
| `signInHeading` | `/libs/iap/conf/LoginPage` | The sign-in heading |
| `title` | `/libs/iap/conf/AppName` | The logo's alternative text, which names the page's heading |
| `logoLight`/`logoDark`, `affiliationLogo*` | `/libs/iap/conf/Media` | The application's logo, at the top of the brand panel; the institution's logo, at the start of the footer |

`tagline`, `signInLabel` and `signInHeading` are repoinit defaults. `introText` is
initial content, in `SLING-INF/content/libs/iap/conf/LoginPage.json`.

Every node in the tree is an `app:Configuration`, the root included. Only nodes of that
type reach the page, so a deployment adding a configuration node of its own gives it
that type. Their property names are open: any property with a value and no namespace
becomes a `<meta>` tag.

### Sign-in methods

The ways of signing in are `iap/login/signInMethod` extensions, shown in their
`defaultOrder`. The first enabled method is shown in place. Each further one is a link,
labelled by its `ext:collapsedLabel`, that opens it. This module registers the local
credentials form as the default method. If no method is enabled, or the point fails to
load, the page shows the credentials form anyway, so there is always a way to sign in.

An identity provider needs no frontend code. It registers the redirect method with a
lower `defaultOrder` than the credentials form's 100, and the form then collapses behind
its "Use a local account instead" link:

```json
{
  "jcr:primaryType": "ext:Extension",
  "ext:pointId": "iap/login/signInMethod",
  "ext:name": "Institutional sign-in",
  "ext:renderURL": "asset:iap-login.RedirectSignIn.js",
  "defaultOrder": 10,
  "ext:targetURL": "/goto-external-login",
  "ext:actionLabel": "Continue to sign-in",
  "ext:hint": "You will be redirected to your institution's sign-in page."
}
```

The redirect method goes to `ext:targetURL`, with the page to return to in its
`resource` query parameter. Where the user lands after signing in is up to the endpoint,
which may ignore the parameter. Sling's OIDC authentication handler ignores it, and
sends the user back to the path that started the sign-in.

Where local accounts should not be offered, disable the credentials form with
`ext:defaultDisabled: true` on its extension node. With no other method enabled, the
page still shows it.

The test data registers a demo redirect method with a placeholder endpoint, in
`test-data/.../Extensions/SignInMethod/ExternalDemo.json`. An instance started with
`./start.sh --test` shows both methods.

### Participating institutions

A deployment serving several institutions can show the institutions taking part under
the sign-in form. It creates the `/libs/iap/ParticipatingInstitutions` registry, with
one child node per institution, in display order:

```json
{
  "jcr:primaryType": "nt:unstructured",
  "label": "Participating institutions",
  "uhn": {
    "jcr:primaryType": "nt:unstructured",
    "name": "University Health Network",
    "logoLight": "/libs/iap/resources/media/default/uhn-logo-light.png",
    "logoDark": "/libs/iap/resources/media/default/uhn-logo-dark.png",
    "url": "https://www.uhn.ca"
  }
}
```

The registry node must be an orderable type, such as `nt:unstructured` and not
`sling:Folder`, for the institutions to keep their order. Its optional `label` replaces
the heading. An institution without logos is shown by name. Without the registry,
nothing is shown. The test data has a sample registry with UHN and CAMH, so `./start.sh
--test` shows the strip.

### Pre-login banners

Above its content, the page shows the `iap/coreUI/frameTop` extensions marked with
`ext:visibleBeforeLogin: true`, such as a maintenance notice. See
[docs/ui-extensions.md](../../docs/ui-extensions.md).
