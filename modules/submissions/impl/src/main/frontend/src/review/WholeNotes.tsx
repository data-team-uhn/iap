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
import { useState } from "react";

import ChevronRightOutlined from "@mui/icons-material/ChevronRightOutlined";
import ExpandMoreOutlined from "@mui/icons-material/ExpandMoreOutlined";
import { Box, Button, ButtonBase, Stack, Typography } from "@mui/material";

import { getPassageCardStyle } from "@iap/documents-pdf-viewer/passageStyles";

import Composer from "./Composer";
import { noteWrites, type ReviewActions } from "./FormReview";
import NoteCard from "./NoteCard";
import { kindInfo, type NoteKind, type ReviewNote } from "./reviewData";

// PRETOTYPE. What is written on the whole submission rather than on a question or passage, as the queue shows and
// writes it: the reviewers' discussion, then the reviewer's notes to self, each while its kind is shown. Last, under
// the notes of whatever page is shown, folded under how many there are, and open no more than half the panel's
// height, so the page's notes keep their room
export default function WholeNotes({ notes, kinds, actions }: {
  notes: ReviewNote[];
  // The kinds shown, of those written here
  kinds: NoteKind[];
  actions: ReviewActions;
}) {
  const [ open, setOpen ] = useState(false);
  const [ writing, setWriting ] = useState(false);
  const shown = kinds.flatMap(kind => notes.filter(note => note.kind === kind));
  return (
    <Box sx={{ borderTop: 1, borderColor: "divider", flexShrink: 0, maxHeight: "50%", display: "flex",
      flexDirection: "column" }}>
      <ButtonBase onClick={() => setOpen(!open)} aria-expanded={open}
        sx={{ width: "100%", justifyContent: "flex-start", gap: 0.5, px: 1, py: 0.75, flexShrink: 0 }}>
        {open ? <ExpandMoreOutlined fontSize="small" /> : <ChevronRightOutlined fontSize="small" />}
        <Typography variant="body2" sx={{ color: "primary.main", fontWeight: "fontWeightMedium" }}>
          On the whole submission{shown.length > 0 ? ` (${shown.length})` : ""}
        </Typography>
      </ButtonBase>
      {open && (
        <Stack spacing={1} sx={{ px: 1.5, pb: 1, minHeight: 0, overflowY: "auto" }}>
          {shown.map(note => (
            <Box key={note.id}
              sx={theme => ({ ...getPassageCardStyle(theme, kindInfo(note.kind).mark), px: 1.25, py: 0.75 })}>
              <NoteCard note={note} {...noteWrites(note, actions)} />
            </Box>
          ))}
          {!actions.readOnly && (writing ? (
            <Box sx={theme => ({ ...getPassageCardStyle(theme, "draft"), px: 1.25, py: 1, outline: "2px solid",
              outlineColor: theme.palette.draft.main, outlineOffset: "-2px" })}>
              <Composer
                kinds={kinds}
                onCancel={() => setWriting(false)}
                onSave={async (kind, said) => {
                  await actions.write({ kind, text: said });
                  setWriting(false);
                }}
              />
            </Box>
          ) : (
            <Button size="small" variant="text" sx={{ alignSelf: "flex-start", px: 0.75, ml: -0.75 }}
              onClick={() => setWriting(true)}>
              Write on the whole submission
            </Button>
          ))}
        </Stack>
      )}
    </Box>
  );
}
