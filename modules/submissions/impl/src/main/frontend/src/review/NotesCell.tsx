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

import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from "react";

import { Box, TextField, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { writeComment } from "./reviewApi";
import { getMessages, type Message } from "./reviewModel";

// A note to self on the submission as a whole, rather than pinned to a question or a passage
const general = ([ , node ]: [string, unknown]) => typeof node !== "object" || node === null
  || !("subjects" in node || "context" in node);

// PRETOTYPE. A reviewer's latest note to self on a submission as a whole, written and changed right in the queue,
// the way they kept a column of their own in a spreadsheet; the review panel lists the same notes.
export default function NotesCell({ path, me }: { path: string; me: string }) {
  const doFetch = useAuthenticatedFetch();
  const [ note, setNote ] = useState<Message>();
  const [ editing, setEditing ] = useState(false);
  const [ draft, setDraft ] = useState("");
  const field = useRef<HTMLTextAreaElement>(null);
  // Into the field as soon as it opens, since opening it is asking to type
  useEffect(() => {
    if (editing) {
      field.current?.focus();
    }
  }, [ editing ]);

  const load = useCallback(() => doFetch(`${path}/notes-${me}.1.json`)
    .then(response => response.ok ? response.json() as Promise<Record<string, unknown>> : undefined)
    .then(notes => setNote(getMessages(notes && Object.fromEntries(Object.entries(notes).filter(general))).at(-1))),
  [ doFetch, path, me ]);
  useEffect(() => {
    void load();
  }, [ load ]);

  const save = async () => {
    setEditing(false);
    const text = draft.trim();
    if (text === (note?.text ?? "")) {
      return;
    }
    if (!text && note) {
      await writeComment(doFetch, path, { remove: note.path });
    } else if (note) {
      await writeComment(doFetch, path, { edit: note.path, text });
    } else if (text) {
      await writeComment(doFetch, path, { kind: "note", text });
    }
    await load();
  };

  const keys = (event: KeyboardEvent) => {
    event.stopPropagation();
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void save();
    } else if (event.key === "Escape") {
      setEditing(false);
    }
  };

  if (editing) {
    return (
      <TextField
        inputRef={field} multiline fullWidth size="small" maxRows={4} value={draft}
        placeholder="A note only you will see"
        onChange={event => setDraft(event.target.value)}
        onBlur={() => void save()}
        onKeyDown={keys}
        onClick={event => event.stopPropagation()}
        slotProps={{ htmlInput: { "aria-label": "Your note" } }}
      />
    );
  }
  return (
    <Box
      role="button" tabIndex={0}
      onClick={event => {
        event.stopPropagation();
        setDraft(note?.text ?? "");
        setEditing(true);
      }}
      onKeyDown={event => {
        if (event.key === "Enter") {
          event.stopPropagation();
          setDraft(note?.text ?? "");
          setEditing(true);
        }
      }}
      sx={{ width: "100%", cursor: "text", py: 0.5 }}
    >
      <Typography
        variant="body2"
        sx={{ color: note ? "text.primary" : "text.disabled", whiteSpace: "normal", display: "-webkit-box",
          WebkitLineClamp: 2, WebkitBoxOrient: "vertical", overflow: "hidden" }}
      >
        {note?.text ?? "Add a note"}
      </Typography>
    </Box>
  );
}
