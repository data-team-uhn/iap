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

### Libraries only some screens need

A package imported where a page starts joins the `vendor` chunk every page loads. One imported only
with `import()` where a screen needs it gets chunks of its own instead, loaded the first time a
screen asks for them, as PDF.js is. A worker the library runs is started with
`new Worker(/* webpackEntryOptions: { library: { type: "module" } } */ new URL("<package>/<script>",
import.meta.url), { type: "module" })`, which webpack bundles the same way. The comment makes the
worker a plain module, since webpack cannot build it as the project's own kind of library.

Some libraries also fetch files by name at run time, like PDF.js's fonts and decoders. A module
names the package folders they come from in its `package-folders.config`, beside `assets.config`:

```
'pdfjs-dist/standard_fonts'
'pdfjs-dist/wasm'
```

The build publishes each folder as it is, in a folder named after its contents' hash, which
browsers keep for good. `assets.json` keys it by the package folder, with a dot for the slash:
`getAssetURL("asset:pdfjs-dist.standard_fonts")` gives the folder's URL.

## Talking to the server

### Use `useAuthenticatedFetch`, not `fetch`

A session can expire while a page is open. Sling then answers a read with a 401 or with
the login page, and a write with a **500**. `useAuthenticatedFetch` asks the user to
sign in again, re-sends the request, and resolves with the answer to the retried one.
The caller sees one pending promise and handles no session itself.

```tsx
const authFetch = useAuthenticatedFetch();
const response = await authFetch("/Categories.deep.simple.json");
```

A 500 can also be a real server error. On a 500, the hook asks Sling's session endpoint
whether the session is still there, and asks the user to sign in only if it is not.
Signing in cannot fix a real server error, and the retry would prompt again. With no
re-login provider on the page, or when the user gives up, the promise rejects with a
`NotAuthenticatedError`.

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

Four helpers in `requestFailure.ts` handle a request that failed.

| Helper | Use |
|---|---|
| `RequestError` | Thrown when the server answered without success. It carries the status, and the server's reason when it gave one |
| `readJson(response)` | The data of a successful response. A page in place of the data fails with a sentence a user can read |
| `messageOf(error)` | The message of any rejection, whether or not it is an `Error` |
| `describeRequestFailure(error)` | One sentence a user can act on. It also logs the original to the console |

Read the data of a successful response with `readJson`, never `response.json()`. A
proxy's page, or the login page after the session expired, can arrive as a success.
`readJson` fails on the login page as an expired session, and on any other page with
"The server's response could not be read", instead of "Unexpected token '<'".

`describeRequestFailure` is what a user should see. It turns a `TypeError` into "the
server could not be reached", or "you appear to be offline" when `navigator.onLine` says
so. A 403 becomes "you do not have permission to do this", and a 409 "this conflicts
with a more recent change". Each ends with the status in parentheses, for someone to
relay. A `RequestError` carrying the server's reason gives that reason instead.

It describes **the cause only, never the attempt**. The dialog or screen asking for the
action already says what was being attempted.

### `useAsyncAction` for anything with a button

```tsx
const { working, failure, run } = useAsyncAction<string>({
  onFailure: describeRequestFailure,
  onSuccess: onClose,
});

<Button onClick={() => run(save)} disabled={working}>Save</Button>
{ failure && <Alert severity="error">{failure}</Alert> }
```

It clears the previous failure before each attempt, and stops reporting busy whether
the action succeeds or fails. A failure thrown by `onSuccess` is not reported as a
failure of the action. `onFailure` turns a rejection into whatever the caller shows, of
the type the caller chose. If it returns `undefined`, no failure is shown.

## Shared components

Before writing a dialog or an error state, check `frontend-commons/components`:

| Component | For |
|---|---|
| `WidgetDashboard`, `Widget` | The responsive grid of an extension point's widgets, and the titled frame around each |
| `WidgetStatList` | The figures inside a widget, read from a tool's `adminSummary.json`: each value, then what it is a figure for, aligned in a grid |
| `ConfirmActionDialog`, `ResponsiveDialog` | A dialog that asks before an action, and a dialog that fills the screen on a phone |
| `ActionBar`, `EventAction` | The actions registered on an [action point](ui-extensions.md#action-points), and an icon button that confirms and sends one workflow event |
| `ErrorDialog`, `ErrorPage`, `LoadError`, `GenericErrorPage`, `PageNotFound` | Failure states |
| `LoadingOverlay` | Waiting |
| `StepProgress` | Work moving through named steps, as a full stepper or on one compact line |
| `NoticeBanner` | A full-width announcement, registered as a data-only extension |
| `useNotice` (`NoticeSnackbar.tsx`) | A notice an action raises over the screen. `NoticeProvider` is mounted once, at the top of the page |
| `FormattedText` | Markdown from content, such as a definition's description |
| `EntityDataGrid` (`entityGrid/`) | A server-paged, server-filtered table over an entity homepage |

`EntityDataGrid` reads a homepage's `.paginate.json`, and sends it the filtering and
sorting the user picks.

An entity type can list child rows under each entity, as a tree. Its `children` say how
to fetch them and which column names them. A schema listing its versions:

```ts
registerEntityType("sch/Schema", {
  homepage: "/Schemas",
  columns: SCHEMA_COLUMNS,
  children: {
    selectors: "1",           // fetch each schema with one level of children
    rows: versionsOf,         // which of those children are rows
    treeField: "title",       // the column shown as the tree, on schemas and versions alike
    countLabel: count => count === 1 ? "1 version" : `${count} versions`,
  },
});
```

Paging, sorting and searching still apply to the schemas alone. On a small screen the
list keeps one card per schema, and the card ends with the schema's versions.

`PdfViewer` shows a PDF with PDF.js's own viewer, finds and marks passages in it, and
shows what changed since an older version. It is a module of its own,
`modules/documents/pdf-viewer`, imported as `@iap/documents-pdf-viewer/PdfViewer`, and
its [README](../modules/documents/pdf-viewer/README.md) describes it.

Before a file is uploaded, check it with `validateUpload` from
`@iap/frontend-commons/fileValidation`. It refuses an empty or oversized file, a type the
caller does not take, and a PDF or Word file whose content does not match its name; see
[file validation](file-validation.md).

A choice someone expects to find again, such as which columns a grid shows, is kept in the browser's
local storage with `useStoredSetting(key, fallback, isValid)`. It gives the fallback for a value never
stored, one `isValid` refuses, and storage the browser does not allow, as in a private window, and reads
the setting again when given another key. Keys start with `iap.` and the component's name
(`iap.entityGrid.<type>.columns`). Storage is a convenience: nothing that must last or be shared goes
there.

### Value inputs

Before writing a field that edits a value, check `frontend-commons/inputs`. Each input
takes the values as a list of strings, whatever the cardinality, and reports them
through `onChange` while typing and `onCommit` once a changed value is finished. An
input never saves anything: the caller decides what to keep, and how a value is stored.

| Input | For |
|---|---|
| `TextInput` | One value typed freely, on one line or several |
| `ChoiceSelect` | One value picked from a list, with None when it is optional |
| `BooleanInput` | A yes or no, stored as `true` or `false` and shown as Yes and No unless `trueLabel` and `falseLabel` say otherwise |
| `MultiValueInput` | Several values as chips, picked from a list or typed |
| `ChoiceSearch` | One value or several, picked from a list read from a `ChoiceSource` and narrowed as it is typed |
| `NumberInput` | One number, whole or decimal; text that is no number is flagged |
| `DateInput` | One calendar day, stored as `yyyy-mm-dd` |
| `ValueText` | Values shown as text after their label, as "Label: a, b"; every input shows this when `readOnly` |

The common props are in `valueInput.ts`. `problems` replaces the description and marks
the field in error; each is a sentence, and the same one is shown once. A `required`
field with no value, or only blank ones, adds "A value is required.", but only once it
has been left. A stored value that the list no longer offers stays selected and shown
as it is, rather than being dropped when the form is saved. So does a `BooleanInput`
value that is neither `true` nor `false`.

A `ChoiceSource` (`choiceSource.ts`) is where a `ChoiceSearch` gets its list, such as
`referenceChoices` for the nodes a reference may point at. `useChoices` reads a source
once, and again only when its `key` changes. `referenceChoices` takes a
`resourceType`, a JCR `nodeType` or both, and a `root`; the query and the parsing it
builds on are in `referenceTargets.ts`. A node type matches its subtypes and the nodes
that have it as a mixin. Each node is labelled by its title or label, else by its path
under the root, and stored as its absolute path. At most a thousand are read, and a
stored path that is not among them stays selected, labelled with the path. `ReferenceInput`
in `fields/` uses a `ChoiceSearch` over `referenceChoices` for a `ContentField`.

`NumberInput` passes every keystroke on. Text that cannot become a number of its kind
is flagged at once, and a number left unfinished once the field is left; only a
finished number, or nothing, is committed. A stored value that is no number shows as
written. The browser's date field reports a half-typed day as empty, so `DateInput`
ignores that change, and the stored day stays until a whole one is typed.

## Styling

MUI's `sx` prop and the theme, not stylesheets. The theme
(`frontend-commons/src/appTheme.ts`) adds what this platform needs to MUI's own. A
deployment's brand colours reach a component only through the theme. What it adds:

- `Typography variant="pageTitle"` for the one top-level heading of a view. A view
  showing one resource puts the resource's tags in line with that heading (`TagChip`
  without a category, in `AdminScreen`'s `status` on an admin page); listing rows and
  cards place their chips as their layout allows.
- `background.default` is a gray canvas and `background.paper` the surfaces standing
  out against it, in both colour schemes.
- `background.tinted` for surfaces that carry the brand quietly, such as the frame bars
  and emphasised widgets. `background.tintedStrong` has more of the primary colour, for
  a tinted surface that stands out, such as one under the pointer.
- `background.muted` for a page region set apart from the background. It is
  translucent, so it shades whatever it overlaps.
- `palette.condition` for content that applies only under a condition: `main` for its
  icon, and `surface` behind the line saying when it applies.
- `palette.diff.added` and `palette.diff.removed` for what a comparison marks as added
  or removed. `main` is for markers, `line` for a changed line's background, and `word`
  for the stronger shade of the words that changed within it. `palette.diff.moved` has
  only `main` and `line`, for what moved. So has `palette.diff.hunk`, for the band
  that stands in for unchanged content left out.
- `assistant` for anything an AI assistant contributed, and `annotation` for what a person
  marked. Both are full palette colours, read in `sx` as `"assistant.main"`. No component's
  `color` prop takes them yet.
- `highlight.assistant` and `highlight.annotation` for marks over a document's own content,
  `highlight.search` for the matches of a search, and `highlight.added` and `highlight.removed` for
  what a comparison found, each with a `…Current` variant for the one in focus, and the last two with a
  `…Marker` variant for the lines drawn over the page: the cursor where text was removed, the
  line striking removed text, the outline of a selected change. They stay the same in both colour
  schemes, since the content under them stays light.
- `iapShell` for the page frame's rail widths and collapse thresholds.

Both colour schemes are defined, so a hard-coded colour is wrong in one of them. A
colour from content, such as a tag definition's `color`, goes through `safeCssColor`
before it reaches a style. It accepts hex, `rgb()` and `hsl()` notation only, so the
value cannot carry other CSS.

## Personas

`ext:personas` on an extension names the personas it is for, and `visibleInPersona`
tests it. An extension naming none is for every persona. See
[personas](ui-extensions.md#personas).

A persona is **presentation only, never access control**. The server authorizes every
request, whatever the persona.

## Tests

Vitest with Testing Library, tests under `src/test/frontend/src/`, run with `pnpm test`
from `aggregated-frontend/src/main/frontend`. `pnpm lint` and `pnpm typecheck` run the
same aggregation first, so run them from there too.

Test what a user sees: query by role and label rather than by class or test id, and
assert on rendered text. The pure model modules are the easy win — `categoryModel.ts`
can be tested exhaustively with no rendering at all, which is a good reason to keep
computation out of components.

Most test files share a process with others, because starting jsdom, React and MUI again
for every file costs more than most files spend testing. A file that calls `vi.mock` or
`vi.doMock` runs in a process of its own: `vitest.config.ts` finds those by reading the
sources, so nothing needs listing by hand. A shared file sees whatever earlier files left
behind: stubbed globals, spies, the document, and the state of the modules they all import.
`vitest.shared.setup.ts` clears that between files. It resets the module registry, so a
module-level cache, such as the tag definitions in `tagDefinitions.ts`, starts empty in each
file with nothing listed by hand. It also removes the `<meta>` tags a test added to the page
head and empties `localStorage`. Anything else a test leaves in the document is its own to
clean up.

The lint configuration is worth reading once. It enforces `consistent-type-imports`
(`import type` for types), `switch-exhaustiveness-check`, and several rules about
implicit conversions; test sources are held to the same standard minus the few rules
that only make sense in production code.
