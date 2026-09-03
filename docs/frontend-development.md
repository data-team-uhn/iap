# Writing frontend code

**Shared code:** `modules/frontend-commons/src/main/frontend/src` ·
**Extensions:** `modules/ui-extension/src/main/frontend/src` (`@iap/ui-extension`) ·
**Build:** `aggregated-frontend/src/main/frontend`

React with TypeScript and MUI, bundled by webpack, delivered as independently loadable
assets. Each module keeps its own frontend under `src/main/frontend/src`, and the build
aggregates them.

This picks up where [how a URL becomes a page](page-rendering.md) leaves off: the shell
has loaded, and everything after that is here.

## What a module ships

```
modules/<name>/src/main/frontend/
├── assets.config          entry points, aggregated into the webpack config
└── src/
    ├── <Name>View.tsx     a routed view
    ├── <Name>Widget.tsx   a dashboard summary
    ├── use<Name>.ts       the hook owning this feature's I/O
    └── <name>Model.ts     pure parsing and computation, no React
```

Tests live in `src/test/frontend/src/`, next to nothing — the aggregation step puts them
where the runner expects.

## Everything is an extension

There is no page to add a route to. A feature contributes UI by registering a
component on an [extension point](ui-extensions.md), and the consumer of that point
renders it without knowing anything about it.

The component is a plain default export, receiving the parsed extension node as an
`extension` prop:

```tsx
function CategoryManager() { … }

export default CategoryManager;
```

It becomes loadable by getting a line in `assets.config`:

```
['iap-categories.CategoryManager']: './src/CategoryManager.tsx',
['iap-categories.CategoriesWidget']: './src/CategoriesWidget.tsx'
```

and reachable by a node registering it on a point, with `asset:iap-categories.CategoryManager.js`
as the `ext:renderURL`. Append `?lazy` for anything not needed on first paint — every
view should be lazy, since a user loads one of them.

The name in `assets.config` is the contract between the three: `<bundle>.<Component>`,
resolved at runtime through the content-hashed `assets.json`. If a new component does
not appear, check that entry exists in `assets.json` first — a missing one means the
build did not pick up the config change.

## Talking to the server

### Use `useAuthenticatedFetch`, not `fetch`

Sessions expire mid-session, and Sling's answer to that is not uniform: a read comes
back 401 or as the login page, while a write with an expired session comes back **500**.
`useAuthenticatedFetch` absorbs all of it — it re-authenticates, re-sends, and resolves
with the retried response, so a caller sees one pending promise and writes no session
handling at all.

```tsx
const authFetch = useAuthenticatedFetch();
const response = await authFetch("/Categories.deep.simple.json");
```

The 500 case is disambiguated by asking whether the session is still alive, because
neither passing it on nor treating it as expired is right: the first loses the case
that matters most, the second prompts a sign-in that fixes nothing and prompts again on
the retry.

### Choose the serialization you need

Endpoints let the caller decide how much comes back — see
[JSON serialization](json-serialization.md). This is a real performance lever, not a detail.
The category manager fetches `.deep.simple.json`; without `simple`, naming a bound
schema on a chip would pull down that schema's whole requirement subtree and a BPMN
document with it.

### One hook owns a feature's I/O

Put every request for a feature behind one hook, exposing operations rather than
requests, and let components call those:

```tsx
const { tree, create, update, move, reorder, setRetired, remove, reload } = useCategoryTree();
```

The advantage is that the reload policy, the error wording and the request shapes live
in one file. `useCategoryTree` re-reads the whole tree after every write, because a
refetch reflects server-side truth — name mangling, ordering, concurrent edits — rather
than an optimistic guess.

### Keep the pure part pure

Parsing and computation belong in a module with no React and no `fetch` in it —
`categoryModel.ts` holds `parseCategoryTree`, `flattenTree`, `findNode`,
`isDescendantPath`, `hasDuplicateLabel`. That half is testable without rendering
anything, and the hook above it stays small enough to read.

## Failures

Three helpers, and the distinction between them matters.

| Helper | Use |
|---|---|
| `RequestError` | Thrown when the server answered without success, carrying the status |
| `messageOf(error)` | Whatever a rejection has to say, for anything that is not necessarily an `Error` |
| `describeRequestFailure(error)` | One sentence a user can act on, and logs the original |

`describeRequestFailure` is what a user should see. It turns a `TypeError` into "the
server could not be reached" (or "you appear to be offline", by checking
`navigator.onLine`), a 403 into "you do not have permission to do this", a 409 into
"this conflicts with a more recent change" — each keeping the status in parentheses for
someone to relay, and none of them leading with it.

It describes **the cause only, never the attempt**. The dialog or screen asking for the
action already says what was being attempted, and repeating it reads as an echo.

### `useAsyncAction` for anything with a button

```tsx
const { working, failure, run } = useAsyncAction<string>({
  onFailure: describeRequestFailure,
  onSuccess: onClose,
});

<Button onClick={() => run(save)} disabled={working}>Save</Button>
{ failure && <Alert severity="error">{failure}</Alert> }
```

It owns the sequence that is easy to get subtly wrong: clear the previous failure before
retrying, stop reporting busy on both paths, and never report a failure thrown by
`onSuccess` as a failure of the action. The failure type is the caller's, so the hook
stays out of the wording business.

## Shared components

Before writing a dialog or an error state, check `frontend-commons/components`:

| Component | For |
|---|---|
| `WidgetDashboard`, `Widget` | The responsive grid and titled frames, parameterized by extension point |
| `ConfirmActionDialog`, `ResponsiveDialog` | Confirmations and dialogs that behave on a phone |
| `ErrorDialog`, `ErrorPage`, `LoadError`, `GenericErrorPage`, `PageNotFound` | Failure states |
| `LoadingOverlay` | Waiting |
| `NoticeBanner`, `NoticeSnackbar` | Announcements and transient feedback |
| `FormattedText` | Rendering the markdown a definition supplied |
| `EntityDataGrid` (`entityGrid/`) | A server-paged, server-filtered table over an entity homepage |

`EntityDataGrid` is worth reaching for rather than assembling: it already speaks the
[pagination endpoint's](ui-extensions.md) filtering and sorting parameters.

## Styling

MUI's `sx` prop and the theme, not stylesheets. The theme
(`frontend-commons/src/appTheme.ts`) is extended with what this platform needs, and
using those extensions is what keeps a deployment's rebranding effective:

- `Typography variant="pageTitle"` for the one top-level heading of a view.
- The `admin` palette entry and `background.admin` for the administrative danger zone.
- `background.muted` for surfaces that need to recede — translucent, so it composes
  with whatever it overlaps rather than assuming white.
- `iapShell` for the page frame's rail widths and collapse thresholds.

Both colour schemes are defined, so anything hard-coded will be wrong in one of them.
Colours arriving from content go through `safeCssColor` before reaching a style — a tag
definition's `color` is authored data, and unvalidated data in a style is an injection.

## Personas

`ext:personas` on an extension restricts it to some personas, and `visibleInPersona` is
the predicate. Naming none means all, which is what keeps an extension that never
considered personas working when a new one is added.

A persona is **presentation only, never access control**. The repository authorizes
every request independently, and a control hidden from a persona must never be the only
thing preventing the action behind it.

## Tests

Vitest with Testing Library, tests under `src/test/frontend/src/`, run with `pnpm test`
from `aggregated-frontend/src/main/frontend`. `pnpm lint` and `pnpm typecheck` run the
same aggregation first, so run them from there too.

Test what a user sees: query by role and label rather than by class or test id, and
assert on rendered text. The pure model modules are the easy win — `categoryModel.ts`
can be tested exhaustively with no rendering at all, which is a good reason to keep
computation out of components.

The lint configuration is worth reading once. It enforces `consistent-type-imports`
(`import type` for types), `switch-exhaustiveness-check`, and several rules about
implicit conversions; test sources are held to the same standard minus the few rules
that only make sense in production code.
