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
| `PdfViewer` (`pdfViewer/`) | A PDF opened at a page, with passages found and marked in it |

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
(`annotation`), or what a comparison found (`added`, `removed`), so it is coloured accordingly. Its
place is given in one of three ways:

- a `quote`, looked for from the mark's own `page` onwards. A passage is matched by ignoring spacing,
  case, accents, typographic quotes and dashes, and line-end hyphens, and across a page break;
- `spans`, when its place is known exactly, such as a change between two versions. Each span is a
  `page` and the `start` and `end` offsets the passage covers in that page's text, one span per page
  it runs over. A span whose `start` equals its `end` is a point between two characters. The viewer
  reads the pages a mark's spans are on first, and places the mark without searching. Spans that run
  backwards or past their page's text are not in the document;
- an `anchor`, for a passage kept to be shown again, such as one a person selected to comment on. It is
  placed where it says as soon as its pages are read, while the text there still says its quote, and
  otherwise found again, as described below.

Offsets are into a page's text as PDF.js reads it for its text layer: the strings `getTextContent`
gives with `disableNormalization`, joined. That is the text of the layer's own text nodes, and
`usePdfDocument`'s `getPageText` gives it. The text layer ends a line with an element rather than a
character, so lines run on in this text without a space. `getPageTextWithLines` says where they end,
and `getPassageText` puts a space there when it shows a passage. Text selected in the text layers maps
back to the same offsets: `getSelectedSpans` gives what a range selects as a span on each page it
selects text of, without the spaces at either end.

A `TextAnchor` keeps a passage the way the W3C's position and quote selectors do: where it starts and
where it ends, each a `page` and a `char` offset into that page's text; what it says (`quote`); and up to
32 characters of the text `before` and `after` it on its pages, as the text reads. `getTextAnchor` makes
one from the spans of a passage. The offsets hold only for the text PDF.js reads today, and a later
PDF.js may read the same file differently, so the quote is what is trusted. When the text at the offsets
no longer says it, the viewer looks for the quote everywhere. It takes the occurrence whose surrounding
text is most like the anchor's `before` and `after`. Ties go to an occurrence whole on a page over one
across a break, then to the one nearest the anchor's page. A quote too short to trust, such as a single
word, is only taken where some of its surrounding text is still the same.

An anchor is kept as text with what it anchors, such as a review comment. `encodeAnchor` gives it as an
`EncodedAnchor`, and `decodeAnchor` reads it back. Its `start` and `end` are each written
`page=3&char=120`, as in PDF.js's open parameters and RFC 5147's text fragments. Its `text` is written as
a text fragment directive without its `text=`: `before-,quote,-after`, each part percent-encoded, dashes
included. `decodeAnchor` gives nothing for an anchor not kept, or one it cannot read.

The viewer reads pages only while some passage is still being looked for. A passage placed by its
spans or kept as an anchor needs only its own pages, and a document opened with no passages is not read
at all. Meanwhile, a bar along the top of the document shows how much of it has been read.
`useMarkPlaces` gives how many pages have been read, along with where the passages are.

Once found, a quote is kept as spans too. The viewer draws every mark over PDF.js's text layer itself,
and leaves PDF.js's own search alone. A mark with a quote or an anchor is shown with its quote, and one
placed by its spans with the text they cover. Marks let the pointer through, so text can still be
selected across them, and a click is matched to a mark by where it falls.

A single passage is shown on a card above the document. Several passages are listed beside it, in
the order given, with those that cannot be found last. In a viewer narrower than
`NARROW_VIEWER_WIDTH` they are shown one at a time above it instead, with controls stepping through
them. It is the viewer's width that counts, not the screen's (`useNarrowViewer`): a viewer in a side
panel is narrow on any screen.
Above the list, a heading says which passage is selected of how many ("Passage 3 of 12"), with
buttons stepping through them. `StepControls` keeps the text between its buttons as wide as the widest it
can show, drawn unseen under it, so stepping from 9 to 10 does not move the buttons.
The list scrolls when the passages do not fit, and fades out at its top or bottom where there are
more (`useScrollEdges` says where, and `fadeEdges` draws the fade).
The card above the document can also be swiped by touch: left to the next passage, right to the
previous, as far as the ends. It follows the finger, springs back when let go of too soon, and the
next card slides in from where the finger went, unless the person prefers reduced motion. `useSwipe`
does this for any element that steps through items by touch.

Selecting a passage from the list scrolls the document to it. Clicking a mark in the document selects
its card in the list and brings the card into view, or, in a narrow viewer, shows it above the
document. When the viewer opens, it selects the first passage in the order given that is not known
to be missing, and takes the reader to it once found. The selection stays there until the person
selects another.

A caller that needs to follow or set the selection, such as a review screen showing comments beside
the document, passes `selectedMarkId` and `onSelectMark`. The viewer reports each passage the person
selects through `onSelectMark`. When the caller sets `selectedMarkId` to another mark, the viewer
selects it and takes the reader to it, as if it had been picked from the list. Echoing back the mark
the viewer just reported doesn't move the reader. `marks` can be a new array on every render: the
viewer compares marks by `id`, `kind` and where they are (`quote` and `page`, `spans`, or `anchor`),
and only looks for passages again when one of those changes.

A caller whose marks are more than passages, such as review comments, passes `markName`, the word for
one and for several (`{ one: "comment", many: "comments" }`). The viewer uses it wherever it counts or
steps through the marks: the heading over the list, the buttons, the list's labels, and what a screen
reader is told ("Comment 3 of 12 is marked on page 4."). A control about the passage's own place in the
document, such as "Show the passage on page 4", still names the passage.

A caller that shows something with each passage, such as the comments on it, passes
`renderMarkDetails`, which is given the mark and whether it is the one selected. What it gives goes under
the passage on its card in the list beside the document. A narrow viewer shows only the selected
passage, and puts it on the card above the document. It goes outside the button selecting the
passage, so it can hold fields and buttons of its own, such as a box for a new comment.
The passage's own text sits on a deeper shade of its mark's colour than the card, setting it apart from
what the caller shows under it.
On the card above the document, what it gives is cut short past a few lines (fewer on a phone) and fades
out where it is cut. One button shows all of the passage and of what is under it ("Show the whole
comment", named by `markName`). Moving the focus into what is cut, such as onto a reply button, shows all
of it too. While what is under the passage holds a field to type in (`TEXT_ENTRY`), such as a comment
being written, the card stays open whatever the person chose. Once the field goes, as when the comment is
saved, the card is again as they chose.
The card takes at most three fifths of the viewer's height and scrolls past that, so the document's
controls below it stay in reach. A caller's field that grows with what is typed should stop growing after
a few rows, so that its buttons stay beside it.
In the list, every entry but the selected one is cut short to a couple of lines, the passage and what is
under it alike, and the selected one is shown whole. Moving the focus into what is cut, such as onto a
reply box in another entry, shows all of it while the focus is there, without selecting the entry.

A caller that lets people comment on passages, such as a review screen, passes `onAnnotate`. Once
someone has selected text in the document and the selection has settled (the pointer let go of, and
nothing changing for `SETTLE_TIME`), the viewer offers a Comment button under the end of the selection.
It runs towards the side of the document with more room, and stays inside it. When the text was selected
by touch, the button sits lower, clear of the phone's handles and of the menu the phone shows above the
selection. Scrolling hides it until the document is still again. Zooming in PDF.js clears the selection,
and the button with it. Escape hides the button until the selection changes, without closing the
viewer. Pressing it gives `onAnnotate` the passage selected, as a `TextAnchor`, and clears the
selection, so the mark the caller makes of it shows. What happens next is the caller's to decide, such
as adding the passage as a mark and opening a comment box for it. Text in a PDF can only be selected
with a pointer, so commenting on a passage needs one too. A view built from the viewer's parts can
follow the selection with `useSelectedText`.

The toolbar also searches the document for a word or phrase, with PDF.js's own search, ignoring case and
accents. Every match is highlighted, and the one shown more deeply. The field says which match is shown
of how many, and the up and down buttons beside it step through them, wrapping round at the ends. The
count grows as PDF.js searches the pages, with a spinner beside it until every page is searched. In the
field, Enter and Shift+Enter do the same, and Escape clears the search. Where the toolbar has no room
for the field, a button opens it in the toolbar's place, and Escape closes it. Ctrl+F, or Cmd+F,
searches while the focus is in the viewer.

A spinner shows in the frame until PDF.js has laid out the document's pages.

On a phone, the space between the viewer's rows and around it is kept small, and the document runs to
the bottom of the screen. Scrolling the document stops at its ends instead of scrolling the dialog
holding it.

It opens in a dialog. Given a `container` element, it fills that element instead, and can pop out
into a dialog and back, keeping the page, the zoom, the marks and the search. A `container` that is
`null`, as a ref is before React attaches it, shows nothing until the element is there.

Its parts can also be used on their own: `usePdfDocument` opens the file and reads each page's text
once, `useMarkPlaces` finds where marks are as those pages are read, and `usePdfView` shows the
document in a frame, with the marks drawn, moves the reader, and searches it. `ViewerPlacement` puts a
viewer in place or in a dialog, `ViewerTitle` is its top row, and `ViewerToolbar` its toolbar.

Before a file is uploaded, check it with `validateUpload` from
`@iap/frontend-commons/fileValidation`. It refuses an empty or oversized file, a type the
caller does not take, and a PDF or Word file whose content does not match its name; see
[file validation](file-validation.md).

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
  marked. Both are full palette colours: `color="assistant"` on a `Chip`, `Button`,
  `IconButton` or icon, `"assistant.main"` in `sx`.
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
