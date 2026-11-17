# IAP PDF viewer

**Module:** `modules/documents/pdf-viewer` · **Bundle:** `iap-documents-pdf-viewer` ·
**Imported as:** `@iap/documents-pdf-viewer/…`, chiefly `PdfViewer`

`PdfViewer` shows a PDF with PDF.js's own viewer, and finds and marks passages in it,
such as the evidence an AI assistant quotes or the passages people comment on. Given an
older version of the same document, it also shows what changed.

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

The document opens at `page`, or at the selected mark's page when there is none. Another
`page` given later takes the reader there, keeping the zoom and the search; another
`url` opens that document afresh.

It opens in a dialog. Given a `container` element, it fills that element instead, and
can pop out into a dialog and back, keeping the page, the zoom, the marks and the
search. A `container` that is `null`, as a ref is before React attaches it, shows
nothing until the element is there. The element needs a height of its own.

A spinner shows in the frame until PDF.js has laid out the document's pages. On a phone,
the space between the viewer's rows and around it is kept small, and the document runs
to the bottom of the screen. Scrolling the document stops at its ends instead of
scrolling the dialog holding it.

## Marks

Each mark has a `kind`: whose mark it is, an AI assistant's (`assistant`) or a person's
(`annotation`), or what a comparison found (`added`, `removed`), so it is coloured
accordingly. Its place is given in one of three ways:

- a `quote`, looked for from the mark's own `page` to the end, then from the first page.
  A passage is matched across a page break, ignoring spacing, case, accents, typographic
  quotes, and any hyphen or dash not before a digit;
- `spans`, when its place is known exactly, such as a change between two versions. Each
  span is a `page` and the `start` and `end` offsets the passage covers in that page's
  text, one span per page it runs over. A span whose `start` equals its `end` is a point
  between two characters. Spans that run backwards or past their page's text are not in
  the document;
- an `anchor`, for a passage kept to be shown again, such as one a person selected to
  comment on. It is placed where it says as soon as its pages are read, while the text
  there still says its quote, and otherwise found again, as [Anchors](#anchors)
  describes.

Offsets are into a page's text as PDF.js reads it for its text layer: the strings
`getTextContent` gives with `disableNormalization`, joined. That is the text of the
layer's own text nodes, and `usePdfDocument`'s `getPageText` gives it. The text layer
ends a line with an element rather than a character, so lines run on in this text
without a space. `getPageTextWithLines` says where they end, and `getPassageText` puts a
space there when it shows a passage. Text selected in the text layers maps back to the
same offsets: `getSelectedSpans` gives what a range selects as a span on each page it
selects text of, without the spaces at either end.

The viewer draws every mark on PDF.js's text layer itself, under the text, so the
matches of PDF.js's own search, which it leaves alone, show over the marks. Marks let
the pointer through, so text can still be selected across them, and a click is matched
to a mark by where it falls. Once found, a quote is kept as spans too. A mark with a
quote or an anchor is shown with its quote, and one placed by its spans with the text
they cover.

## Anchors

A `TextAnchor` keeps a passage the way the W3C's position and quote selectors do: where
it starts and where it ends, each a `page` and a `char` offset into that page's text;
what it says (`quote`); and up to 32 characters of the text `before` and `after` it on
its pages, as the text reads (`CONTEXT_LENGTH`). `getTextAnchor` makes one from the
spans of a passage.

The offsets hold only for the text PDF.js reads today, and a later PDF.js may read the
same file differently, so the quote is what is trusted. When the text at the offsets no
longer says it, the viewer looks for the quote everywhere. It takes the occurrence whose
surrounding text is most like the anchor's `before` and `after`. Ties go to an
occurrence whole on a page over one across a break, then to the one nearest the anchor's
page. A quote too short to trust, such as a single word, is only taken where some of its
surrounding text is still the same.

An anchor is kept as text with what it anchors, such as a review comment. `encodeAnchor`
gives it as an `EncodedAnchor`, and `decodeAnchor` reads it back. Its `start` and `end`
are each written `page=3&char=120`, as in PDF.js's open parameters and RFC 5147's text
fragments. Its `text` is written as a text fragment directive without its `text=`:
`before-,quote,-after`, each part percent-encoded, dashes included. `decodeAnchor` gives
nothing for an anchor not kept, or one it cannot read.

## Reading the pages

The viewer reads pages only while some passage is still being looked for. It reads first
the pages marks say they are on: those of their spans or anchors, and the page a quote
is looked for from. A passage that is where it says is then found without waiting for
the pages before it, and a document opened with no passages is not read at all.
Meanwhile, a bar along the top of the document shows how much of it has been read.
`useMarkPlaces` gives how many pages have been read, along with where the passages are.

`marks` can be a new array on every render: the viewer compares marks by `id`, `kind`
and where they are (`quote` and `page`, `spans`, or `anchor`). A mark that has not
changed keeps the place found for it, so adding a mark, such as a new comment, leaves
the others where they are and looks only for the new one.

## The text over the page

Marks, selections and search matches are drawn on PDF.js's text layer: transparent text
laid over each page. PDF.js sets it in a generic font, `sans-serif`, `serif` or
`monospace`, and stretches each piece of text to the width the page draws it. A
character inside a piece then sits where the generic font puts it, up to tens of pixels
from its glyph on a line mixing narrow and wide letters. Selecting it could pick up a
letter beside it.

`textLayerFonts` sets each piece in the font PDF.js loaded to draw the page, and keeps its
width. Once a page has both its text and its fonts, the viewer aligns it and draws its
marks again. This relies on PDF.js internals, listed in that file: where they do not hold,
the text keeps PDF.js's own layout. The `pdf-text-layer` integration test checks a line of
narrow and wide letters in a real browser, so a PDF.js upgrade that changes them fails it.

## Listing and selecting

A single passage is shown on a card above the document, and several are listed beside
the document, in the order given, with those that cannot be found last. A card or list
entry ends with the page the passage was found on, or, for a mark placed where it was
known to be, such as a comment or a change, just its page.

In a viewer narrower than `NARROW_VIEWER_WIDTH` (720px) they are shown one at a time
above it instead, with controls stepping through them. It is the viewer's width that
counts, not the screen's (`useNarrowViewer`): a viewer in a side panel is narrow on any
screen. The card above the document can also be swiped by touch: left to the next
passage, right to the previous, as far as the ends. It follows the finger, springs back
when let go of too soon, and the next card slides in the way the finger went, unless the
person prefers reduced motion. `useSwipe`, in `frontend-commons`, does this for any
element that steps through items by touch.

Above the list, a heading says which passage is selected of how many ("Passage 3 of
12"), with buttons stepping through them. `StepControls` keeps the text between its
buttons as wide as the widest it can show, drawn unseen under it, so stepping from 9 to
10 does not move the buttons. The list lies on the grey of `background.default`, as the
pages do, with each card on the paper. It scrolls when the passages do not fit, and
fades out at its top or bottom where there are more (`useScrollEdges` says where, and
`fadeEdges` draws the fade).

Selecting a passage from the list scrolls the document to it. Clicking a mark in the
document selects its card in the list and brings the card into view, or, in a narrow
viewer, shows it above the document. When the viewer opens, it selects the first passage
in the order given that is not known to be missing, and takes the reader to it once
found, unless they have scrolled the document by hand meanwhile, by a wheel, a finger,
its scrollbar or a key that scrolls (`isScrollByHand`). The selection stays there until
the person selects another.

A caller that needs to follow or set the selection, such as a review screen showing
comments beside the document, passes `selectedMarkId` and `onSelectMark`. The viewer
reports each passage the person selects through `onSelectMark`. When the caller sets
`selectedMarkId` to another mark, the viewer selects it and takes the reader to it, as
if it had been picked from the list. Echoing back the mark the viewer just reported does
not move the reader; selecting that mark again later does.

A caller whose marks are more than passages, such as review comments, passes `markName`,
the word for one and for several (`{ one: "comment", many: "comments" }`). Changes found
by a comparison keep their own name. The viewer uses it wherever it counts or steps
through the marks: the heading over the list, the buttons, the list's labels, and what a
screen reader is told ("Comment 3 of 12 is marked on page 4."). A control about the
passage's own place in the document, such as "Show the passage on page 4", still names
the passage.

## What the caller shows with each passage

A caller that shows something with each passage, such as the comments on it, passes
`renderMarkDetails`, which is given the mark and whether it is the one selected. What it
gives goes under the passage on its card in the list beside the document. A narrow
viewer shows only the selected passage, and puts it on the card above the document. It
goes outside the button selecting the passage, so it can hold fields and buttons of its
own, such as a box for a new comment. The passage's own text sits on a deeper shade of
its mark's colour than the card, setting it apart from what the caller shows under it.

On the card above the document, what it gives is cut short past a few lines
(`MAX_DETAILS_LINES`, 6, or 3 on a phone) and fades out where it is cut. One button
shows all of the passage and of what is under it ("Show the whole comment", named after
the mark on the card). Moving the focus into what is cut, such as onto a reply button,
shows all of it too. While what is under the passage holds a field to type in
(`TEXT_ENTRY`), such as a comment being written, the card stays open whatever the person
chose. Once the field goes, as when the comment is saved, the card is again as they
chose.

The card takes at most three fifths of the viewer's height and scrolls past that, so the
document keeps some room. A caller's field that grows with what is typed should stop
growing after a few rows, so that its buttons stay beside it.

In the list, every entry but the selected one is cut short to a couple of lines, the
passage and what is under it alike, and the selected one is shown whole. Moving the
focus into what is cut, such as onto a reply box in another entry, shows all of it while
the focus is there, without selecting the entry.

## Commenting on a selection

A caller that lets people comment on passages, such as a review screen, passes
`onAnnotate`. Once someone has selected text in the document and the selection has
settled (the pointer let go of, and nothing changing for `SETTLE_TIME`, 300ms), the
viewer offers a Comment button under the end of the selection. It runs towards the side
of the document with more room, and stays inside it. When the text was selected by
touch, the button sits lower, clear of the phone's handles and of the menu the phone
shows above the selection.

Scrolling hides the button until the document is still again. Zooming in PDF.js clears
the selection, and the button with it. Escape hides the button until the selection
changes, without closing the viewer. Pressing it gives `onAnnotate` the passage
selected, as a `TextAnchor`, and clears the selection, so the mark the caller makes of
it shows. What happens next is the caller's to decide, such as adding the passage as a
mark and opening a comment box for it.

Text in a PDF can only be selected with a pointer, so commenting on a passage needs one
too. A view built from the viewer's parts can follow the selection with
`useSelectedText`.

## Searching

The toolbar also searches the document for a word or phrase, with PDF.js's own search,
ignoring case and accents. Every match is highlighted, and the one shown more deeply.
The field says which match is shown of how many, and the up and down buttons beside it
step through them, wrapping round at the ends. The count grows as PDF.js searches the
pages, with a spinner beside it until every page is searched. In the field, Enter and
Shift+Enter do the same, and Escape clears the search. Where the toolbar is narrower
than `SEARCH_FIELD_ROOM` (600px), a button opens the field in the toolbar's place, and
Escape closes it. Ctrl+F, or Cmd+F, searches while the focus is in the viewer.

## Using the parts on their own

`usePdfDocument` opens the file and reads each page's text once, `useMarkPlaces` finds
where marks are as those pages are read, and `usePdfView` shows the document in a frame,
with the marks drawn, moves the reader, and searches it. `ViewerPlacement` puts a viewer
in place or in a dialog, `ViewerTitle` is its top row, and `ViewerToolbar` its toolbar.
The toolbar lays out what it is given at its start and end around the search: the viewer
gives it `PageControls` and `ZoomControls`.

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

### How it compares

Only the text is compared, as PDF.js reads it for the text layer. It works like a diff
of lines followed by a diff of words: whole sentences are matched first, then the words
inside the sentences that changed. That way a common word, such as a recurring term,
only counts as the same in both versions inside a matching sentence, and a rewritten
passage is not split into several changes around it. Inside changed sentences, two
changes with only a few shared words between them, no more than half the size of each,
are joined into one (`joinChanges`).

`getWordKey` decides when two words are the same. It ignores spacing, line ends, case,
the punctuation around a word, and hyphens inside it, which a line break may add or
remove. Apostrophes count, so "patient's" and "patients" differ. A word with a digit in
it keeps its separators, hyphens and sign, so "1,000" and "1.000" differ, as do "IL-6"
and "IL6". Two words that match but still differ in case or punctuation are a minor
change, and so is a change of punctuation alone. A full stop or a capital that only
moved because of the change next to it, such as a full stop now after an added word, is
not a change at all.

Each version's pages are read into words, each with its page and offsets, so a change is
marked straight from them, even across a page break, and versions that break into pages
differently still compare.

### Where the code is

The diff is in `revisionDiffModel.ts`. `compareRevisions` uses jsdiff and returns a
promise, so it can move to a worker later without its callers changing, and it rejects
rather than throws. Its time and word limits are shared by all the diffs it runs. What
the comparison says, on screen and to a screen reader, is in `comparisonText.ts`.
`useComparison` opens the older version, compares the two as they are read, and keeps
the person's choices. Including or leaving out minor changes only shows or hides them:
nothing is compared again, and every change keeps its number. When a minor change is
selected and minor changes are then left out, the next change shown is selected instead.

Passing another `compareWith` compares in place, as a version picker will need: the
newer version stays open where the reader is, and the older one opens at the matching
place once compared. Each comparison numbers its changes afresh
(`change-<comparison>-<n>`), so a change selected in the last comparison, whether the
viewer or its caller remembers it, selects nothing in the next, and the viewer picks a
mark as it does on opening.

### How changes look

Each change is a mark of its own kind, `added` or `removed`: added text is marked in the
newer version, and removed text in the older. Where text was removed, the newer version
shows a translucent box around a text cursor, a little taller than the line and at least
22px high (`POINT_MIN_SIZE`). A finger can tap it within 40px, but a tap on a mark next
to it still goes to that mark. Text that replaced removed text starts with a bar in the
colour of removed text, so it reads differently from text simply added. Selecting a
change, by stepping, from the list or in either version, takes every version shown to
it, and tells a screen reader what changed and where ("Change 3 of 17. Text removed on
page 4. It said “…”.").

With the newer version alone, the changes are listed and stepped through with the
caller's marks: after the marks found, and before any that cannot be. Each mark is named
and numbered as what it is ("Change 2 of 7", "Comment 3 of 5"), whatever the caller
calls its own marks, and the step buttons are named after the mark they go to
(`nameMarks`). A change's card shows what was removed, struck through, and what was
added, among a few of the words around it, stopping short of the changes either side
(`getChangeInContext`, drawn inline by `ValueChange`), and the page it is on. A screen
reader does not read inside the card's button, so it hears the change as a sentence
instead. A change's card has no colour of its own: it is a card on the paper with a grey
(`divider`) stripe and outline. Above the document, a change's card is always two lines
high, so stepping does not move the document: a longer change fades out where it is cut,
and the card's button shows all of it.

Side by side, where the versions already show both sides of each change, changes have no
cards. They are stepped through with their own controls above the versions, and only the
caller's marks are listed.

### The menu

One menu, the same on every screen, picks how changes are shown. It says how many there
are once there are some ("Show 7 changes"), and "Show changes" until then:

| Choice | Shows | Changes shown as |
|---|---|---|
| Off | The newer version alone | Nothing. The older version is not opened, and nothing is compared |
| On the newer version (the default) | The newer version alone | Added text, and a box with a cursor where text was removed |
| Side by side | Both versions, each under its label and its own page bar, with one search and one zoom | Removed text in the older version, added text in the newer |

Below a divider, "Include 4 minor changes" adds the changes of punctuation and case
alone. Checking it leaves the menu open. It opens on the current choice, and the
keyboard can reach a choice there is no room for, to hear why. Minor changes are counted
among all the changes found, included or not, so the numbers add up.

A caption after the menu says how many minor changes are left out ("3 minor changes of
punctuation or letter case left out.") or included ("Including 3 minor changes of
punctuation or letter case."), or that there are no changes. While the versions are read
and compared, it says how far that has got instead. It sits on the menu's row when there
is room, and wraps below it when there is not, as on a phone. A viewer narrower than
`NARROW_VIEWER_WIDTH` leaves the minor changes to the menu once there are other changes,
and only mentions them when they are all there is.

A viewer narrower than `NARROW_VIEWER_WIDTH` has no room for side by side: the choice is
greyed out in the menu, and the newer version is shown alone, whatever was chosen. Side
by side, the caller's marks are listed beside the versions only in a viewer at least
`SPLIT_WITH_LIST_WIDTH` (1440px) wide, and shown one at a time above them otherwise. The
choices are kept with `useStoredSetting`, and a narrow viewer does not overwrite a
choice of side by side. A comparison opens in a dialog as wide as the screen, up to its
`xl` width. A screen reader hears what the comparison found once it is done, even while
the status is about the selected mark.

### Scrolling together

Side by side, the versions scroll together, matched by their text (`useScrollSync`).
Whatever is in the middle of the version scrolled by hand goes to the middle of the
other, through the words the comparison paired (`mapPoint`). A word only one version has
goes to where its change is in the other, so the versions move at different speeds
through added or removed text. The matching text is also put as far along its line, so
the other version moves as smoothly. After a zoom, or a page picked from either
version's page bar, the other version follows.

A search, and each next or previous match, takes each version to its own match, so text
only the older version has can be found too, and the versions part until the next scroll
by hand. Going to one of the caller's marks, which only the newer version has, the older
version follows the newer. Going to a change takes each version to its own side of it,
and they stay apart until the next scroll by hand.

### Waiting and failing

While the versions are read and compared, a line says how far it has got, and a bar runs
along the documents. A version without text, such as a scan, cannot be compared, and two
versions too different for the diff's limits (`DIFF_LIMITS`: 1 second, and 20,000 words
added and removed) are not. The viewer says so in either case, and says the versions
could not be compared if comparing them fails.
