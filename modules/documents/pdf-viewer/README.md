# IAP PDF viewer

**Module:** `modules/documents/pdf-viewer` · **Bundle:** `iap-documents-pdf-viewer` ·
**Imported as:** `@iap/documents-pdf-viewer/…`, chiefly `PdfViewer`

`PdfViewer` shows a PDF in PDF.js's own viewer, and finds and marks passages in it, such
as the evidence an AI assistant quotes or the passages people comment on. Given an older
version of the same document, it also shows what changed.

## Showing a document

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

| Prop | What it does |
|---|---|
| `url` | The PDF to open. A different `url` opens that document from the start |
| `page` | The page to open at, counting from 1. Without it, the viewer opens at the selected mark's page, or else at page 1. A different `page` later takes the reader there, and keeps the zoom and the search |
| `marks` | The passages to mark, in the order to list them ([Marks](#marks)) |
| `selectedMarkId`, `onSelectMark` | Follow and set the selection ([Listing and selecting](#listing-and-selecting)) |
| `markName` | What the marks are called, such as `{ one: "comment", many: "comments" }`. Passages by default |
| `renderMarkDetails` | What to show under each passage ([What the caller shows](#what-the-caller-shows-with-each-passage)) |
| `onAnnotate` | Offers a Comment button on selected text ([Commenting](#commenting-on-a-selection)) |
| `title` | The viewer's title. The PDF's file name by default |
| `compareWith`, `label` | An older version to compare with, and this version's label ([Comparing](#comparing-two-versions)) |
| `container` | An element to fill instead of opening in a dialog |
| `onClose` | Called when the person closes the viewer |

The viewer opens in a dialog. Given a `container`, it fills that element instead and can
pop out into a dialog and back, keeping the page, zoom, marks and search. **The element
needs a height of its own.** While `container` is `null`, as a ref is before React
attaches it, the viewer shows nothing. Unlike PDF.js's own viewer, a file dropped on the
document does nothing, and scrolling stops at the document's ends rather than scrolling
the dialog around it.

## Marks

Each mark has an `id`, a `kind`, and its place, given in one of three ways:

| Place | For | How it is placed |
|---|---|---|
| `quote`, with an optional `page` | A passage known by its text, such as an assistant's evidence | Looked for from `page` to the end, then from the first page |
| `spans` | A passage whose place is known, such as a change between two versions | Where it says. Spans that run backwards or past their page's text count as not found |
| `anchor` | A passage kept to be shown again, such as one a person commented on | Where it says, while the text there still matches its quote. Otherwise it is found again ([Anchors](#anchors)) |

The `kind` sets the colour: `assistant` or `annotation` for whose passage it is, `added`
or `removed` for what a comparison found.

A quote is matched across page breaks too. Matching ignores spacing, case, accents,
typographic quotes, and any hyphen or dash that is not before a digit. Once found, the
quote is kept as spans too.

A span is a `page` with the `start` and `end` offsets it covers in that page's text, one
span per page the passage runs over. Equal offsets make a point between two characters,
drawn after the character before it, or with `starts` before the character after it, as
the start of the text that follows.

**Offsets are into a page's text as PDF.js reads it for its text layer**: the strings
that `getTextContent` gives with `disableNormalization`, joined. This is the text of the
layer's own text nodes, which `usePdfDocument`'s `getPageText` returns. The text layer
ends a line with an element rather than a character, so lines run on in this text
without a space; `getPageTextWithLines` says where lines end, and `getPassageText` puts
a space there. Selected text maps back to the same offsets: `getSelectedSpans` gives a
range as one span per page, leaving out the spaces at either end.

Marks are drawn on PDF.js's text layer, under the text, so PDF.js's own search matches
show over them. They let the pointer through, so text can still be selected across them,
and a click goes to the mark where it falls.

## Anchors

A `TextAnchor` records a passage in the form of the text position and text quote
selectors of the W3C's Web Annotation model:

| Field | What it is |
|---|---|
| `start`, `end` | Where the passage starts and ends, each a `page` and a `char` offset into that page's text |
| `quote` | What the passage says |
| `before`, `after` | Up to `CONTEXT_LENGTH` (32) characters of the text either side, on the passage's pages |

`getTextAnchor` makes one from a passage's spans.

**The quote is what is trusted**, since a later PDF.js may read the same file into
different offsets. When the text at the offsets no longer says the quote, the viewer
looks for the quote everywhere and takes the occurrence whose surrounding text is most
like `before` and `after`; a tie goes to an occurrence on a single page, then to the
one nearest the anchor's page. A quote too short to trust, such as a single word, is
taken only where some of its surrounding text is unchanged.

An anchor is stored as text with what it anchors, such as a review comment.
`encodeAnchor` gives an `EncodedAnchor`, and `decodeAnchor` reads one back, or returns
`undefined` for one it cannot read. Its `start` and `end` are each written
`page=3&char=120`, as in PDF.js's open parameters and RFC 5147's text fragments. Its
`text` is a text fragment directive without its `text=`: `before-,quote,-after`, each
part percent-encoded, dashes included.

## Reading the pages

The viewer reads page text only while some passage is still being looked for. It starts
with the pages the marks name: those of their spans or anchors, and the page each quote
is looked for from. A passage that is where it says is then found without waiting for
the pages before it. A document opened with no passages is not read at all. A bar along
the top of the document shows how much has been read; `useMarkPlaces` gives that count
with where the passages are.

`marks` can be a new array on every render: the viewer compares marks by `id`, `kind`
and place, and an unchanged mark keeps the place found for it, so adding a comment looks
only for the new one.

## The text over the page

Marks, selections and search matches all sit on PDF.js's text layer, transparent text
laid over each page. PDF.js sets it in a generic font and stretches each piece to the
width the page draws it, so a character inside a piece sits where the generic font puts
it. On a line of narrow and wide letters, that can be tens of pixels from its glyph, and
selecting the character picks up the letter beside it.

`textLayerFonts` sets each piece in the font PDF.js loaded to draw the page, at the same
width, and the viewer redraws a page's marks once it has both its text and its fonts.
**This relies on PDF.js internals**, listed in that file; where they do not hold, the
text keeps PDF.js's own layout. The `pdf-text-layer` integration test checks a line of
narrow and wide letters in a real browser, so a PDF.js upgrade that breaks them fails
it.

## Listing and selecting

A single passage is shown on a card above the document. Several are listed beside it in
the order given, those that cannot be found last, each entry ending with its page. A
heading says which passage is selected and how many there are ("Passage 3 of 12"), with
buttons to step through them. The list scrolls when the passages do not fit, and fades
out at an end that has more beyond it; `useScrollEdges` and `fadeEdges`, in
`frontend-commons`, do this for any scrolling element.

A viewer narrower than `NARROW_VIEWER_WIDTH` shows passages one at a time above the
document instead. **It is the viewer's width that counts, not the screen's**
(`useNarrowViewer`), so a viewer in a side panel is narrow on any screen. There the card
can be swiped, left for the next and right for the previous; `useSwipe`, in
`frontend-commons`, does this for any element.

Selecting a passage takes the reader to it, and clicking a mark in the document selects
it. On opening, the viewer selects the first passage not known to be missing and goes to
it once found. It does not go there if the person has scrolled by hand in the meantime,
with a wheel, a finger, the scrollbar or a key (`isScrollByHand`).

A caller following or setting the selection, such as a review screen showing comments
beside the document, passes `selectedMarkId` and `onSelectMark`. The viewer reports each
passage the person selects through `onSelectMark`; setting `selectedMarkId` to another
mark selects it and takes the reader there. **Echoing back the mark the viewer just
reported does not move the reader**, but selecting it again later does.

With `markName`, the viewer counts and steps through the marks by that name: in the
heading, the buttons, the list and what a screen reader hears ("Comment 3 of 12 is
marked on page 4."). Changes found by a comparison keep their own name, and a control
about the text a mark covers, such as "Show the passage on page 4", still says passage.

## What the caller shows with each passage

`renderMarkDetails` is given the mark and whether it is selected, and what it returns
goes under the passage: on the passage's entry in the list, or, in a narrow viewer, only
on the selected passage's card. It sits outside the button that selects the passage, so
it can hold fields and buttons of its own, such as a box for a new comment. **Pass the
same function on every render**, as from `useCallback`, or every passage is redrawn each
time a search counts its matches.

| Where | Cut short past | Shown whole |
|---|---|---|
| The card above the document | `MAX_DETAILS_LINES` (6), or 3 on a phone | By its button ("Show the whole comment"), or while the focus is inside |
| A list entry not selected | `MAX_LINES` and `MAX_DETAILS_LINES` (2 each) | While the focus is inside, without selecting it |
| The selected list entry | Never | Always |

While what is on the card holds a field to type in (`TEXT_ENTRY`, from
`frontend-commons/textEntry`), such as a comment being written, the card stays open. The
card takes at most three fifths of the viewer's height and scrolls past that, so a
caller's field that grows as it is typed in should stop growing after a few rows, so
that its buttons stay in view.

## Commenting on a selection

With `onAnnotate`, the viewer offers a Comment button once text is selected and the
selection has settled: the pointer is up, and the selection has not changed for
`SETTLE_TIME` (300ms). The button sits under the end of the selection, inside the
document, and lower after a touch selection, clear of the phone's handles and menu.
Scrolling or zooming hides it until the document is still; Escape hides it until the
selection changes, without closing the viewer.

Pressing it gives `onAnnotate` the selected passage as a `TextAnchor` and clears the
selection. What happens next is up to the caller, typically adding the passage as a mark
and opening a comment box for it. A view built from the viewer's parts can follow the
selection with `useSelectedText`.

## Searching

The toolbar searches with PDF.js's own search, ignoring case and accents. Every match is
highlighted and the current one more deeply; the field says which match of how many,
counting as PDF.js searches, with a spinner until every page is done.

| Key | Does |
|---|---|
| Enter, Shift+Enter | The next match, the previous one, wrapping at the ends |
| Escape | Clears the search, or closes the field where it took the toolbar's place |
| Ctrl+F, Cmd+F | Searches, while the focus is in the viewer |

In a toolbar narrower than `SEARCH_FIELD_ROOM`, a Search button opens the field in the
toolbar's place.

## Sizes and limits

| Constant | Value | File | What it bounds |
|---|---|---|---|
| `NARROW_VIEWER_WIDTH` | 720px | `useNarrowViewer.ts` | Below it, passages one at a time above the document, and no side by side |
| `SPLIT_WITH_LIST_WIDTH` | 1440px | `ViewerBody.tsx` | Below it, side by side shows passages one at a time rather than listed |
| `SEARCH_FIELD_ROOM` | 600px | `ViewerToolbar.tsx` | Below it, the search field opens in the toolbar's place |
| `SETTLE_TIME` | 300ms | `useSelectedText.ts` | How long a selection is still before the Comment button shows |
| `CONTEXT_LENGTH` | 32 | `textAnchor.ts` | The characters an anchor keeps either side of its quote |
| `POINT_MIN_SIZE` | 22px | `markLayer.ts` | The least height of the box marking removed text |
| `POINT_HIT_SIZE` | 40px touch, 16px mouse | `markLayer.ts` | The least size of the target centred on a point mark |
| `DIFF_LIMITS` | 1s, 20,000 words | `revisionDiffModel.ts` | Versions more different than this are not compared |

## Using the parts on their own

| Part | What it does |
|---|---|
| `usePdfDocument` | Opens the file, and reads each page's text once |
| `useMarkPlaces` | Finds where the marks are as the pages are read |
| `usePdfView` | Shows the document in `PdfDocumentView`'s frame with the marks drawn, moves the reader, searches |
| `PdfDocumentView` | The frame PDF.js draws in, with the marks' colours, a spinner while it opens and a bar while it is read |
| `ViewerPlacement` | Puts a viewer in place or in a dialog |
| `ViewerTitle` | The viewer's top row |
| `ViewerToolbar` | The toolbar: what it is given at its start and end, around the search |
| `PageControls`, `ZoomControls` | The controls the viewer puts in the toolbar |
| `useSelectedText` | Follows the text selected in the document |

## Comparing two versions

Given an older version, `PdfViewer` shows what changed since, in a dialog as wide as the
screen up to its `xl` width:

```tsx
<PdfViewer
  url={current.file}
  label="Version 3"
  compareWith={{ url: previous.file, label: "Version 2" }}
  marks={[]}
  onClose={close}
/>
```

### How it compares

Only the text is compared, as PDF.js reads it for the text layer, in two passes: whole
sentences are matched first, then the words inside the sentences that changed. A common
word therefore counts as the same only within the changed sentences around it, and a
rewritten passage is one change rather than several around it. Two changes separated by
no more shared words than half the size of each change are joined into one
(`joinChanges`).

`getLenientKey` decides when two words are the same, and `getStrictKey` when they are
written the same. Spacing, line ends, case, the punctuation around a word and hyphens
inside it, which a line break may add, are ignored. Apostrophes count, so "patient's"
and "patients" differ, and a word with a digit keeps its separators, hyphens and sign,
so "1,000" and "1.000" differ, as do "IL-6" and "IL6". Words that match but differ in
case or punctuation, or a change of punctuation alone, are a **minor** change; a full
stop or capital that only moved because of the change beside it is no change at all.

Each version's pages are read into words with their page and offsets, so a change is
marked straight from them, even across a page break, and versions paginated differently
still compare.

| File | What it holds |
|---|---|
| `revisionDiffModel.ts` | The diff. `compareRevisions` uses jsdiff and returns a promise that rejects rather than throws; all the diffs one comparison runs share `DIFF_LIMITS` |
| `comparisonText.ts` | What the comparison says, on screen and to a screen reader |
| `useComparison.ts` | Opens the older version, compares as the two are read, and keeps the person's choices |
| `useComparedVersions.ts` | The older version's view, and what moves both versions |
| `useComparisonDisplay.tsx` | What the comparison adds to what the viewer shows |
| `useScrollSync.ts` | Scrolls the versions together |

Including or leaving out minor changes only shows or hides them: nothing is compared
again, and every change keeps its id. If a minor change is selected when they are left
out, the next change shown is selected instead.

**Each comparison numbers its changes anew**, as `change-<comparison>-<n>`. A different
`compareWith` compares in place: the newer version stays where the reader is, and the
older one opens at the matching place. A change selected in the last comparison, by the
viewer or its caller, selects nothing in the next, and the viewer picks a mark as it
does on opening.

### How changes look

Each change is a mark: `added` text in the newer version, `removed` text in the older.
Where text was removed, the newer version shows a translucent box around a text cursor,
at least `POINT_MIN_SIZE` high, that a finger can tap from up to 20px away (8px with a
mouse) without taking a tap meant for a mark beside it. Text that replaced removed text
starts with a bar in the colour of removed text, so it can be told apart from text that
was only added.

Selecting a change, by stepping, from the list or in either version, takes every version
shown to it.

With the newer version alone, changes are listed and stepped through among the caller's
marks, after those found and before any that cannot be found. Each is named as what it
is ("Change 2 of 7", "Comment 3 of 5"), with step buttons named after the mark they go
to (`getStepNames`). A change's card shows the removed text struck through beside the
added text, among a few words of context that stop short of the changes either side
(`getChangeInContext`, drawn by `frontend-commons`' `ValueChange`). Above the document
the card is always two lines high, so stepping does not move the document.

With the versions side by side, each shows its own side of every change, so changes have
no cards: they are stepped through with their own controls above the versions, and only
the caller's marks are listed.

### The menu

One menu, the same on every screen, picks how changes are shown, labelled "Show 7
changes" once there are some:

| Choice | Shows | Changes shown as |
|---|---|---|
| Off | The newer version alone | Nothing. The older version is not opened, and nothing is compared |
| On the newer version (the default) | The newer version alone | Added text, and a box with a cursor where text was removed |
| Side by side | Both versions, each under its label and its own page controls, with one search and one zoom | Removed text in the older version, added text in the newer |

Below a divider, "Include 4 minor changes" adds changes of punctuation and case alone.
Minor changes are counted among all changes found, included or not, so the numbers add
up. The choices are kept with `useStoredSetting`. A caption beside the menu says how
many minor changes are left out or included, or that there are none, and how far
comparing has got while it runs.

A narrow viewer has no room for side by side: the choice is disabled and the newer
version is shown alone, without overwriting a stored choice of side by side.

### Scrolling together

When shown side by side, the versions scroll together, matched by their text
(`useScrollSync`): whatever is in the middle of the version scrolled by hand goes to the
middle of the other, through the words the comparison paired (`mapPoint`). A word only
one version has goes to where its change is in the other, so the other version holds
still through text it lacks. The lines either side of the middle are each mapped
(`getTextLinesAround`) and the middle interpolated between them, so the other version
moves smoothly rather than a line at a time. A zoom, or a page picked from either
version's page controls, is followed too.

| Going to | The older version |
|---|---|
| A search match | Goes to its own match, so text only it has can be found; the versions move apart until the next scroll by hand |
| One of the caller's marks | Follows the newer version, which alone has the mark |
| A change | Goes to its own side of the change; the versions move apart until the next scroll by hand |

### Waiting and failing

While the versions are read and compared, a line says how far it has got and a bar runs
along the documents. A version without text, such as a scan, cannot be compared, and two
versions more different than `DIFF_LIMITS` allows are not. The viewer says so in either
case, and says the versions could not be compared if comparing fails.
