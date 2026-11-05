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

import { useCallback, useMemo, useState } from "react";

import { Box } from "@mui/material";

import type { Mark, TextAnchor } from "@iap/frontend-commons/pdfViewer/marksModel";
import PdfViewer from "@iap/frontend-commons/pdfViewer/PdfViewer";

import { toFileUrl } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard from "./NoteCard";
import { kindInfo, notesAbout, type NoteKind, type ReviewData, type ReviewedDocument } from "./reviewData";

import type { ReviewActions } from "./FormReview";

const DRAFT = "draft";

// PRETOTYPE. A document, whole, with everything written about its passages marked on it: where the AI read
// answers, what its pre-review raised, the reviewers' issues and discussion, and the reviewer's own notes.
// Selecting a passage offers to write about it.
export default function DocumentReview({ document, data, shown, selected, onSelect, page, actions, questionLabel,
  onClose }: {
  document: ReviewedDocument;
  data: ReviewData;
  shown: Set<NoteKind>;
  selected?: string;
  onSelect: (id: string) => void;
  page?: number;
  actions: ReviewActions;
  // The question an extracted answer answers, and the answer as its options call it
  questionLabel: (question: string | undefined, extracted: string | undefined) => { text: string; answer?: string };
  onClose: () => void;
}) {
  const [ place, setPlace ] = useState<HTMLElement | null>(null);
  const [ draft, setDraft ] = useState<TextAnchor>();
  const notes = useMemo(() => notesAbout(data.notes, [ document.uuid, document.version ])
    .filter(note => note.passage && shown.has(note.kind)), [ data.notes, document, shown ]);
  const marks: Mark[] = useMemo(() => [
    ...notes.map(note => ({ id: note.id, kind: kindInfo(note.kind).mark, quote: note.passage?.quote ?? "",
      page: note.passage?.page })),
    ...draft ? [ { id: DRAFT, kind: "annotation" as const, anchor: draft } ] : [],
  ], [ notes, draft ]);

  const renderMarkDetails = useCallback((mark: Mark) => {
    if (mark.id === DRAFT && draft) {
      return (
        <Composer
          kinds={WRITTEN} initialKind="issue"
          onCancel={() => setDraft(undefined)}
          onSave={async (kind, text, severity) => {
            await actions.write({ kind, text, subjects: [ document.uuid ], source: document.version,
              quote: draft.quote, prefix: draft.before, suffix: draft.after, page: String(draft.start.page),
              ...severity ? { severity } : {} });
            setDraft(undefined);
          }}
        />
      );
    }
    const note = notes.find(one => one.id === mark.id);
    if (!note) {
      return null;
    }
    const asked = note.kind === "evidence" ? questionLabel(note.question, note.extracted) : undefined;
    return (
      <Box>
        {asked && <Box sx={{ typography: "body2", color: "text.secondary", mb: 0.5 }}>{asked.text}</Box>}
        <NoteCard
          note={note} answerLabel={asked?.answer}
          onReply={text => actions.write({ replyTo: note.id, text })}
          onEdit={(text, severity) => actions.write({ edit: note.id, text, ...severity ? { severity } : {} })}
          onRemove={() => actions.write({ remove: note.id })}
        />
      </Box>
    );
  }, [ notes, draft, actions, document, questionLabel ]);

  return (
    <>
      <Box ref={setPlace} sx={{ height: { xs: "75vh", md: "calc(100vh - 220px)" }, minHeight: 420 }} />
      <PdfViewer
        url={toFileUrl(document.url)}
        page={page}
        title={document.title}
        marks={marks}
        markName={{ one: "note", many: "notes" }}
        selectedMarkId={draft ? DRAFT : selected}
        onSelectMark={onSelect}
        onAnnotate={anchor => {
          setDraft(anchor);
          onSelect(DRAFT);
        }}
        renderMarkDetails={renderMarkDetails}
        container={place}
        plain
        onClose={onClose}
      />
    </>
  );
}
