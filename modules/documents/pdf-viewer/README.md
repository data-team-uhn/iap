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
| `url` | The PDF to open. Another `url` opens that document afresh |
| `page` | The page to open at, from 1. Without it, the selected mark's page, or else the first. Another `page` takes the reader there, keeping the zoom and the search |
| `marks` | The passages to mark, in the order to list them ([Marks](#marks)) |
| `selectedMarkId`, `onSelectMark` | Follow and set the selection ([Listing and selecting](#listing-and-selecting)) |
| `markName` | What the marks are called, such as `{ one: "comment", many: "comments" }`. Passages by default |
| `renderMarkDetails` | What to show under each passage ([What the caller shows](#what-the-caller-shows-with-each-passage)) |
| `onAnnotate` | Offers a Comment button on selected text ([Commenting](#commenting-on-a-selection)) |
| `title` | The viewer's title. The PDF's file name by default |
| `compareWith`, `label` | An older version to compare with, and this version's label ([Comparing](#comparing-two-versions)) |
| `container` | An element to fill instead of opening in a dialog |
| `onClose` | Called when the person closes the viewer |

The viewer opens in a dialog. Given a `container`, it fills that element instead, and
can pop out into a dialog and back, keeping the page, the zoom, the marks and the
search. The element needs a height of its own. A `container` that is `null`, as a ref is
before React attaches it, shows nothing until the element is there.

A spinner shows in the frame until PDF.js has laid out the pages. On a phone, the space
around and between the viewer's rows is kept small, and the document runs to the bottom
of the screen. Scrolling the document stops at its ends, rather than scrolling the
dialog that holds it.

## Marks

Each mark has an `id`, a `kind`, and its place, given in one of three ways:

| Place | For | How it is placed |
|---|---|---|
| `quote`, with an optional `page` | A passage known by its text, such as an assistant's evidence | Looked for from `page` to the end, then from the first page |
| `spans` | A passage whose place is known, such as a change between two versions | Where it says. Spans that run backwards or past their page's text are not in the document |
| `anchor` | A passage kept to be shown again, such as one a person commented on | Where it says while the text there still says its quote, otherwise found again ([Anchors](#anchors)) |

The `kind` sets the mark's colour: `assistant` or `annotation` for whose passage it is,
`added` or `removed` for what a comparison found.

A quote is matched across a page break, ignoring spacing, case, accents and typographic
quotes. It also ignores any hyphen or dash that is not before a digit. Once found, a
quote is kept as spans too.

A span is a `page` and the `start` and `end` offsets it covers in that page's text, one
span for each page the passage runs over. Equal offsets make a point between two
characters, drawn after the character before it. With `starts`, the point is drawn
before the character after it instead, as the start of the text that follows.

Offsets are into a page's text as PDF.js reads it for its text layer: the strings
`getTextContent` gives with `disableNormalization`, joined. It is the text of the
layer's own text nodes, and `usePdfDocument`'s `getPageText` gives it. The text layer
ends a line with an element rather than a character, so lines run on in this text
without a space. `getPageTextWithLines` says where they end, and `getPassageText` puts a
space there. Selected text maps back to the same offsets: `getSelectedSpans` gives a
range as one span on each page it covers, leaving out the spaces at either end.

The viewer draws every mark on PDF.js's text layer, under the text. Matches of PDF.js's
own search therefore show over the marks. Marks let the pointer through, so text can
still be selected across them, and a click goes to the mark where it falls. A mark is
shown with its quote, or, when placed by its spans, with the text they cover.

## Anchors

A `TextAnchor` keeps a passage as the W3C's text position and quote selectors do:

| Field | What it is |
|---|---|
| `start`, `end` | Where the passage starts and ends, each a `page` and a `char` offset into that page's text |
| `quote` | What the passage says |
| `before`, `after` | Up to `CONTEXT_LENGTH` (32) characters of the text either side, on the passage's pages |

`getTextAnchor` makes one from the spans of a passage.

The quote is what is trusted, since a later PDF.js may read the same file into different
offsets. When the text at the offsets no longer says the quote, the viewer looks for the
quote everywhere. It takes the occurrence whose surrounding text is most like `before`
and `after`. A tie goes first to an occurrence whole on one page, then to the one
nearest the anchor's page. A quote too short to trust, such as a single word, is taken
only where some of its surrounding text is still the same.

An anchor is stored as text with what it anchors, such as a review comment.
`encodeAnchor` gives an `EncodedAnchor`, and `decodeAnchor` reads one back, or gives
nothing for one it cannot read. Its `start` and `end` are each written
`page=3&char=120`, as in PDF.js's open parameters and RFC 5147's text fragments. Its
`text` is a text fragment directive without its `text=`: `before-,quote,-after`, each
part percent-encoded, dashes included.

## Reading the pages

The viewer reads pages only while some passage is still being looked for. It reads the
pages marks name first: those of their spans or anchors, and the page a quote is looked
for from. A passage that is where it says is then found without waiting for the pages
before it. A document opened with no passages is not read at all. Meanwhile, a bar along
the top of the document shows how much has been read. `useMarkPlaces` gives that count
with where the passages are.

`marks` can be a new array on every render. The viewer compares marks by `id`, `kind`
and place. A mark that has not changed keeps the place found for it, so adding a comment
looks only for the new one.

## The text over the page

Marks, selections and search matches all sit on PDF.js's text layer: transparent text
laid over each page. PDF.js sets it in a generic font, `sans-serif`, `serif` or
`monospace`, and stretches each piece of text to the width the page draws it. A
character inside a piece then sits where the generic font puts it. On a line of narrow
and wide letters, that is tens of pixels from its glyph, and selecting it picks up a
letter beside it.

`textLayerFonts` sets each piece in the font PDF.js loaded to draw the page, at the same
width. Once a page has both its text and its fonts, the viewer aligns it and draws its
marks again. This relies on PDF.js internals, listed in that file. Where they do not
hold, the text keeps PDF.js's own layout. The `pdf-text-layer` integration test checks a
line of narrow and wide letters in a real browser, so a PDF.js upgrade that breaks them
fails it.

## Listing and selecting

A single passage is shown on a card above the document. Several are listed beside the
document in the order given, those that cannot be found last. Each entry ends with the
page the passage was found on, or, for a mark placed where it was known to be, its page.

A viewer narrower than `NARROW_VIEWER_WIDTH` shows them one at a time above the document
instead, with controls to step through them. It is the viewer's width that counts, not
the screen's (`useNarrowViewer`), so a viewer in a side panel is narrow on any screen.
The card can be swiped: left to the next passage, right to the previous. It follows the
finger and springs back when let go too soon. The next card slides in the way the finger
went, unless the person prefers reduced motion. `useSwipe`, in `frontend-commons`, does
this for any element.

Above the list, a heading says which passage is selected of how many ("Passage 3 of
12"), with buttons to step through them. `StepControls` keeps that text as wide as the
widest it can show, so stepping from 9 to 10 does not move the buttons. The list lies on
`background.default`, as the pages do, with each entry a card. It scrolls when the
passages do not fit, and fades out at an end with more beyond it (`useScrollEdges` says
where, `fadeEdges` draws it).

Selecting a passage in the list takes the reader to it. Clicking a mark in the document
selects its entry and brings it into view, or, in a narrow viewer, shows it above the
document. On opening, the viewer selects the first passage not known to be missing, and
takes the reader to it once found. It does not if the person has meanwhile scrolled by
hand: a wheel, a finger, the scrollbar or a scrolling key (`isScrollByHand`).

A caller that follows or sets the selection, such as a review screen showing comments
beside the document, passes `selectedMarkId` and `onSelectMark`. The viewer reports each
passage the person selects through `onSelectMark`. When the caller sets `selectedMarkId`
to another mark, the viewer selects it and takes the reader to it. Echoing back the mark
the viewer just reported does not move the reader, but selecting it again later does.

With `markName`, the viewer counts and steps through the marks by that name. It does so
in the heading, the buttons, the list's labels, and what a screen reader hears ("Comment
3 of 12 is marked on page 4."). Changes found by a comparison keep their own name. A
control about the text a mark covers, such as "Show the passage on page 4", still says
passage.

## What the caller shows with each passage

`renderMarkDetails` is given the mark and whether it is selected, and what it returns
goes under the passage. In the list, that is on the passage's entry. In a narrow viewer,
only the selected passage has it, on the card above the document. It sits outside the
button that selects the passage, so it can hold fields and buttons of its own, such as a
box for a new comment. The passage's own text sits on a deeper shade of its mark's
colour, apart from what the caller shows. Give the same function on every render, as
from `useCallback`, or the passages are drawn again each time a search counts its
matches.

| Where | Cut short past | Shown whole |
|---|---|---|
| The card above the document | `MAX_DETAILS_LINES` (6), or 3 on a phone | By its button ("Show the whole comment"), or while the focus is inside |
| A list entry not selected | `MAX_LINES` and `MAX_DETAILS_LINES` (2 each) | While the focus is inside, without selecting it |
| The selected list entry | Never | Always |

What is cut short fades out where it is cut. While what is under the passage on the card
holds a field to type in (`TEXT_ENTRY`, from `frontend-commons/textEntry`), such as a
comment being written, the card stays open. Once the field goes, as when the comment is
saved, the card is again as the person chose. The card takes at most three fifths of the
viewer's height and scrolls past that. A caller's field that grows as it is typed in
should stop after a few rows, to keep its buttons beside it.

## Commenting on a selection

With `onAnnotate`, the viewer offers a Comment button once someone has selected text and
the selection has settled: the pointer let go, and nothing changing for `SETTLE_TIME`
(300ms). The button sits under the end of the selection, runs towards the side with more
room, and stays inside the document. After a selection by touch, it sits lower, clear of
the phone's handles and of the menu the phone shows.

Scrolling or zooming hides the button until the document is still again. Escape hides it
until the selection changes or the document moves, without closing the viewer. Pressing
it gives `onAnnotate` the selected passage as a `TextAnchor` and clears the selection,
so the caller's mark of it shows. What follows is the caller's: typically adding the
passage as a mark and opening a comment box for it.

On a phone, a long press selects text, as on any page. A view built from the viewer's
parts can follow the selection with `useSelectedText`.

## Searching

The toolbar searches the document with PDF.js's own search, ignoring case and accents.
Every match is highlighted, the one shown more deeply. The field says which match is
shown of how many, and its up and down buttons step through them, wrapping at the ends.
The count grows as PDF.js searches, with a spinner until every page is done.

| Key | Does |
|---|---|
| Enter, Shift+Enter | The next match, the previous one |
| Escape | Clears the search, or closes the field where it took the toolbar's place |
| Ctrl+F, Cmd+F | Searches, while the focus is in the viewer |

In a toolbar narrower than `SEARCH_FIELD_ROOM` (600px), a Search button opens the field
in the toolbar's place.

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
| `PageControls`, `ZoomControls` | What the viewer gives the toolbar |
| `useSelectedText` | Follows the text selected in the document |

## Comparing two versions

Give `PdfViewer` an older version, and it shows what changed since:

```tsx
<PdfViewer
  url={current.file}
  label="Version 3"
  compareWith={{ url: previous.file, label: "Version 2" }}
  marks={[]}
  onClose={close}
/>
```

A comparison opens in a dialog as wide as the screen, up to its `xl` width.

### How it compares

Only the text is compared, as PDF.js reads it for the text layer. It works like a diff
of lines followed by a diff of words: whole sentences are matched first, then the words
inside the sentences that changed. A common word, such as a recurring term, therefore
counts as the same only inside a matching sentence. A rewritten passage is not split
into several changes around it. Two changes in a sentence with only a few words between
them, no more than half the size of each, are joined into one (`joinChanges`).

`getLenientKey` decides when two words are the same, and `getStrictKey` when they are
written the same. It ignores spacing, line ends, case, the punctuation around a word,
and hyphens inside it, which a line break may add. Apostrophes count, so "patient's" and
"patients" differ. A word with a digit keeps its separators, hyphens and sign, so
"1,000" and "1.000" differ, as do "IL-6" and "IL6". Two words that match but differ in
case or punctuation are a minor change, and so is a change of punctuation alone. A full
stop or a capital that only moved because of the change beside it is not a change at
all.

Each version's pages are read into words, each with its page and offsets. A change is
therefore marked straight from them, even across a page break, and versions that break
into pages differently still compare.

### Where the code is

| File | What it holds |
|---|---|
| `revisionDiffModel.ts` | The diff. `compareRevisions` uses jsdiff and returns a promise that rejects rather than throws. All the diffs one comparison runs share `DIFF_LIMITS` |
| `comparisonText.ts` | What the comparison says, on screen and to a screen reader |
| `useComparison.ts` | Opens the older version, compares as the two are read, and keeps the person's choices |
| `useComparedVersions.ts` | The older version's view, and what moves both versions |
| `useComparisonDisplay.tsx` | What the comparison adds to what the viewer shows |
| `useScrollSync.ts` | Scrolls the versions together |

Including or leaving out minor changes only shows or hides them. Nothing is compared
again, and every change keeps its id, while the number shown counts the changes shown.
If a minor change is selected when they are left out, the next change shown is selected
instead.

Another `compareWith` compares in place: the newer version stays where the reader is,
and the older one opens at the matching place once compared. Each comparison numbers its
changes afresh (`change-<comparison>-<n>`). A change selected in the last comparison, by
the viewer or its caller, therefore selects nothing in the next. The viewer then picks a
mark as it does on opening.

### How changes look

Each change is a mark of its own kind: `added` text is marked in the newer version, and
`removed` text in the older. Where text was removed, the newer version shows a
translucent box around a text cursor, a little taller than the line and at least
`POINT_MIN_SIZE` high. A finger can tap it from up to 20px away (8px with a mouse), but
a tap on a mark beside it still goes to that mark. Text that replaced removed text
starts with a bar in the colour of removed text, so it reads differently from text
simply added.

Selecting a change, by stepping, from the list or in either version, takes every version
shown to it. A screen reader hears what changed and where ("Change 3 of 17. Text removed
on page 4. It said “…”.").

With the newer version alone, the changes are listed and stepped through with the
caller's marks: after the marks found, and before any that cannot be. Each is named and
numbered as what it is ("Change 2 of 7", "Comment 3 of 5"). The step buttons are named
after the mark they go to (`getStepNames`). A change's card shows the removed text
struck through and the added text, among a few words around them (`getChangeInContext`,
drawn by `ValueChange`). It stops short of the changes either side. A screen reader
hears it as a sentence instead, since it does not read inside the card's button. A
change's card is plain paper with a grey (`divider`) stripe and outline. Above the
document, it is always two lines high, so stepping does not move the document. A longer
change fades out where it is cut, and the card's button shows all of it.

Side by side, where each version shows its own side of every change, changes have no
cards. They are stepped through with their own controls above the versions, and only the
caller's marks are listed.

### The menu

One menu, the same on every screen, picks how changes are shown. It says "Show 7
changes" once there are some, and "Show changes" until then:

| Choice | Shows | Changes shown as |
|---|---|---|
| Off | The newer version alone | Nothing. The older version is not opened, and nothing is compared |
| On the newer version (the default) | The newer version alone | Added text, and a box with a cursor where text was removed |
| Side by side | Both versions, each under its label and its own page bar, with one search and one zoom | Removed text in the older version, added text in the newer |

Below a divider, "Include 4 minor changes" adds the changes of punctuation and case
alone, and leaves the menu open. The menu opens on the current choice, and the keyboard
can reach a choice there is no room for, to hear why. Minor changes are counted among
all the changes found, included or not, so the numbers add up. The choices are kept with
`useStoredSetting`.

A caption after the menu says how many minor changes are left out ("3 minor changes of
punctuation or letter case left out.") or included, or that there are no changes. While
the versions are read and compared, it says how far that has got. It sits on the menu's
row when there is room, and wraps below it otherwise. A narrow viewer leaves the minor
changes to the menu once there are other changes.

A narrow viewer has no room for side by side: the choice is greyed out, and the newer
version is shown alone whatever was chosen. It does not overwrite a stored choice of
side by side. A screen reader hears what the comparison found once it is done, even
while the status is about the selected mark.

### Scrolling together

Side by side, the versions scroll together, matched by their text (`useScrollSync`).
Whatever is in the middle of the version scrolled by hand goes to the middle of the
other, through the words the comparison paired (`mapPoint`). A word only one version has
goes to where its change is in the other, so the versions move at different speeds
through added or removed text. The matching text is also put as far down its line as in
the version scrolled, so the other version moves smoothly rather than a line at a time.
After a zoom, or a page picked from either page bar, the other version follows.

| Going to | The older version |
|---|---|
| A search match, the next or the previous | Goes to its own match, so text only it has can be found. The versions part until the next scroll by hand |
| One of the caller's marks | Follows the newer version, which alone has the mark |
| A change | Goes to its own side of the change. The versions part until the next scroll by hand |

### Waiting and failing

While the versions are read and compared, a line says how far it has got, and a bar runs
along the documents. A version without text, such as a scan, cannot be compared. Two
versions more different than `DIFF_LIMITS` allows are not. The viewer says so in either
case, and says the versions could not be compared if comparing them fails.
