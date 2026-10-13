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
`new Worker(new URL("<package>/<script>", import.meta.url), { type: "module" })`, which webpack
bundles the same way.

Some libraries also fetch files by name at run time, like PDF.js's fonts and decoders. A module
names the package folders they come from in its `package-folders.config`, beside `assets.config`:

```
'pdfjs-dist/standard_fonts'
'pdfjs-dist/wasm'
```

The build publishes each folder as it is, in a folder named after its contents' hash, which the
server has browsers keep for good, and keys it in `assets.json` by the package folder, with a dot
for the slash: `getAssetURL("asset:pdfjs-dist.standard_fonts")` gives the folder's URL.

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
| `WidgetStatList` | The figures inside a widget, fetched from a tool's `adminSummary.json`: a value, then what it is a figure for, all aligned in a grid |
| `ConfirmActionDialog`, `ResponsiveDialog` | Confirmations and dialogs that behave on a phone |
| `ErrorDialog`, `ErrorPage`, `LoadError`, `GenericErrorPage`, `PageNotFound` | Failure states |
| `LoadingOverlay` | Waiting |
| `StepProgress` | Work moving through named steps, as a full stepper or on one compact line |
| `NoticeBanner`, `NoticeSnackbar` | Announcements and transient feedback |
| `FormattedText` | Rendering the markdown a definition supplied |
| `EntityDataGrid` (`entityGrid/`) | A server-paged, server-filtered table over an entity homepage |
| `PdfViewer` (`pdfViewer/`) | A PDF opened at a page, with passages found and marked in it |

`EntityDataGrid` is worth reaching for rather than assembling: it already speaks the
[pagination endpoint's](ui-extensions.md) filtering and sorting parameters.

It is possible to display nested entities. If an entity type expects child rows, it can specify how to fetch
and display them using the `children` structure, and the grid nests those rows under their parent entity as a tree.
A schema listing its versions:

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

Paging, sorting and searching still apply to the schemas alone. On a small screen the list keeps one
card per schema, so the card describes its versions.

`PdfViewer` shows a PDF with PDF.js's own viewer, and finds and marks passages in it:

```tsx
<PdfViewer
  url={file}
  page={evidence.page}
  marks={[
    { id: evidence.id, quote: evidence.quote, page: evidence.page, kind: "assistant" },
  ]}
  title="Protocol"
  onClose={close}
/>
```

Each mark has a `kind`: whose mark it is, an AI assistant's (`assistant`) or a person's
(`annotation`), so it is coloured accordingly. Where it is comes in one of three ways:

- a `quote`, looked for from the mark's own `page` onwards. A passage is matched by ignoring spacing,
  case, accents, typographic quotes and dashes, and line-end hyphens, and across a page break;
- `spans`, when its place is known exactly, such as a change between two versions. Each span is a `page` and the `start` and `end` offsets the passage covers in that
  page's text, one span per page it runs over. A span whose `start` equals its `end` is a point
  between two characters. The viewer reads the pages a mark's spans are on first, and places the mark
  without searching. Spans that run backwards or past their page's text are not in the document;
- an `anchor`, for a passage kept to be shown again, such as one a person selected to comment on. It is
  placed where it says as soon as its pages are read, while the text there still says its quote, and
  otherwise found again, as described below.

Offsets are into a page's text as PDF.js reads it for its text layer (`getTextContent` with
`disableNormalization`, the items' strings joined), which is the text of the layer's own text nodes:
`usePdfDocument`'s `getPageText` gives it. Lines run on in it without a space, since the text layer ends
them with elements rather than characters; `getPageTextWithLines` says where they end, and the text of a
passage as shown (`getPassageText`) puts a space there. Text selected in the text layers maps back to
the same offsets: `getSelectedSpans` gives what a range selects as a span on each page it selects text
of, without the spaces at either end.

A `TextAnchor` keeps a passage the way the W3C's position and quote selectors do: where it starts and
where it ends, each a `page` and a `char` offset into that page's text; what it says (`quote`); and up to
32 characters of the text `before` and `after` it on its pages, as the text reads. `getTextAnchor` makes
one from the spans of a passage. The offsets are quick to place, but they are only right for the text
PDF.js reads today: a later PDF.js may read the same file differently. So the quote is what is trusted.
When the text at the offsets no longer says it, the viewer looks for the quote everywhere, and takes the
occurrence with the text around it most like what was around it, whole on a page rather than across a
break, and nearest the anchor's page. A quote too short to trust, such as a single word, is only taken
where some of that text is still the same.

An anchor is kept as text with what it anchors, such as a review comment. `encodeAnchor` gives it as an
`EncodedAnchor`, and `decodeAnchor` reads it back. Its `start` and `end` points are each written as
PDF.js's open parameters and RFC 5147's text fragments write one, `page=3&char=120`, and its `text`, what
the passage says, as a text fragment directive writes it, without its `text=`: `before-,quote,-after`, each
part percent-encoded, dashes included. `decodeAnchor` gives nothing for an anchor not kept, or one it
cannot read.

Once found, a quote is kept as spans too, and the viewer draws every mark over PDF.js's text layer
itself, leaving PDF.js's own search untouched. A mark found or placed is shown with the text it covers,
the quote itself for a quote or an anchor. Marks let the pointer through, so text can still be selected
across them, and a click is matched to a mark by where it falls.

If only one passage is passed to the viewer, it is shown on a card above the document. When there
are multiple passages to highlight, they are listed beside it, in the order given, with those that
cannot be found appearing last; in a viewer narrower than `NARROW_VIEWER_WIDTH` they are shown one
at a time above it instead, with controls stepping through them. It is the viewer's width that
counts, not the screen's (`useNarrowViewer`): a viewer in a side panel is narrow on any screen.

Selecting a passage from the list scrolls the document to take the reader to it; similarly, clicking
its mark in the document selects the corresponding card in the list and brings it in view (or, in a
narrow viewer, shows it on the card above the document). When the viewer opens, it selects the first
passage in the order given, skipping any that are not found in the document, and takes the reader to
it once found. The selection stays there until the person selects another.

A caller that needs to follow or set the selection, such as a review screen showing comments beside
the document, passes `selectedMarkId` and `onSelectMark`. The viewer reports each passage the person
selects through `onSelectMark`. When the caller sets `selectedMarkId` to another mark, the viewer
selects it and takes the reader to it, as if it had been picked from the list. Echoing back the mark
the viewer just reported doesn't move the reader. `marks` can be a new array on every render: the
viewer compares marks by `id`, `kind` and where they are (`quote` and `page`, `spans`, or `anchor`),
and only looks for passages again when one of those changes.

A caller that shows something with each passage, such as the comments on it, passes
`renderMarkDetails`, which is given the mark and whether it is the one selected. What it gives goes under
the passage, on its card in the list beside the document, or on the card above the document in a
narrow viewer, which shows only the selected passage. It goes outside the button selecting the passage,
so it can hold fields and buttons of its own, such as a box for a new comment on the passage selected.

A caller that lets people comment on passages, such as a review screen, passes `onAnnotate`. Once
someone has selected text in the document and the selection has settled (the pointer let go of, and
nothing changing for `SETTLE_TIME`), the viewer offers a Comment button under the end of the selection,
running towards the side of the document with more room, and kept inside it. When the text was selected
by touch, the button sits further down, past the phone's handles, and out of the way of its own menu
above the selection. The button is the same height on every screen, so that it can be tapped anywhere.
Scrolling puts it away until the document is still again; zooming in PDF.js lets go of the selection
altogether, so the button goes with it. Escape puts the button away until the selection changes, without
closing the viewer. Pressing it gives `onAnnotate` the passage selected, as a `TextAnchor`, and lets go
of the selection, so the mark the caller makes of it shows. Pressing it holds what was selected first,
as tapping a button clears the selection on some phones, until the pointer is pressed anywhere else.
What happens next is the caller's to decide, such as adding the passage as a mark and opening a comment
box for it. Text in a PDF can only be selected with a pointer, so commenting on a passage needs one too.
`useSelectedText` follows the selection, for a view combining the parts apart.

The toolbar also searches the document for a word or phrase, with PDF.js's own search, ignoring case
and accents. Every match is highlighted, the one shown more deeply; the field says which match is
shown of how many, and the up and down buttons beside it step through them, wrapping round at the
ends. In the field, Enter and Shift+Enter do the same, and Escape clears the search. Where the
toolbar has no room for the field, a button opens it in the toolbar's place, and Escape closes it.
Ctrl+F, or Cmd+F, searches while the focus is in the viewer.

A spinner shows in the frame until PDF.js has laid out the document's pages.

It opens in a dialog, or, given a `container` element, fills that element and offers to pop out into
a dialog and back, keeping the page, the zoom, the marks and the search. A `container` that is
`null`, as a ref is before React attaches it, shows nothing until the element is there.

Its parts can be combined apart: `usePdfDocument` opens the file and reads each page's text once,
`useMarkPlaces` finds where marks are as those pages are read, and `usePdfView` shows the document
in a frame, with the marks drawn, moves the reader, and searches it.

## Styling

MUI's `sx` prop and the theme, not stylesheets. The theme
(`frontend-commons/src/appTheme.ts`) is extended with what this platform needs, and
using those extensions is what keeps a deployment's rebranding effective:

- `Typography variant="pageTitle"` for the one top-level heading of a view. A view
  showing one resource puts the resource's tags in line with that heading (`TagChip`
  without a category, in `AdminScreen`'s `status` on an admin page); listing rows and
  cards place their chips as their layout allows.
- `background.default` is a gray canvas and `background.paper` the surfaces standing
  out against it, in both colour schemes.
- `background.tinted` for surfaces that carry the brand quietly: the frame bars and
  emphasised widgets.
- `background.muted` for surfaces that need to recede — translucent, so it composes
  with whatever it overlaps rather than assuming white.
- `palette.diff.added` and `palette.diff.removed` for what a comparison marks as added
  or removed. `main` is for markers, `line` for a changed line's background, and `word`
  for the stronger shade of the words that changed within it. `palette.diff.moved` has
  only `main` and `line`, for what moved. So has `palette.diff.hunk`, for the band
  that stands in for unchanged content left out.
- `assistant` for anything an AI assistant contributed, wherever it appears, and `annotation`
  for what a person marked: full palette colours (`color="assistant"` on a `Chip`, `Button`,
  `IconButton` or icon, `"assistant.main"` in `sx`), built on a lilac accent and a soft
  highlighter yellow.
- `highlight.assistant` and `highlight.annotation` for marks over a document's own content,
  and `highlight.search` for the matches of a search, each with a `…Current` variant for the one
  in focus. They stay the same in both colour schemes, since the content under them stays light.
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
