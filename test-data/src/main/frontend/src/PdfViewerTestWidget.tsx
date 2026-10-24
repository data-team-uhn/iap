/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useRef, useState, type KeyboardEvent } from "react";

import { Box, Button, Checkbox, FormControlLabel, MenuItem, Stack, TextField, Typography } from "@mui/material";

import {
  PASSAGE_KINDS,
  type Mark,
  type PassageKind,
  type TextAnchor,
} from "@iap/frontend-commons/pdfViewer/marksModel";
import PdfViewer, { type PdfViewerProps } from "@iap/frontend-commons/pdfViewer/PdfViewer";
import { decodeAnchor, encodeAnchor, type EncodedAnchor } from "@iap/frontend-commons/pdfViewer/textAnchor";

// What the form said when Open was pressed.
type Shown = Pick<PdfViewerProps, "url" | "page" | "title" | "compareWith"> & {
  passages: Mark[];
  commenting: boolean;
  inPlace: boolean;
};

// Whose marks the passages are: one kind for all, or both in turn.
type MarkedBy = PassageKind | "both";

// A comment on a passage, with its anchor kept as text. A draft has not been saved yet.
interface PassageComment {
  id: string;
  anchor: EncodedAnchor;
  text: string;
  draft: boolean;
}

const COMMENTS = { one: "comment", many: "comments" };
// What the marks are called when they are both passages and comments. PdfViewer calls changes changes.
const MARKS = { one: "mark", many: "marks" };

// A dashboard widget for trying PdfViewer by hand on any reachable PDF, with any of what it does: passages
// to look for, given one per line; comments on passages selected in the document; and an older version to
// compare it with. Open shows what the form says at that moment, so editing the form afterwards does not
// reopen the document. The comments last until the viewer is opened again. Each keeps its anchor encoded
// as text, and its mark is decoded from that text.
function PdfViewerTestWidget() {
  const [url, setUrl] = useState("");
  const [page, setPage] = useState("");
  const [quotes, setQuotes] = useState("");
  const [markedBy, setMarkedBy] = useState<MarkedBy>("assistant");
  const [olderUrl, setOlderUrl] = useState("");
  const [commenting, setCommenting] = useState(true);
  const [title, setTitle] = useState("");
  const [inPlace, setInPlace] = useState(true);
  const [place, setPlace] = useState<HTMLDivElement | null>(null);
  const [shown, setShown] = useState<Shown | undefined>(undefined);
  const [comments, setComments] = useState<PassageComment[]>([]);
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [draftText, setDraftText] = useState("");
  const [count, setCount] = useState(0);
  const draftInput = useRef<HTMLTextAreaElement>(null);
  const draftId = comments.find(comment => comment.draft)?.id;

  useEffect(() => {
    draftInput.current?.focus();
  }, [draftId]);

  const lines = quotes.split("\n").map(line => line.trim()).filter(line => line.length > 0);

  const open = () => {
    const firstPage = Number(page) > 0 ? Number(page) : undefined;
    setComments([]);
    setSelectedId(undefined);
    setShown({
      url: url.trim(),
      page: firstPage,
      passages: lines.map((quote, index) => ({
        id: `passage-${index + 1}`,
        quote,
        page: firstPage,
        kind: markedBy === "both" ? PASSAGE_KINDS[index % PASSAGE_KINDS.length] : markedBy,
      })),
      compareWith: olderUrl.trim().length > 0 ? { url: olderUrl.trim() } : undefined,
      commenting,
      title: title.trim().length > 0 ? title.trim() : undefined,
      inPlace,
    });
  };

  const annotate = (anchor: TextAnchor) => {
    const id = `comment-${count + 1}`;
    setCount(count + 1);
    const draft = { id, anchor: encodeAnchor(anchor), text: "", draft: true };
    setComments([ ...comments.filter(comment => !comment.draft), draft ]);
    setDraftText("");
    setSelectedId(id);
  };

  const save = (id: string) => {
    setComments(comments.map(comment => (
      comment.id === id ? { ...comment, text: draftText.trim(), draft: false } : comment
    )));
  };

  const cancel = (id: string) => {
    setComments(comments.filter(comment => comment.id !== id));
    setSelectedId(undefined);
  };

  // Escape gives up the draft, rather than closing the viewer around it.
  const onDraftKeyDown = (id: string) => (event: KeyboardEvent) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      cancel(id);
    }
  };

  const renderComment = (comment: PassageComment, selected: boolean) => {
    if (!comment.draft) {
      return (
        <Typography key={comment.id} variant="body2" sx={{ px: 1.5, pb: 1 }}>{comment.text}</Typography>
      );
    }
    if (!selected) {
      return (
        <Typography key={comment.id} variant="body2" sx={{ px: 1.5, pb: 1, color: "text.secondary" }}>
          A comment not saved yet.
        </Typography>
      );
    }
    return (
      <Stack key={comment.id} spacing={1} sx={{ px: 1.5, pb: 1.5 }}>
        <TextField
          label="Comment"
          value={draftText}
          onChange={event => setDraftText(event.target.value)}
          onKeyDown={onDraftKeyDown(comment.id)}
          inputRef={draftInput}
          multiline
          minRows={2}
          maxRows={6}
          size="small"
          fullWidth
        />
        <Stack direction="row" spacing={1} sx={{ justifyContent: "flex-end" }}>
          <Button onClick={() => cancel(comment.id)}>Cancel</Button>
          <Button variant="contained" disabled={draftText.trim() === ""} onClick={() => save(comment.id)}>
            Save
          </Button>
        </Stack>
      </Stack>
    );
  };

  const renderMarkDetails = (mark: Mark, selected: boolean) => {
    const details = comments
      .filter(comment => comment.id === mark.id)
      .map(comment => renderComment(comment, selected));
    return details.length === 0 ? undefined : details;
  };

  const commentMarks = comments.flatMap(({ id, anchor: encoded }): Mark[] => {
    const anchor = decodeAnchor(encoded);
    return anchor === undefined ? [] : [ { id, anchor, kind: "annotation" } ];
  });
  let markName: PdfViewerProps["markName"];
  if (shown?.commenting === true && shown.passages.length === 0) {
    markName = COMMENTS;
  } else if (commentMarks.length > 0) {
    markName = MARKS;
  }

  return (
    <>
      <Stack spacing={2}>
        <TextField
          label="PDF URL"
          value={url}
          onChange={event => setUrl(event.target.value)}
          placeholder="/path/to/file.pdf"
          fullWidth
        />
        <TextField
          label="Page (optional)"
          value={page}
          onChange={event => setPage(event.target.value)}
          slotProps={{ htmlInput: { inputMode: "numeric", pattern: "[0-9]*" } }}
          fullWidth
        />
        <TextField
          label="Text to find (optional)"
          helperText="One passage per line"
          value={quotes}
          onChange={event => setQuotes(event.target.value)}
          multiline
          minRows={2}
          fullWidth
        />
        <TextField
          select
          label="Marked by"
          value={markedBy}
          onChange={event => setMarkedBy(event.target.value as MarkedBy)}
          fullWidth
        >
          <MenuItem value="assistant">An AI assistant</MenuItem>
          <MenuItem value="annotation">A person</MenuItem>
          <MenuItem value="both">Both, in turn</MenuItem>
        </TextField>
        <TextField
          label="Older version URL (optional)"
          helperText="Compares the PDF with it"
          value={olderUrl}
          onChange={event => setOlderUrl(event.target.value)}
          placeholder="/path/to/v1.pdf"
          fullWidth
        />
        <TextField
          label="Title (optional)"
          value={title}
          onChange={event => setTitle(event.target.value)}
          fullWidth
        />
        <FormControlLabel
          control={<Checkbox checked={commenting} onChange={event => setCommenting(event.target.checked)} />}
          label="Comment on passages selected in the document"
        />
        <FormControlLabel
          control={<Checkbox checked={inPlace} onChange={event => setInPlace(event.target.checked)} />}
          label="Show in place, below the form"
        />
        <Button variant="contained" fullWidth disabled={url.trim().length === 0} onClick={open}>
          Open
        </Button>
      </Stack>
      <Box ref={setPlace} sx={{ display: shown?.inPlace ? "block" : "none", height: "70vh", mt: 2 }} />
      {shown !== undefined && (
        <PdfViewer
          url={shown.url}
          page={shown.page}
          marks={[ ...shown.passages, ...commentMarks ]}
          markName={markName}
          selectedMarkId={selectedId}
          onSelectMark={setSelectedId}
          onAnnotate={shown.commenting ? annotate : undefined}
          renderMarkDetails={shown.commenting ? renderMarkDetails : undefined}
          title={shown.title}
          compareWith={shown.compareWith}
          container={shown.inPlace ? place : undefined}
          onClose={() => setShown(undefined)}
        />
      )}
    </>
  );
}

export default PdfViewerTestWidget;
