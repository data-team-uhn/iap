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
import type { DocumentSearch } from "@iap/frontend-commons/pdfViewer/pdfViewerModel";

import { toFileUrl } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard from "./NoteCard";
import { issueFrom, kindInfo, notesAbout, type NoteKind, type ReviewData, type ReviewedDocument } from "./reviewData";

import type { ReviewActions } from "./FormReview";

const DRAFT = "draft";

// PRETOTYPE. A document, whole, with everything written about its passages marked on it: where the AI read
// answers, what its pre-review raised, the reviewers' issues and discussion, and the reviewer's own notes.
// Selecting a passage offers to write about it.
export default function DocumentReview({ document, data, shown, selected, onSelect, page, actions, questionLabel,
  search, onSearch, onClose }: {
  document: ReviewedDocument;
  data: ReviewData;
  shown: Set<NoteKind>;
  selected?: string;
  onSelect: (id: string) => void;
  page?: number;
  actions: ReviewActions;
  // The question an extracted answer answers, and the answer as its options call it
  questionLabel: (question: string | undefined, extracted: string | undefined) => { text: string; answer?: string };
  // The submission search, which finds in the document instead of its own search field
  search: { query: string; step: number; previous: boolean };
  onSearch: (search: DocumentSearch) => void;
  onClose: () => void;
}) {
  const [ place, setPlace ] = useState<HTMLElement | null>(null);
  const [ draft, setDraft ] = useState<TextAnchor>();
  // The AI pre-review note being made into an issue, coloured as one meanwhile
  const [ adopting, setAdopting ] = useState<string>();
  const notes = useMemo(() => notesAbout(data.notes, [ document.uuid, document.version ])
    .filter(note => note.passage && shown.has(note.kind)), [ data.notes, document, shown ]);
  const marks: Mark[] = useMemo(() => [
    ...notes.map(note => ({ id: note.id, kind: kindInfo(adopting === note.id ? "issue" : note.kind).mark,
      quote: note.passage?.quote ?? "",
      page: note.passage?.page })),
    ...draft ? [ { id: DRAFT, kind: "draft" as const, anchor: draft } ] : [],
  ], [ notes, draft, adopting ]);

  const renderMarkDetails = useCallback((mark: Mark) => {
    if (mark.id === DRAFT && draft) {
      return (
        <Box sx={{ px: 1.5, pb: 1 }}>
          <Composer
            kinds={WRITTEN} initialKind="issue" about={[ data.partOf.get(document.uuid) ?? "" ]}
            onCancel={() => setDraft(undefined)}
            onSave={async (kind, text, severity, topic) => {
              await actions.write({ kind, text, subjects: [ document.uuid ], source: document.version,
                quote: draft.quote, prefix: draft.before, suffix: draft.after, page: String(draft.start.page),
                ...severity ? { severity } : {}, ...topic ? { topic } : {} });
              setDraft(undefined);
            }}
          />
        </Box>
      );
    }
    const note = notes.find(one => one.id === mark.id);
    if (!note) {
      return null;
    }
    const asked = note.kind === "evidence" ? questionLabel(note.question, note.extracted) : undefined;
    return (
      <Box sx={{ px: 1.5, pb: 1 }}>
        {asked && <Box sx={{ typography: "body2", color: "text.secondary", mb: 0.5 }}>{asked.text}</Box>}
        <NoteCard
          note={note} answerLabel={asked?.answer}
          onReply={text => actions.write({ replyTo: note.id, text })}
          onEdit={(text, severity, topic) => actions.write({ edit: note.id, text, ...severity ? { severity } : {},
            ...topic !== undefined ? { topic } : {} })}
          onRemove={() => actions.write({ remove: note.id })}
          onMakeIssue={(text, severity, topic) => actions.write(issueFrom(note, text, severity, topic))}
          onDismiss={() => actions.write({ dismiss: note.id })}
          adopting={adopting === note.id}
          onAdopting={on => setAdopting(on ? note.id : undefined)}
        />
      </Box>
    );
  }, [ notes, draft, actions, document, questionLabel, adopting, data.partOf ]);

  return (
    <>
      {/* On a wide screen, the rest of the page's height, which matches the window's */}
      <Box ref={setPlace} sx={{ height: { xs: "75vh", md: "auto" }, flex: { md: 1 }, minHeight: { xs: 420, md: 0 } }} />
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
        annotateLabel="Write about this"
        search={search}
        onSearch={onSearch}
        plain
        onClose={onClose}
      />
    </>
  );
}
