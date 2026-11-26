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

import { useCallback, useMemo, useState, type ReactNode } from "react";

import AddCommentOutlined from "@mui/icons-material/AddCommentOutlined";
import { Box, IconButton, Tooltip } from "@mui/material";

import type { Mark, TextAnchor } from "@iap/documents-pdf-viewer/marksModel";
import PdfViewer from "@iap/documents-pdf-viewer/PdfViewer";
import type { DocumentSearch } from "@iap/documents-pdf-viewer/pdfViewerModel";

import { toFileUrl } from "../submissionForm";
import Composer, { WRITTEN } from "./Composer";
import NoteCard, { ChangeHeading, DraftHeading, NoteActions, NoteHeading, NoteSummary } from "./NoteCard";
import { kindInfo, notesAbout, raiseAsIssue, type NoteKind, type ReviewData, type ReviewedDocument,
  type ReviewNote } from "./reviewData";

import type { ReviewActions } from "./FormReview";

const DRAFT = "draft";

// PRETOTYPE. A document, whole, with everything written about its passages marked on it: where the AI read
// answers, what its pre-review raised, the reviewers' issues and discussion, and the reviewer's own notes.
// Selecting a passage offers to write about it.
// What reviewers and the AI pre-review write, shown in brief when not selected, leading with its words
const BRIEF_KINDS = new Set<NoteKind>([ "aiIssue", "issue", "otherIssue", "discussion", "note" ]);

export default function DocumentReview({ document, data, shown, selected, onSelect, page, actions, questionLabel,
  search, onSearch, notesWidth, onNotesWidth, heading, onShowAnswer, notesSlot, onClose, compareWith }: {
  document: ReviewedDocument;
  // PRETOTYPE: an older version to show what changed since, and what each is called
  compareWith?: { url: string; label: string; current: string };
  data: ReviewData;
  shown: Set<NoteKind>;
  selected?: string;
  onSelect: (id: string) => void;
  page?: number;
  actions: ReviewActions;
  // The question an extracted answer answers, and the answer as its options call it
  questionLabel: (question: string | undefined, extracted: string | undefined) => {
    text: string;
    answer?: string;
    given?: string;
    form?: string;
  };
  // The submission search, which finds in the document instead of its own search field
  search: { query: string; step: number; previous: boolean };
  onSearch: (search: DocumentSearch) => void;
  // How wide the notes beside the document are, which the reader sizes
  notesWidth: number;
  onNotesWidth: (width: number) => void;
  // What heads the document's column, the title of the page it is on, beside its notes
  heading?: ReactNode;
  // Going from a passage to the answer in the form it backs or contradicts
  onShowAnswer: (note: ReviewNote) => void;
  // A column of the screen's own to list the notes in; null while another page's are listed there
  notesSlot?: HTMLElement | null;
  onClose: () => void;
}) {
  const [ place, setPlace ] = useState<HTMLElement | null>(null);
  // A note being started: on the passage selected, or, PRETOTYPE, about the whole document
  const [ draft, setDraft ] = useState<TextAnchor | "document">();
  // The AI pre-review note being made into an issue, coloured as one meanwhile
  const [ adopting, setAdopting ] = useState<string>();
  // The reviewer's own note being changed, whose card then shows its editor
  const [ editing, setEditing ] = useState<string>();
  const notes = useMemo(() => notesAbout(data.notes, [ document.uuid, ...document.versions.map(one => one.uuid) ])
    .filter(note => (note.passage !== undefined || note.subjects.includes(document.uuid)) && shown.has(note.kind)),
  [ data.notes, document, shown ]);
  const marks: Mark[] = useMemo(() => [
    ...notes.map(note => ({ id: note.id, kind: kindInfo(adopting === note.id ? "issue" : note.kind).mark,
      quote: note.passage?.quote ?? "",
      page: note.passage?.page })),
    // About the whole document, a note has no passage, and is listed after those that have
    ...draft === "document" ? [ { id: DRAFT, kind: "draft" as const, quote: "" } ]
      : draft ? [ { id: DRAFT, kind: "draft" as const, anchor: draft } ] : [],
  ], [ notes, draft, adopting ]);

  const renderMarkDetails = useCallback((mark: Mark, selected: boolean) => {
    if (mark.id === DRAFT && draft) {
      return (
        <Box sx={{ px: 1.5, pb: 1 }}>
          <Composer
            kinds={WRITTEN} initialKind="issue" about={[ data.partOf.get(document.uuid) ?? "" ]}
            onCancel={() => setDraft(undefined)}
            onSave={async (kind, text, severity, topic) => {
              await actions.write({ kind, text, subjects: [ document.uuid ], ...draft === "document" ? {} : {
                source: document.version, quote: draft.quote, prefix: draft.before, suffix: draft.after,
                page: String(draft.start.page) }, ...severity ? { severity } : {}, ...topic ? { topic } : {} });
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
    // Not selected, what a reviewer wrote shows by its own words, the passage cut to a line above them
    if (!selected && BRIEF_KINDS.has(note.kind)) {
      return <Box data-note={note.id}><NoteSummary note={note} headed /></Box>;
    }
    const asked = note.kind === "evidence" || note.kind === "contradiction"
      ? questionLabel(note.question, note.extracted) : undefined;
    return (
      <Box data-note={note.id} sx={{ px: 1.5, pb: 1 }}>
        <NoteCard
          note={note} answerLabel={asked?.answer} question={asked?.text} formAnswer={asked?.given} headed
          onShowAnswer={asked ? () => onShowAnswer(note) : undefined}
          askedAs={asked && { question: asked.text, form: asked.form }}
          onReply={text => actions.write({ replyTo: note.id, text })}
          onEditReply={(reply, text) => actions.write({ edit: reply, text })}
          onRemoveReply={reply => actions.write({ remove: reply })}
          onEdit={(text, severity, topic) => actions.write({ edit: note.id, text, ...severity ? { severity } : {},
            ...topic !== undefined ? { topic } : {} })}
          onRemove={() => actions.write({ remove: note.id })}
          onMakeIssue={(text, severity, topic) => raiseAsIssue(actions.write, note, text, severity, topic)}
          onDismiss={() => actions.write({ dismiss: note.id })}
          write={actions.write}
          onShowNote={actions.show}
          adopting={adopting === note.id}
          onAdopting={on => setAdopting(on ? note.id : undefined)}
          editing={editing === note.id}
          onEditing={on => setEditing(on ? note.id : undefined)}
        />
      </Box>
    );
  }, [ notes, draft, actions, document, questionLabel, adopting, editing, data.partOf, onShowAnswer ]);

  const condensed = useCallback((mark: Mark) => {
    const note = notes.find(one => one.id === mark.id);
    // Only a source or a contradiction needs the words it was read from beside the document, which shows every passage
    if (!note || note.kind === "evidence" || note.kind === "contradiction") {
      return undefined;
    }
    return BRIEF_KINDS.has(note.kind) ? { quote: 0, details: 4 } : { quote: 0 };
  }, [ notes ]);

  // Each note headed by what it is, when and by whom; the draft as a new note, over the passage it is about
  const markHeading = useCallback((mark: Mark) => {
    if (mark.id === DRAFT) {
      return <DraftHeading />;
    }
    if (mark.kind === "added" || mark.kind === "removed") {
      return <ChangeHeading />;
    }
    const note = notes.find(one => one.id === mark.id);
    return note && <NoteHeading note={note} />;
  }, [ notes ]);
  // PRETOTYPE: a note written on an older version whose passage the latest no longer has says its text changed
  const describeUnfound = useCallback((mark: Mark) => {
    const source = notes.find(one => one.id === mark.id)?.passage?.source;
    return source && source !== document.version && document.versions.some(one => one.uuid === source)
      ? "its passage changed since it was sent back" : undefined;
  }, [ notes, document ]);
  // At the end of the heading, changing or removing the reviewer's own note; changing it selects it, to open it whole
  const markHeadingActions = useCallback((mark: Mark) => {
    const note = notes.find(one => one.id === mark.id);
    return note && editing !== note.id && (
      <NoteActions
        note={note}
        onEdit={() => {
          setEditing(note.id);
          onSelect(note.id);
        }}
        onRemove={() => actions.write({ remove: note.id })}
      />
    );
  }, [ notes, editing, actions, onSelect ]);

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
        condensedMarks={condensed}
        annotateLabel="Write about this"
        search={search}
        onSearch={onSearch}
        listWidth={notesWidth}
        onListWidth={onNotesWidth}
        markHeading={markHeading}
        markHeadingActions={markHeadingActions}
        lead={heading}
        leadEnd={draft !== "document" && (
          // PRETOTYPE: as each question has, a button to write about the document as a whole, without a passage
          <Tooltip title="Write about this document">
            <IconButton size="small" aria-label={`Write about: ${document.title}`}
              onClick={() => {
                setDraft("document");
                onSelect(DRAFT);
              }}>
              <AddCommentOutlined fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        listContainer={notesSlot}
        describeUnfound={describeUnfound}
        compareWith={compareWith && { url: toFileUrl(compareWith.url), label: compareWith.label }}
        label={compareWith?.current}
        plain
        onClose={onClose}
      />
    </>
  );
}
