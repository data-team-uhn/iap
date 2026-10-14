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

import { Box, Button, Stack, TextField, Typography } from "@mui/material";

import { type Mark, type TextAnchor } from "@iap/frontend-commons/pdfViewer/marksModel";
import PdfViewer from "@iap/frontend-commons/pdfViewer/PdfViewer";
import { decodeAnchor, encodeAnchor, type EncodedAnchor } from "@iap/frontend-commons/pdfViewer/textAnchor";

// A comment on a passage, with its anchor kept as text. A draft has not been saved yet.
interface PassageComment {
  id: string;
  anchor: EncodedAnchor;
  text: string;
  draft: boolean;
}

// Each passage marked is one comment's, so the viewer counts and steps through them as comments.
const COMMENTS = { one: "comment", many: "comments" };

// A dashboard widget for trying out comments on passages of a PDF by hand. Select text in the document,
// press Comment, and write a comment in the box under the passage. The comments last until the page is
// reloaded. Each keeps its anchor encoded as text, and its mark is decoded from that text.
function PdfAnnotationsTestWidget() {
  const [url, setUrl] = useState("");
  const [opened, setOpened] = useState<string | undefined>(undefined);
  const [place, setPlace] = useState<HTMLDivElement | null>(null);
  const [comments, setComments] = useState<PassageComment[]>([]);
  const [selectedId, setSelectedId] = useState<string | undefined>(undefined);
  const [draftText, setDraftText] = useState("");
  const [count, setCount] = useState(0);
  const draftInput = useRef<HTMLTextAreaElement>(null);
  const draftId = comments.find(comment => comment.draft)?.id;

  useEffect(() => {
    draftInput.current?.focus();
  }, [draftId]);

  const marks = comments.flatMap(({ id, anchor: encoded }): Mark[] => {
    const anchor = decodeAnchor(encoded);
    return anchor === undefined ? [] : [ { id, anchor, kind: "annotation" } ];
  });

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

  const renderMarkDetails = (mark: Mark, selected: boolean) => comments
    .filter(comment => comment.id === mark.id)
    .map(comment => renderComment(comment, selected));

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
        <Button
          variant="contained"
          fullWidth
          disabled={url.trim().length === 0}
          onClick={() => {
            setComments([]);
            setSelectedId(undefined);
            setOpened(url.trim());
          }}
        >
          Open
        </Button>
      </Stack>
      <Box ref={setPlace} sx={{ display: opened === undefined ? "none" : "block", height: "70vh", mt: 2 }} />
      {opened !== undefined && (
        <PdfViewer
          url={opened}
          marks={marks}
          markName={COMMENTS}
          selectedMarkId={selectedId}
          onSelectMark={setSelectedId}
          onAnnotate={annotate}
          renderMarkDetails={renderMarkDetails}
          container={place}
          onClose={() => setOpened(undefined)}
        />
      )}
    </>
  );
}

export default PdfAnnotationsTestWidget;
